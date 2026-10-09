package com.itjiang.qa.config;

import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;

import javax.sql.DataSource;
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
     * <p>参数里显式带上 {@link DataDirInitializer}，是为了用<b>类型依赖</b>保证
     * 目录先于数据源创建 —— 否则 H2 会因为父目录不存在而给出一个很晦涩的连接错误。
     *
     * @param properties 应用配置，提供数据根目录
     * @param dirs       数据目录初始化器（保证目录已存在）
     * @return H2 数据源
     */
    @Bean
    public DataSource dataSource(QaProperties properties, DataDirInitializer dirs) {
        Path dbDir = dirs.dataRoot().resolve("data");
        // 归一化为正斜杠：H2 的 URL 解析器对反斜杠不友好
        String dbFile = dbDir.resolve(DB_NAME).toString().replace('\\', '/');

        HikariConfig config = new HikariConfig();
        config.setPoolName("qa-h2-pool");
        config.setDriverClassName("org.h2.Driver");
        config.setJdbcUrl("jdbc:h2:file:" + dbFile
                // MODE=MySQL：让 H2 接受 AUTO_INCREMENT 等 MySQL 语法，
                // 两份建表脚本（schema-h2.sql / schema-mysql.sql）才能保持一致
                + ";MODE=MySQL"
                // ★ 刻意不加 DATABASE_TO_LOWER=TRUE：
                //   加上它会让 schema 名变成小写 public，于是 IDEA / DBeaver 等外部工具
                //   用默认设置连接时会报 `Schema "public" not found`（实际踩过，
                //   报错堆栈会写进 data/qa.trace.db）。
                //   不加的话 H2 用默认的大写 PUBLIC，而我们的 SQL 全是不带引号的
                //   小写标识符，H2 会自动归一化 —— 应用照常工作，外部工具也能用裸 URL 连。
                // AUTO_SERVER=TRUE：允许 IDEA 与命令行同时访问同一个库文件
                + ";AUTO_SERVER=TRUE");
        config.setUsername("sa");
        config.setPassword("");
        config.setMaximumPoolSize(5);
        config.setMinimumIdle(1);
        config.setConnectionTimeout(10_000);

        log.info("H2 数据源就绪: {}", dbFile + ".mv.db");
        return new HikariDataSource(config);
    }
}
