package org.booklore.service;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.DisposableBean;

import java.io.IOException;
import java.time.Duration;
import java.util.concurrent.*;
import java.util.function.BooleanSupplier;

/**
 * Sealed base class that serialises native library calls onto a dedicated
 * single platform thread, protecting non-thread-safe JNI / FFM code from
 * concurrent access.
 *
 * <h3>Design rationale</h3>
 * <ul>
 *   <li>Callers (typically virtual threads) submit work via {@link #execute}
 *       and park on {@link Future#get} — no monitor contention.</li>
 *   <li>A bounded {@link ArrayBlockingQueue} caps memory usage under
 *       backpressure; excess submissions are rejected with a clear error.</li>
 *   <li>{@link Future#get} (not {@code join()}) preserves the interrupt flag
 *       so virtual threads shut down cleanly.</li>
 *   <li>A configurable timeout prevents a hung native call from blocking
 *       every subsequent request forever.</li>
 *   <li>{@link DisposableBean} gives Spring a timed, graceful shutdown.</li>
 * </ul>
 */
@Slf4j
public abstract sealed class NativeExecutorService
        implements DisposableBean
        permits PdfiumNativeService, EpubNativeService {

    private static final int DEFAULT_QUEUE_CAPACITY = 256;
    private static final Duration DEFAULT_TIMEOUT = Duration.ofSeconds(30);
    private static final Duration SHUTDOWN_GRACE = Duration.ofSeconds(5);

    private final ExecutorService executor;
    private final BooleanSupplier availabilityCheck;
    private final String libraryName;
    private final Duration timeout;

    /** Production constructor — creates a bounded single-thread executor. */
    protected NativeExecutorService(BooleanSupplier availabilityCheck,
                                    String libraryName, String threadName) {
        this(availabilityCheck, libraryName, threadName, DEFAULT_TIMEOUT, DEFAULT_QUEUE_CAPACITY);
    }

    protected NativeExecutorService(BooleanSupplier availabilityCheck,
                                    String libraryName, String threadName,
                                    Duration timeout, int queueCapacity) {
        this.availabilityCheck = availabilityCheck;
        this.libraryName = libraryName;
        this.timeout = timeout;
        this.executor = new ThreadPoolExecutor(
                1, 1, 0L, TimeUnit.MILLISECONDS,
                new ArrayBlockingQueue<>(queueCapacity),
                Thread.ofPlatform().name(threadName).factory()
        );
    }

    /** Test constructor — accepts a pre-built executor. */
    protected NativeExecutorService(ExecutorService executor,
                                    BooleanSupplier availabilityCheck,
                                    String libraryName) {
        this.executor = executor;
        this.availabilityCheck = availabilityCheck;
        this.libraryName = libraryName;
        this.timeout = DEFAULT_TIMEOUT;
    }

    /**
     * Submit a {@link Callable} to the native worker thread and block until
     * it completes or the timeout elapses.
     */
    public <T> T execute(Callable<T> nativeCall) throws IOException {
        if (!availabilityCheck.getAsBoolean()) {
            throw new IOException(libraryName + " native library is not available on this platform");
        }
        Future<T> future;
        try {
            future = executor.submit(nativeCall);
        } catch (RejectedExecutionException e) {
            throw new IOException(libraryName + " native service overloaded — try again later", e);
        }
        try {
            return future.get(timeout.toMillis(), TimeUnit.MILLISECONDS);
        } catch (InterruptedException ie) {
            Thread.currentThread().interrupt();
            future.cancel(false);
            throw new IOException(libraryName + " native call interrupted", ie);
        } catch (ExecutionException ee) {
            return unwrap(ee);
        } catch (TimeoutException te) {
            future.cancel(true);
            throw new IOException(libraryName + " native call timed out after " + timeout, te);
        }
    }

    /** Convenience wrapper for native calls that return no value. */
    public void executeVoid(VoidNativeCall nativeCall) throws IOException {
        execute(() -> { nativeCall.run(); return null; });
    }

    @Override
    public void destroy() {
        executor.shutdown();
        try {
            if (!executor.awaitTermination(SHUTDOWN_GRACE.toSeconds(), TimeUnit.SECONDS)) {
                log.warn("{} executor did not terminate in {}; forcing shutdown",
                        libraryName, SHUTDOWN_GRACE);
                executor.shutdownNow();
            }
        } catch (InterruptedException e) {
            executor.shutdownNow();
            Thread.currentThread().interrupt();
        }
    }

    // ------------------------------------------------------------------
    // Exception unwrapping — uses pattern matching (Java 21+)
    // ------------------------------------------------------------------

    private <T> T unwrap(ExecutionException ee) throws IOException {
        Throwable cause = ee.getCause();
        if (cause instanceof IOException ioe) throw ioe;
        if (cause instanceof RuntimeException re) throw re;
        if (cause instanceof Error err) throw err;
        throw new IOException(libraryName + " native call failed", cause);
    }
}
