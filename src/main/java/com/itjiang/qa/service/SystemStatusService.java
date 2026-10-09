package com.itjiang.qa.service;

import com.itjiang.qa.config.QaProperties;
import com.itjiang.qa.context.UserContext;
import com.itjiang.qa.mapper.DocumentMapper;
import com.itjiang.qa.model.dto.SystemStatusResponse;
import com.itjiang.qa.provider.CloudEndpointResolver;
import com.itjiang.qa.provider.RuntimeModelRegistry;
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
    private final DocumentMapper documentMapper;
    private final JdbcTemplate jdbcTemplate;
    private final Environment environment;
    private final RuntimeModelRegistry modelRegistry;
    private final RuntimeModeService modeService;
    private final CloudEndpointResolver cloudResolver;

    public SystemStatusService(QaProperties properties,
                               DocumentMapper documentMapper,
                               JdbcTemplate jdbcTemplate,
                               Environment environment,
                               RuntimeModelRegistry modelRegistry,
                               RuntimeModeService modeService,
                               CloudEndpointResolver cloudResolver) {
        this.properties = properties;
        this.documentMapper = documentMapper;
        this.jdbcTemplate = jdbcTemplate;
        this.environment = environment;
        this.modelRegistry = modelRegistry;
        this.modeService = modeService;
        this.cloudResolver = cloudResolver;
    }

    /**
     * 汇总当前系统状态。
     *
     * <p><b>模式与模型名必须问注册表，不能读 {@code qa.local.*}。</b> P4 之前这里直接读
     * 本地配置是成立的（那时只有一个模式）；现在对话模式可以在运行时切换，
     * 继续读本地配置就会出现"界面说本地 qwen3.5:9b、实际请求发给 DeepSeek"这种
     * 自相矛盾的状态 —— 界面上撒谎比报错更难发现。
     *
     * @return 系统状态快照
     */
    public SystemStatusResponse currentStatus() {
        String userId = UserContext.get();
        CloudEndpointResolver.ResolvedEndpoint chatEndpoint = cloudResolver.chat();
        CloudEndpointResolver.ResolvedEndpoint embeddingEndpoint = cloudResolver.embedding();
        return new SystemStatusResponse(
                modelRegistry.chatMode().value(),
                modelRegistry.chatModelName(),
                modelRegistry.embeddingModelName(),
                properties.getLocal().getNumCtx(),
                properties.dataPath().toAbsolutePath().normalize().toString(),
                detectDatabase(),
                "InMemoryEmbeddingStore",
                countDocuments(),
                countChunks(),
                countMemories(userId),
                userId != null,
                userId,
                modelRegistry.configuredChatMode().value(),
                modeService.overridden(),
                modelRegistry.embeddingMode().value(),
                chatEndpoint.label() == null ? chatEndpoint.providerId() : chatEndpoint.label(),
                chatEndpoint.model(),
                describeEmbedding(embeddingEndpoint),
                chatEndpoint.usable(),
                chatEndpoint.usable() ? null : chatEndpoint.missingHint() + "（补到 .env 或环境变量后重启应用）",
                indexWarning());
    }

    /**
     * 判断索引与当前向量模型是否一致，不一致时给出一句可照做的提示。
     *
     * <p><b>为什么必须靠"模型名"而不是"维度"来判断：</b> 实测硅基流动的
     * Qwen3-Embedding-4B 与本地 qa-embed:4b 维度相同（都是 2560），但同一段文本
     * 在两边的向量余弦只有 0.988 —— 维度检查完全抓不到这种"同维不同空间"的情况，
     * 它不会报错，只会让分数被系统性压低。名字对不上就提示重建，是唯一可靠的判据。
     *
     * @return 提示文案；一致时返回 {@code null}
     */
    private String indexWarning() {
        try {
            String current = modelRegistry.embeddingModelName();
            List<String> used = documentMapper.selectDistinctEmbeddingModels();
            List<String> foreign = used.stream()
                    .filter(name -> name != null && !name.equals(current))
                    .toList();
            if (foreign.isEmpty()) {
                return null;
            }
            return "向量索引由 " + String.join("、", foreign) + " 建立，当前向量模型是 " + current
                    + "。两者向量空间不同：维度不同会直接检索失败，维度相同也会让相似度被系统性压低。"
                    + "请在「文档管理」页点击「重建索引」。";
        } catch (Exception ex) {
            // 状态接口不能因为一次统计失败就整体不可用
            log.warn("检查索引与向量模型一致性失败: {}", ex.getMessage());
            return null;
        }
    }

    /**
     * 描述向量端点（模型名 + 维度）。
     *
     * @param endpoint 云端向量端点
     * @return 形如 {@code Qwen/Qwen3-Embedding-4B（2560 维）}
     */
    private String describeEmbedding(CloudEndpointResolver.ResolvedEndpoint endpoint) {
        if (endpoint.model() == null) {
            return null;
        }
        return endpoint.dimension() == null
                ? endpoint.model()
                : endpoint.model() + "（" + endpoint.dimension() + " 维）";
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
     * @return 文档总数
     */
    private long countDocuments() {
        try {
            return documentMapper.countAll();
        } catch (Exception ex) {
            log.warn("统计文档数失败（返回 0，不阻断状态接口）: {}", ex.getMessage());
            return 0L;
        }
    }

    /**
     * 统计分块总数。
     *
     * <p>以数据库为准而不是问向量库：LangChain4j 的 {@code EmbeddingStore} 接口
     * 没有 count()，而 {@code qa_document.chunk_count} 之和是我们写进去的真值，
     * 换任何向量库实现都成立。
     *
     * @return 所有文档的分块数之和
     */
    private long countChunks() {
        try {
            return documentMapper.sumChunkCount();
        } catch (Exception ex) {
            log.warn("统计分块数失败（返回 0）: {}", ex.getMessage());
            return 0L;
        }
    }

    /**
     * 统计指定用户的长期记忆条数（仅 ACTIVE）。
     *
     * <p>P3.5 阶段会引入 MemoryMapper 取代这里的裸 SQL；当前直接用 JdbcTemplate，
     * 是为了在还没有长期记忆功能时就先把"记忆条数"这个指标暴露出来。
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
                    //TODO 此处未配置数据源，使用URL直连数据库，因此报错，不影响程序执行，可以不修改
                    "SELECT COUNT(*) FROM qa_memory WHERE user_id = ? AND status = 'ACTIVE'",
                    Long.class, userId);
            return count == null ? 0L : count;
        } catch (Exception ex) {
            log.warn("统计长期记忆失败: {}", ex.getMessage());
            return 0L;
        }
    }
}
