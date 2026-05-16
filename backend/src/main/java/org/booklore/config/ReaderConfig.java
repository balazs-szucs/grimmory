package org.booklore.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

@Configuration
public class ReaderConfig {

    /**
     * Shared executor for background reader tasks (archive extraction, PDF rendering).
     * Using a fixed pool sized to half of available processors ensures background
     * tasks don't starve the main HTTP threads during heavy load.
     */
    @Bean(destroyMethod = "shutdown")
    public ExecutorService readerCacheExecutor() {
        return Executors.newVirtualThreadPerTaskExecutor();
    }
}
