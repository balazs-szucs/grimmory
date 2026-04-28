package org.booklore.service.metadata;

import lombok.AllArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.TaskScheduler;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Supplier;

/**
 * Service providing non-blocking rate limiting using TaskScheduler.
 * Replaces Thread.sleep anti-patterns in parsers.
 */
@Slf4j
@Service
@AllArgsConstructor
public class RateLimitService {

    private final TaskScheduler taskScheduler;
    private final Map<String, AtomicLong> lastExecutionTimes = new ConcurrentHashMap<>();

    /**
     * Executes a task with a minimum interval from the last execution for the same source.
     * Respects any backoff set via setBackoffUntil.
     */
    public <T> CompletableFuture<T> execute(String source, long minIntervalMs, Supplier<T> task) {
        AtomicLong lastTime = lastExecutionTimes.computeIfAbsent(source, k -> new AtomicLong(0));
        
        long now = System.currentTimeMillis();
        long scheduleAt;
        synchronized (lastTime) {
            scheduleAt = Math.max(now, lastTime.get() + minIntervalMs);
            lastTime.set(scheduleAt);
        }

        CompletableFuture<T> future = new CompletableFuture<>();
        taskScheduler.schedule(() -> {
            try {
                future.complete(task.get());
            } catch (Exception e) {
                future.completeExceptionally(e);
            }
        }, Instant.ofEpochMilli(scheduleAt));

        return future;
    }

    /**
     * Sets a time until which no tasks for the given source will be executed.
     */
    public void setBackoffUntil(String source, long untilTime) {
        AtomicLong lastTime = lastExecutionTimes.computeIfAbsent(source, k -> new AtomicLong(0));
        synchronized (lastTime) {
            lastTime.set(Math.max(lastTime.get(), untilTime));
        }
    }

    /**
     * Overload for void tasks.
     */
    public CompletableFuture<Void> execute(String source, long minIntervalMs, Runnable task) {
        return execute(source, minIntervalMs, () -> {
            task.run();
            return null;
        });
    }
}
