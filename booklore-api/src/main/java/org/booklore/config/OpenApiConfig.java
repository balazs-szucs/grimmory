package org.booklore.config;

import io.swagger.v3.oas.models.Components;
import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.info.Contact;
import io.swagger.v3.oas.models.info.Info;
import io.swagger.v3.oas.models.info.License;
import io.swagger.v3.oas.models.security.SecurityRequirement;
import io.swagger.v3.oas.models.security.SecurityScheme;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
@ConditionalOnProperty(name = "app.swagger.enabled", havingValue = "true", matchIfMissing = false)
public class OpenApiConfig {

    @Bean
    public OpenAPI bookLoreOpenAPI() {
        return new OpenAPI()
                .info(new Info()
                        .title("Grimmory API")
                        .description("Self-hosted book library manager with metadata enrichment, OPDS feeds, Kobo sync, and more.")
                        .version("v1")
                        .contact(new Contact()
                                .name("Grimmory Team")
                                .url("https://github.com/grimmory-tools/grimmory")
                                .email("coding_contact@pm.me"))
                        .license(new License()
                                .name("GNU AGPLv3")
                                .url("https://github.com/grimmory-tools/grimmory/blob/main/LICENSE")))
                .addSecurityItem(new SecurityRequirement().addList("Bearer Authentication"))
                .components(new Components()
                        .addSecuritySchemes("Bearer Authentication",
                                new SecurityScheme()
                                        .type(SecurityScheme.Type.HTTP)
                                        .scheme("bearer")
                                        .bearerFormat("JWT")
                                        .description("JWT token obtained from /api/v1/auth/login")));
    }
}
