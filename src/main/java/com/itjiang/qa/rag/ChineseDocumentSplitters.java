package com.itjiang.qa.rag;

import dev.langchain4j.data.document.DocumentSplitter;
import dev.langchain4j.data.document.splitter.DocumentByCharacterSplitter;
import dev.langchain4j.data.document.splitter.DocumentByLineSplitter;
import dev.langchain4j.data.document.splitter.DocumentByParagraphSplitter;
import dev.langchain4j.data.document.splitter.DocumentByRegexSplitter;
import dev.langchain4j.data.document.splitter.DocumentByWordSplitter;

/**
 * 中文友好的递归分块器。
 *
 * <h2>为什么需要它</h2>
 * <p>LangChain4j 自带的 {@code DocumentSplitters.recursive} 分四层：
 * <pre>
 *   段落 \s*(\R)\s*(\R)\s*   ← 语言无关 ✅
 *     └ 换行 \s*\R\s*        ← 语言无关 ✅
 *         └ 句子             ← OpenNLP 模型 /opennlp/opennlp-en-ud-ewt-sentence-*.bin
 *             └ 词 \s+       ← 空白符
 * </pre>
 *
 * <p><b>后两层对中文是失效的</b>：
 * <ul>
 *   <li>句子层用的是 <b>OpenNLP 的英文句子模型</b>，认不出「。」「！」「？」</li>
 *   <li>词层按 {@code \s+} 切分，而中文根本不用空格分词</li>
 * </ul>
 *
 * <p>后果：一个没有空行的长中文段落（超过 chunkSize），前两层切不动、后两层无效，
 * 最终**退化成按字符硬切**，可能从句子中间切断。检索出来的片段读起来是断的，
 * 模型拿到的上下文质量随之下降。
 *
 * <h2>本实现</h2>
 * <p>把句子层换成<b>中文标点正则</b>，其余层次保持原样：
 * <pre>
 *   段落 → 换行 → 句子(?<=[。！？；]) → 词(\s+) → 字符
 * </pre>
 * 保留词层与字符层是为了不破坏英文文档的切分效果 —— 这是一个增量修正，
 * 不是把原实现推倒重来。
 *
 * @author Jiang
 */
public final class ChineseDocumentSplitters {

    /**
     * 句子边界正则。
     *
     * <p>两个分支：
     * <ul>
     *   <li>{@code (?<=[。！？；])} —— <b>零宽后顾</b>：在中文标点之后切，
     *       标点本身留在前一段末尾。这是中文文档真正起作用的那条。</li>
     *   <li>{@code (?<=[.!?])\s+} —— 英文句末标点 + 空白：顺带把英文也处理好，
     *       免得中英混排的文档里英文段落又被退化成按字符切。</li>
     * </ul>
     *
     * <p>刻意<b>不用</b>能匹配空串的写法（如 {@code )\s*}）：Java 的
     * {@code String.split} 遇到可匹配空串的模式会在位置 0 也产生匹配，
     * 结果是每段都多出一个空串前缀，很难排查。
     */
    private static final String SENTENCE_REGEX = "(?<=[。！？；])|(?<=[.!?])\\s+";

    /**
     * 句子重组的连接符。
     *
     * <p>中文句子之间不需要空格，用空串；英文那条分支虽然会吃掉一个空格，
     * 但只在"把小片段合并成大片段"时才用到，影响可以忽略。
     */
    private static final String SENTENCE_JOIN_DELIMITER = "";

    /**
     * 工具类，禁止实例化。
     */
    private ChineseDocumentSplitters() {
    }

    /**
     * 构造中文友好的递归分块器。
     *
     * @param maxSegmentSize 单块最大长度（字）
     * @param maxOverlapSize 块间重叠长度（字）
     * @return 分块器
     */
    public static DocumentSplitter recursive(int maxSegmentSize, int maxOverlapSize) {
        // 从最内层往外包：字符 → 词 → 句子 → 换行 → 段落
        DocumentSplitter character = new DocumentByCharacterSplitter(maxSegmentSize, maxOverlapSize);
        DocumentSplitter word = new DocumentByWordSplitter(maxSegmentSize, maxOverlapSize, character);
        DocumentSplitter sentence = new DocumentByRegexSplitter(
                SENTENCE_REGEX, SENTENCE_JOIN_DELIMITER, maxSegmentSize, maxOverlapSize, word);
        DocumentSplitter line = new DocumentByLineSplitter(maxSegmentSize, maxOverlapSize, sentence);
        return new DocumentByParagraphSplitter(maxSegmentSize, maxOverlapSize, line);
    }
}
