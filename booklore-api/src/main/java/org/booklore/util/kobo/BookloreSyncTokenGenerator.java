package org.booklore.util.kobo;


import jakarta.servlet.http.HttpServletRequest;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.booklore.model.dto.BookloreSyncToken;
import org.booklore.model.dto.kobo.KoboHeaders;
import org.springframework.stereotype.Component;
import tools.jackson.databind.ObjectMapper;

import java.util.Base64;

@Slf4j
@RequiredArgsConstructor
@Component
public class BookloreSyncTokenGenerator {

    private static final String BOOKLORE_TOKEN_PREFIX = "BOOKLORE.";

    private final ObjectMapper objectMapper;
    private final Base64.Encoder base64Encoder = Base64.getEncoder().withoutPadding();
    private final Base64.Decoder base64Decoder = Base64.getDecoder();

    public BookloreSyncToken fromBase64(String base64Token) {
        try {
            // Check for a BookLore token
            if (base64Token.startsWith(BOOKLORE_TOKEN_PREFIX)) {
                byte[] decoded = base64Decoder.decode(base64Token.substring(BOOKLORE_TOKEN_PREFIX.length()));
                return objectMapper.readValue(decoded, BookloreSyncToken.class);
            }

            // Check for a Calibre-Web token (single base64 string without dots)
            if (!base64Token.contains(".")) {
                try {
                    var json = objectMapper.readTree(base64Decoder.decode(base64Token));
                    var dataNode = json.get("data");
                    if (dataNode != null) {
                        var rawTokenNode = dataNode.get("raw_kobo_store_token");
                        if (rawTokenNode != null) {
                            return BookloreSyncToken.builder()
                                    .rawKoboSyncToken(rawTokenNode.asText())
                                    .build();
                        }
                    }
                } catch (Exception e) {
                    log.warn("Failed to parse potential Calibre-Web token");
                }
            }

            // Check for a Kobo store token (format: base64.base64)
            if (base64Token.contains(".")) {
                return BookloreSyncToken.builder()
                        .rawKoboSyncToken(base64Token)
                        .build();
            }
        } catch (Exception ignored) {
        }
        return new BookloreSyncToken();
    }

    public String toBase64(BookloreSyncToken token) {
        try {
            String json = objectMapper.writeValueAsString(token);
            return BOOKLORE_TOKEN_PREFIX + base64Encoder.encodeToString(json.getBytes());
        } catch (Exception e) {
            log.error("Failed to serialize Booklore sync token", e);
            return BOOKLORE_TOKEN_PREFIX;
        }
    }

    public BookloreSyncToken fromRequestHeaders(HttpServletRequest request) {
        if (request == null) return null;
        String tokenB64 = request.getHeader(KoboHeaders.X_KOBO_SYNCTOKEN);
        return tokenB64 != null ? fromBase64(tokenB64) : null;
    }
}
