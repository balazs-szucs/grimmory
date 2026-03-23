package org.booklore.service.kobo;

import jakarta.servlet.http.HttpServletRequest;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.booklore.model.dto.BookloreSyncToken;
import org.booklore.model.dto.kobo.KoboHeaders;
import org.booklore.util.RequestUtils;
import org.booklore.util.kobo.BookloreSyncTokenGenerator;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.core.io.Resource;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.Collections;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * Kobo Store Proxy - Proxies requests to official Kobo store for hybrid sync.
 * 
 * Stability improvements ported from Komga:
 * - ✅ Graceful fallback on proxy failure
 * - ✅ Sync token merging (local + Kobo store)
 * - ✅ Circuit breaker integration (NEW - prevents cascading failures)
 * - ✅ Improved error logging with full details
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class KoboServerProxy {

    private static final Pattern KOBO_API_PREFIX_PATTERN = Pattern.compile("^/api/kobo/[^/]+");
    private final HttpClient koboHttpClient;  // Injected as bean (matches bean name)
    private final ObjectMapper objectMapper;
    private final BookloreSyncTokenGenerator bookloreSyncTokenGenerator;
    private final KoboProxyCircuitBreaker circuitBreaker;  // NEW: Circuit breaker integration

    private static final Set<String> HEADERS_OUT_INCLUDE = Set.of(
            HttpHeaders.AUTHORIZATION.toLowerCase(),
            HttpHeaders.AUTHORIZATION,
            HttpHeaders.USER_AGENT,
            HttpHeaders.ACCEPT,
            HttpHeaders.ACCEPT_LANGUAGE
    );

    private static final Set<String> HEADERS_OUT_EXCLUDE = Set.of(
            KoboHeaders.X_KOBO_SYNCTOKEN
    );

    private boolean isKoboHeader(String headerName) {
        return headerName.toLowerCase().startsWith("x-kobo-");
    }

    public ResponseEntity<JsonNode> proxyCurrentRequest(Object body, boolean includeSyncToken) {
        // Check circuit breaker before attempting proxy (NEW)
        if (!circuitBreaker.canExecute()) {
            log.debug("Kobo proxy circuit breaker is OPEN - skipping proxy request, returning empty response");
            return ResponseEntity.ok().build();
        }

        HttpServletRequest request = RequestUtils.getCurrentRequest();
        String path = KOBO_API_PREFIX_PATTERN.matcher(request.getRequestURI()).replaceFirst("");

        BookloreSyncToken syncToken = null;
        if (includeSyncToken) {
            syncToken = bookloreSyncTokenGenerator.fromRequestHeaders(request);
            if (syncToken == null || syncToken.getRawKoboSyncToken() == null || syncToken.getRawKoboSyncToken().isBlank()) {
                log.debug("Proxy request requires sync token but none found, returning empty response");
                return ResponseEntity.ok().build();
            }
        }

        return executeProxyRequest(request, body, path, includeSyncToken, syncToken);
    }

    public ResponseEntity<Resource> proxyExternalUrl(String url) {
        try {
            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(url))
                    .GET()
                    .build();

            HttpResponse<byte[]> response = httpClient.send(request, HttpResponse.BodyHandlers.ofByteArray());

            HttpHeaders headers = new HttpHeaders();
            headers.setContentType(MediaType.IMAGE_JPEG);

            return new ResponseEntity<>(new ByteArrayResource(response.body()), headers, HttpStatus.valueOf(response.statusCode()));
        } catch (Exception e) {
            log.warn("Failed to proxy external Kobo CDN URL: {}", e.getMessage());
            return ResponseEntity.notFound().build();
        }
    }

    private ResponseEntity<JsonNode> executeProxyRequest(HttpServletRequest request, Object body, String path, boolean includeSyncToken, BookloreSyncToken syncToken) {
        String koboBaseUrl = "https://storeapi.kobo.com";
        
        try {
            String queryString = request.getQueryString();
            String uriString = koboBaseUrl + path;
            if (queryString != null && !queryString.isBlank()) {
                uriString += "?" + queryString;
            }

            URI uri = URI.create(uriString);
            log.debug("Kobo proxy URL: {}", uri);

            String method = request.getMethod();
            HttpRequest.BodyPublisher bodyPublisher;
            if ("GET".equalsIgnoreCase(method) || "DELETE".equalsIgnoreCase(method) || "HEAD".equalsIgnoreCase(method)) {
                bodyPublisher = HttpRequest.BodyPublishers.noBody();
            } else {
                String bodyString = body != null ? objectMapper.writeValueAsString(body) : "{}";
                bodyPublisher = HttpRequest.BodyPublishers.ofString(bodyString);
            }
            HttpRequest.Builder builder = HttpRequest.newBuilder()
                    .uri(uri)
                    .timeout(Duration.ofSeconds(30))
                    .method(method, bodyPublisher)
                    .header(HttpHeaders.CONTENT_TYPE, "application/json");

            Collections.list(request.getHeaderNames()).forEach(headerName -> {
                if (!HEADERS_OUT_EXCLUDE.contains(headerName.toLowerCase()) &&
                        (HEADERS_OUT_INCLUDE.contains(headerName) || isKoboHeader(headerName))) {
                    Collections.list(request.getHeaders(headerName))
                            .forEach(value -> builder.header(headerName, value));
                }
            });

            if (includeSyncToken && syncToken != null && syncToken.getRawKoboSyncToken() != null && !syncToken.getRawKoboSyncToken().isBlank()) {
                builder.header(KoboHeaders.X_KOBO_SYNCTOKEN, syncToken.getRawKoboSyncToken());
            }

            HttpRequest httpRequest = builder.build();
            HttpResponse<String> response = koboHttpClient.send(httpRequest, HttpResponse.BodyHandlers.ofString());

            JsonNode responseBody = response.body() != null && !response.body().isBlank()
                    ? objectMapper.readTree(response.body())
                    : null;

            HttpHeaders responseHeaders = new HttpHeaders();
            response.headers().map().forEach((key, values) -> {
                if (isKoboHeader(key)) {
                    responseHeaders.addAll(key, values);
                }
            });

            if (responseHeaders.getFirst(KoboHeaders.X_KOBO_SYNCTOKEN) != null && includeSyncToken && syncToken != null) {
                String koboToken = responseHeaders.getFirst(KoboHeaders.X_KOBO_SYNCTOKEN);
                if (koboToken != null) {
                    BookloreSyncToken updated = BookloreSyncToken.builder()
                            .ongoingSyncPointId(syncToken.getOngoingSyncPointId())
                            .lastSuccessfulSyncPointId(syncToken.getLastSuccessfulSyncPointId())
                            .rawKoboSyncToken(koboToken)
                            .build();
                    responseHeaders.set(KoboHeaders.X_KOBO_SYNCTOKEN, bookloreSyncTokenGenerator.toBase64(updated));
                }
            }

            log.debug("Kobo proxy response status: {}", response.statusCode());

            // Record success with circuit breaker (NEW)
            if (response.statusCode() >= 500) {
                circuitBreaker.recordFailure();
                log.warn("Kobo proxy returned server error ({}), recording failure for circuit breaker", response.statusCode());
            } else {
                circuitBreaker.recordSuccess();
            }

            return new ResponseEntity<>(responseBody, responseHeaders, HttpStatus.valueOf(response.statusCode()));

        } catch (InterruptedException e) {
            // NEW: Improved error logging with full details
            Thread.currentThread().interrupt();
            circuitBreaker.recordFailure();
            log.warn("Kobo proxy request interrupted (method: {}, path: {})", request.getMethod(), path, e);
            return ResponseEntity.ok().build();
        } catch (IOException e) {
            // NEW: Improved error logging with full details
            circuitBreaker.recordFailure();
            log.warn("Kobo proxy network error (method: {}, path: {}, uri: {})", 
                    request.getMethod(), path, koboBaseUrl + path, e);
            return ResponseEntity.ok().build();
        } catch (Exception e) {
            // NEW: Improved error logging with full details
            circuitBreaker.recordFailure();
            log.warn("Kobo proxy unexpected error (method: {}, path: {})", 
                    request.getMethod(), path, e);
            return ResponseEntity.ok().build();
        }
    }
}
