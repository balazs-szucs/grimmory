package org.booklore.config;

import jakarta.servlet.Filter;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.ServletRequest;
import jakarta.servlet.ServletResponse;
import jakarta.servlet.annotation.WebFilter;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletRequestWrapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

import java.io.IOException;

/**
 * Servlet filter that normalizes invalid HTTP headers from Kobo eReaders.
 * 
 * Problem: Kobo eReaders do not send valid HTTP Host headers, which can break
 * cover downloads and file downloads behind reverse proxies.
 * 
 * From Komga documentation: "The Kobo eReader does not send valid HTTP Host header,
 * which could break the covers and file download in certain circumstances."
 * 
 * This filter:
 * 1. Detects Kobo devices by User-Agent
 * 2. Normalizes the Host header to include the server's actual address
 * 3. Allows X-Forwarded-Host to override the Host header (for reverse proxy setups)
 * 
 * @see <a href="https://komga.org/docs/guides/kobo/">Komga Kobo Integration</a>
 */
@Slf4j
@Component
@Order(Ordered.HIGHEST_PRECEDENCE)
public class KoboHeaderNormalizationFilter implements Filter {

    private static final String KOBO_USER_AGENT_PATTERN = "kobo";
    
    @Override
    public void doFilter(ServletRequest request, ServletResponse response, FilterChain chain)
            throws IOException, ServletException {
        
        if (!(request instanceof HttpServletRequest httpServletRequest)) {
            chain.doFilter(request, response);
            return;
        }
        
        // Only process Kobo API requests
        String path = httpServletRequest.getRequestURI();
        if (!path.startsWith("/api/kobo/")) {
            chain.doFilter(request, response);
            return;
        }
        
        String userAgent = httpServletRequest.getHeader("User-Agent");
        if (userAgent == null || !userAgent.toLowerCase().contains(KOBO_USER_AGENT_PATTERN)) {
            // Not a Kobo device, pass through unchanged
            chain.doFilter(request, response);
            return;
        }
        
        // Kobo device detected - normalize headers
        KoboHeaderWrapper wrappedRequest = new KoboHeaderWrapper(httpServletRequest);
        
        log.debug("Kobo device detected (UA: {}), normalizing headers", userAgent);
        
        chain.doFilter(wrappedRequest, response);
    }
    
    /**
     * Wrapper that normalizes Kobo's invalid Host header.
     */
    private static class KoboHeaderWrapper extends HttpServletRequestWrapper {
        
        private final HttpServletRequest request;
        
        public KoboHeaderWrapper(HttpServletRequest request) {
            super(request);
            this.request = request;
        }
        
        @Override
        public String getHeader(String name) {
            if ("Host".equalsIgnoreCase(name)) {
                // Check if X-Forwarded-Host is present (reverse proxy scenario)
                String forwardedHost = request.getHeader("X-Forwarded-Host");
                if (forwardedHost != null && !forwardedHost.isBlank()) {
                    log.trace("Using X-Forwarded-Host for Kobo request: {}", forwardedHost);
                    return forwardedHost;
                }
                
                // Fall back to server's actual address
                String serverName = request.getServerName();
                int serverPort = request.getServerPort();
                String normalizedHost = serverName + (isNonStandardPort(serverPort) ? ":" + serverPort : "");
                
                log.trace("Normalized Kobo Host header to: {}", normalizedHost);
                return normalizedHost;
            }
            
            return super.getHeader(name);
        }
        
        /**
         * Check if port is non-standard (not 80 for HTTP, not 443 for HTTPS).
         */
        private boolean isNonStandardPort(int port) {
            return port != 80 && port != 443;
        }
    }
}
