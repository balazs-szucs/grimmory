package org.booklore.config.monitoring;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.UUID;

@Component
public class QueryMonitoringFilter extends OncePerRequestFilter {

    private static final Logger log = LoggerFactory.getLogger(QueryMonitoringFilter.class);

    @Override
    protected void doFilterInternal(HttpServletRequest request,
                                    HttpServletResponse response,
                                    FilterChain filterChain) throws ServletException, IOException {

        String requestURI = request.getRequestURI();
        // Ignore static assets to prevent log bloat
        if (requestURI.startsWith("/static/") || requestURI.startsWith("/assets/") || 
            requestURI.endsWith(".js") || requestURI.endsWith(".css") || 
            requestURI.endsWith(".png") || requestURI.endsWith(".ico") || 
            requestURI.endsWith(".svg")) {
            filterChain.doFilter(request, response);
            return;
        }

        String requestId = UUID.randomUUID().toString().substring(0, 8); // Concise UUID for readability
        MDC.put("requestId", requestId);
        MDC.put("method", request.getMethod());
        MDC.put("uri", requestURI);

        long start = System.currentTimeMillis();
        QueryCountInterceptor.startCounting();

        try {
            filterChain.doFilter(request, response);
        } finally {
            long duration = System.currentTimeMillis() - start;

            log.info("REQUEST_COMPLETE | requestId={} | method={} | uri={} | status={} | durationMs={}",
                    requestId,
                    request.getMethod(),
                    requestURI,
                    response.getStatus(),
                    duration);

            log.info("QUERY_COUNT | requestId={} | uri={} | queries={}",
                    requestId, requestURI, QueryCountInterceptor.getQueryCount());

            QueryCountInterceptor.clear();
            MDC.clear();
        }
    }
}
