package com.itjiang.qa.memory;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 「刚删掉的记忆」的短期作废通知。
 *
 * <h2>它解决的是一个实测踩到的真问题</h2>
 * <p>用户删掉一条偏好记忆后，在<b>同一个会话</b>里继续提问，模型仍然照做 ——
 * 于是用户认定"记忆删了没用"。
 *
 * <p>但根因<b>不是</b>长期记忆（L3）复活：我们查过数据库，那条记忆确实是
 * {@code DELETED}、{@code memoryCount} 为 0。真正的原因是<b>会话历史（L2）</b>：
 * 用户当初在对话里说过"每次回答后加个喵~"，模型也照做过，
 * 而 L2 每轮都会把整段对话历史交给模型 —— 模型看到"我一直在这么做"，就继续这么做。
 *
 * <p>换句话说：<b>删掉 L3 的记忆，删不掉 L2 里已经形成的行为。</b>
 * 这不是模型出错，是"遗忘"这个动作没有传递到当前对话。
 *
 * <h2>怎么做</h2>
 * <p>删除时记一条短时通知；下一次提问把它拼进用户消息，明确告诉模型
 * "那条偏好已作废、不要再照做，也不要模仿历史里按它生成的内容"。
 * 用户不需要退出会话或清空聊天记录。
 *
 * <h2>为什么是"一段时间内"而不是永久</h2>
 * <p>通知的语义是"这个动作刚刚发生"，不是"这个用户永远不许有偏好"。
 * 过期后若用户又提出新偏好，应当正常遵守 —— 否则会把一次删除变成永久禁言。
 *
 * <h2>为什么只留最近一次</h2>
 * <p>用户可能连删几条。对模型来说"刚才那条偏好作废了"这个信息就够了，
 * 逐条罗列反而会把提示词撑长、还可能把已删内容又复述一遍（等于没删干净）。
 *
 * @author Jiang
 */
@Component
public class DeletedMemoryNotice {

    private static final Logger log = LoggerFactory.getLogger(DeletedMemoryNotice.class);

    /**
     * 通知有效期。
     *
     * <p>10 分钟：够覆盖"删完立刻再问一句"这个真实场景，
     * 又不至于让一次删除影响很久之后的正常使用。
     */
    private static final Duration TTL = Duration.ofMinutes(10);

    /** userId → 最近一次删除。 */
    private final Map<String, Notice> notices = new ConcurrentHashMap<>();

    /**
     * 记下一次删除。
     *
     * @param userId       用户标识
     * @param isPreference 删掉的是不是偏好类记忆（决定措辞强弱）
     * @param content      被删掉的记忆原文；用它点名"具体是哪条"，比泛泛说"口头禅"有效得多
     */
    public void record(String userId, boolean isPreference, String content) {
        if (userId == null || userId.isBlank()) {
            return;
        }
        notices.put(userId, new Notice(Instant.now(), isPreference, content));
        log.info("已记录记忆删除通知: userId={}, 偏好类={}, 内容={}",
                userId, isPreference, abbreviate(content));
    }

    /**
     * 取当前有效的通知文案。
     *
     * <p>取用<b>不消费</b>（不 remove）：用户往往要连问几句才能确认"真的不再照做了"，
     * 只对第一句生效会让人以为没修好。
     *
     * @param userId 用户标识
     * @return 拼进用户消息的提示段；无有效通知时返回 {@code null}
     */
    public String take(String userId) {
        if (userId == null || userId.isBlank()) {
            return null;
        }
        Notice n = notices.get(userId);
        if (n == null) {
            return null;
        }
        if (Duration.between(n.at(), Instant.now()).compareTo(TTL) > 0) {
            notices.remove(userId);
            return null;
        }
        // ⚠️ 措辞是按实测结果定的，别"顺手润色"。
        //
        // 演进过程（每一版都实测过，记下来免得又走回去）：
        //   ① "用户刚刚删除了一条关于回答方式的偏好，不要再按那条偏好作答"
        //      → **完全无效**。30 条消息、每答必带"喵~"的会话里，模型 4/4 继续带。
        //        请求体日志确认通知进了 Prompt，所以不是链路问题，是服从度问题。
        //   ② 加"优先于上文"+ 点名"口头禅/结尾语/固定格式"
        //      → 好转但**不稳定**：4 轮里第 3 步仍有 1 轮带"喵~"（步骤 4 则 4/4 通过）。
        //   ③ 再补上"被删记忆的原文"，让模型能把"作废"和具体那句话对上
        //      → 这一版才是 4/4。
        //
        // 结论：**笼统的"别照做"压不过多轮稳定行为；必须点名具体内容**。
        String quoted = (n.content() == null || n.content().isBlank())
                ? null : "（原文：" + abbreviate(n.content()) + "）";

        if (n.isPreference()) {
            return "\n\n【重要｜优先于上文】"
                    + "用户刚刚删除了之前记住的一条「回答方式偏好」，那条偏好已作废。"
                    + (quoted == null ? "" : quoted) + "\n"
                    + "现在的要求：按默认风格回答；"
                    + "**不要**在回答末尾或任何位置附加口头禅、结尾语、固定符号或固定格式。\n"
                    + "即使上面的对话历史里你此前多次那样回答过，也从现在这一轮起停止 —— "
                    + "历史中的做法不代表用户现在的意愿。";
        }
        return "\n\n【重要｜优先于上文】"
                + "用户刚刚删除了之前记住的一条事实。" + (quoted == null ? "" : quoted) + "\n"
                + "现在的要求：不要把它当作已知信息使用；"
                + "即使上面的对话历史里提到过它，也不要再据此作答。";
    }

    /**
     * 截断长文本用于日志与提示词，避免把整段记忆塞进去。
     *
     * @param text 原文
     * @return 截断后的文本
     */
    private static String abbreviate(String text) {
        if (text == null) {
            return "";
        }
        String t = text.strip();
        return t.length() <= 60 ? t : t.substring(0, 60) + "…";
    }

    /**
     * 待生效的删除通知。
     *
     * @param at           记录时刻
     * @param isPreference 是否为偏好类
     * @param content      被删记忆的原文（可空）
     */
    private record Notice(Instant at, boolean isPreference, String content) {
    }
}
