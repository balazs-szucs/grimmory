package org.booklore.interceptor;

import org.booklore.context.KomgaCleanContext;
import jakarta.servlet.*;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.stereotype.Component;
import java.io.IOException;

/**
 * Filter to handle the "clean" query parameter for Komga API endpoints.
 * When the "clean" parameter is present (with or without a value) or set to "true",
 * it enables clean mode which filters out "Lock" fields, null values, and empty arrays
 * from the JSON response.
 *
 * Uses ScopedValue for thread-safe, lexical context management.
 */
@Component
public class KomgaCleanFilter implements Filter {

    @Override
    public void doFilter(ServletRequest request, ServletResponse response, FilterChain chain) throws IOException, ServletException {
        if (request instanceof HttpServletRequest httpRequest) {
            String requestURI = httpRequest.getRequestURI();

            if (requestURI != null && requestURI.startsWith("/komga/api")) {
                String cleanParam = httpRequest.getParameter("clean");
                boolean cleanMode = cleanParam != null && (cleanParam.isEmpty() || "true".equalsIgnoreCase(cleanParam));

                ScopedValue.where(KomgaCleanContext.CLEAN_MODE, cleanMode)
                           .run(() -> {
                               try {
                                   chain.doFilter(request, response);
                               } catch (IOException | ServletException e) {
                                   throw new RuntimeException(e);
                               }
                           });
                return;
            }
        }
        chain.doFilter(request, response);
    }
}
