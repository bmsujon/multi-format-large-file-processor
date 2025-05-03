package com.wahid.multi_format_large_file_processor.config;

import org.springframework.beans.factory.annotation.Qualifier; // Import Qualifier
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.task.AsyncTaskExecutor;
import org.springframework.core.task.support.TaskExecutorAdapter;
import org.springframework.scheduling.annotation.EnableAsync;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Configures Spring's asynchronous execution mechanism and related executors.
 */
@Configuration
@EnableAsync // Enables Spring's @Async annotation support
public class AsyncConfig {

    /**
     * Creates the underlying ExecutorService using virtual threads.
     * This can be shared by different bean definitions if needed.
     * Note: Making this a separate bean makes it injectable directly.
     * It is explicitly named "virtualThreadExecutor" to satisfy dependencies
     * requesting this specific qualifier.
     *
     * @return An ExecutorService configured for virtual threads.
     */
    @Bean("virtualThreadExecutor") // <--- Explicitly name the bean here
    public ExecutorService virtualThreadExecutorService() {
        return Executors.newVirtualThreadPerTaskExecutor();
    }

    /**
     * Defines the default task executor used by @Async methods,
     * adapting the virtualThreadExecutor bean.
     *
     * @param virtualThreadExecutor The ExecutorService bean to adapt (found by type and name).
     * @return The AsyncTaskExecutor configured for virtual threads.
     */
    @Bean("asyncTaskExecutor") // Explicitly name the bean for @Async
    public AsyncTaskExecutor asyncTaskExecutor(
            @Qualifier("virtualThreadExecutor") ExecutorService virtualThreadExecutor // Use Qualifier here too for clarity
    ) {
        // Adapt the ExecutorService bean defined above
        return new TaskExecutorAdapter(virtualThreadExecutor);
    }

    // Now, any component requiring `@Autowired @Qualifier("virtualThreadExecutor") ExecutorService`
    // will receive the correctly named bean.
}