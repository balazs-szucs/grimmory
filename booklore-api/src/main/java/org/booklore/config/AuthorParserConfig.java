package org.booklore.config;

import org.booklore.model.enums.AuthorMetadataSource;
import org.booklore.service.metadata.parser.AuthorParser;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

@Configuration
public class AuthorParserConfig {

    @Bean
    public Map<AuthorMetadataSource, AuthorParser> authorParserMap(List<AuthorParser> parsers) {
        return parsers.stream()
                .collect(Collectors.toMap(AuthorParser::getSource, Function.identity()));
    }
}
