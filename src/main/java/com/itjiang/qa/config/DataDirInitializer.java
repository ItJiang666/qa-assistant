package com.itjiang.qa.config;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * 运行时目录初始化（决策 19）。
 *
 * <p><b>为什么单独抽一个组件，而不是写在数据源配置里？</b>
 * 一开始是把建目录塞在 {@code H2DataSourceConfig} 里的，但那有个隐患：
 * 那个类带 {@code @Profile("h2")}，一旦切到 MySQL 就不会执行，
 * {@code uploads} / {@code index} 目录不会被创建，直到用户第一次上传才报错 ——
 * 而且报的是底层 IO 异常，看不出是"目录没建"。
 *
 * <p>现在改成由构造函数创建目录：任何依赖它的 Bean（数据源、向量库）
 * 被创建之前，目录一定已经就绪。用类型依赖代替时序假设，比写注释"注意顺序"可靠。
 *
 * @author Jiang
 */
@Component
public class DataDirInitializer {

    private static final Logger log = LoggerFactory.getLogger(DataDirInitializer.class);

    private final Path dataRoot;

    /**
     * 创建全部运行时目录。
     *
     * @param properties 应用配置
     * @throws IllegalStateException 目录创建失败时抛出 —— 启动期就该失败，
     *                               而不是等到用户上传文件时才暴露
     */
    public DataDirInitializer(QaProperties properties) {
        this.dataRoot = properties.dataPath().toAbsolutePath().normalize();
        create(dataRoot, dataRoot.resolve("data"), dataRoot.resolve("uploads"),
                dataRoot.resolve("index"), dataRoot.resolve("logs"));
        log.info("运行时数据目录: {}", dataRoot);
    }

    /**
     * @return 数据根目录
     */
    public Path dataRoot() {
        return dataRoot;
    }

    /**
     * @return 上传原文件目录
     */
    public Path uploadDir() {
        return dataRoot.resolve("uploads");
    }

    /**
     * @return 向量库落盘目录
     */
    public Path indexDir() {
        return dataRoot.resolve("index");
    }

    /**
     * 幂等创建目录。
     *
     * @param dirs 待创建目录
     */
    private void create(Path... dirs) {
        for (Path dir : dirs) {
            try {
                Files.createDirectories(dir);
            } catch (IOException ex) {
                throw new IllegalStateException("无法创建数据目录: " + dir, ex);
            }
        }
    }
}
