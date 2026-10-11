package com.itjiang.qa.ocr;

import com.itjiang.qa.common.ErrorCode;
import com.itjiang.qa.config.DurationParser;
import com.itjiang.qa.config.QaProperties;
import com.itjiang.qa.model.dto.OcrResult;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * RapidOCR 引擎：以<b>子进程</b>方式调用 {@code deploy/ocr/qa_ocr.py}。
 *
 * <h2>为什么是子进程而不是常驻服务</h2>
 * <p>OCR 是<b>偶发重活</b>：一个用户可能一天用一次。常驻 Python 服务会一直占
 * 200~500 MB 内存，而子进程用完即退、崩了不影响主进程、还能被超时强杀。
 * 代价是每次冷启动约 0.4 s，相对单页 2.4 s 的识别时间可以忽略。
 *
 * <h2>四个必须处理的工程细节（都不是理论问题，是实测踩出来的）</h2>
 * <ol>
 *   <li><b>UTF-8 读取</b>：Windows 上 Python 默认按系统 ANSI 代码页（本机 GBK）写 stdout，
 *       而 JSON 里必然有中文。脚本侧已 reconfigure 成 UTF-8，这里也显式按 UTF-8 读 ——
 *       两头都钉死，不依赖平台默认。不处理的症状是
 *       {@code UnicodeDecodeError: invalid start byte} 或满屏乱码。</li>
 *   <li><b>哨兵包裹</b>：第三方库（RapidOCR / ONNX Runtime）会往 stdout 打日志，
 *       绝不能假设"stdout 全是 JSON"。只在哨兵之间取内容。</li>
 *   <li><b>进程树强杀</b>：超时后只 {@code destroy()} 主进程，Python 拉起的子进程会成为
 *       孤儿继续吃 CPU。必须先杀 descendants 再杀主进程。</li>
 *   <li><b>可用性探测短期缓存</b>：{@code --check} 要 fork 一个 Python，成本约 0.4 秒，
 *       所以结果缓存 5 秒用于合并同一次页面加载的多个请求。
 *       <b>但不能永久缓存</b> —— 那会让"装完依赖刷新页面"看不到任何变化，
 *       用户只能靠重启应用才能生效，而这件事他无从得知。</li>
 * </ol>
 *
 * @author Jiang
 */
@Component
public class RapidOcrEngine implements OcrEngine {

    private static final Logger log = LoggerFactory.getLogger(RapidOcrEngine.class);

    /** 与 {@code qa_ocr.py} 约定的哨兵。 */
    private static final String SENTINEL_BEGIN = "###QAOCR_BEGIN###";
    private static final String SENTINEL_END = "###QAOCR_END###";

    private static final Pattern PAYLOAD = Pattern.compile(
            Pattern.quote(SENTINEL_BEGIN) + "(.*?)" + Pattern.quote(SENTINEL_END), Pattern.DOTALL);

    /** 脚本相对项目根的位置（与 start.cmd/sh 的约定一致）。 */
    private static final String SCRIPT_RELATIVE = "deploy/ocr/qa_ocr.py";

    private static final Duration CHECK_TIMEOUT = Duration.ofSeconds(20);

    private final QaProperties properties;
    private final ObjectMapper objectMapper;

    /** 探测出的 Python 可执行文件；未探测时为 {@code null}。 */
    private volatile String resolvedPython;

    /** 最近一次探测结果；配合 {@link #AVAILABILITY_TTL} 判断是否还有效。 */
    private volatile Availability cachedAvailability;

    /**
     * 可用性缓存的有效期。
     *
     * <p>缓存<b>不再永久有效</b>（原实现是"进程级只探一次"）。原因是那会让用户困惑：
     * 他按提示装好依赖、刷新页面，界面却依旧显示"缺少依赖" —— 因为他必须先重启应用。
     * 而"装完依赖要重启才能生效"这件事，对用户来说毫无道理，也不会有人去猜。
     *
     * <p>代价可控：一次探测 = fork 一个 Python 跑 {@code --check}（不加载模型），
     * 实测约 0.4 秒。而探测只发生在用户打开「文档管理」页时，不在问答链路上。
     * 用 0.4 秒换掉"必须重启"这个反直觉约束，划算。
     */
    private static final Duration AVAILABILITY_TTL = Duration.ofSeconds(5);

    /** 上次探测的时刻；配合 {@link #AVAILABILITY_TTL} 决定是否重探。 */
    private volatile long probedAtMillis;

    public RapidOcrEngine(QaProperties properties, ObjectMapper objectMapper) {
        this.properties = properties;
        this.objectMapper = objectMapper;
    }

    /**
     * 探测 OCR 可用性。
     *
     * <p>结果缓存 {@link #AVAILABILITY_TTL}，过期后重探 —— 所以用户装完依赖
     * 刷新页面就能看到状态变化，不需要重启应用。
     *
     * <p>短 TTL 而不是完全不缓存：一次页面加载可能触发多个请求（列表 + 状态），
     * 5 秒窗口足以把它们合并成一次探测，又不影响"装完刷新即生效"。
     *
     * @return 可用性
     */
    @Override
    public Availability availability() {
        long now = System.currentTimeMillis();
        Availability cached = cachedAvailability;
        if (cached != null && now - probedAtMillis < AVAILABILITY_TTL.toMillis()) {
            return cached;
        }
        synchronized (this) {
            now = System.currentTimeMillis();
            if (cachedAvailability == null || now - probedAtMillis >= AVAILABILITY_TTL.toMillis()) {
                cachedAvailability = probeAvailability();
                probedAtMillis = now;
            }
            return cachedAvailability;
        }
    }

    /**
     * 实际探测：找脚本 → 找 Python → 跑 {@code --check}。
     *
     * @return 可用性
     */
    private Availability probeAvailability() {
        if (!properties.getOcr().isEnabled()) {
            log.info("OCR 已通过配置关闭（qa.ocr.enabled=false）");
            return Availability.DISABLED;
        }
        Path script = scriptPath();
        if (!Files.isRegularFile(script)) {
            log.info("OCR 脚本不存在: {}（从项目根目录启动即可解决）", script);
            return Availability.NO_SCRIPT;
        }
        String python = findPython();
        if (python == null) {
            log.info("未找到 Python 解释器，OCR 不可用（不影响其余功能）");
            return Availability.NO_PYTHON;
        }
        this.resolvedPython = python;

        ProbeResult probe = runCheck(python, script);
        if (probe.ok) {
            log.info("本地 OCR 可用: python={}, 依赖={}", python, probe.detail);
            return Availability.READY;
        }
        log.info("本地 OCR 依赖缺失: python={}, 原因={}", python, probe.detail);
        return Availability.NO_DEPENDENCY;
    }

    /**
     * 探测结果。
     */
    private record ProbeResult(boolean ok, String detail) {
    }

    /**
     * 跑一次 {@code --check}。
     *
     * @param python Python 可执行文件
     * @param script 脚本路径
     * @return 探测结果
     */
    private ProbeResult runCheck(String python, Path script) {
        try {
            Process process = new ProcessBuilder(python, script.toString(), "--check")
                    .redirectErrorStream(true)
                    .start();
            String output;
            try (BufferedReader reader = new BufferedReader(
                    new InputStreamReader(process.getInputStream(), StandardCharsets.UTF_8))) {
                StringBuilder sb = new StringBuilder();
                String line;
                while ((line = reader.readLine()) != null) {
                    sb.append(line).append('\n');
                }
                output = sb.toString();
            }
            if (!process.waitFor(CHECK_TIMEOUT.toSeconds(), TimeUnit.SECONDS)) {
                killTree(process);
                return new ProbeResult(false, "依赖自检超时");
            }
            JsonNode payload = extractPayload(output);
            if (payload == null) {
                return new ProbeResult(false, "自检输出无法解析");
            }
            boolean ok = payload.path("ok").asBoolean(false);
            String detail = ok
                    ? payload.path("versions").toString()
                    : payload.path("message").asString("未知原因");
            return new ProbeResult(ok, detail);
        } catch (Exception ex) {
            return new ProbeResult(false, ex.getClass().getSimpleName() + ": " + ex.getMessage());
        }
    }

    @Override
    public OcrResult recognize(Path pdf, Long documentId) {
        Availability availability = availability();
        if (!availability.usable()) {
            throw new OcrException(ErrorCode.OCR_UNAVAILABLE, unavailableMessage(availability));
        }
        Path script = scriptPath();
        String python = resolvedPython != null ? resolvedPython : findPython();
        if (python == null) {
            throw new OcrException(ErrorCode.OCR_UNAVAILABLE, unavailableMessage(Availability.NO_PYTHON));
        }

        QaProperties.Ocr cfg = properties.getOcr();
        List<String> command = new ArrayList<>(List.of(
                python, script.toString(), pdf.toString(),
                "--max-pages", String.valueOf(cfg.getMaxPages()),
                "--dpi", String.valueOf(cfg.getDpi()),
                "--min-confidence", String.valueOf(cfg.getMinConfidence())));

        // 时长配置是字符串（形如 3m），与项目其它 timeout 走同一个解析器，保证 3m/180s 都认
        java.time.Duration timeout = DurationParser.parse(cfg.getTimeout(), java.time.Duration.ofMinutes(3));

        long start = System.currentTimeMillis();
        Process process = null;
        try {
            process = new ProcessBuilder(command)
                    .redirectErrorStream(true)     // 合并 stderr：出问题时能拿到完整现场
                    .start();

            String output = readAll(process);
            boolean finished = process.waitFor(timeout.toSeconds(), TimeUnit.SECONDS);
            if (!finished) {
                killTree(process);
                long elapsed = System.currentTimeMillis() - start;
                log.warn("OCR 超时: id={}, 已等待 {} ms, 上限 {}s", documentId, elapsed, timeout.toSeconds());
                throw new OcrException(ErrorCode.OCR_TIMEOUT,
                        "识别超时（已等待 " + timeout.toSeconds() + " 秒）。"
                                + "请减少页数、拆分文件后重试，或调大 qa.ocr.timeout。");
            }

            int exitCode = process.exitValue();
            JsonNode payload = extractPayload(output);

            // 脚本退出码是为"给机器看"设计的，比解析文本可靠；payload 缺失时只能靠它
            if (payload == null) {
                log.warn("OCR 输出无法解析: id={}, exit={}, 输出前 300 字={}",
                        documentId, exitCode, abbreviate(output, 300));
                throw new OcrException(ErrorCode.OCR_FAILED,
                        "识别进程的输出无法解析（退出码 " + exitCode + "）。详见应用日志。");
            }
            if (!payload.path("ok").asBoolean(false)) {
                throw mapFailure(payload, exitCode);
            }

            return new OcrResult(
                    documentId,
                    payload.path("engine").asString("rapidocr"),
                    payload.path("engineVersion").asString("unknown"),
                    payload.path("totalPages").asInt(0),
                    payload.path("pagesWithText").asInt(0),
                    // 内嵌图片统计。旧版脚本不回这两个字段 —— 用 asInt(0) 兜底，
                    // 使"脚本没升级"退化成旧行为而不是解析失败。
                    payload.path("imageCount").asInt(0),
                    payload.path("imagesWithText").asInt(0),
                    payload.path("chars").asInt(0),
                    payload.path("avgConfidence").isNull() ? null : payload.path("avgConfidence").asDouble(),
                    payload.path("elapsedMs").asLong(System.currentTimeMillis() - start),
                    payload.path("preview").asString(""),
                    payload.path("text").asString(""));

        } catch (OcrException ex) {
            throw ex;
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            if (process != null) {
                killTree(process);
            }
            throw new OcrException(ErrorCode.OCR_FAILED, "识别被中断，请重试。", ex);
        } catch (Exception ex) {
            log.error("OCR 执行失败: id={}", documentId, ex);
            if (process != null && process.isAlive()) {
                killTree(process);
            }
            throw new OcrException(ErrorCode.OCR_FAILED,
                    "识别失败：" + ex.getClass().getSimpleName() + "。详见应用日志。", ex);
        }
    }

    /**
     * 把脚本返回的失败信息映射成业务错误码。
     *
     * <p>按脚本约定的退出码/错误码分类，而不是按 message 文本猜 ——
     * 文本会改，码不会。
     *
     * @param payload  脚本返回的 JSON
     * @param exitCode 进程退出码
     * @return 对应异常
     */
    private OcrException mapFailure(JsonNode payload, int exitCode) {
        String message = payload.path("message").asString("识别失败");
        int code = payload.path("errorCode").asInt(exitCode);
        return switch (code) {
            case 2 -> new OcrException(ErrorCode.OCR_UNAVAILABLE,
                    "本机缺少 OCR 依赖（" + message + "）。请执行：pip install -r deploy/ocr/requirements.txt");
            case 3 -> new OcrException(ErrorCode.PARAM_INVALID, message);
            case 4 -> new OcrException(ErrorCode.OCR_TOO_MANY_PAGES, message
                    + "。请拆分文件，或调大 qa.ocr.max-pages 后重试。");
            case 5 -> new OcrException(ErrorCode.OCR_PDF_UNREADABLE, message);
            case 6 -> new OcrException(ErrorCode.OCR_EMPTY, message);
            default -> new OcrException(ErrorCode.OCR_FAILED, message);
        };
    }

    /**
     * 从哨兵之间取出 JSON。
     *
     * <p>包级可见（而非 private）：这是与 Python 脚本之间<b>唯一的契约点</b>，
     * 必须能被单测直接锁住。第三方库会往 stdout 打日志，一旦这里写得过于乐观
     * （比如"去掉首尾空白就当 JSON 解析"），线上表现是"识别成功但结果解析不出来"。
     *
     * @param output 进程完整输出
     * @return JSON 节点；找不到或解析失败时返回 {@code null}
     */
    JsonNode extractPayload(String output) {
        if (output == null) {
            return null;
        }
        Matcher matcher = PAYLOAD.matcher(output);
        if (!matcher.find()) {
            return null;
        }
        try {
            return objectMapper.readTree(matcher.group(1).trim());
        } catch (Exception ex) {
            log.warn("OCR JSON 解析失败: {}", ex.getMessage());
            return null;
        }
    }

    /**
     * 读尽进程输出。
     *
     * <p><b>必须在 waitFor 之前读完</b>：管道缓冲区满了之后子进程会阻塞在写上，
     * 于是永远不退出、waitFor 一直等到超时 —— 表现为"识别明明很快却总是超时"。
     *
     * @param process 进程
     * @return 输出文本
     * @throws IOException 读取失败
     */
    private String readAll(Process process) throws IOException {
        StringBuilder sb = new StringBuilder();
        try (BufferedReader reader = new BufferedReader(
                new InputStreamReader(process.getInputStream(), StandardCharsets.UTF_8))) {
            String line;
            while ((line = reader.readLine()) != null) {
                sb.append(line).append('\n');
            }
        }
        return sb.toString();
    }

    /**
     * 杀掉整个进程树。
     *
     * <p>只杀主进程会留下孤儿 Python 子进程继续吃 CPU（ONNX Runtime 会起工作线程，
     * 某些情况下还会起子进程），这在"用户连点几次识别"之后会很明显。
     *
     * @param process 进程
     */
    private void killTree(Process process) {
        try {
            process.descendants().forEach(ProcessHandle::destroyForcibly);
            process.destroyForcibly();
            process.waitFor(5, TimeUnit.SECONDS);
        } catch (Exception ex) {
            log.warn("结束 OCR 进程失败: {}", ex.getMessage());
        }
    }

    /**
     * 找 Python 解释器。
     *
     * <p>顺序：配置显式指定 → {@code python3} → {@code python}。
     * 先 python3 是因为 Linux/macOS 上 `python` 常常不存在或指向 Python 2。
     *
     * @return 可执行文件名；找不到返回 {@code null}
     */
    private String findPython() {
        String configured = properties.getOcr().getPython();
        if (configured != null && !configured.isBlank()) {
            return configured.trim();
        }
        for (String candidate : List.of("python3", "python")) {
            try {
                Process p = new ProcessBuilder(candidate, "--version")
                        .redirectErrorStream(true)
                        .start();
                boolean ok = p.waitFor(10, TimeUnit.SECONDS) && p.exitValue() == 0;
                if (ok) {
                    return candidate;
                }
            } catch (Exception ignored) {
                // 试下一个候选
            }
        }
        return null;
    }

    /**
     * OCR 脚本的绝对路径。
     *
     * <p>相对<b>当前工作目录</b>解析（与 `.env`、`deploy/ollama/Modelfile` 同一套约定）：
     * 应用要求从项目根启动，这一点 README 与 start 脚本里都已写明。
     * 从别处启动时会落到 {@link Availability#NO_SCRIPT}，界面给出可读提示而不是崩。
     *
     * @return 脚本路径
     */
    private Path scriptPath() {
        String configured = properties.getOcr().getScript();
        if (configured != null && !configured.isBlank()) {
            return Path.of(configured.trim()).toAbsolutePath().normalize();
        }
        return Path.of(SCRIPT_RELATIVE).toAbsolutePath().normalize();
    }

    /**
     * 生成"不可用"的可照做提示。
     *
     * @param availability 可用性状态
     * @return 提示文案
     */
    private String unavailableMessage(Availability availability) {
        return switch (availability) {
            case DISABLED -> "本地 OCR 已被配置关闭（qa.ocr.enabled=false）。";
            case NO_PYTHON -> "本机未找到 Python，扫描件识别需要 Python 3.10+。"
                    + "不装也能正常使用其余功能，只是扫描件无法入库。";
            // 这条文案要能直接照抄执行，所以写清三件事：缺哪些包、在哪执行、装完干什么。
            // 之前只写了一句 `pip install -r deploy/ocr/requirements.txt` —— 相对路径，
            // 用户在任意目录下执行都会失败，而这是界面给出的唯一指引。
            case NO_DEPENDENCY -> "本机缺少 OCR 依赖（需要 rapidocr + onnxruntime + pymupdf 三个包）。"
                    + "先在程序目录下执行：python -m pip install -r deploy\\ocr\\requirements.txt"
                    + "（这一步需要联网）；装完刷新本页即可，看到绿色提示就说明成功了。"
                    + "注意 onnxruntime 要单独装 —— rapidocr 不会自动带上它。";
            case NO_SCRIPT -> "找不到 OCR 脚本 deploy/ocr/qa_ocr.py。"
                    + "请从项目根目录启动应用（脚本按工作目录解析）。";
            case READY -> "OCR 可用";
        };
    }

    /**
     * 截断长文本用于日志。
     *
     * @param text   原文
     * @param maxLen 上限
     * @return 截断后的文本
     */
    private String abbreviate(String text, int maxLen) {
        if (text == null) {
            return "";
        }
        String oneLine = text.replace('\n', ' ');
        return oneLine.length() <= maxLen ? oneLine : oneLine.substring(0, maxLen) + "…";
    }
}
