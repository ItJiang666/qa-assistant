package com.itjiang.qa.service;

import com.itjiang.qa.common.BusinessException;
import com.itjiang.qa.common.ErrorCode;
import com.itjiang.qa.config.QaProperties;
import com.itjiang.qa.mapper.MemoryMapper;
import com.itjiang.qa.memory.DeletedMemoryNotice;
import com.itjiang.qa.model.dto.MemoryDto;
import com.itjiang.qa.model.entity.Memory;
import com.itjiang.qa.model.enums.MemoryType;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;

/**
 * 长期记忆读写（开发文档 4.6.5）—— L3 的读取侧与管理。
 *
 * <h2>读取：为什么起步不做向量检索</h2>
 * <p>一个用户几十条记忆，按 (重要度, 更新时间) 排序全量取几条，
 * 比向量召回<b>更快、更可解释</b>，而且不会出现"答非所问式注入"。
 * 等单用户记忆超过上限（默认 200 条）再考虑上向量召回 ——
 * 那时是增量叠加，表结构不用动（开发文档 4.6.5）。
 *
 * <h2>管理：不是可选项</h2>
 * <p>{@link #list} / {@link #update} / {@link #delete} / {@link #clear} 这一组接口
 * 是长期记忆唯一的纠错入口。模型抽取一定会有抽错的时候，
 * 一个"看不见内容、删不掉"的记忆系统，用户第一次发现它记错东西时就会彻底关掉它。
 *
 * @author Jiang
 */
@Service
public class LongTermMemoryService {

    private static final Logger log = LoggerFactory.getLogger(LongTermMemoryService.class);

    /**
     * 没有记忆时返回的占位文本。
     *
     * <p>公开给 {@code ChatService} 判断"要不要往用户消息里拼记忆段落" ——
     * 把占位文本原样拼进去只会白占 token。
     */
    public static final String NO_MEMORY = "（暂无）";

    private final MemoryMapper memoryMapper;
    private final QaProperties properties;
    private final DeletedMemoryNotice deletedNotice;

    public LongTermMemoryService(MemoryMapper memoryMapper,
                                 QaProperties properties,
                                 DeletedMemoryNotice deletedNotice) {
        this.memoryMapper = memoryMapper;
        this.properties = properties;
        this.deletedNotice = deletedNotice;
    }

    /**
     * 取该用户最重要的若干条记忆，拼成可注入 Prompt 的文本。
     *
     * @param userId 用户标识；为空时返回占位文本（未识别用户不做长期记忆）
     * @return 供 Prompt 使用的记忆段落
     */
    public String loadForPrompt(String userId) {
        if (userId == null || userId.isBlank() || !properties.getMemory().isExtractEnabled()) {
            return NO_MEMORY;
        }

        int maxCount = properties.getMemory().getInjectMaxCount();
        int maxChars = properties.getMemory().getInjectMaxChars();

        List<Memory> memories = memoryMapper.selectTopForInject(userId, maxCount);
        if (memories.isEmpty()) {
            return NO_MEMORY;
        }

        StringBuilder text = new StringBuilder();
        List<Long> injectedIds = new ArrayList<>();
        int used = 0;
        for (Memory memory : memories) {
            String line = formatLine(memory);
            if (used + line.length() > maxChars) {
                // 超预算就截断：宁可少注入几条，也不能把检索片段挤出上下文
                break;
            }
            text.append(line);
            used += line.length();
            injectedIds.add(memory.getId());
        }

        if (injectedIds.isEmpty()) {
            return NO_MEMORY;
        }
        memoryMapper.increaseHitCount(userId, injectedIds);
        log.debug("注入长期记忆 {} 条（{} 字）", injectedIds.size(), used);
        return text.toString().stripTrailing();
    }

    /**
     * 把一条记忆格式化成注入 Prompt 的一行。
     *
     * <h2>为什么按类型用不同的措辞，而不是统一写"(偏好) 用户希望…"</h2>
     * <p>这不是排版问题，是<b>实测出来的效果差异</b>。第一版统一写成第三人称描述：
     * <pre>
     *   - (偏好) 用户希望助手的回答尽量简短，控制在三句话以内
     * </pre>
     * 结果：记忆确实被注入了（日志可查），但模型完全不理，
     * 同一个问题的回答长度与没有记忆时<b>一字不差</b>（370 字 vs 370 字）。
     *
     * <p>原因：模型对"描述"的服从度远低于对"指令"的服从度。
     * 改为按类型给出明确的行为指示后，偏好才真正生效。
     * <b>"注入"不等于"生效"</b> —— 这是长期记忆最容易被忽略的失败模式。
     *
     * @param memory 记忆实体
     * @return 注入用的一行文本
     */
    private String formatLine(Memory memory) {
        MemoryType type = MemoryType.from(memory.getMemoryType());
        return switch (type) {
            // 偏好写成硬性要求，并点明"本次回答"
            case PREFERENCE -> "- 【偏好｜请在本次回答中严格遵守】" + memory.getContent() + "\n";
            // 事实只作背景，不要求模型做什么
            case FACT -> "- 【用户背景】" + memory.getContent() + "\n";
            // 结论是可以直接引用的既有判断
            case CONCLUSION -> "- 【已确认的结论，可直接使用】" + memory.getContent() + "\n";
        };
    }

    /**
     * 列出某用户的有效记忆。
     *
     * @param userId 用户标识
     * @return 记忆列表
     */
    public List<MemoryDto> list(String userId) {
        if (userId == null || userId.isBlank()) {
            return List.of();
        }
        return memoryMapper.selectActiveByUser(userId).stream().map(this::toDto).toList();
    }

    /**
     * 记忆条数。
     *
     * @param userId 用户标识
     * @return 条数
     */
    public long count(String userId) {
        return (userId == null || userId.isBlank()) ? 0L : memoryMapper.countActiveByUser(userId);
    }

    /**
     * 手工新增一条记忆（对应前端的「记住这条」按钮）。
     *
     * <p>置信度直接给 1.0：用户亲口说的，不需要模型来评估可信度。
     *
     * @param userId  用户标识
     * @param content 记忆内容
     * @param importance 重要度，可为 {@code null}（默认 4，用户手动的通常更重要）
     * @return 新建的记忆
     */
    public MemoryDto create(String userId, String content, Integer importance, String memoryType) {
        if (userId == null || userId.isBlank()) {
            throw new BusinessException(ErrorCode.PARAM_INVALID, "缺少 X-User-Id 请求头，无法归属这条记忆");
        }
        if (content == null || content.isBlank()) {
            throw new BusinessException(ErrorCode.PARAM_INVALID, "记忆内容不能为空");
        }
        String trimmed = content.trim();
        MemoryType type = resolveType(memoryType, trimmed);

        Memory entity = new Memory();
        entity.setUserId(userId);
        entity.setMemoryType(type.getValue());
        entity.setMemKey("manual_" + System.currentTimeMillis());
        entity.setContent(trimmed);
        // 置信度直接给 1.0：用户亲口说的，不需要模型来评估可信度
        entity.setConfidence(BigDecimal.valueOf(1.00));
        entity.setImportance(importance == null ? 4 : Math.min(Math.max(importance, 1), 5));
        entity.setSourceConvId(null);
        memoryMapper.insert(entity);

        log.info("手动新增记忆: userId={}, type={}{}, content={}", userId, type.getValue(),
                memoryType == null || memoryType.isBlank() ? "（自动判定）" : "", trimmed);
        return toDto(memoryMapper.selectById(userId, entity.getId()));
    }

    /**
     * 自动判定为"偏好"的措辞线索。
     *
     * <p>判定从宽：宁可把一句陈述误判成偏好（注入语气强一点，最坏是多遵守一条无害要求），
     * 也不要把一句指令误判成结论 —— 那会"记了等于没记"，是实测踩过的坑。
     */
    private static final List<String> PREFERENCE_HINTS = List.of(
            "请", "务必", "必须", "不要", "别", "每次", "一律", "以后", "今后",
            "尽量", "希望", "要求", "记住", "回答要", "答案要", "用中文", "简洁", "简短");

    /**
     * 决定这条记忆的类型。
     *
     * <p>显式传入时以传入为准；否则按措辞自动判定 —— 这一步是为了让<b>默认行为就是对的</b>：
     * 实测"每次回答结束后加上一句（回答完毕）"被一律存成结论，而结论的注入措辞是
     * "可直接使用"，模型根本不会照做。判错的代价是"记了等于没记"，所以宁可从宽。
     *
     * @param memoryType 显式类型，可为空
     * @param content    记忆内容
     * @return 最终类型
     */
    private MemoryType resolveType(String memoryType, String content) {
        if (memoryType != null && !memoryType.isBlank()) {
            return MemoryType.from(memoryType.trim());
        }
        // 祈使/长期行为类措辞 → 偏好（只有偏好会注入"请严格遵守"，也才会触发铁律第 5 条）
        for (String hint : PREFERENCE_HINTS) {
            if (content.contains(hint)) {
                return MemoryType.PREFERENCE;
            }
        }
        return MemoryType.CONCLUSION;
    }

    /**
     * 修改记忆内容与重要度。
     *
     * @param userId     用户标识
     * @param id         记忆 ID
     * @param content    新内容
     * @param importance 新重要度，可为 {@code null} 表示不改
     * @return 修改后的记忆
     */
    public MemoryDto update(String userId, Long id, String content, Integer importance) {
        Memory existing = requireOwned(userId, id);
        String newContent = (content == null || content.isBlank()) ? existing.getContent() : content.trim();
        Integer newImportance = importance == null ? existing.getImportance()
                : Math.min(Math.max(importance, 1), 5);
        memoryMapper.updateContent(userId, id, newContent, newImportance);
        return toDto(memoryMapper.selectById(userId, id));
    }

    /**
     * 删除一条记忆（软删）。
     *
     * <p><b>同时记一条"作废通知"</b>：光把 L3 行标成 DELETED 是不够的 ——
     * 用户往往在<b>同一个会话</b>里验证，而会话历史（L2）里模型已经按那条偏好
     * 回答过若干次，它会继续照做，用户就会认定"删了没用"。
     * 详见 {@code DeletedMemoryNotice}。
     *
     * @param userId 用户标识
     * @param id     记忆 ID
     */
    public void delete(String userId, Long id) {
        // 先查出来再删：要拿到 memoryType 才能决定通知措辞
        Memory existing = requireOwned(userId, id);
        memoryMapper.softDelete(userId, id);
        boolean isPreference = MemoryType.PREFERENCE.getValue().equalsIgnoreCase(
                existing.getMemoryType() == null ? "" : existing.getMemoryType());
        deletedNotice.record(userId, isPreference, existing.getContent());
        log.info("已删除记忆: userId={}, id={}, 偏好类={}", userId, id, isPreference);
    }

    /**
     * 清空某用户的全部长期记忆。
     *
     * <p>这是隐私要求（决策 7），必须提供，且是<b>物理删除</b>而非软删 ——
     * 用户说"忘掉我"，就得是真忘掉。
     *
     * @param userId 用户标识
     * @return 删除条数
     */
    public int clear(String userId) {
        if (userId == null || userId.isBlank()) {
            return 0;
        }
        int deleted = memoryMapper.deleteAllByUser(userId);
        // 清空也要记通知：用户说"忘掉我"之后，最不能接受的就是模型还照旧行事。
        // 而且清空是**物理删除**、不留任何痕迹，这通知是唯一的"遗忘信号"。
        deletedNotice.record(userId, true, null);
        log.warn("已清空用户长期记忆: userId={}, 删除 {} 条", userId, deleted);
        return deleted;
    }

    /**
     * 取归属校验后的记忆。
     *
     * @param userId 用户标识
     * @param id     记忆 ID
     * @return 记忆实体
     */
    private Memory requireOwned(String userId, Long id) {
        Memory memory = (userId == null || userId.isBlank()) ? null : memoryMapper.selectById(userId, id);
        if (memory == null) {
            throw new BusinessException(ErrorCode.MEMORY_NOT_FOUND);
        }
        return memory;
    }

    /**
     * 实体 → DTO。
     *
     * @param memory 记忆实体
     * @return 视图对象
     */
    private MemoryDto toDto(Memory memory) {
        return new MemoryDto(
                memory.getId(),
                memory.getMemoryType(),
                memory.getContent(),
                memory.getImportance(),
                memory.getConfidence() == null ? null : memory.getConfidence().doubleValue(),
                memory.getSourceConvId(),
                memory.getHitCount(),
                memory.getUpdatedAt());
    }
}
