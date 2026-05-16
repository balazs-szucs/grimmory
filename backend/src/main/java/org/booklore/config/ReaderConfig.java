package org.booklore.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Semaphore;

@Configuration
public class ReaderConfig {

    /**
     * Shared executor for background reader tasks (archive extraction, PDF rendering).
     * Virtual threads keep blocking I/O paths lightweight while CPU/native-heavy
     * sections are bounded by {@link #readerCpuSemaphore(AppProperties)}.
     */
    @Bean(destroyMethod = "shutdown")
    public ExecutorService readerCacheExecutor() {
        return Executors.newVirtualThreadPerTaskExecutor();
    }

    @Bean
    public Semaphore readerCpuSemaphore(AppProperties appProperties) {
        Integer configured = appProperties.getReader() != null
                && appProperties.getReader().getCache() != null
                ? appProperties.getReader().getCache().getCpuPermits()
                : null;

        int permits = (configured != null && configured > 0)
                ? configured
                : Math.max(1, Runtime.getRuntime().availableProcessors() / 2);

        return new Semaphore(permits);
    }
}
