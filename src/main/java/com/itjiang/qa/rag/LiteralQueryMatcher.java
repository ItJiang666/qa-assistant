package com.itjiang.qa.rag;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 从提问里认出"字面引用"——章节号、引号里的原文字符串。
 *
 * <h2>为什么需要它（实测数据，别删）</h2>
 * <p>用户问「<b>1.3 内容是什么</b>」时，向量检索完全失效：
 * <pre>
 *   含答案的分块排名  #21 / 49，分数 0.2991（阈值 0.65 → 一条都不放行）
 *   而用正则 {@code 1\.3\s*[.．]} 直接搜 → 精确命中 5 个分块，答案就在其中
 * </pre>
 * 原因不难理解：{@code 1.3} 在向量空间里<b>几乎没有语义</b> ——
 * 它是"位置信息"（文档的第几节），不是"意思"。把位置信息交给语义检索，
 * 就像拿门牌号去问人"这一家住着谁"，能碰上纯属巧合。
 *
 * <p>所以这类提问要换一把尺子：<b>字面匹配</b>。它不猜意思，只找"哪个分块里
 * 真的写着 {@code 1.3.}"——而这恰恰就是用户想要的那个分块。
 *
 * <h2>认哪几种引用</h2>
 * <ol>
 *   <li><b>中文章节号</b>：{@code 第3章}、{@code 第三章}、{@code 第2节}</li>
 *   <li><b>数字小节号</b>：{@code 1.3}、{@code 2.2.1}（要求自成一段，避免匹配到小数）</li>
 *   <li><b>引号内原文</b>：{@code “曲棍球棒效应”}、{@code "曲棍球棒效应"}</li>
 * </ol>
 *
 * <h2>为什么不做"包含匹配"这种更宽松的规则</h2>
 * <p>宽松规则的误伤是静默的：命中了无关分块，用户只会觉得"答案不对"，
 * 而不会想到是检索规则的问题。所以这里只认<b>形式明确</b>的引用，
 * 宁可漏（大不了退回原来的向量检索），不可错。
 *
 * @author Jiang
 */
final class LiteralQueryMatcher {

    /**
     * 数字小节号：{@code 1.3}、{@code 2.2.1}。
     *
     * <p><b>两侧的边界是本条正则的全部难点</b>（实测踩过，别随手简化）：
     * <ul>
     *   <li>前面不能是数字或小数点 —— 否则 {@code 11.3} 会被切出 {@code 1.3}；</li>
     *   <li>后面不能跟数字或小数点 —— 否则 {@code 0.4372} 这种小数会被整段吃掉；</li>
     *   <li>后面不能跟单位 —— {@code 172.1亿件}、{@code 2135亿元} 是在陈述事实，不是指章节；</li>
     *   <li><b>每段限 1~2 位、最多三段</b> —— 章节号的真实形态就是 {@code 1.3} / {@code 2.2.1}，
     *       放开段内位数会让 {@code 0.4372}（段长 4）与版本号 {@code 1.28.0} 混进来。</li>
     * </ul>
     *
     * <p>⚠️ 这四个约束<b>缺一不可</b>，而且是测出来的：早先只写"后面不能跟数字"时，
     * 正则回溯会让 {@code 0.4372} 整段被匹配成一个 key（试 {@code 0.4}→{@code 0.43}→
     * {@code 0.437}，每次都因后面还有数字而回退）。所以"段长上限"不是优化，是必需项。
     *
     * <p>判据是"这个数字是否<b>自成一段</b>"：章节号在文中的写法是
     * {@code 1.3. 国内外研究现状}、{@code 1.3、}、{@code 1.3 } 或行尾。
     */
    private static final Pattern NUMERIC_SECTION = Pattern.compile(
            "(?<![\\d.])(\\d{1,2}\\.\\d{1,2}(?:\\.\\d{1,2})?)(?!\\.?\\d|[亿万%元])");

    /** 中文章节号：第一章 / 第 3 章 / 第二章 曲棍球棒效应。 */
    private static final Pattern CHINESE_SECTION = Pattern.compile(
            "第\\s*([0-9一二三四五六七八九十]+)\\s*([章节部分])");

    /** 引号内的原文（中英文引号都收）。 */
    private static final Pattern QUOTED = Pattern.compile(
            "[“\"'‘]([^”\"'’]{2,40})[”\"'’]");

    /**
     * "这个 key 是不是数字小节号"。
     *
     * <p>单独抽出来是为了让 {@link #matches} 的判据与 {@link #NUMERIC_SECTION}
     * 用的是同一套语义（都由"纯数字 + 点"构成），避免两处各写一个、日后走偏。
     */
    private static final Pattern NUMERIC_KEY = Pattern.compile("\\d+(?:\\.\\d+)+");

    private final List<String> keys;

    private LiteralQueryMatcher(List<String> keys) {
        this.keys = keys;
    }

    /**
     * 从提问里提取字面引用。
     *
     * @param question 用户提问
     * @return 匹配器；没有认出任何引用时 {@link #isEmpty()} 为 {@code true}
     */
    static LiteralQueryMatcher from(String question) {
        Set<String> keys = new LinkedHashSet<>();
        if (question != null && !question.isBlank()) {
            Matcher numeric = NUMERIC_SECTION.matcher(question);
            while (numeric.find()) {
                keys.add(numeric.group(1));
            }
            Matcher chinese = CHINESE_SECTION.matcher(question);
            while (chinese.find()) {
                keys.add("第" + chinese.group(1) + chinese.group(2));
                // 同时收一个"去空格"的写法：文档里可能写成"第 3 章"
                keys.add("第" + chinese.group(1) + "\\s*" + chinese.group(2));
            }
            Matcher quoted = QUOTED.matcher(question);
            while (quoted.find()) {
                String inner = quoted.group(1).trim();
                if (!inner.isEmpty()) {
                    keys.add(inner);
                }
            }
        }
        return new LiteralQueryMatcher(new ArrayList<>(keys));
    }

    /**
     * 有没有认出引用。
     *
     * @return 没有引用时返回 {@code true}（此时应跳过这条通道）
     */
    boolean isEmpty() {
        return keys.isEmpty();
    }

    /**
     * 这个分块命中了几条引用。
     *
     * <p>命中数用于给字面结果排序：同时出现 {@code 1.3} 与 {@code 1.3.1} 的分块，
     * 比只出现一个的更可能是用户要的那一节。
     *
     * @param text 分块文本
     * @return 命中的引用条数；0 表示未命中
     */
    int hitCount(String text) {
        if (text == null || text.isEmpty()) {
            return 0;
        }
        int hits = 0;
        for (String key : keys) {
            hits += countOccurrences(text, key);
        }
        return hits;
    }

    /**
     * 单个引用在分块文本里出现了几次。
     *
     * <p>数"次数"而不是"有没有"，是因为它要用于排序：同时含 {@code 1.3} 与
     * {@code 1.3.1}、{@code 1.3.2} 的分块，比只提了一次的更可能是用户要的那一节。
     * 实测分块 #05 就同时含 {@code 1.3.} 与 {@code 1.3.1.}，而 #43 只含一个 {@code 4.1.3}。
     *
     * @param text 分块文本
     * @param key  引用
     * @return 出现次数；0 表示未命中
     */
    private int countOccurrences(String text, String key) {
        if (key.contains("\\s*")) {
            return countMatches(Pattern.compile(key), text);
        }
        if (NUMERIC_KEY.matcher(key).matches()) {
            // 例如 1.3 → 要求写成 "1.3" 后跟 . ． 、 空白 或行尾，
            // 这样 1.33 / 11.3 里的片段不会被误算
            return countMatches(Pattern.compile(Pattern.quote(key) + "(?=[.．、\\s]|$)"), text);
        }
        // 中文章节号与引号内容：直接按字面出现次数算
        return countLiteral(text, key);
    }

    /**
     * 数一个正则在文本里的匹配次数。
     *
     * @param pattern 正则
     * @param text    文本
     * @return 次数
     */
    private int countMatches(Pattern pattern, String text) {
        Matcher matcher = pattern.matcher(text);
        int count = 0;
        while (matcher.find()) {
            count++;
        }
        return count;
    }

    /**
     * 数一个字面串在文本里出现几次（不重叠）。
     *
     * @param text   文本
     * @param needle 字面串
     * @return 次数
     */
    private int countLiteral(String text, String needle) {
        int count = 0;
        int from = 0;
        while (true) {
            int at = text.indexOf(needle, from);
            if (at < 0) {
                return count;
            }
            count++;
            from = at + needle.length();
        }
    }

    /**
     * 供日志用的引用清单。
     *
     * @return 形如 {@code [1.3, 第3章]}
     */
    List<String> keys() {
        return keys;
    }
}
