package com.wahid.multi_format_large_file_processor.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.task.AsyncTaskExecutor;
import org.springframework.core.task.support.TaskExecutorAdapter;
import org.springframework.scheduling.annotation.EnableAsync;

import java.util.concurrent.Executors;

/**
 * Configures Spring's asynchronous execution mechanism.
 */
@Configuration
@EnableAsync // Enables Spring's @Async annotation support
public class AsyncConfig {

    /**
     * Defines the default task executor used by @Async methods.
     * This executor uses Java 21's virtual threads, ideal for I/O-bound tasks.
     *
     * @return The AsyncTaskExecutor configured for virtual threads.
     */
    @Bean // This bean will be named "taskExecutor" by default
    public AsyncTaskExecutor asyncTaskExecutor() {
        // Use Java 21's virtual thread per task executor
        // Adapt it for Spring's TaskExecutor interface
        return new TaskExecutorAdapter(Executors.newVirtualThreadPerTaskExecutor());
    }

    // You could define other executors with specific names if needed
    // e.g., @Bean("specificExecutor") public TaskExecutor specificExecutor() { ... }
    // and then use @Async("specificExecutor") on methods.
}