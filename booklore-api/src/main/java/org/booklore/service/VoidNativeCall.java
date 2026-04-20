package org.booklore.service;

/**
 * Functional interface for native operations that return no value.
 * Shared by all {@link NativeExecutorService} subclasses.
 */
@FunctionalInterface
public interface VoidNativeCall {
    void run() throws Exception;
}
