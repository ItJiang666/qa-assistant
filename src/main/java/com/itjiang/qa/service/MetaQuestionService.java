package com.itjiang.qa.service;

import com.itjiang.qa.mapper.DocumentMapper;
import com.itjiang.qa.provider.RuntimeModelRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.Locale;

/**
 * 元问题与测试口令的"短路应答"。
 *
 * <h2>为什么必须短路，而不是继续靠提示词</h2>
 * <p>铁律第 9 条 + 【助手说明】已经覆盖了 7 类元问题，但实测有两个缺口：
 * <ol>
 *   <li><b>问不出"你是什么模型"</b>：【助手说明】是 {@code Prompts.SYSTEM} 里的<b>静态常量</b>，
 *       里面既没有模型名、也无法按请求插值（它由 {@code JdbcChatMemoryStore} 直接供给，
 *       没有任何插值环节）。所以就算模型乖乖按第 9 条回答，它也没有这条信息 ——
 *       要么答错、要么答"不知道"。</li>
 *   <li><b>服从度不稳定</b>：本地 8B 模型对长规则清单里"例外条款"的遵守度明显弱于云端，
 *       同一句"你是谁"在本地更容易掉回第 2 条（答"现有资料中未找到相关信息"）。
 *       而这恰恰是最常被用来<b>验证系统是否正常</b>的一类问题 ——
 *       它答错时用户会以为整个系统坏了。</li>
 * </ol>
 *
 * <p>所以这里改由一个小的规则层直接给出<b>固定文案</b>：不进检索、不调模型、不等生成。
 * 三个直接收益：<b>确定</b>（不依赖模型情绪）、<b>快</b>（毫秒级）、
 * 以及<b>能当冒烟测试用</b>（发一句"测试"就能确认链路与当前模型）。
 *
 * <h2>为什么要入库</h2>
 * <p>曾考虑过"短路回答不入库"（省 token、省一轮），但那会造成更糟的体验：
 * 用户点开会话，看到自己问过、却没有回答 —— 像是系统漏答了。而这点 token
 * （固定文案控制在 100 字以内）相对于它换来的对话完整性是划算的。
 * 但**不触发长期记忆抽取**：元问题本身不含任何值得记住的用户偏好，
 * 白跑一次抽取模型既费钱又可能抽错。
 *
 * <h2>匹配策略：只做"完全相等或明确前缀"</h2>
 * <p>刻意不做包含匹配。反例很具体：{@code "测试环境怎么部署"} 是一个正经的知识库问题，
 * 若按"包含『测试』就算口令"来判，它会被劫持成固定文案 —— 那比答不出来更糟，
 * 因为用户不会怀疑是匹配规则，只会认为检索坏了。
 *
 * @author Jiang
 */
@Service
public class MetaQuestionService {

    private static final Logger log = LoggerFactory.getLogger(MetaQuestionService.class);

    private final RuntimeModelRegistry modelRegistry;
    private final ThinkingModeService thinkingMode;
    private final DocumentMapper documentMapper;

    public MetaQuestionService(RuntimeModelRegistry modelRegistry,
                               ThinkingModeService thinkingMode,
                               DocumentMapper documentMapper) {
        this.modelRegistry = modelRegistry;
        this.thinkingMode = thinkingMode;
        this.documentMapper = documentMapper;
    }

    /**
     * 元问题类别。
     *
     * <p>分类不是为了"给答案分类"，而是为了让匹配词表可读、可逐类追加：
     * 用户报"问某句话没走短路"时，能一眼看出该往哪一类加词。
     */
    private enum Kind {
        /** 你是谁 / 你是什么。 */
        IDENTITY,
        /** 你是什么模型 / 用的哪个模型 —— 这一类只能靠短路回答，提示词里根本没有这个信息。 */
        MODEL,
        /** 你能做什么 / 支持哪些格式。 */
        CAPABILITY,
        /** 你怎么记住我 / 记忆存在哪。 */
        MEMORY,
        /** 你的资料从哪来 / 知识库在哪。 */
        DATASOURCE,
        /** 测试 / test —— 冒烟口令。 */
        SMOKE
    }

    /**
     * 匹配词表：每类给若干"完全相等"的整句，外加少量"以此开头"的前缀。
     *
     * <p>整句与前缀都写成小写、无空格的规范形式，比较前先对用户输入做同样的规范化，
     * 于是"你是什么模型？"、"你 是 什么 模型"、"你是什么模型！"都能命中，
     * 而"测试环境怎么部署"不会命中。
     */
    private static final String[][] EXACT_PHRASES = {
            // IDENTITY
            {"你是谁", "你是什么", "你叫什么", "你叫什么名字", "介绍一下你自己", "自我介绍", "whoareyou"},
            // MODEL
            {"你是什么模型", "你用的什么模型", "你用的哪个模型", "你是什么大模型", "用的什么大模型",
                    "你背后是什么模型", "底层是什么模型", "当前用的什么模型", "whatmodelareyou"},
            // CAPABILITY
            {"你能做什么", "你能干什么", "你会什么", "你有什么功能", "支持什么", "支持哪些格式",
                    "支持什么文件格式", "你能回答什么"},
            // MEMORY
            {"你怎么记住我", "你怎么记住我的", "你有记忆吗", "你记得我吗", "记忆存在哪", "长期记忆存在哪"},
            // DATASOURCE
            {"你的资料从哪来", "你的资料从哪来?", "你的知识从哪来", "你的知识库在哪", "知识库在哪",
                    "文档存在哪"},
            // SMOKE
            {"测试", "test", "测试一下", "冒烟测试", "自检", "ping", "在吗", "hello", "你好"}
    };

    /** 与 {@link #EXACT_PHRASES} 同序的类别。 */
    private static final Kind[] EXACT_KINDS = {
            Kind.IDENTITY, Kind.MODEL, Kind.CAPABILITY, Kind.MEMORY, Kind.DATASOURCE, Kind.SMOKE
    };

    /**
     * 判断这句话是不是元问题 / 测试口令。
     *
     * @param question 用户原始问题
     * @return 命中的类别；未命中返回 {@code null}
     */
    public String match(String question) {
        Kind kind = classify(question);
        return kind == null ? null : kind.name().toLowerCase(Locale.ROOT);
    }

    /**
     * 分类。
     *
     * @param question 用户原始问题
     * @return 类别；未命中返回 {@code null}
     */
    private Kind classify(String question) {
        if (question == null) {
            return null;
        }
        String normalized = normalize(question);
        if (normalized.isEmpty()) {
            return null;
        }
        for (int i = 0; i < EXACT_PHRASES.length; i++) {
            for (String phrase : EXACT_PHRASES[i]) {
                if (normalized.equals(phrase)) {
                    return EXACT_KINDS[i];
                }
            }
        }
        return null;
    }

    /**
     * 规范化：去首尾空白、去全部内部空白、去末尾标点、转小写。
     *
     * <p>只去<b>末尾</b>标点而不去中间：{@code "你的知识从哪来?"} 这种句尾问号很常见，
     * 而中间的标点往往是实义（比如"支持哪些格式：txt 还是 pdf"），
     * 去掉反而会引入误判。
     *
     * @param raw 原始输入
     * @return 规范形式
     */
    private String normalize(String raw) {
        String s = raw.trim().toLowerCase(Locale.ROOT).replaceAll("\\s+", "");
        while (!s.isEmpty() && "？?！!。.,，、~～：:".indexOf(s.charAt(s.length() - 1)) >= 0) {
            s = s.substring(0, s.length() - 1);
        }
        return s;
    }

    /**
     * 生成固定回答。
     *
     * <p>固定文案而不是"让模型自己组织语言"：模型对自己是什么模型的认知本来就不靠谱，
     * 而这句回答的用途是<b>确认系统状态</b>——必须准确、必须每次都一样。
     *
     * <p>内容控制在 200 字以内（与铁律第 8 条一致），并按"稳定不变的事实在前、
     * 可能变化的状态在后"排列：用户扫一眼前半句就知道是不是自己要的。
     *
     * @param kind 命中类别（{@link #match} 的返回值）
     * @return 固定回答
     */
    public String reply(String kind) {
        String model = modelRegistry.chatModelName();
        String mode = modelRegistry.chatMode().value();
        String embeddingModel = modelRegistry.embeddingModelName();
        long documents = countDocuments();

        StringBuilder out = new StringBuilder();
        out.append("我是知识库问答助手：只依据你上传的文档回答，并给出引用来源；")
                .append("资料里没有的会明确说「未找到」，不拿模型自身知识补充。\n");

        if ("model".equals(kind)) {
            out.append("当前对话模型：").append(model)
                    .append("（").append("cloud".equals(mode) ? "云端" : "本地 Ollama").append("）");
            if ("cloud".equals(mode)) {
                out.append("，思考模式").append(thinkingMode.current() ? "开" : "关");
            }
            out.append("；向量模型：").append(embeddingModel).append("。\n");
        } else if ("memory".equals(kind)) {
            out.append("记忆分两层：同一会话内的多轮上下文，以及跨会话的长期记忆")
                    .append("（可记住你的偏好与背景，能在「记忆管理」页查看、修改、删除）。\n");
        } else if ("datasource".equals(kind)) {
            out.append("资料只来自你在「文档管理」页上传的文档，当前已入库 ")
                    .append(describeDocuments(documents)).append("。\n");
        } else if ("capability".equals(kind)) {
            out.append("支持 txt / md / docx / pdf / xlsx / html（扫描版 PDF 无文字层，本期不支持）；")
                    .append("可切换本地与云端两种模型来源。\n");
        } else if ("smoke".equals(kind)) {
            out.append("链路正常。当前对话模型 ").append(model)
                    .append("，向量模型 ").append(embeddingModel)
                    .append("，已入库 ").append(describeDocuments(documents)).append("。\n");
        }

        out.append("（本条为固定回复，未经模型生成。）");
        return out.toString().stripTrailing();
    }

    /**
     * 把文档数渲染成可读文本。
     *
     * <p>统计失败时写"文档数暂不可用"而不是 {@code -1 份}：元问题回答的用途
     * 恰恰是"在出问题时告诉用户系统还活着"，往里面塞一个负数只会新增困惑。
     *
     * @param documents 文档数；{@code -1} 表示统计失败
     * @return 可读文本
     */
    private String describeDocuments(long documents) {
        return documents < 0 ? "文档数暂不可用" : documents + " 份文档";
    }

    /**
     * 统计已入库文档数。
     *
     * <p>查询失败不抛异常：元问题回答不能因为数据库抖动而变成 500 ——
     * 它的用途恰恰是"在出问题时告诉用户系统还活着"。
     *
     * @return 文档数；查询失败返回 -1
     */
    private long countDocuments() {
        try {
            return documentMapper.countAll();
        } catch (Exception ex) {
            log.warn("元问题回答统计文档数失败: {}", ex.getMessage());
            return -1L;
        }
    }
}
