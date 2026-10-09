package com.itjiang.qa.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.nio.file.Path;

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

    /** 允许上传的扩展名，逗号分隔。 */
    private String allowedExtensions = "txt,md,docx,pdf,xlsx,html";

    private Ingest ingest = new Ingest();
    private Retrieval retrieval = new Retrieval();
    private Memory memory = new Memory();
    private Local local = new Local();
    private Cloud cloud = new Cloud();

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

        /** 检索返回的最相似片段数量。 */
        private int topK = 4;

        /**
         * 相似度阈值，低于它的片段不交给模型。
         *
         * <p>这是防幻觉的第一道闸门：宁可回答"资料里没有"，也不要拿不相干的片段硬编。
         */
        private double minScore = 0.6;

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

        /** 向量模型。注意维度是 2560，不是网上常说的 768。 */
        private String embeddingModel = "qwen3-embedding:4b";

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
     * 云端模型参数（决策 3/4：本期只留骨架，P4 才联调）。
     */
    public static class Cloud {

        /** 对话模型配置。 */
        private Endpoint chat = new Endpoint();

        /**
         * 向量模型配置。
         *
         * <p>必须与对话分开配：DeepSeek 没有 embedding 接口（实测 404），
         * 云端模式下向量只能另找一家。
         */
        private Endpoint embedding = new Endpoint();

        public Endpoint getChat() {
            return chat;
        }

        public void setChat(Endpoint chat) {
            this.chat = chat;
        }

        public Endpoint getEmbedding() {
            return embedding;
        }

        public void setEmbedding(Endpoint embedding) {
            this.embedding = embedding;
        }

        /**
         * 一个 OpenAI 兼容端点。
         */
        public static class Endpoint {

            private String baseUrl;
            private String model;
            private String apiKey;

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
        }
    }
}
