package com.itjiang.qa.service;

import com.itjiang.qa.config.QaProperties;
import com.itjiang.qa.mapper.MemoryMapper;
import com.itjiang.qa.model.dto.ExtractedMemory;
import dev.langchain4j.model.chat.ChatModel;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;
import tools.jackson.databind.ObjectMapper;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.regex.Pattern;

/**
 * 长期记忆抽取（开发文档 4.6.4）—— L3 的写入侧。
 *
 * <p>每轮问答结束后异步跑一次：让模型读这轮对话，判断"有什么值得长期记住的"，
 * 输出严格 JSON，过滤后写入 {@code qa_memory}。
 *
 * <h2>三条设计红线</h2>
 * <ol>
 *   <li><b>绝不阻塞回答。</b> 抽取在 {@code memoryExecutor} 里跑，
 *       用户拿到回答时它可能还没开始。</li>
 *   <li><b>绝不把助手的话当成用户的事实。</b> 这是长期记忆最容易出的错 ——
 *       模型编造的内容一旦被记成"用户说过"，就会在之后每一轮对话里被当成事实注入。
 *       提示词里显式排除了助手发言，入库时还带 source_conv_id 以便回溯。</li>
 *   <li><b>抽取失败不影响主流程。</b> 解析失败、模型超时、JSON 格式错 ——
 *       一律记日志后静默跳过，绝不让记忆功能把聊天搞挂。</li>
 * </ol>
 *
 * @author Jiang
 */
@Service
public class MemoryExtractService {

    private static final Logger log = LoggerFactory.getLogger(MemoryExtractService.class);

    /**
     * 敏感信息正则：命中即丢弃。
     *
     * <p>宁可少记一条，也不要把密码或身份证号存进数据库 —— 那不只是隐私问题，
     * 它还会在后续每一轮对话里被注入 Prompt。
     */
    private static final Pattern SENSITIVE = Pattern.compile(
            "密码|口令|身份证|银行卡|信用卡|手机号|1[3-9]\\d{9}|sk-[A-Za-z0-9]{10,}");

    /** 单条记忆正文的长度上限，与表定义 VARCHAR(500) 对齐。 */
    private static final int CONTENT_MAX_LENGTH = 500;

    /** 主题键长度上限，与表定义 VARCHAR(128) 对齐。 */
    private static final int KEY_MAX_LENGTH = 128;

    /** 模型输出里可能出现的 markdown 代码块围栏。 */
    private static final Pattern CODE_FENCE = Pattern.compile("```(?:json)?|```", Pattern.CASE_INSENSITIVE);

    /**
     * 说明：这里用的是 Jackson 3 的 {@code tools.jackson.databind.ObjectMapper}。
     *
     * <p>Spring Boot 4 已经把默认 JSON 实现升级到 Jackson 3（包名从
     * {@code com.fasterxml.jackson} 改成 {@code tools.jackson}），
     * 容器里只注册 Jackson 3 的 ObjectMapper。
     * 如果注入 Jackson 2 的那个，启动时会直接报
     * {@code NoSuchBeanDefinitionException} —— 这个问题只在启动时才暴露，
     * 编译期完全看不出来。
     */
    private final ChatModel extractModel;
    private final MemoryMapper memoryMapper;
    private final ObjectMapper objectMapper;
    private final QaProperties properties;

    public MemoryExtractService(@Qualifier("qaExtractChatModel") ChatModel extractModel,
                               MemoryMapper memoryMapper,
                               ObjectMapper objectMapper,
                               QaProperties properties) {
        this.extractModel = extractModel;
        this.memoryMapper = memoryMapper;
        this.objectMapper = objectMapper;
        this.properties = properties;
    }

    /**
     * 异步抽取本轮对话中的长期记忆。
     *
     * @param userId         用户标识；为 {@code null} 表示未识别用户，直接跳过
     * @param conversationId 会话 ID，作为记忆的来源标记
     * @param userText       用户原话
     * @param assistantText  助手回答
     */
    @Async("memoryExecutor")
    public void extractAsync(String userId, String conversationId, String userText, String assistantText) {
        // userId 必须由调用方显式传入：ThreadLocal 不会传播到异步线程
        if (userId == null || userId.isBlank()) {
            log.debug("未识别用户，跳过记忆抽取");
            return;
        }
        if (!properties.getMemory().isExtractEnabled()) {
            return;
        }

        try {
            String raw = extractModel.chat(buildPrompt(userText, assistantText));
            List<ExtractedMemory> extracted = parse(raw);
            if (extracted.isEmpty()) {
                log.debug("本轮没有值得记住的内容: conversationId={}", conversationId);
                return;
            }

            int saved = 0;
            int skipped = 0;
            for (ExtractedMemory memory : extracted) {
                if (!isAcceptable(memory)) {
                    skipped++;
                    continue;
                }
                upsert(userId, memory, conversationId);
                saved++;
            }
            evictOverflow(userId);
            log.info("记忆抽取完成: conversationId={}, 写入 {} 条, 过滤 {} 条",
                    conversationId, saved, skipped);

        } catch (Exception ex) {
            // 抽取是"锦上添花"，任何失败都不该影响已完成的问答
            log.warn("记忆抽取失败（不影响本次问答）: conversationId={}, 原因={}",
                    conversationId, ex.getMessage());
        }
    }

    /**
     * 构造抽取提示词。
     *
     * <p>措辞里的每一条规则都对应一次实际会犯的错，不要随意精简。
     *
     * @param userText      用户原话
     * @param assistantText 助手回答
     * @return 提示词
     */
    private String buildPrompt(String userText, String assistantText) {
        return """
                阅读下面这一轮对话，只抽取「值得长期记住的、关于【用户】的偏好、身份或长期事实」。

                规则：
                1. 只抽取用户明确表达的偏好 / 身份 / 长期有效的事实
                2. 不要抽取寒暄、一次性的提问，也不要抽取【助手】说的话
                3. 不要抽取密码、证件号、手机号等敏感信息
                4. 没有值得记住的内容，就返回空数组 []
                5. 每条记忆写成一句话，不超过 50 字
                6. key 用英文小写短键，同一主题必须用同一个 key（便于去重合并）

                只输出 JSON 数组，不要任何解释、不要 markdown 代码块：
                [{"type":"PREFERENCE|FACT|CONCLUSION","key":"英文短键","content":"一句话","confidence":0.9,"importance":3}]

                【用户】%s
                【助手】%s
                """.formatted(userText, assistantText);
    }

    /**
     * 解析模型输出的 JSON。
     *
     * <p>三道防护：去掉 markdown 围栏、截取第一个 {@code [} 到最后一个 {@code ]}、
     * 整体失败则返回空列表。模型输出 JSON 从来不是 100% 可靠的，
     * 与其重试不如直接放弃这一轮 —— 记忆漏一条没有代价，主流程被拖住才有。
     *
     * @param raw 模型原始输出
     * @return 抽取结果，解析失败时返回空列表
     */
    private List<ExtractedMemory> parse(String raw) {
        if (raw == null || raw.isBlank()) {
            return List.of();
        }
        String cleaned = CODE_FENCE.matcher(raw).replaceAll("").trim();
        int start = cleaned.indexOf('[');
        int end = cleaned.lastIndexOf(']');
        if (start < 0 || end <= start) {
            log.debug("模型未返回 JSON 数组，跳过本轮: {}", abbreviate(cleaned));
            return List.of();
        }
        try {
            ExtractedMemory[] parsed = objectMapper.readValue(
                    cleaned.substring(start, end + 1), ExtractedMemory[].class);
            List<ExtractedMemory> result = new ArrayList<>(parsed.length);
            for (ExtractedMemory memory : parsed) {
                if (memory != null) {
                    result.add(memory);
                }
            }
            return result;
        } catch (Exception ex) {
            log.warn("记忆 JSON 解析失败，跳过本轮: {} | 原文={}", ex.getMessage(), abbreviate(cleaned));
            return List.of();
        }
    }

    /**
     * 判断一条抽取结果是否可以入库。
     *
     * @param memory 抽取结果
     * @return 通过返回 {@code true}
     */
    private boolean isAcceptable(ExtractedMemory memory) {
        if (memory.content() == null || memory.content().isBlank()) {
            return false;
        }
        if (memory.content().length() > CONTENT_MAX_LENGTH) {
            return false;
        }
        if (SENSITIVE.matcher(memory.content()).find()) {
            log.debug("丢弃疑似敏感信息的记忆: {}", abbreviate(memory.content()));
            return false;
        }
        return memory.confidenceOrDefault() >= properties.getMemory().getMinConfidence();
    }

    /**
     * 写入或合并一条记忆（upsert）。
     *
     * <p>用"先 UPDATE，0 行再 INSERT"而不是 MySQL 的
     * {@code ON DUPLICATE KEY UPDATE}：后者在 H2 上要靠兼容模式，
     * 而本项目两种数据库都要跑（开发文档 4.1）。多一次查询换来方言无关，值得。
     *
     * @param userId         用户标识
     * @param extracted      抽取结果
     * @param conversationId 来源会话
     */
    private void upsert(String userId, ExtractedMemory extracted, String conversationId) {
        String memKey = normalizeKey(extracted);

        com.itjiang.qa.model.entity.Memory entity = new com.itjiang.qa.model.entity.Memory();
        entity.setUserId(userId);
        entity.setMemoryType(com.itjiang.qa.model.enums.MemoryType.from(extracted.type()).getValue());
        entity.setMemKey(memKey);
        entity.setContent(extracted.content().trim());
        entity.setConfidence(BigDecimal.valueOf(extracted.confidenceOrDefault())
                .setScale(2, RoundingMode.HALF_UP));
        entity.setImportance(extracted.importanceOrDefault());
        entity.setSourceConvId(conversationId);

        int updated = memoryMapper.updateByUserAndKey(entity);
        if (updated > 0) {
            log.debug("记忆已合并: key={}, 内容={}", memKey, abbreviate(entity.getContent()));
            return;
        }
        try {
            memoryMapper.insert(entity);
        } catch (Exception ex) {
            // 并发抽取同一主题时可能撞上唯一约束：此时改成更新一次即可
            log.debug("记忆插入冲突，转为更新: key={}", memKey);
            memoryMapper.updateByUserAndKey(entity);
        }
    }

    /**
     * 归一化主题键。
     *
     * <p>模型给的 key 可能是 {@code Answer_Length}、{@code answer length} 等写法，
     * 不归一化就会绕过唯一约束、把同一件事存成多条。
     *
     * @param extracted 抽取结果
     * @return 归一化后的键
     */
    private String normalizeKey(ExtractedMemory extracted) {
        String raw = extracted.key();
        if (raw == null || raw.isBlank()) {
            // 模型没给 key 时用正文兜底，总比让同一件事反复入库好
            raw = extracted.content();
        }
        String normalized = raw.toLowerCase(Locale.ROOT)
                .replaceAll("[^a-z0-9\\u4e00-\\u9fff]+", "_")
                .replaceAll("^_+|_+$", "");
        if (normalized.isEmpty()) {
            normalized = "misc";
        }
        return normalized.length() <= KEY_MAX_LENGTH
                ? normalized
                : normalized.substring(0, KEY_MAX_LENGTH);
    }

    /**
     * 淘汰超出上限的记忆。
     *
     * @param userId 用户标识
     */
    private void evictOverflow(String userId) {
        int keep = properties.getMemory().getMaxPerUser();
        if (memoryMapper.countActiveByUser(userId) <= keep) {
            return;
        }
        int evicted = memoryMapper.evictOverflow(userId, keep);
        if (evicted > 0) {
            log.info("记忆超出上限 {}，已淘汰 {} 条不常用记忆", keep, evicted);
        }
    }

    /**
     * 截断用于日志的文本。
     *
     * @param text 原文
     * @return 至多 60 字的摘要
     */
    private String abbreviate(String text) {
        if (text == null) {
            return "";
        }
        String oneLine = text.replaceAll("\\s+", " ").trim();
        return oneLine.length() <= 60 ? oneLine : oneLine.substring(0, 60) + "…";
    }
}
