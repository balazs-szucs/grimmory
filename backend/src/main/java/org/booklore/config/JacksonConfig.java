package org.booklore.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.booklore.util.json.ObjectMapper;

/**
 * Jackson configuration for Komga API clean mode.
 */
@Configuration
public class JacksonConfig {

    public static final String KOMGA_CLEAN_OBJECT_MAPPER = "komgaCleanObjectMapper";

    @Bean(name = KOMGA_CLEAN_OBJECT_MAPPER)
    public ObjectMapper komgaCleanObjectMapper() {
        return new ObjectMapper();
    }
}
