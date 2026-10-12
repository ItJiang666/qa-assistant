package com.itjiang.qa.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * qa-assistant 全部可配置项，统一绑定 {@code qa.*}（开发文档 10.5）。
 *
 * <p>集中定义的好处：配置项有单一出处，IDEA 里能自动补全、能跳转查看默认值，
 * 也不容易出现"yml 里改了但代码读的是另一个 key"这类幽灵问题。
 *
 * <p><b>命名约定</b>：yml 里用 kebab-case（{@code chunk-size}），
 * Java 用 camelCase（{@code chunkSize}），Spring 自动完成转换。
 *
 * @author Jiang
 */
@ConfigurationProperties(prefix = "qa")
public class QaProperties {

    /**
     * 模型模式：{@code local}（本地 Ollama）/ {@code cloud}（云端 API）。
     * 与 Spring profile 是两条正交的轴 —— profile 管数据源，本项管模型。
     */
    private String mode = "local";

    /**
     * 运行时数据根目录。
     *
     * <p>默认 {@code ${user.home}/.qa-assistant}（决策 19）。放在用户目录而不是项目目录，
     * 是为了让"IDEA 里启动"和"命令行 java -jar 启动"看到同一份数据。
     */
    private String dataDir;

    /**
     * 允许上传的扩展名，逗号分隔。
     *
     * <p>{@code doc} 是 Word 97-2003 的老式二进制格式，由 Tika 背后的
     * Apache POI HWPF 模块解析（{@code poi-scratchpad}，Tika 依赖里已经带了）。
     * 实测一份 25 KB 的真实 .doc 简历：解析出 4 个分块、正文无乱码、可被检索命中。
     *
     * <p>保留它的现实理由：政企、学校场景里存量 .doc 远多于 .docx。
     * 但老式二进制的解析健壮性不如 OOXML —— 复杂表格、嵌入对象、域代码
     * 可能解析不全。所以文档里统一建议"优先用 .docx"。
     *
     * <p><b>图片（jpg/jpeg/png/webp/bmp）与文字型格式的定位完全不同</b>，
     * 别把两者混为一谈：Tika 从图片里<b>提不出任何文字</b>（没有文字层），
     * 所以图片上传后必然停在"解析失败"，需要用户再点一次「识别文字」走 OCR
     * ——与扫描版 PDF 是同一条路。图片的扩展名必须在
     * {@code qa.ocr.recognizable-extensions} 里同样列一份，否则会出现
     * "能上传但识别按钮永远置灰"的死角（见该字段说明）。
     *
     * <p><b>顺序有意义</b>：报错话术会按这个顺序原样展示给用户，
     * 别改成 Set 或重排。
     */
    private String allowedExtensions = "txt,md,docx,doc,pdf,xlsx,html,jpg,jpeg,png,webp,bmp";

    private Ingest ingest = new Ingest();
    private Retrieval retrieval = new Retrieval();
    private Memory memory = new Memory();
    private Local local = new Local();
    private Cloud cloud = new Cloud();
    private Debug debug = new Debug();
    private Ocr ocr = new Ocr();

    /**
     * 本地离线 OCR（扫描版 PDF 识别）。
     *
     * <h2>为什么它是"可选增强"而不是又一个必需依赖</h2>
     * <p>本项目的核心承诺之一是"复制粘贴就能跑"：装个 JDK + Ollama 就能用。
     * OCR 要给 Python 生态（RapidOCR + PyMuPDF 约 32 MB），如果变成必需，
     * 这个承诺就破了。所以设计成：<b>不装也能跑全部主流程</b>，
     * 只有"扫描件入库"这一项需要它，且界面会给出明确的安装指引。
     *
     * <h2>为什么默认开启</h2>
     * <p>{@code enabled} 的语义是"允许尝试"，不是"已就绪"：
     * 真正能不能用由 {@code OcrEngine.availability()} 探测（找 Python → 找依赖）。
     * 默认 true 让装了依赖的人开箱可用，没装的人只是看到一个置灰的按钮 + 一句安装提示。
     */
    public static class Ocr {

        /** 是否允许尝试 OCR。关掉后界面直接置灰，不再探测。 */
        private boolean enabled = true;

        /**
         * 哪些扩展名值得走 OCR，逗号分隔。
         *
         * <h2>为什么需要它</h2>
         * <p>OCR 的价值只有一个：<b>从没有文字层的东西里把字读出来</b>。
         * 两类文件符合：
         * <ul>
         *   <li>{@code pdf} —— 扫描版 PDF 没有文字层（文字版 PDF 有，走普通解析即可）；</li>
         *   <li>{@code jpg/jpeg/png/webp/bmp} —— 图片<b>永远</b>没有文字层。</li>
         * </ul>
         * 其它格式（docx/doc/txt/md/xlsx/html）本来就带文字层，点「识别文字」
         * 等于把已经能读的字再"猜"一遍，纯属帮倒忙，所以拒绝并说明理由。
         *
         * <h2>为什么不干脆放开所有格式</h2>
         * <p>放开的话，用户对一份正常 docx 点识别能得到"可用的结果"（OCR 确实认得出来），
         * 于是他会以为这条路更好 —— 实际拿到的是<b>带错字的近似文本</b>，
         * 还把原件里清晰的文字层换成了机器猜的。这不是能力问题，是会误导人。
         *
         * <h2>与 allowed-extensions 的关系（重要）</h2>
         * <p>两者<b>独立配置、必须保持同步</b>：本项多出一个扩展名只会让按钮亮起来，
         * 上传那一步仍会被 {@code allowed-extensions} 挡住；
         * 反过来 {@code allowed-extensions} 多出 jpg 而本项没有，
         * 就会出现"能上传、解析必然失败、识别按钮却永远置灰"的死角 ——
         * 文件躺在列表里既不能用也删不掉（其实能删，但用户会以为坏了）。
         *
         * <p>不做成"自动取 allowed-extensions 的子集"：那样两个概念被绑死，
         * 以后想单独放开某个格式就得先拆结构。宁可多一行配置 + 一句提醒。
         */
        private String recognizableExtensions = "pdf,jpg,jpeg,png,webp,bmp";

        /**
         * 上传后是否自动跑一次 OCR，省掉"手动点识别文字"这一步。
         *
         * <h2>触发条件（很窄，不是每次上传都跑）</h2>
         * <p>只在<b>两个条件同时满足</b>时才自动识别：
         * <ol>
         *   <li>解析出来的文字是空的 —— 也就是扫描件、图片这类没有文字层的文件。
         *       正常 docx/txt 抽得到文字，压根不走这条。</li>
         *   <li>扩展名在 {@link #recognizableExtensions} 里。</li>
         * </ol>
         * 换句话说：**只对"本来就会解析失败"的文件生效**，正常文档的入库路径一个字都没变。
         *
         * <h2>为什么默认开启</h2>
         * <p>因为不自动识别的话，用户上传一张图会看到"失败"，还得自己意识到
         * "哦这里有个按钮可以点" —— 这一步对不熟悉系统的人是个坎。
         * 自动跑完之后仍然<b>停在"待确认"</b>，不会绕过人工核对（见 {@link #autoConfirm} 的说明）。
         *
         * <h2>关掉它会发生什么</h2>
         * <p>回到旧行为：上传后显示"失败" + 原因，用户自己点「识别文字」。
         * 保留这个开关是因为<b>OCR 是可选依赖</b>：没装 Python 的机器上，
         * 自动识别会白白失败一次（虽然会优雅降级，但日志里会多一条无意义的失败记录）。
         */
        private boolean autoRecognize = true;

        /**
         * 自动识别完成后，是否<b>跳过人工确认直接入库</b>。
         *
         * <h2>默认 false，且不建议改成 true</h2>
         * <p>OCR <b>一定</b>会有错字。这些错字一旦入库，就会被检索、被引用、被模型当成依据 ——
         * 而"收拾一份已经混进错误金额的扫描合同"比"点一下确认"麻烦得多。
         * 实测同一批清晰印刷体里既有全对的（`EQ-2043-A7`、`SN-88231`），
         * 也有被读坏的（`! d p`、全角 `２00`），而<b>用户无法从界面分辨哪份是哪份</b>。
         *
         * <p>所以自动化的边界划在"把字提出来"这一步，<b>不含"决定它能不能进知识库"</b>。
         * 留这个配置项是为了让"我就是要全自动"这个需求有出口，
         * 但开启即意味着放弃逐字核对这道闸门 —— 请只在自己的、可承担后果的语料上开。
         */
        private boolean autoConfirm = false;

        /**
         * Python 解释器路径。
         *
         * <p>留空则自动探测：先 {@code python3}、再 {@code python}。
         * 之所以要能显式指定：Windows 上常有多套 Python（venv、conda、Microsoft Store 版），
         * 自动探测可能挑中一个没装依赖的，那时用户需要一个不用改代码的出口。
         */
        private String python = "";

        /** OCR 脚本路径。留空则用 {@code deploy/ocr/qa_ocr.py}（相对工作目录）。 */
        private String script = "";

        /**
         * 单次识别的页数上限。
         *
         * <p>实测单页约 2.4 s（A4@200DPI），50 页约 2 分钟 —— 这是"用户愿意等"的上限。
         * 更重要的是：<b>必须在应用层挡</b>。若让 Python 因页数过多被 OOM 杀掉，
         * Java 只能看到一个退出码，给不出"请减少页数"这种可照做的提示。
         *
         * <p><b>v1.28 从 50 提到 100</b>：优化后单页耗时大幅下降（实测同一份 38 页扫描件
         * 从 128 s 降到 16.7 s），100 页扫描件约 45 s，仍在"可等"范围内。
         * 上限仍保留而不是放开：它同时是 OOM 与超时的第一道防线。
         */
        private int maxPages = 100;

        /**
         * 拆页渲染精度（DPI）。
         *
         * <p>200 是实测的平衡点：再低小字号容易糊（识别率下降），
         * 再高只是线性变慢（300 DPI 的像素量是 200 的 2.25 倍）。
         * <p>另有一条实测结论值得记下来：<b>降到 120 DPI 只快 16%</b>（1.54 s vs 1.83 s），
         * 因为识别耗时是按"文本行数"走的、不随 DPI 线性变化。所以调这个参数省不出时间，
         * 别指望它。真正有效的是 {@link #maxSideLen} 与 GPU。
         */
        private int dpi = 200;

        /** 低于此置信度的行直接丢弃 —— 错的内容比没有更危险（模型会当真）。 */
        private double minConfidence = 0.5;

        /**
         * 是否用 GPU 跑识别：{@code auto}（默认）/ {@code on} / {@code off}。
         *
         * <p><b>实测收益（用户那份 38 页扫描件，RTX 5070）</b>：CPU 65.3 s → GPU 20.2 s，
         * <b>3.2 倍</b>；识别结果逐页相似度 99.96%，38 页里 33 页输出完全一致 ——
         * <b>准确率零损失</b>。
         *
         * <p><b>{@code auto} 的语义是"能用就用，用不了静默回落 CPU"</b>：这在交付给他人
         * 部署的项目里是硬要求 —— 没显卡、没装 onnxruntime-gpu、CUDA 运行库缺失，
         * 三种情况都<b>不能报错</b>，只是不快而已。脚本会回报实际用的是哪个，
         * 见识别结果里的 {@code gpu} / {@code gpuReason}。
         *
         * <p>{@code on} 是"我确认有显卡，没生效就要告诉我" —— 不可用时直接失败并
         * 说明该装什么，而不是悄悄退回 CPU 让人以为"显卡白买了"。
         */
        private String useGpu = "auto";

        /**
         * 送进检测网络的位图长边上限。
         *
         * <p><b>实测 2000 → 1280 快 30%（2.21 → 1.55 s/页），且不丢内容</b>：
         * 逐行比对两档输出，差异全是"同一句话的空格/逗号位置不同"
         * （例：{@code ...偏好度为 rf (U, Fi)，} vs {@code ...偏好度为rf(U,Fi)，}），
         * 没有整句丢失。降到 960 会开始掉字（首页 756→725），所以 1280 是拐点。
         */
        private int maxSideLen = 1280;

        /**
         * 内嵌图占页面面积超过此比例时，判为"整页扫描图"并跳过单独识别。
         *
         * <p><b>为什么需要它（实测省 46% 时间）</b>：扫描件 PDF 的典型结构就是
         * "每页一张大图 = 这一页的扫描件本身"。实测用户那份 38 页 PDF 每页 1 张
         * 1190×1683 的图、占页面 <b>100%</b>；而"逐页渲染 + 识别"主流程已经把整页读过，
         * 再把这些图抽出来放大识别一遍是纯重复 —— 那一遍花了 <b>62 秒</b>，
         * 38 张里 <b>37 张被判重丢弃、只有 1 张真有字</b>。
         *
         * <p><b>能力一点没减</b>：这条通道是为"正文有文字层、中间插了张小截图"
         * 存在的（那种图只占页面一小块，约 15%，不会触发 80% 阈值）。
         * 已用对照文档验证：占页面 14.4% 的插图仍被单独识别、图内独有文字照旧读出来。
         *
         * <p>值设为 {@code >1} 可关闭这道过滤。
         */
        private double pageImageRatio = 0.8;

        /**
         * 单次识别超时，形如 {@code 3m} / {@code 180s}。
         *
         * <p>3 分钟：优化后 100 页 CPU 约 2 分钟，留出余量。
         * 超时会<b>连进程树一起杀</b>，避免孤儿 Python 继续吃 CPU。
         *
         * <p>用字符串 + {@code DurationParser} 而不是 {@code java.time.Duration} 字段：
         * 与本项目其它 timeout 配置（{@code qa.cloud.chat.timeout} 等）保持同一套写法，
         * 避免同一个项目里出现两种时长绑定约定。
         */
        private String timeout = "3m";

        public boolean isEnabled() {
            return enabled;
        }

        public void setEnabled(boolean enabled) {
            this.enabled = enabled;
        }

        public String getRecognizableExtensions() {
            return recognizableExtensions;
        }

        public void setRecognizableExtensions(String recognizableExtensions) {
            this.recognizableExtensions = recognizableExtensions;
        }

        public boolean isAutoRecognize() {
            return autoRecognize;
        }

        public void setAutoRecognize(boolean autoRecognize) {
            this.autoRecognize = autoRecognize;
        }

        public boolean isAutoConfirm() {
            return autoConfirm;
        }

        public void setAutoConfirm(boolean autoConfirm) {
            this.autoConfirm = autoConfirm;
        }

        public String getPython() {
            return python;
        }

        public void setPython(String python) {
            this.python = python;
        }

        public String getScript() {
            return script;
        }

        public void setScript(String script) {
            this.script = script;
        }

        public int getMaxPages() {
            return maxPages;
        }

        public void setMaxPages(int maxPages) {
            this.maxPages = maxPages;
        }

        public int getDpi() {
            return dpi;
        }

        public void setDpi(int dpi) {
            this.dpi = dpi;
        }

        public double getMinConfidence() {
            return minConfidence;
        }

        public void setMinConfidence(double minConfidence) {
            this.minConfidence = minConfidence;
        }

        public String getUseGpu() {
            return useGpu;
        }

        public void setUseGpu(String useGpu) {
            this.useGpu = useGpu;
        }

        public int getMaxSideLen() {
            return maxSideLen;
        }

        public void setMaxSideLen(int maxSideLen) {
            this.maxSideLen = maxSideLen;
        }

        public double getPageImageRatio() {
            return pageImageRatio;
        }

        public void setPageImageRatio(double pageImageRatio) {
            this.pageImageRatio = pageImageRatio;
        }

        public String getTimeout() {
            return timeout;
        }

        public void setTimeout(String timeout) {
            this.timeout = timeout;
        }
    }

    /**
     * 取 OCR 配置。
     *
     * @return OCR 配置
     */
    public Ocr getOcr() {
        return ocr;
    }

    /**
     * 设置 OCR 配置。
     *
     * @param ocr OCR 配置
     */
    public void setOcr(Ocr ocr) {
        this.ocr = ocr;
    }

    /**
     * 排查用开关。
     *
     * <h2>为什么单独分一节，而不是塞进 local / cloud</h2>
     * <p>因为它们<b>与模型来源无关</b>：{@code log-model-requests} 在本地与云端都要生效 ——
     * 实测那个"回答为 {@code []}"的异常在两种模式下各出现过一次，
     * 只挂在某一侧就会漏掉另一半现场。
     */
    public static class Debug {

        /**
         * 是否把发给模型的完整消息列表打进日志。
         *
         * <p>默认关闭。⚠️ 开启后日志里会有<b>用户提问原文与检索到的文档片段</b>，
         * 只在抓现场时临时打开，抓到即关。
         *
         * <p>它打印的是消息内容，<b>不含 HTTP 头</b>，因此不会泄露 API Key ——
         * 这也是不能直接用 LangChain4j 自带 {@code logRequests(true)} 的原因
         * （那个会连 {@code Authorization} 头一起打出来）。
         */
        private boolean logModelRequests = false;

        public boolean isLogModelRequests() {
            return logModelRequests;
        }

        public void setLogModelRequests(boolean logModelRequests) {
            this.logModelRequests = logModelRequests;
        }
    }

    public Debug getDebug() {
        return debug;
    }

    public void setDebug(Debug debug) {
        this.debug = debug;
    }

    /**
     * @return 数据根目录（已解析为 Path）
     */
    public Path dataPath() {
        return Path.of(dataDir);
    }

    /**
     * @return 上传文件落盘目录
     */
    public Path uploadPath() {
        return dataPath().resolve("uploads");
    }

    /**
     * @return 向量库落盘目录
     */
    public Path indexPath() {
        return dataPath().resolve("index");
    }

    // ── getter / setter（JavaBean 绑定需要）─────────────────────────────────

    public String getMode() {
        return mode;
    }

    public void setMode(String mode) {
        this.mode = mode;
    }

    public String getDataDir() {
        return dataDir;
    }

    public void setDataDir(String dataDir) {
        this.dataDir = dataDir;
    }

    public String getAllowedExtensions() {
        return allowedExtensions;
    }

    public void setAllowedExtensions(String allowedExtensions) {
        this.allowedExtensions = allowedExtensions;
    }

    public Ingest getIngest() {
        return ingest;
    }

    public void setIngest(Ingest ingest) {
        this.ingest = ingest;
    }

    public Retrieval getRetrieval() {
        return retrieval;
    }

    public void setRetrieval(Retrieval retrieval) {
        this.retrieval = retrieval;
    }

    public Memory getMemory() {
        return memory;
    }

    public void setMemory(Memory memory) {
        this.memory = memory;
    }

    public Local getLocal() {
        return local;
    }

    public void setLocal(Local local) {
        this.local = local;
    }

    public Cloud getCloud() {
        return cloud;
    }

    public void setCloud(Cloud cloud) {
        this.cloud = cloud;
    }

    /**
     * 文档入库参数。
     */
    public static class Ingest {

        /** 分块大小（字）。500 是起步值，可按文档类型调整。 */
        private int chunkSize = 500;

        /** 分块重叠（字）。建议为 chunkSize 的 10%，太小会切断跨段语义。 */
        private int chunkOverlap = 50;

        public int getChunkSize() {
            return chunkSize;
        }

        public void setChunkSize(int chunkSize) {
            this.chunkSize = chunkSize;
        }

        public int getChunkOverlap() {
            return chunkOverlap;
        }

        public void setChunkOverlap(int chunkOverlap) {
            this.chunkOverlap = chunkOverlap;
        }
    }

    /**
     * 检索参数。
     */
    public static class Retrieval {

        /**
         * 检索返回的最相似片段数量。
         *
         * <p>默认 <b>4</b>。第一轮召回评测曾据此选 8（topK=4 命中 79%、6→86%、8→93%），
         * 但第二轮端到端评测（12 探针，判定"最终答没答对"）显示：
         * <b>topK=8 与 topK=4 的答对率完全相同（8/8）</b>，而 8 每次无条件塞满 8 条，
         * 多出来的片段是"模型拿无关内容补全回答"的诱因。
         * 第一轮量的是"答案块在不在前 k 名"，第二轮量的是用户真正感知的结果 ——
         * 后者显示 4 已经够用。上限受开发文档 2.2 约束（单次问答检索 topK ≤ 10）。
         */
        private int topK = 4;

        /**
         * 相似度阈值，低于它的片段不交给模型。
         *
         * <p><b>默认 0.65。</b>它是防幻觉的第一道闸门：低于阈值的片段不进 Prompt，
         * 从而触发"空资料占位符"这条路径。
         *
         * <h2>为什么从 0.75 降到 0.65（2026-10-11 实测）</h2>
         * <p>0.75 是在<b>三份 206/8/4 块的大文档</b>上调出来的，对那种库完全正确
         * （无关提问 0.63~0.73 被挡住）。但它有个当时没暴露的毛病：
         * <b>短文档的相似度天然偏低</b>，会被一并误杀。
         *
         * <p>决定性对照（同内容、同块大小，只差"有没有文字层"）：
         * <pre>
         *   扫描件（3 页无文字层，走 OCR）  6 问平均 0.7010，过 0.75 的 0/6
         *   同一内容的有文字层 PDF         6 问平均 0.7220，过 0.75 的 1/6
         * </pre>
         * 也就是说：<b>这跟 OCR 准不准、跟是不是图片都无关</b> ——
         * 是"短文本对短查询"这个组合本身分低。实测分数与库的规模强相关：
         * <pre>
         *   206 块 / 每块 500~1000 字  →  0.80 ~ 0.85
         *   1~3 块 / 每块 400~500 字   →  0.67 ~ 0.78
         * </pre>
         * 代价是这类文档在 0.75 下"入库成功却永远检索不到"，而界面上完全看不出原因。
         *
         * <h2>为什么是 0.65 而不是 0.70</h2>
         * <p>0.70 救不回上面那批文档（分数 0.67~0.74，多数仍被挡）；
         * 0.65 能救回，且经探针验证没有引入编造 —— 域外与"话题相关但无答案"共 11 问，
         * 在 0.65 下仍全部如实回答"现有资料中未找到相关信息"。
         *
         * <h2>仍然切不掉的那一类</h2>
         * <p>实测分数分布里"话题相关但不含答案"是 0.81~0.89，比"真正含答案"的
         * 0.83~0.89 还要高一点 —— <b>两者重叠</b>，所以任何阈值都切不掉这一类。
         * 别指望靠调它解决幻觉；它只能挡住"完全无关"的那一批。
         *
         * <h2>v1.29 用当前向量模型（text-embedding-v4）复测的分布</h2>
         * <p>38 页扫描件 / 49 个分块：
         * <pre>
         *   本领域·答案在库里（9 问）                    0.67 ~ 0.89   全部 ≥0.65
         *   域外（首都 / 天气 / 红烧肉）                  0.59 ~ 0.64   全部 &lt;0.65
         *   其中"章节号型"提问（1.3内容是什么）的 top1   0.6894
         * </pre>
         * 结论不变：<b>0.65 在这份语料上恰好把两类分开</b>。
         * 但要注意那个"章节号型"提问虽然过了阈值，<b>真正含答案的分块却排在第 21 名</b> ——
         * 那是**排序问题不是阈值问题**，靠调这个参数永远解决不了，
         * 得用 {@link Retrieval#literalSearch} 那条字面通道。
         */
        private double minScore = 0.65;

        /**
         * 是否启用"字面引用检索"这条补充通道（默认开）。
         *
         * <h2>它解决的是向量检索天生做不到的一类问题 —— 实测这就是本次修复的来源</h2>
         * 用户问「1.3 内容是什么」时，{@code 1.3} 在向量空间里<b>几乎没有语义</b> ——
         * 它是"位置信息"（文档的第几节），不是"意思"。而真正含 1.3 正文的那个分块
         * 向量排名第 21，进不了 topK=10，于是模型只看得到目录里那一行。
         *
         * <p>三条通道的分离对照（同一份 38 页扫描件、49 个分块）：
         * <pre>
         *   两条通道都关  → 答"资料片段中只出现了目录条目 1.3 国内外研究现状" ❌
         *   只开字面通道  → 答出 1.3.1 国内 / 1.3.2 国外的正文内容            ✅
         *   两条都开      → 同上（说明相对兜底没参与）
         * </pre>
         *
         * <p>这是<b>加一条通道，不是替换向量检索</b>：向量那一路完全不动。
         * 风险很低 —— 它只认形式明确的引用（章节号 / 引号内原文），
         * 命中本身就构成"这个分块里真的出现了你引用的字符串"这一相关性证据。
         */
        private boolean literalSearch = true;

        /**
         * 相对兜底：绝对阈值一条都没放行时，允许"明显胜出"的候选进来（默认开）。
         *
         * <p><b>老实说：这条在本项目实测的语料上一次都没触发过，收益没有被验证。</b>
         * 留下它是作为"低分语料"的保险 —— 那种语料里所有命中都低于绝对阈值，
         * 只剩"谁明显胜出"这一个可用判据。
         *
         * <h2>⚠️ 一段值得记下来的弯路</h2>
         * <p>我一度用"自己写脚本算余弦"的方式量出：这份文档 7 个"答案确实在库里"的
         * 问题只有 3 个能过 0.65（其余 0.44~0.64），据此判定"绝对阈值对同质文档整体误杀"。
         * <b>那个结论是错的。</b> 换成应用自己的 {@code /documents/search} 重新量，
         * 真实分布是 0.67~0.89（本领域 9 问全部达标）、0.59~0.64（域外全部不达标）——
         * <b>0.65 恰好准确</b>。离线脚本算出的排名与线上完全一致，但绝对值整体低约 0.31。
         *
         * <p>教训：<b>排名对、数值错的结论比不知道更危险</b> —— 它会让人去改一个
         * 本来正确的参数。调 {@code minScore} 之前必须用 {@code /documents/search} 取分。
         */
        private boolean relativeFallback = true;

        /**
         * 相对兜底的比值门槛：top1 / top2 ≥ 此值才认为"有明显赢家"。
         *
         * <p>默认 1.15 —— 实测这份语料的比值大多在 1.00~1.06（本领域与域外都如此），
         * 所以它在实际使用中很少触发，这与"它一次都没触发过"的观察一致。
         * 保留这个门槛是为了在真正低分的语料上仍然要求"有明显赢家"，
         * 而不是无条件放行 top1。
         */
        private double relativeFallbackRatio = 1.15;

        /** 字面引用检索最多补进几条候选。 */
        private int literalMaxResults = 3;

        private QueryExpansion queryExpansion = new QueryExpansion();

        /**
         * 查询改写（Query Expansion）。
         *
         * <h2>它解决的是哪一类问题</h2>
         * <p>不是"检索算得不准"，而是<b>提问用词与文档用词对不上</b>：
         * 实测里唯一一个怎么放大召回都捞不到的探针，是"检索为空时怎么防止模型乱答" ——
         * 文档里写的是"空资料占位符""防幻觉"，压根没有"乱答"这两个字。
         * 向量检索对这种"同义不同词"无能为力，而让模型先把问题改写成文档可能使用的说法，
         * 是最直接的解法。
         *
         * <h2>代价（必须知道）</h2>
         * <p>每个问题多一次小模型调用：本地约 0.3~0.6 秒，云端约 0.2~0.5 秒，并产生少量 token 费用。
         * 因此单独给一个开关，量出收益不划算时可以立刻关掉。
         */
        public static class QueryExpansion {

            /**
             * 是否启用。
             *
             * <p><b>默认关闭 —— 这是评测结论，不是保守。</b>
             * 15 个探针开/关对照，命中率完全一样，而检索耗时约 3 倍（395ms → 1186ms）。
             * 追查后发现唯一那条漏检是"内容根本不在知识库里"（库里那份 docx 是旧版本），
             * 即对库里确实存在的内容，单路检索已经全部命中 ——
             * 改写救不了"库里没有的内容"，在这个知识库上它只是纯开销。
             *
             * <p>但它的适用场景真实存在（文档用词与用户口语差距大、或换一批文档），
             * 所以实现与开关都留着：开启前先用 {@code /api/v1/documents/search}
             * 传 {@code expand=true} 量一遍增益再决定。
             */
            private boolean enabled = false;

            /**
             * 额外生成几条改写式（不含原问题）。
             *
             * <p>默认 2：加上原问题共 3 路检索。再多收益递减，而每次都多花一次调用。
             */
            private int extraQueries = 2;

            public boolean isEnabled() {
                return enabled;
            }

            public void setEnabled(boolean enabled) {
                this.enabled = enabled;
            }

            public int getExtraQueries() {
                return extraQueries;
            }

            public void setExtraQueries(int extraQueries) {
                this.extraQueries = extraQueries;
            }
        }

        public QueryExpansion getQueryExpansion() {
            return queryExpansion;
        }

        public void setQueryExpansion(QueryExpansion queryExpansion) {
            this.queryExpansion = queryExpansion;
        }

        public int getTopK() {
            return topK;
        }

        public void setTopK(int topK) {
            this.topK = topK;
        }

        public double getMinScore() {
            return minScore;
        }

        public void setMinScore(double minScore) {
            this.minScore = minScore;
        }

        public boolean isLiteralSearch() {
            return literalSearch;
        }

        public void setLiteralSearch(boolean literalSearch) {
            this.literalSearch = literalSearch;
        }

        public boolean isRelativeFallback() {
            return relativeFallback;
        }

        public void setRelativeFallback(boolean relativeFallback) {
            this.relativeFallback = relativeFallback;
        }

        public double getRelativeFallbackRatio() {
            return relativeFallbackRatio;
        }

        public void setRelativeFallbackRatio(double relativeFallbackRatio) {
            this.relativeFallbackRatio = relativeFallbackRatio;
        }

        public int getLiteralMaxResults() {
            return literalMaxResults;
        }

        public void setLiteralMaxResults(int literalMaxResults) {
            this.literalMaxResults = literalMaxResults;
        }
    }

    /**
     * 记忆体系参数（开发文档 4.6 / 6.3）。
     */
    public static class Memory {

        /** L3 每轮注入条数上限。 */
        private int injectMaxCount = 8;

        /** L3 每轮注入字数上限，与 token 预算对齐。 */
        private int injectMaxChars = 800;

        /** L3 单用户记忆总量上限，超出按重要度淘汰。 */
        private int maxPerUser = 200;

        /** L3 抽取置信度阈值，低于此值不写入。 */
        private double minConfidence = 0.6;

        /** 长期记忆总开关，关闭后只保留 L2 会话记忆。 */
        private boolean extractEnabled = true;

        /** L2 历史 token 预算。 */
        private int windowMaxTokens = 3000;

        /** L2 兜底策略：按条数裁剪时的条数。 */
        private int windowFallbackMessages = 20;

        public int getInjectMaxCount() {
            return injectMaxCount;
        }

        public void setInjectMaxCount(int injectMaxCount) {
            this.injectMaxCount = injectMaxCount;
        }

        public int getInjectMaxChars() {
            return injectMaxChars;
        }

        public void setInjectMaxChars(int injectMaxChars) {
            this.injectMaxChars = injectMaxChars;
        }

        public int getMaxPerUser() {
            return maxPerUser;
        }

        public void setMaxPerUser(int maxPerUser) {
            this.maxPerUser = maxPerUser;
        }

        public double getMinConfidence() {
            return minConfidence;
        }

        public void setMinConfidence(double minConfidence) {
            this.minConfidence = minConfidence;
        }

        public boolean isExtractEnabled() {
            return extractEnabled;
        }

        public void setExtractEnabled(boolean extractEnabled) {
            this.extractEnabled = extractEnabled;
        }

        public int getWindowMaxTokens() {
            return windowMaxTokens;
        }

        public void setWindowMaxTokens(int windowMaxTokens) {
            this.windowMaxTokens = windowMaxTokens;
        }

        public int getWindowFallbackMessages() {
            return windowFallbackMessages;
        }

        public void setWindowFallbackMessages(int windowFallbackMessages) {
            this.windowFallbackMessages = windowFallbackMessages;
        }
    }

    /**
     * 本地模型（Ollama）参数。
     */
    public static class Local {

        /** Ollama 服务地址。 */
        private String baseUrl = "http://localhost:11434";

        /**
         * 对话模型。
         *
         * <p>默认 {@code qwen3.5:9b}。不要换成 {@code deepseek-r1:8b}：
         * 那是推理模型，每次回答前会先"思考" 20~40 秒，交互体验极差。
         */
        private String chatModel = "qwen3.5:9b";

        /**
         * 向量模型。
         *
         * <p>默认 {@code qa-embed:4b}，由 {@code deploy/ollama/Modelfile.qa-embed} 派生：
         * {@code FROM qwen3-embedding:4b} + {@code PARAMETER num_ctx 2048}。部署前要执行一次
         * {@code ollama create qa-embed:4b -f deploy/ollama/Modelfile.qa-embed}。
         *
         * <p><b>为什么不直接用 qwen3-embedding:4b：</b>那个模型自带 40960 的上下文窗口，
         * Ollama 会照此分配 KV 缓存，实测占掉约 10 GB 显存。在 12 GB 显卡上它与
         * 对话模型无法共存，Ollama 只能"加载一个踢掉另一个"，于是<b>每次提问</b>都要
         * 重新加载：嵌入 2.9 秒 + 对话 4.8 秒。压到 2048 后占用降到 4.1 GB，两者长期共存，
         * 单次问答从 7.7 秒回到 0.7 秒（实测见开发文档 9.1）。
         *
         * <p><b>维度仍是 2560</b>（与 qwen3-embedding:4b 共用同一份权重，num_ctx 只影响
         * 能读多长的输入，不影响向量本身），因此切换过来<b>不需要</b>重建索引；
         * 换成别的向量模型才必须全量重建。
         */
        private String embeddingModel = "qa-embed:4b";

        /**
         * 上下文窗口大小，可填 {@code auto} 或具体数字（4096 / 8192 / 16384）。
         *
         * <p>{@code auto} 会探测显存后选择安全值（开发文档 12.6）——
         * 这是开源场景的必需项：本机按 12 GB 显存算出 16384，
         * 换一台 8 GB 的机器硬编码同一个值会直接 OOM。
         */
        private String numCtx = "auto";

        /**
         * 单次回答的最大生成长度（token），对应 Ollama 的 {@code num_predict}。
         *
         * <p>默认 <b>2048</b>，两个用途：
         * <ol>
         *   <li><b>别把完整答案截断</b>：原先取值 1024（约 700 汉字），是按"回答控制在
         *       200 字以内"那条提示词配的。2026-10-12 提示词改成"答完整"之后，
         *       700 字的余量对"把资料里各方面都列出来"就偏紧了 ——
         *       截断发生在用户看不见的地方（回答戛然而止且无提示），比慢几秒更糟。</li>
         *   <li><b>防止跑飞</b>：极端情况下模型可能进入复读，有硬上限才能保证请求最终结束。</li>
         * </ol>
         * 2048 token 约合 1400 汉字。实测完整回答里最长的一条是 409 字（约 600 token），
         * 所以这个上限留了足够余量，同时仍能在复读时兜住。
         * 填 0 或负数表示不限制（回到模型默认）。
         */
        private int maxOutputTokens = 2048;

        /** 模型调用超时（ISO-8601 时长格式）。 */
        private String timeout = "120s";

        /**
         * 是否让 qwen3.5 这类模型先输出思维链再作答。
         *
         * <p><b>默认关闭，这个默认值是有实测依据的。</b> 本机实测（RTX 5070 / qwen3.5:9b）：
         * <pre>
         *   think=true   一句"你好"生成 777 字思维链，耗时 6860 ms
         *   think=false  同样的问题，耗时  415 ms   —— 快 16.5 倍
         * </pre>
         * 本项目的场景是"查资料后给结论"，不是数学证明，思维链带来的收益远小于它的延迟代价。
         * 另外提示词层面的 {@code /no_think} 软开关实测<b>无效</b>（6.5 秒，仍生成思维链），
         * 必须走 API 参数，所以这里必须显式设置。
         */
        private boolean think = false;

        /**
         * 是否打印发往 Ollama 的请求体。
         *
         * <p>默认关闭。只在排查"参数到底有没有生效"这类问题时临时打开 ——
         * 例如 {@code think=false} 是否真的传给了服务端。请求体里会包含用户问题，
         * 因此<b>生产环境不要打开</b>。
         */
        private boolean logRequests = false;

        /**
         * 是否打印 Ollama 的原始响应体。
         *
         * <p>同样只用于排查问题：可以据此确认模型是否返回了 thinking 段落。
         */
        private boolean logResponses = false;

        public String getBaseUrl() {
            return baseUrl;
        }

        public void setBaseUrl(String baseUrl) {
            this.baseUrl = baseUrl;
        }

        public String getChatModel() {
            return chatModel;
        }

        public void setChatModel(String chatModel) {
            this.chatModel = chatModel;
        }

        public String getEmbeddingModel() {
            return embeddingModel;
        }

        public void setEmbeddingModel(String embeddingModel) {
            this.embeddingModel = embeddingModel;
        }

        public String getNumCtx() {
            return numCtx;
        }

        public void setNumCtx(String numCtx) {
            this.numCtx = numCtx;
        }

        public int getMaxOutputTokens() {
            return maxOutputTokens;
        }

        public void setMaxOutputTokens(int maxOutputTokens) {
            this.maxOutputTokens = maxOutputTokens;
        }

        public String getTimeout() {
            return timeout;
        }

        public void setTimeout(String timeout) {
            this.timeout = timeout;
        }

        public boolean isThink() {
            return think;
        }

        public void setThink(boolean think) {
            this.think = think;
        }

        public boolean isLogRequests() {
            return logRequests;
        }

        public void setLogRequests(boolean logRequests) {
            this.logRequests = logRequests;
        }

        public boolean isLogResponses() {
            return logResponses;
        }

        public void setLogResponses(boolean logResponses) {
            this.logResponses = logResponses;
        }
    }

    /**
     * 云端模型参数（P4）。
     *
     * <h2>为什么对话与向量各自独立配一套</h2>
     * <p>因为它们<b>必然来自不同厂商</b>：DeepSeek 没有 embedding 接口（实测 404），
     * 云端模式下向量只能另找一家。所以这里是两个平行的端点配置，而不是"选一个厂商"。
     *
     * <h2>预设（preset）+ 覆盖（override）</h2>
     * <p>国内厂商的差异只有三样：base-url、模型名、Key 所在的环境变量。
     * 与其让用户每次手填，不如内置一份预设表（{@code qa.cloud.presets}），
     * 用户只写 {@code provider: deepseek}；某一项要特殊处理时再单独覆盖。
     * <b>留空 = 用预设值，填了 = 以填的为准</b>，这条规则只在
     * {@code CloudEndpointResolver} 一处实现，别处不许再判断。
     *
     * <p><b>为什么 Key 只从环境变量取：</b>仓库与数据库都不该出现密钥（开发文档 12.4 红线）。
     * 预设里只写"该读哪个环境变量"（{@code api-key-env}），值永远来自运行环境；
     * 界面上只显示"已配置 / 未配置"，不回显内容。
     */
    public static class Cloud {

        /** 对话端点。 */
        private Chat chat = new Chat();

        /** 向量端点。 */
        private Embedding embedding = new Embedding();

        /**
         * 厂商预设表：id → 端点默认值。
         *
         * <p>用 {@code LinkedHashMap} 保持 YAML 里的书写顺序 —— 界面的厂商下拉框
         * 直接按这个顺序展示，常用厂商排在前面靠配置顺序控制即可。
         */
        private Map<String, Preset> presets = new LinkedHashMap<>();

        public Chat getChat() {
            return chat;
        }

        public void setChat(Chat chat) {
            this.chat = chat;
        }

        public Embedding getEmbedding() {
            return embedding;
        }

        public void setEmbedding(Embedding embedding) {
            this.embedding = embedding;
        }

        public Map<String, Preset> getPresets() {
            return presets;
        }

        public void setPresets(Map<String, Preset> presets) {
            this.presets = presets;
        }

        /**
         * 对话端点（OpenAI 兼容）。
         */
        public static class Chat {

            /** 厂商预设 id，对应 {@code qa.cloud.presets} 的键。 */
            private String provider = "deepseek";

            /** base-url 覆盖值；留空用预设。 */
            private String baseUrl;

            /** 模型名覆盖值；留空用预设。 */
            private String model;

            /** Key 覆盖值；留空则按预设的 {@code api-key-env} 读环境变量。 */
            private String apiKey;

            /**
             * 单次请求超时。
             *
             * <p>默认 60s，比本地的 120s 短：云端超时通常意味着网络问题或服务异常，
             * 让用户早一点看到明确错误，比干等两分钟强。
             */
            private String timeout = "60s";

            /**
             * 失败重试次数（指数退避）。
             *
             * <p>比本地的 1 次多：云端会因限流返回 429，这类失败重试一下往往就好了；
             * 本地失败几乎都是"Ollama 没启动"，重试没有意义。
             */
            private int maxRetries = 2;

            /** 单次回答 token 上限，语义与 {@code qa.local.max-output-tokens} 一致。 */
            private int maxOutputTokens = 2048;

            /**
             * 抽取模型是否要求 JSON 输出（{@code response_format: json_object}）。
             *
             * <p>OpenAI 兼容协议里这是标准能力，能让 L3 记忆抽取的 JSON 更稳；
             * 个别厂商不支持该参数，遇到 400 就把它关掉。
             */
            private boolean jsonMode = true;

            /**
             * 是否允许云端模型先输出思维链再作答。
             *
             * <p><b>默认关闭，理由与本地模式实测一致</b>（决策 22）：本项目是"查资料给结论"，
             * 不是数学证明。本地实测 {@code think=true} 比 {@code false} 慢 <b>16.5 倍</b>；
             * 而 DeepSeek 当前模型是<b>默认开启思考</b>的，不显式关掉就会重演那个延迟。
             *
             * <p>各厂商"怎么关"的参数名不一样（DeepSeek 是 {@code thinking}，
             * 通义系是 {@code enable_thinking}），所以<b>不写死在这里</b>，
             * 而是由厂商预设的 {@code thinking-off-params} 描述。
             */
            private boolean thinking = false;

            /**
             * 额外透传到请求体的参数（原样交给厂商）。
             *
             * <p>给"厂商上了新参数但我们还没发版"留的口子：例如临时调
             * {@code reasoning_effort}。与预设参数冲突时<b>以这里为准</b>
             * （用户显式配置优先于内置预设）。
             */
            private Map<String, Object> customParameters = new LinkedHashMap<>();

            public boolean isThinking() {
                return thinking;
            }

            public void setThinking(boolean thinking) {
                this.thinking = thinking;
            }

            public Map<String, Object> getCustomParameters() {
                return customParameters;
            }

            public void setCustomParameters(Map<String, Object> customParameters) {
                this.customParameters = customParameters;
            }

            public String getProvider() {
                return provider;
            }

            public void setProvider(String provider) {
                this.provider = provider;
            }

            public String getBaseUrl() {
                return baseUrl;
            }

            public void setBaseUrl(String baseUrl) {
                this.baseUrl = baseUrl;
            }

            public String getModel() {
                return model;
            }

            public void setModel(String model) {
                this.model = model;
            }

            public String getApiKey() {
                return apiKey;
            }

            public void setApiKey(String apiKey) {
                this.apiKey = apiKey;
            }

            public String getTimeout() {
                return timeout;
            }

            public void setTimeout(String timeout) {
                this.timeout = timeout;
            }

            public int getMaxRetries() {
                return maxRetries;
            }

            public void setMaxRetries(int maxRetries) {
                this.maxRetries = maxRetries;
            }

            public int getMaxOutputTokens() {
                return maxOutputTokens;
            }

            public void setMaxOutputTokens(int maxOutputTokens) {
                this.maxOutputTokens = maxOutputTokens;
            }

            public boolean isJsonMode() {
                return jsonMode;
            }

            public void setJsonMode(boolean jsonMode) {
                this.jsonMode = jsonMode;
            }
        }

        /**
         * 向量端点（OpenAI 兼容）。
         */
        public static class Embedding {

            /** 厂商预设 id。 */
            private String provider = "siliconflow";

            /** base-url 覆盖值；留空用预设。 */
            private String baseUrl;

            /** 模型名覆盖值；留空用预设。 */
            private String model;

            /** Key 覆盖值；留空则按预设的 {@code api-key-env} 读环境变量。 */
            private String apiKey;

            private String timeout = "60s";

            private int maxRetries = 2;

            /**
             * 向量维度。留空则按预设里该模型的维度推导。
             *
             * <p><b>这个值不是装饰</b>：换向量模型 = 换向量空间，维度不同时旧索引
             * 必须重建（开发文档 4.5）。启动时用它和已有索引的维度比对，
             * 不一致就明确报错，绝不静默降级 —— 静默的后果是"检索结果莫名其妙地烂掉"。
             */
            private Integer dimension;

            /**
             * 单次 embedAll 的批大小。
             *
             * <p>默认 10。这个默认值是**被真实报错教育出来的**：本地 Ollama 无所谓，
             * 但云端有限制，而且各家不一样 —— 百炼 {@code text-embedding-v4}
             * 单次最多 10 条，超了直接 400：
             * <pre>
             *   400 InvalidParameter: batch size is invalid, it should not be larger than 10.
             * </pre>
             * 所以这里取"所有已知厂商都安全"的最小值；各家上限写在预设的
             * {@code embedding-max-batch} 里，由 {@code CloudEndpointResolver}
             * 取"配置值与厂商上限的较小者"，用户想为了提速调大也不会越界。
             */
            private int batchSize = 10;

            public String getProvider() {
                return provider;
            }

            public void setProvider(String provider) {
                this.provider = provider;
            }

            public String getBaseUrl() {
                return baseUrl;
            }

            public void setBaseUrl(String baseUrl) {
                this.baseUrl = baseUrl;
            }

            public String getModel() {
                return model;
            }

            public void setModel(String model) {
                this.model = model;
            }

            public String getApiKey() {
                return apiKey;
            }

            public void setApiKey(String apiKey) {
                this.apiKey = apiKey;
            }

            public String getTimeout() {
                return timeout;
            }

            public void setTimeout(String timeout) {
                this.timeout = timeout;
            }

            public int getMaxRetries() {
                return maxRetries;
            }

            public void setMaxRetries(int maxRetries) {
                this.maxRetries = maxRetries;
            }

            public Integer getDimension() {
                return dimension;
            }

            public void setDimension(Integer dimension) {
                this.dimension = dimension;
            }

            public int getBatchSize() {
                return batchSize;
            }

            public void setBatchSize(int batchSize) {
                this.batchSize = batchSize;
            }
        }

        /**
         * 一个厂商预设。
         *
         * <p>只描述"官方端点长什么样、Key 放哪个环境变量"，不掺任何业务逻辑。
         * 模型清单是给界面下拉框与自检提示用的，实际调用以 {@code model} 字段为准 ——
         * 厂商上新模型比我们发版快，用户可以随时在配置里覆盖。
         */
        public static class Preset {

            /** 界面显示名。 */
            private String label;

            /** Key 所在的环境变量名，例如 {@code DEEPSEEK_API_KEY}。 */
            private String apiKeyEnv;

            /** OpenAI 兼容端点根地址。 */
            private String baseUrl;

            /** 常用对话模型名，第一个作为默认值。 */
            private List<String> chatModels = new ArrayList<>();

            /** 常用向量模型名，第一个作为默认值。 */
            private List<String> embeddingModels = new ArrayList<>();

            /** 向量模型 → 维度。用于切模型时的维度校验与界面提示。 */
            private Map<String, Integer> embeddingDimensions = new LinkedHashMap<>();

            /**
             * 该厂商向量接口单次能提交的最大条数；未知则留空。
             *
             * <p>这是"厂商差异"的又一处硬限制：百炼 {@code text-embedding-v4} 是 10，
             * 智谱 {@code embedding-3} 是 64，混元只收 input/model 两个参数。
             * 写进预设后，用户换厂商不必去翻文档，也不会因为超限而在入库时吃 400。
             */
            private Integer embeddingMaxBatch;

            /** 该厂商的注意事项（界面提示用），例如"无 embedding 接口"。 */
            private String notes;

            /**
             * 关闭思考模式要发的厂商专属参数。
             *
             * <p>这是"厂商差异"最典型的一处：DeepSeek 用 {@code thinking: {type: disabled}}，
             * 通义系用 {@code enable_thinking: false}，还有的用 {@code reasoning_effort: none}。
             * 写死在代码里就意味着"每接一家新厂商都要改 Java"，所以放进预设。
             *
             * <p>仅当 {@code qa.cloud.chat.thinking=false} 时才会合并进请求体。
             */
            private Map<String, Object> thinkingOffParams = new LinkedHashMap<>();

            public String getLabel() {
                return label;
            }

            public void setLabel(String label) {
                this.label = label;
            }

            public String getApiKeyEnv() {
                return apiKeyEnv;
            }

            public void setApiKeyEnv(String apiKeyEnv) {
                this.apiKeyEnv = apiKeyEnv;
            }

            public String getBaseUrl() {
                return baseUrl;
            }

            public void setBaseUrl(String baseUrl) {
                this.baseUrl = baseUrl;
            }

            public List<String> getChatModels() {
                return chatModels;
            }

            public void setChatModels(List<String> chatModels) {
                this.chatModels = chatModels;
            }

            public List<String> getEmbeddingModels() {
                return embeddingModels;
            }

            public void setEmbeddingModels(List<String> embeddingModels) {
                this.embeddingModels = embeddingModels;
            }

            public Map<String, Integer> getEmbeddingDimensions() {
                return embeddingDimensions;
            }

            public void setEmbeddingDimensions(Map<String, Integer> embeddingDimensions) {
                this.embeddingDimensions = embeddingDimensions;
            }

            public Integer getEmbeddingMaxBatch() {
                return embeddingMaxBatch;
            }

            public void setEmbeddingMaxBatch(Integer embeddingMaxBatch) {
                this.embeddingMaxBatch = embeddingMaxBatch;
            }

            public String getNotes() {
                return notes;
            }

            public void setNotes(String notes) {
                this.notes = notes;
            }

            public Map<String, Object> getThinkingOffParams() {
                return thinkingOffParams;
            }

            public void setThinkingOffParams(Map<String, Object> thinkingOffParams) {
                this.thinkingOffParams = thinkingOffParams;
            }
        }
    }
}
