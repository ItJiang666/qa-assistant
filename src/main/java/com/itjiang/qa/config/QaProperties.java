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
     * <p><b>顺序有意义</b>：报错话术会按这个顺序原样展示给用户，
     * 别改成 Set 或重排。
     */
    private String allowedExtensions = "txt,md,docx,doc,pdf,xlsx,html";

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
         */
        private int maxPages = 50;

        /**
         * 拆页渲染精度（DPI）。
         *
         * <p>200 是实测的平衡点：再低小字号容易糊（识别率下降），
         * 再高只是线性变慢（300 DPI 的像素量是 200 的 2.25 倍）。
         */
        private int dpi = 200;

        /** 低于此置信度的行直接丢弃 —— 错的内容比没有更危险（模型会当真）。 */
        private double minConfidence = 0.5;

        /**
         * 单次识别超时，形如 {@code 3m} / {@code 180s}。
         *
         * <p>3 分钟：50 页 × 2.4 s ≈ 2 分钟，留出余量。
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
         * <p><b>默认 0.75。</b>它是防幻觉的第一道闸门：低于阈值的片段不进 Prompt，
         * 从而触发"空资料占位符"这条路径。
         *
         * <p>实测分数分布（本地 qwen3.5:9b + 三份真实文档）：完全无关的话题
         * 0.63~0.73、话题相关但不含答案 0.81~0.89、真正含答案 0.83~0.89。
         * <b>后两档互相重叠</b>，所以这个阈值<b>切不掉"相关但没答案"的片段</b>，
         * 只能清掉完全无关的那一批 —— 别指望靠调它解决幻觉。
         *
         * <p>旧值 0.6 低于所有实际分数，等于没有过滤（任何提问都返回满 topK 条）；
         * 0.75 让完全无关的提问回落到 0 条，既缩短上下文，也让"没有资料"路径
         * 真正有机会被触发。调大的风险是误杀真正含答案的片段，表现为
         * "库里明明有的问题却答未找到"。
         */
        private double minScore = 0.75;

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
         * <p>默认 1024，两个用途：
         * <ol>
         *   <li><b>延迟兜底</b>：本地实测生成速度约 60 token/s，不设上限时模型偶尔会长篇大论，
         *       单次回答轻松超过 10 秒。1024 token 约合 700 汉字，与开发文档 4.6.6
         *       的"回答预算 ≈1000 token"一致。</li>
         *   <li><b>防止跑飞</b>：极端情况下模型可能进入复读，有硬上限才能保证请求最终结束。</li>
         * </ol>
         * 填 0 或负数表示不限制（回到模型默认）。
         */
        private int maxOutputTokens = 1024;

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
            private int maxOutputTokens = 1024;

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
