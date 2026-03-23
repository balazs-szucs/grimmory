package org.booklore.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.net.http.HttpClient;
import java.time.Duration;
import java.util.concurrent.Executors;

/**
 * Configuration for HTTP clients used in the application.
 * 
 * Java 21+ HttpClient implements AutoCloseable and should be managed as a Spring bean
 * to ensure proper lifecycle management and prevent executor thread leaks.
 * 
 * Configured with:
 * - HTTP/2 support (storeapi.kobo.com supports HTTP/2)
 * - Virtual threads for blocking I/O (Java 25 stable feature)
 * - Normal redirect following (Kobo CDN uses redirects)
 * - 30 second connect timeout
 * 
 * @see <a href="https://bugs.openjdk.org/browse/JDK-8267140">JDK-8267140</a>
 */
@Configuration
public class HttpClientConfig {

    /**
     * HTTP client for Kobo store proxy requests.
     * Configured with appropriate timeouts and features for Kobo sync operations.
     */
    @Bean
    public HttpClient koboHttpClient() {
        return HttpClient.newBuilder()
                .version(HttpClient.Version.HTTP_2)  // HTTP/2 for better performance
                .connectTimeout(Duration.ofSeconds(30))
                .followRedirects(HttpClient.Redirect.NORMAL)  // Required for Kobo CDN redirects
                .executor(Executors.newVirtualThreadPerTaskExecutor())  // Virtual threads for blocking I/O
                .build();
    }
}
