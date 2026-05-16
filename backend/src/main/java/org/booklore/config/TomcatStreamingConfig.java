package org.booklore.config;

import lombok.extern.slf4j.Slf4j;
import org.apache.coyote.http11.AbstractHttp11Protocol;
import org.springframework.boot.tomcat.TomcatConnectorCustomizer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Enables Tomcat's kernel-level sendfile for large file transfers.
 *
 * <ul>
 *   <li><b>sendfile</b> delegates large transfers to the OS kernel,
 *       bypassing the JVM heap. Activated via
 *       {@code org.apache.tomcat.sendfile.*} request attributes set by
 *       {@link org.booklore.service.FileStreamingService}.</li>
 *   <li><b>DNS lookups disabled</b> eliminates reverse-DNS overhead per request.</li>
 * </ul>
 */
@Slf4j
@Configuration
public class TomcatStreamingConfig {

    @Bean
    TomcatConnectorCustomizer streamingTomcatCustomizer() {
        return connector -> {
            connector.setEnableLookups(false);

            if (connector.getProtocolHandler() instanceof AbstractHttp11Protocol<?> protocol) {
                protocol.setUseSendfile(true);

                log.info("Tomcat sendfile enabled: {}", protocol.getUseSendfile());
            }
        };
    }
}
