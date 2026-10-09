package com.itjiang.qa.config;

import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;

import javax.sql.DataSource;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * H2 数据源装配（默认 profile）。
 *
 * <p><b>为什么不用 yml 里的 {@code spring.datasource.url} 直接拼？</b>
 * 因为 H2 的 JDBC URL 不接受 Windows 反斜杠路径。{@code ${user.home}} 在 Windows 上
 * 解析出来是 {@code C:\Users\Jiang}，直接拼进 URL 会得到
 * {@code jdbc:h2:file:C:\Users\Jiang\.qa-assistant/data/qa} —— 混用两种分隔符，
 * 在别的机器上极易出现"路径找不到"。这里统一归一化为正斜杠，彻底消除这一整类问题。
 *
 * <p>顺带在这里创建数据目录：H2 <b>不会</b>自动创建不存在的父目录，
 * 首次启动必须先建好 {@code data} / {@code uploads} / {@code index} 三个子目录，
 * 否则用户看到的是一个晦涩的 JDBC 连接失败。
 *
 * <p><b>注意</b>：定义了自己的 {@link DataSource} 之后，
 * {@code spring.datasource.*} 就不再对本 profile 生效（Hikari 参数改用配置类维护），
 * 但 {@code spring.sql.init.*} 仍然照常工作 —— 它面向的是"容器里的 DataSource"，而非某个具体实现。
 *
 * @author Jiang
 */
@Configuration
@Profile("h2")
public class H2DataSourceConfig {

    private static final Logger log = LoggerFactory.getLogger(H2DataSourceConfig.class);

    /** H2 数据库文件名（不含扩展名），实际落盘为 {@code qa.mv.db}。 */
    private static final String DB_NAME = "qa";

    /**
     * 构建 H2 文件模式数据源。
     *
     * @param properties 应用配置，提供数据根目录
     * @return H2 数据源
     */
    @Bean
    public DataSource dataSource(QaProperties properties) {
        Path dataRoot = properties.dataPath().toAbsolutePath().normalize();
        Path dbDir = dataRoot.resolve("data");
        Path uploadDir = dataRoot.resolve("uploads");
        Path indexDir = dataRoot.resolve("index");

        createDirectories(dbDir, uploadDir, indexDir);

        // 归一化为正斜杠：H2 的 URL 解析器对反斜杠不友好
        String dbFile = dbDir.resolve(DB_NAME).toString().replace('\\', '/');

        HikariConfig config = new HikariConfig();
        config.setPoolName("qa-h2-pool");
        config.setDriverClassName("org.h2.Driver");
        config.setJdbcUrl("jdbc:h2:file:" + dbFile
                // MODE=MySQL：让 H2 接受 AUTO_INCREMENT 等 MySQL 语法，两份 DDL 才能保持一致
                + ";MODE=MySQL"
                // 表名/列名统一按小写处理，避免大小写敏感导致的"表不存在"
                + ";DATABASE_TO_LOWER=TRUE"
                // 允许 IDEA 与命令行同时访问同一个库文件（否则第二个连接会被拒绝）
                + ";AUTO_SERVER=TRUE");
        config.setUsername("sa");
        config.setPassword("");
        config.setMaximumPoolSize(5);
        config.setMinimumIdle(1);
        config.setConnectionTimeout(10_000);

        log.info("H2 数据源就绪: {}", dbFile + ".mv.db");
        log.info("数据根目录: {}", dataRoot);
        return new HikariDataSource(config);
    }

    /**
     * 创建运行时数据目录（幂等）。
     *
     * @param dirs 待创建的目录
     */
    private void createDirectories(Path... dirs) {
        for (Path dir : dirs) {
            try {
                Files.createDirectories(dir);
            } catch (IOException ex) {
                // 启动期失败必须立刻终止：目录建不出来，后面所有读写都会失败，
                // 带着一个坏掉的数据源继续启动只会让问题在更难排查的地方爆出来
                throw new IllegalStateException("无法创建数据目录: " + dir, ex);
            }
        }
    }
}
