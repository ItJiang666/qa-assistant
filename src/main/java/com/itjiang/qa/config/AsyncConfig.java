package com.itjiang.qa.config;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableAsync;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

import java.util.concurrent.Executor;
import java.util.concurrent.ThreadPoolExecutor;

/**
 * 异步线程池配置。
 *
 * <p><b>关键设计：入库和记忆抽取必须用两个独立的池。</b>
 * 如果共用，用户上传一个 50MB 的 PDF 会把线程占满，
 * 与此同时所有"记忆抽取"任务都在排队 —— 表现为"聊了十几轮，它什么都没记住"，
 * 而且极难定位。分开之后，两条链路互不干扰。
 *
 * <p>两个池都采用 {@link ThreadPoolExecutor.CallerRunsPolicy}：
 * 队列满时由调用线程自己执行，等于自动降级为同步，<b>宁可慢，也不丢任务</b>。
 *
 * @author Jiang
 */
@Configuration
@EnableAsync
public class AsyncConfig {

    private static final Logger log = LoggerFactory.getLogger(AsyncConfig.class);

    /**
     * 文档入库线程池。
     *
     * <p>入库是 CPU + IO 混合型（解析文档、批量向量化），并发度过高反而互相拖慢，
     * 因此核心线程数压在 2；队列给足，让上传接口始终能立刻返回。
     *
     * @return 入库专用线程池
     */
    @Bean("ingestExecutor")
    public Executor ingestExecutor() {
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        executor.setCorePoolSize(2);
        executor.setMaxPoolSize(4);
        executor.setQueueCapacity(200);
        executor.setKeepAliveSeconds(60);
        executor.setThreadNamePrefix("ingest-");
        executor.setRejectedExecutionHandler(new ThreadPoolExecutor.CallerRunsPolicy());
        // 优雅停机：等在途的入库任务做完，避免留下"PARSING"状态的僵尸记录
        executor.setWaitForTasksToCompleteOnShutdown(true);
        executor.setAwaitTerminationSeconds(30);
        executor.initialize();
        log.info("入库线程池已初始化: core=2, max=4, queue=200");
        return executor;
    }

    /**
     * 记忆抽取线程池。
     *
     * <p>抽取是低频、低并发的任务（每轮对话一次），1 个线程绰绰有余；
     * 队列放大到 500，因为<b>记忆宁可延迟写入，也不要因为队列满而丢弃</b>。
     *
     * @return 记忆抽取专用线程池
     */
    @Bean("memoryExecutor")
    public Executor memoryExecutor() {
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        executor.setCorePoolSize(1);
        executor.setMaxPoolSize(2);
        executor.setQueueCapacity(500);
        executor.setKeepAliveSeconds(60);
        executor.setThreadNamePrefix("mem-extract-");
        executor.setRejectedExecutionHandler(new ThreadPoolExecutor.CallerRunsPolicy());
        executor.setWaitForTasksToCompleteOnShutdown(true);
        executor.setAwaitTerminationSeconds(20);
        executor.initialize();
        log.info("记忆抽取线程池已初始化: core=1, max=2, queue=500");
        return executor;
    }
}
