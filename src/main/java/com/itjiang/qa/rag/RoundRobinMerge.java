package com.itjiang.qa.rag;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Function;

/**
 * 多路检索结果合并：轮转（round-robin）取用 + 按内容去重。
 *
 * <h2>为什么用轮转，而不是"把所有结果按分数合并排序"</h2>
 * <p>因为<b>不同检索式之间的分数不可比</b>：0.72 这条来自原问题、0.88 那条来自改写式，
 * 数值高低并不代表谁更相关 —— 各自的分数量纲、甚至长度分布都不同。
 * 直接按分数混排，很容易让改写式的高分片段把原问题的结果全部挤掉；
 * 而轮转（先各取第一名、再各取第二名…）保证了<b>每一路都有代表进入结果</b>，
 * 也就保证了改写的价值能真正兑现，同时原问题始终排在最前。
 *
 * <h2>为什么按内容去重</h2>
 * <p>同一段文字常会被多个检索式同时命中。不去重的话，topK=8 里可能只有 3 个不同片段，
 * 等于把宝贵的上下文预算浪费在重复内容上。
 *
 * <p>刻意做成泛型：问答链路用的是 LangChain4j 的 {@code Content}，
 * 而检索试跑接口用的是我们自己的 {@code SearchHit} —— 两边共用同一份合并逻辑，
 * 避免"评测看到的合并规则和线上不是一套"。
 *
 * @author Jiang
 */
public final class RoundRobinMerge {

    /**
     * 工具类，禁止实例化。
     */
    private RoundRobinMerge() {
    }

    /**
     * 合并多路结果。
     *
     * @param rankedLists 各路结果，按优先级排列（第一路是原问题的结果）
     * @param limit       最终保留几条
     * @param keyFn       去重键提取函数
     * @param <T>         结果类型
     * @return 合并去重后的结果
     */
    public static <T> List<T> merge(List<List<T>> rankedLists, int limit, Function<T, String> keyFn) {
        if (rankedLists == null || rankedLists.isEmpty() || limit <= 0) {
            return List.of();
        }
        // LinkedHashMap 保序：先去重，再按轮转顺序输出
        Map<String, T> merged = new LinkedHashMap<>();
        int maxLength = rankedLists.stream().mapToInt(list -> list == null ? 0 : list.size()).max().orElse(0);

        for (int rank = 0; rank < maxLength && merged.size() < limit; rank++) {
            for (List<T> list : rankedLists) {
                if (list == null || rank >= list.size()) {
                    continue;
                }
                T item = list.get(rank);
                merged.putIfAbsent(keyOf(item, keyFn), item);
                if (merged.size() >= limit) {
                    break;
                }
            }
        }
        return new ArrayList<>(merged.values());
    }

    /**
     * 计算去重键，空值兜底避免 NPE。
     *
     * @param item  结果项
     * @param keyFn 键提取函数
     * @param <T>   结果类型
     * @return 去重键
     */
    private static <T> String keyOf(T item, Function<T, String> keyFn) {
        if (item == null) {
            return "null";
        }
        String key = keyFn.apply(item);
        return key == null ? String.valueOf(System.identityHashCode(item)) : key;
    }
}
