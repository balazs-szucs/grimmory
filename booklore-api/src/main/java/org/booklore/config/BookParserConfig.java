package org.booklore.config;

import org.booklore.model.enums.MetadataProvider;
import org.booklore.service.metadata.parser.BookParser;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

@Configuration
public class BookParserConfig {

    @Bean
    public Map<MetadataProvider, BookParser> parserMap(List<BookParser> parsers) {
        return parsers.stream()
                .collect(Collectors.toMap(BookParser::getProvider, Function.identity()));
    }
}
