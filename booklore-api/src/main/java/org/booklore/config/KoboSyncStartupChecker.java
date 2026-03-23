package org.booklore.config;

import jakarta.annotation.PostConstruct;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.net.InetAddress;

/**
 * Startup checks and warnings for Kobo sync configuration.
 *
 * Provides:
 * 1. DNS resolution check for storeapi.kobo.com
 * 2. HTTPS enforcement warning
 * 3. Configuration validation
 */
@Slf4j
@Component
@ConditionalOnProperty(name = "booklore.kobo.sync-enabled", havingValue = "true", matchIfMissing = true)
public class KoboSyncStartupChecker {

    private static final String KOBO_STORE_HOST = "storeapi.kobo.com";

    @Value("${server.ssl.enabled:false}")
    private boolean sslEnabled;

    @Value("${server.port:8080}")
    private int serverPort;

    @PostConstruct
    public void performStartupChecks() {
        checkDnsResolution();
        checkHttpsConfiguration();
    }

    /**
     * Check if storeapi.kobo.com can be resolved.
     * DNS failures are a common cause of Kobo sync issues in Docker deployments.
     */
    private void checkDnsResolution() {
        try {
            InetAddress[] addresses = InetAddress.getAllByName(KOBO_STORE_HOST);
            log.info("Kobo sync DNS check: {} resolves to {}", KOBO_STORE_HOST,
                    addresses[0].getHostAddress());
        } catch (Exception e) {
            log.warn("⚠️  DNS RESOLUTION FAILED: Cannot resolve '{}'. " +
                    "Kobo proxy features will not work. " +
                    "If running in Docker, add DNS configuration: dns: [8.8.8.8, 8.8.4.4]",
                    KOBO_STORE_HOST, e);
        }
    }

    /**
     * Warn if Kobo sync is enabled but HTTPS is not configured.
     * Kobo sync transmits sensitive information (auth tokens, reading progress).
     */
    private void checkHttpsConfiguration() {
        if (!sslEnabled) {
            log.warn("⚠️  SECURITY WARNING: Kobo sync is enabled but HTTPS is not configured. " +
                    "Kobo eReaders transmit sensitive information during sync. " +
                    "It is highly recommended to enable HTTPS. " +
                    "See: https://komga.org/docs/guides/kobo/");
        } else {
            log.info("Kobo sync HTTPS check: SSL/TLS is enabled ✓");
        }
    }
}
