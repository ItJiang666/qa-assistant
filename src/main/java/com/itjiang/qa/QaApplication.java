package com.itjiang.qa;

import com.itjiang.qa.config.QaProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.core.env.Environment;

/**
 * qa-assistant 启动类。
 *
 * <p>设计要点：
 * <ul>
 *   <li>启动完成后主动打印关键运行参数（数据目录、数据源、模型模式）——
 *       很多"数据不见了""怎么这么慢"的疑问，都是因为用户不知道程序到底用了哪份配置。
 *       把答案打在启动日志里，比写在文档里有用。</li>
 * </ul>
 *
 * @author Jiang
 */
@SpringBootApplication
@EnableConfigurationProperties(QaProperties.class)
public class QaApplication {

    private static final Logger log = LoggerFactory.getLogger(QaApplication.class);

    public static void main(String[] args) {
        ConfigurableApplicationContext ctx = SpringApplication.run(QaApplication.class, args);

        Environment env = ctx.getEnvironment();
        QaProperties props = ctx.getBean(QaProperties.class);

        log.info("""
                        
                        ────────────────────────────────────────────────────────|
                         qa-assistant 启动完成
                          模型模式   : {}
                          激活 profile: {}
                          数据目录   : {}
                          监听地址   : http://localhost:{}
                        ────────────────────────────────────────────────────────""",
                props.getMode(),
                String.join(",", env.getActiveProfiles()),
                props.getDataDir(),
                env.getProperty("server.port", "8080"));
    }
}
