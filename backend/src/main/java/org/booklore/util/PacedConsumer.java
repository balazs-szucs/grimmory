package org.booklore.util;

import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.TaskScheduler;
import java.time.Instant;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;

/**
 * A consumer that paces emissions using a TaskScheduler to ensure a minimum interval between items.
 * This provides a smooth streaming experience without blocking threads with Thread.sleep.
 */
@Slf4j
public class PacedConsumer<T> implements Consumer<T>, AutoCloseable {
    private final Consumer<T> delegate;
    private final long intervalMs;
    private final TaskScheduler scheduler;
    private final BlockingQueue<T> queue = new LinkedBlockingQueue<>();
    private final CountDownLatch completionLatch = new CountDownLatch(1);
    private final AtomicBoolean finished = new AtomicBoolean(false);
    private final AtomicBoolean isProcessing = new AtomicBoolean(false);

    public PacedConsumer(Consumer<T> delegate, long intervalMs, TaskScheduler scheduler) {
        this.delegate = delegate;
        this.intervalMs = intervalMs;
        this.scheduler = scheduler;
    }

    @Override
    public void accept(T t) {
        if (finished.get()) {
            return;
        }
        queue.add(t);
        tryStartProcessing();
    }

    private void tryStartProcessing() {
        if (isProcessing.compareAndSet(false, true)) {
            scheduleProcess(0);
        }
    }

    private void scheduleProcess(long delayMs) {
        scheduler.schedule(this::process, Instant.now().plusMillis(delayMs));
    }

    private void process() {
        T item = queue.poll();
        if (item != null) {
            try {
                delegate.accept(item);
            } catch (Exception e) {
                log.error("Error in PacedConsumer delegate", e);
            }
            scheduleProcess(intervalMs);
        } else {
            isProcessing.set(false);
            // Check again in case something was added just after poll returned null
            if (!queue.isEmpty()) {
                tryStartProcessing();
            } else if (finished.get()) {
                completionLatch.countDown();
            }
        }
    }

    public void finish() {
        this.finished.set(true);
        if (!isProcessing.get() && queue.isEmpty()) {
            completionLatch.countDown();
        }
    }

    public void awaitCompletion() throws InterruptedException {
        completionLatch.await();
    }

    @Override
    public void close() {
        finish();
        try {
            awaitCompletion();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
