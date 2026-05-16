package org.booklore.config;

import lombok.extern.slf4j.Slf4j;
import org.apache.coyote.http11.AbstractHttp11Protocol;
import org.springframework.boot.tomcat.TomcatConnectorCustomizer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 *
 * <ul>
 *   <li><b>sendfile</b> delegates large file transfers to the OS kernel,
 *       bypassing the JVM heap entirely. Tomcat uses this when
 *       {@code org.apache.tomcat.sendfile.support} is set on the request.</li>
 *   <li><b>Socket buffers</b> 128 KB read/write buffers for the non-sendfile
 *       fallback path ({@link org.booklore.service.FileStreamingService} NIO loop).</li>
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
