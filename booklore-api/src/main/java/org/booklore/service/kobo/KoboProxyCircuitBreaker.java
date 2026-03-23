package org.booklore.service.kobo;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.time.Instant;

/**
 * Circuit Breaker for Kobo Store Proxy - Prevents cascading failures when Kobo store is unavailable.
 * 
 * Ported from Komga's stability patterns and research on distributed system resilience.
 * 
 * States:
 * - CLOSED: Normal operation, all requests go through
 * - OPEN: Kobo store failing, skip proxy requests for reset timeout
 * - HALF_OPEN: Testing if service recovered, allow one request
 * 
 * Research basis:
 * - GitHub Issue #2177: Proxy failures causing sync timeouts
 * - Komga pattern: Graceful degradation on proxy failure
 * - Calibre-Web pattern: Fallback to local data only
 * 
 * Thread Safety: Uses volatile fields + synchronized methods for safe concurrent access.
 */
@Slf4j
@Component
public class KoboProxyCircuitBreaker {
    
    // Failure threshold before opening circuit (ported from industry best practices)
    private static final int FAILURE_THRESHOLD = 3;
    
    // Reset timeout - how long to wait before trying again (5 minutes)
    private static final Duration RESET_TIMEOUT = Duration.ofMinutes(5);
    
    // Fields accessed only within synchronized methods - volatile not needed
    private int failureCount = 0;
    private Instant lastFailureTime = null;
    private CircuitState state = CircuitState.CLOSED;
    
    public enum CircuitState {
        CLOSED,      // Normal operation - proxy all requests
        OPEN,        // Failing - skip proxy, use local data only
        HALF_OPEN    // Testing - allow one request to check recovery
    }
    
    /**
     * Check if proxy execution is allowed.
     * @return true if request should be proxied, false if circuit is open
     */
    public synchronized boolean canExecute() {
        if (state == CircuitState.CLOSED) {
            return true;
        }
        
        if (state == CircuitState.OPEN) {
            if (lastFailureTime != null && 
                Duration.between(lastFailureTime, Instant.now()).compareTo(RESET_TIMEOUT) >= 0) {
                log.info("Kobo proxy circuit breaker transitioning from OPEN to HALF_OPEN state (timeout expired)");
                state = CircuitState.HALF_OPEN;
                return true;
            }
            log.debug("Kobo proxy circuit breaker is OPEN - skipping proxy request");
            return false;
        }
        
        // HALF_OPEN - allow one request to test if service recovered
        log.debug("Kobo proxy circuit breaker is HALF_OPEN - allowing test request");
        return true;
    }
    
    /**
     * Record successful proxy execution.
     * Transitions from HALF_OPEN to CLOSED on success.
     */
    public synchronized void recordSuccess() {
        int previousFailures = failureCount;
        failureCount = 0;  // Direct assignment is safe in synchronized method
        if (state == CircuitState.HALF_OPEN) {
            log.info("Kobo proxy circuit breaker transitioning from HALF_OPEN to CLOSED state (recovery confirmed)");
            state = CircuitState.CLOSED;
        } else if (previousFailures > 0) {
            log.debug("Kobo proxy circuit breaker reset failure count after success");
        }
    }
    
    /**
     * Record failed proxy execution.
     * Opens circuit after reaching failure threshold.
     */
    public synchronized void recordFailure() {
        int failures = ++failureCount;  // Increment failure count
        lastFailureTime = Instant.now();

        if (failures >= FAILURE_THRESHOLD) {
            log.warn("Kobo proxy circuit breaker transitioning from {} to OPEN state after {} consecutive failures. " +
                    "Will retry after {} minutes.", state, failures, RESET_TIMEOUT.toMinutes());
            state = CircuitState.OPEN;
        } else {
            log.debug("Kobo proxy circuit breaker recorded failure {}/{}", failures, FAILURE_THRESHOLD);
        }
    }

    /**
     * Get current circuit state for monitoring/debugging.
     */
    public CircuitState getState() {
        return state;
    }

    /**
     * Get failure count for monitoring.
     * Note: May be slightly stale under concurrent access (not synchronized).
     */
    public int getFailureCount() {
        return failureCount;  // Non-volatile read - approximate value
    }

    /**
     * Manual reset - use for testing or admin intervention.
     */
    public synchronized void reset() {
        log.info("Kobo proxy circuit breaker manually reset");
        failureCount = 0;
        state = CircuitState.CLOSED;
        lastFailureTime = null;
    }
}
