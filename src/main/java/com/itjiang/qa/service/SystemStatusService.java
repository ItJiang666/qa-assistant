package com.itjiang.qa.service;

import com.itjiang.qa.config.QaProperties;
import com.itjiang.qa.context.UserContext;
import com.itjiang.qa.model.dto.SystemStatusResponse;
import com.itjiang.qa.model.enums.RunMode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.env.Environment;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.util.List;

/**
 * 系统状态查询（F9）。
 *
 * <p>本服务在 P0 阶段还承担一个额外职责：<b>用一次真实的数据库查询验证整条链路</b> ——
 * H2 文件是否创建、{@code schema-h2.sql} 是否执行成功、表是否真的存在。
 * 只要这个接口能返回数字，就说明数据层是通的；返回 500 则说明建表脚本有问题。
 *
 * <p>刻意不引入 Mapper/Entity：这里只需要三个计数，为它建三套映射类属于过度设计，
 * 等 P1 真正开始读写业务表时再按 MyBatis 规范补齐。
 *
 * @author Jiang
 */
@Service
public class SystemStatusService {

    private static final Logger log = LoggerFactory.getLogger(SystemStatusService.class);

    private final QaProperties properties;
    private final JdbcTemplate jdbcTemplate;
    private final Environment environment;

    public SystemStatusService(QaProperties properties, JdbcTemplate jdbcTemplate, Environment environment) {
        this.properties = properties;
        this.jdbcTemplate = jdbcTemplate;
        this.environment = environment;
    }

    /**
     * 汇总当前系统状态。
     *
     * @return 系统状态快照
     */
    public SystemStatusResponse currentStatus() {
        String userId = UserContext.get();
        return new SystemStatusResponse(
                RunMode.from(properties.getMode()).getValue(),
                properties.getLocal().getChatModel(),
                properties.getLocal().getEmbeddingModel(),
                properties.getLocal().getNumCtx(),
                properties.dataPath().toAbsolutePath().normalize().toString(),
                detectDatabase(),
                "InMemoryEmbeddingStore",
                countDocuments(),
                countChunks(),
                countMemories(userId),
                userId != null,
                userId);
    }

    /**
     * 识别当前激活的数据源。
     *
     * <p>通过激活的 profile 判断，而不是读 JDBC URL —— URL 可能被环境变量整串覆盖，
     * profile 才是我们自己的约定。
     *
     * @return {@code h2} 或 {@code mysql}
     */
    private String detectDatabase() {
        List<String> profiles = List.of(environment.getActiveProfiles());
        return profiles.contains("mysql") ? "mysql" : "h2";
    }

    /**
     * 统计已入库文档数。
     *
     * <p>查询失败时返回 0 而不抛异常：状态接口的意义是"无论如何都能告诉你现在怎么样"，
     * 它自己挂掉就失去了价值。
     *
     * @return 文档总数
     */
    private long countDocuments() {
        return queryCount("SELECT COUNT(*) FROM qa_document", "qa_document");
    }

    /**
     * 统计分块总数。
     *
     * @return 所有文档的分块数之和
     */
    private long countChunks() {
        return queryCount("SELECT COALESCE(SUM(chunk_count), 0) FROM qa_document", "qa_document.chunk_count");
    }

    /**
     * 统计指定用户的长期记忆条数（仅 ACTIVE）。
     *
     * @param userId 用户标识，为 {@code null} 时不做查询直接返回 0
     * @return 记忆条数
     */
    private long countMemories(String userId) {
        if (userId == null) {
            return 0L;
        }
        try {
            Long count = jdbcTemplate.queryForObject(
                    "SELECT COUNT(*) FROM qa_memory WHERE user_id = ? AND status = 'ACTIVE'",
                    Long.class, userId);
            return count == null ? 0L : count;
        } catch (Exception ex) {
            log.warn("统计长期记忆失败: {}", ex.getMessage());
            return 0L;
        }
    }

    /**
     * 执行计数查询，失败时降级为 0。
     *
     * @param sql  计数 SQL
     * @param what 用于日志的说明
     * @return 计数结果
     */
    private long queryCount(String sql, String what) {
        try {
            Long count = jdbcTemplate.queryForObject(sql, Long.class);
            return count == null ? 0L : count;
        } catch (Exception ex) {
            log.warn("统计 {} 失败（返回 0，不阻断状态接口）: {}", what, ex.getMessage());
            return 0L;
        }
    }
}
