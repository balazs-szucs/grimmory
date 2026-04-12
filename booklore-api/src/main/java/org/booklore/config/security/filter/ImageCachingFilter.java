package org.booklore.config.security.filter;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.stereotype.Component;
import org.springframework.http.HttpHeaders;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;

@Component
public class ImageCachingFilter extends OncePerRequestFilter {

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain filterChain) throws ServletException, IOException {
        filterChain.doFilter(request, response);
        String uri = request.getRequestURI();
        if (uri.startsWith("/api/v1/media/book/") &&
            (uri.contains("/cover") || uri.contains("/thumbnail") || uri.contains("/backup-cover") ||
             uri.contains("/cbx/pages/"))) {
            if (!response.containsHeader(HttpHeaders.CACHE_CONTROL)) {
                response.setHeader(HttpHeaders.CACHE_CONTROL, "private, max-age=3600");
            }
        }
    }
}
