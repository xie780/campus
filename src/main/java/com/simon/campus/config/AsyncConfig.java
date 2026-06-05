package com.simon.campus.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

import java.util.concurrent.Executor;

/**
 * 异步任务配置：定义文档入库和通用异步任务的线程池
 */
@Configuration
public class AsyncConfig {

    /**
     * 文档入库专用线程池：核心 2 线程，最大 4 线程
     */
    @Bean("ingestExecutor")
    public Executor ingestExecutor() {
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor(); // 创建线程池
        executor.setCorePoolSize(2); // 核心线程数
        executor.setMaxPoolSize(4); // 最大线程数
        executor.setQueueCapacity(50); // 队列容量
        executor.setThreadNamePrefix("ingest-"); // 线程名前缀
        executor.setWaitForTasksToCompleteOnShutdown(true); // 关闭时等待任务完成
        executor.setAwaitTerminationSeconds(60); // 等待超时时间
        executor.initialize(); // 初始化线程池
        return executor; // 返回线程池
    }

    /**
     * 通用异步任务线程池：核心 2 线程，最大 6 线程
     */
    @Bean("taskExecutor")
    @Primary
    public Executor taskExecutor() {
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor(); // 创建线程池
        executor.setCorePoolSize(2); // 核心线程数
        executor.setMaxPoolSize(6); // 最大线程数
        executor.setQueueCapacity(100); // 队列容量
        executor.setThreadNamePrefix("async-"); // 线程名前缀
        executor.setWaitForTasksToCompleteOnShutdown(true); // 关闭时等待任务完成
        executor.setAwaitTerminationSeconds(30); // 等待超时时间
        executor.initialize(); // 初始化线程池
        return executor; // 返回线程池
    }
}
