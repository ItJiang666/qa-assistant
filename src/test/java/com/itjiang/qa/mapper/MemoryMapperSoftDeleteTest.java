package com.itjiang.qa.mapper;

import org.apache.ibatis.mapping.Environment;
import org.apache.ibatis.session.Configuration;
import org.apache.ibatis.session.SqlSession;
import org.apache.ibatis.session.SqlSessionFactory;
import org.apache.ibatis.session.SqlSessionFactoryBuilder;
import org.apache.ibatis.transaction.jdbc.JdbcTransactionFactory;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.Statement;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 「删掉的记忆不能被自动抽取复活」的回归测试。
 *
 * <h2>为什么值得单独写一个真库测试，而不是只测 SQL 文本</h2>
 * <p>这是个**已经发生过的数据缺陷**：唯一键是 {@code (user_id, mem_key)}，
 * 而 {@code updateByUserAndKey} 原先的 WHERE 只有 user_id + mem_key，
 * SET 里却写着 {@code status = 'ACTIVE'}}。于是用户删除一条记忆后，
 * 下一轮问答的异步抽取只要抽到同一个 mem_key，就把那条已删除的行改回活跃 ——
 * 记忆原地复活，连重启服务都救不回来（因为数据库里的行真的被改了）。
 *
 * <p>这个 bug 的形态决定了它必须用**真库 + 真 MyBatis** 验证：
 * 断言"WHERE 里有没有 status 条件"是在测文本，而只有真的删一条、再跑一次
 * upsert，才能回答"它会不会回来"。
 *
 * <p>用 H2 内存库（{@code MODE=MySQL}）而不是 mock：本项目要同时跑 H2 与 MySQL，
 * 方言差异正是这类语句最容易出问题的地方。
 *
 * @author Jiang
 */
class MemoryMapperSoftDeleteTest {

    private Connection connection;
    private SqlSession session;
    private MemoryMapper mapper;

    /** 测试用用户标识。 */
    private static final String USER = "test-user";
    /** 测试用主题键。 */
    private static final String KEY = "answer_style";

    @BeforeEach
    void setUp() throws Exception {
        connection = DriverManager.getConnection("jdbc:h2:mem:memtest;MODE=MySQL;DB_CLOSE_DELAY=-1", "sa", "");
        try (Statement st = connection.createStatement()) {
            st.execute("DROP TABLE IF EXISTS qa_memory");
            st.execute("""
                    CREATE TABLE qa_memory (
                      id             BIGINT       AUTO_INCREMENT PRIMARY KEY,
                      user_id        VARCHAR(64)  NOT NULL,
                      memory_type    VARCHAR(16)  NOT NULL,
                      mem_key        VARCHAR(128) NOT NULL,
                      content        VARCHAR(500) NOT NULL,
                      confidence     DECIMAL(3,2) NOT NULL DEFAULT 0.80,
                      importance     TINYINT      NOT NULL DEFAULT 3,
                      source_conv_id VARCHAR(64),
                      status         VARCHAR(16)  NOT NULL DEFAULT 'ACTIVE',
                      hit_count      INT          NOT NULL DEFAULT 0,
                      created_at     TIMESTAMP    NOT NULL DEFAULT CURRENT_TIMESTAMP,
                      updated_at     TIMESTAMP    NOT NULL DEFAULT CURRENT_TIMESTAMP,
                      CONSTRAINT uk_qa_memory_user_key UNIQUE (user_id, mem_key)
                    )
                    """);
        }

        Configuration cfg = new Configuration(new Environment("test",
                new JdbcTransactionFactory(), new UnpooledDataSourceShim(connection)));
        // 必须显式加载映射 XML：手搓 Configuration 不会自动扫描 resources/mapper 下的文件，
        // 结果就是 "Invalid bound statement (not found)" —— 接口有、语句没有。
        // 这里加载的正是生产环境用的那份 XML，所以测的是真语句而不是复刻品。
        //
        // 注意：XML 的 namespace 就是 MemoryMapper 的全限定名，parse() 会**顺带注册接口**，
        // 所以后面不能再 addMapper(...) —— 会报 "already known to the MapperRegistry"。
        try (var in = org.apache.ibatis.io.Resources.getResourceAsStream("mapper/MemoryMapper.xml")) {
            new org.apache.ibatis.builder.xml.XMLMapperBuilder(
                    in, cfg, "mapper/MemoryMapper.xml", cfg.getSqlFragments()).parse();
        }
        SqlSessionFactory factory = new SqlSessionFactoryBuilder().build(cfg);
        session = factory.openSession(true);
        mapper = session.getMapper(MemoryMapper.class);
    }

    @AfterEach
    void tearDown() throws Exception {
        if (session != null) {
            session.close();
        }
        if (connection != null && !connection.isClosed()) {
            try (Statement st = connection.createStatement()) {
                st.execute("DROP TABLE IF EXISTS qa_memory");
            }
            connection.close();
        }
    }

    /**
     * 造一条记忆实体。
     *
     * @param content 内容
     * @return 实体
     */
    private com.itjiang.qa.model.entity.Memory entity(String content) {
        var m = new com.itjiang.qa.model.entity.Memory();
        m.setUserId(USER);
        m.setMemoryType("PREFERENCE");
        m.setMemKey(KEY);
        m.setContent(content);
        m.setConfidence(new java.math.BigDecimal("0.90"));
        m.setImportance(3);
        return m;
    }

    /**
     * 读当前该 key 的状态与内容。
     *
     * @return [status, content]，无行时返回 null
     */
    private String[] state() throws Exception {
        try (Statement st = connection.createStatement();
             ResultSet rs = st.executeQuery(
                     "SELECT status, content FROM qa_memory WHERE user_id='" + USER + "' AND mem_key='" + KEY + "'")) {
            if (!rs.next()) {
                return null;
            }
            return new String[]{rs.getString(1), rs.getString(2)};
        }
    }

    @Test
    @DisplayName("★ 核心：已删除的记忆，不能被自动抽取（upsert）复活")
    void deletedMemoryMustNotBeRevivedByUpsert() throws Exception {
        // 1) 正常写入一条
        mapper.insert(entity("回答简短一点"));
        assertThat(state()[0]).isEqualTo("ACTIVE");

        // 2) 用户删掉它（软删）
        int deleted = mapper.softDelete(USER, 1L);
        assertThat(deleted).isEqualTo(1);
        assertThat(state()[0]).isEqualTo("DELETED");

        // 3) 下一轮对话又抽到同一个主题 —— 这一步就是当初把它复活的地方
        int updated = mapper.updateByUserAndKey(entity("回答简短一点"));

        // 断言两条：UPDATE 不该命中已删行，且状态必须还是 DELETED
        assertThat(updated)
                .as("UPDATE 不应命中已删除的行（WHERE 缺 status 条件时会命中并把状态改回 ACTIVE）")
                .isZero();
        assertThat(state()[0])
                .as("已删除的记忆必须保持删除状态")
                .isEqualTo("DELETED");
        assertThat(state()[1])
                .as("已删除记忆的内容不该被自动抽取覆写")
                .isEqualTo("回答简短一点");
    }

    @Test
    @DisplayName("被淘汰（evictOverflow）的记忆同样不能被复活")
    void evictedMemoryMustNotBeRevived() throws Exception {
        mapper.insert(entity("旧偏好"));
        int evicted = mapper.evictOverflow(USER, 0);
        assertThat(evicted).isEqualTo(1);
        assertThat(state()[0]).isEqualTo("DELETED");

        assertThat(mapper.updateByUserAndKey(entity("旧偏好"))).isZero();
        assertThat(state()[0]).isEqualTo("DELETED");
    }

    @Test
    @DisplayName("活跃记忆仍能正常合并（别把功能一起修没了）")
    void activeMemoryShouldStillBeMerged() throws Exception {
        mapper.insert(entity("回答简短一点"));
        int updated = mapper.updateByUserAndKey(entity("回答尽量简短，三句以内"));

        assertThat(updated).as("活跃记忆必须仍可被 upsert 合并").isEqualTo(1);
        assertThat(state()[0]).isEqualTo("ACTIVE");
        assertThat(state()[1]).isEqualTo("回答尽量简短，三句以内");
    }

    @Test
    @DisplayName("注入与计数只认 ACTIVE：删除后立刻不再被取到")
    void deletedMemoryShouldNotBeSelected() throws Exception {
        mapper.insert(entity("回答简短一点"));
        assertThat(mapper.selectTopForInject(USER, 8)).hasSize(1);

        mapper.softDelete(USER, 1L);

        assertThat(mapper.selectTopForInject(USER, 8))
                .as("注入查询必须过滤 status，否则删除当场失效")
                .isEmpty();
        assertThat(mapper.countActiveByUser(USER)).isZero();
    }

    /**
     * 把已有连接包成 MyBatis 能用的 DataSource。
     *
     * <p>只实现 {@code getConnection()} 两个重载 —— 这个测试是单线程的，
     * 不需要连接池。用现成的 Hikari 会引入一个与测试目标无关的依赖。
     */
    private record UnpooledDataSourceShim(Connection conn) implements javax.sql.DataSource {
        @Override public Connection getConnection() { return conn; }
        @Override public Connection getConnection(String u, String p) { return conn; }
        @Override public java.io.PrintWriter getLogWriter() { return null; }
        @Override public void setLogWriter(java.io.PrintWriter out) { }
        @Override public void setLoginTimeout(int seconds) { }
        @Override public int getLoginTimeout() { return 0; }
        @Override public java.util.logging.Logger getParentLogger() { return null; }
        @Override public <T> T unwrap(Class<T> iface) { throw new UnsupportedOperationException(); }
        @Override public boolean isWrapperFor(Class<?> iface) { return false; }
    }
}
