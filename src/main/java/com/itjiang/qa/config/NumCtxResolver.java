package com.itjiang.qa.config;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.TimeUnit;

/**
 * 解析本地模型的上下文窗口大小（{@code qa.local.num-ctx}）。
 *
 * <p><b>为什么需要这个类？</b> 上下文窗口是"本机能吃多少"的物理问题，不是配置偏好。
 * 开发机（RTX 5070 / 12GB）算出来安全值是 16384，但换一台 8GB 显存的机器照搬这个数字
 * 会直接 OOM。开源之后别人的机器千差万别，硬编码只有一个后果：<b>他跑不起来</b>。
 *
 * <p>所以配置支持 {@code auto}：启动时探测显存总量，按档位选一个安全的窗口。
 *
 * <p><b>为什么不引入 GPU 计算库？</b> 只需要"显存总量"这一个数字，
 * 解析 {@code nvidia-smi} 的输出就够了。为此拖进 CUDA 绑定或 JNI 库，
 * 会让一个纯 Java 项目多出几百 MB 依赖和一堆平台兼容问题，得不偿失。
 *
 * @author Jiang
 */
@Component
public class NumCtxResolver {

    private static final Logger log = LoggerFactory.getLogger(NumCtxResolver.class);

    /** 探测不到显卡时使用的保守值：宁可上下文小，也不要启动即 OOM。 */
    private static final int FALLBACK_NUM_CTX = 4096;

    /** 显存 ≥ 此值（MB）时使用 16384 —— 12GB 档。 */
    private static final long VRAM_TIER_HIGH_MB = 11_000;

    /** 显存 ≥ 此值（MB）时使用 8192 —— 8GB 档。 */
    private static final long VRAM_TIER_MID_MB = 7_000;

    /** nvidia-smi 命令超时（秒），探测失败不能拖慢启动。 */
    private static final int PROBE_TIMEOUT_SECONDS = 5;

    private final QaProperties properties;

    /**
     * 解析结果缓存。显存不会在运行期变化，反复 fork 进程去查是浪费。
     */
    private volatile Integer resolved;

    public NumCtxResolver(QaProperties properties) {
        this.properties = properties;
    }

    /**
     * 解析上下文窗口大小。
     *
     * @return {@code qa.local.num-ctx} 配置的数字；配置为 {@code auto} 时返回按显存探测出的值
     */
    public int resolve() {
        Integer cached = this.resolved;
        if (cached != null) {
            return cached;
        }
        synchronized (this) {
            if (this.resolved == null) {
                this.resolved = doResolve(properties.getLocal().getNumCtx());
            }
            return this.resolved;
        }
    }

    /**
     * 执行实际解析。
     *
     * @param configured 配置值，可能是数字也可能是 {@code auto}
     * @return 最终使用的上下文窗口大小
     */
    private int doResolve(String configured) {
        if (configured != null && !"auto".equalsIgnoreCase(configured.trim())) {
            try {
                int explicit = Integer.parseInt(configured.trim());
                log.info("num_ctx 使用显式配置值: {}", explicit);
                return explicit;
            } catch (NumberFormatException ex) {
                // 配置写错了不要静默吞掉：明确告诉用户回退到了 auto，否则他会困惑
                // "我明明配了 16384，为什么日志说 4096"
                log.warn("qa.local.num-ctx 配置值非法（{}），已回退为 auto 探测", configured);
            }
        }

        long vramMb = probeTotalVramMb();
        if (vramMb <= 0) {
            log.warn("未检测到可用显存信息（无独显或 nvidia-smi 不可用），"
                    + "num_ctx 使用保守值 {}。若回答明显变慢或被截断，建议改用云端模式。", FALLBACK_NUM_CTX);
            return FALLBACK_NUM_CTX;
        }

        int numCtx;
        if (vramMb >= VRAM_TIER_HIGH_MB) {
            numCtx = 16384;
        } else if (vramMb >= VRAM_TIER_MID_MB) {
            numCtx = 8192;
        } else {
            numCtx = FALLBACK_NUM_CTX;
        }
        log.info("显存 {} MB -> num_ctx 自动选定 {}", vramMb, numCtx);
        return numCtx;
    }

    /**
     * 读取第一块 NVIDIA 显卡的显存总量。
     *
     * @return 显存总量（MB）；探测失败返回 0
     */
    private long probeTotalVramMb() {
        Process process = null;
        try {
            process = new ProcessBuilder(
                    "nvidia-smi", "--query-gpu=memory.total", "--format=csv,noheader,nounits")
                    .redirectErrorStream(true)
                    .start();

            try (BufferedReader reader = new BufferedReader(
                    new InputStreamReader(process.getInputStream(), StandardCharsets.UTF_8))) {
                String line = reader.readLine();
                if (line == null || line.isBlank()) {
                    return 0L;
                }
                // 多卡机器会输出多行，取第一行即可（本项目面向单机单卡场景）
                return Long.parseLong(line.trim());
            }
        } catch (Exception ex) {
            // 探测失败是"预期内"的情况（没装显卡、没有 nvidia-smi、非 N 卡），
            // 因此用 DEBUG 而不是 ERROR，避免在别人的机器上刷出吓人的报错
            log.debug("显存探测失败: {}", ex.getMessage());
            return 0L;
        } finally {
            if (process != null) {
                try {
                    if (!process.waitFor(PROBE_TIMEOUT_SECONDS, TimeUnit.SECONDS)) {
                        process.destroyForcibly();
                    }
                } catch (InterruptedException ex) {
                    Thread.currentThread().interrupt();
                    process.destroyForcibly();
                }
            }
        }
    }
}
