package org.booklore.service.kobo;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.RandomStringUtils;
import org.booklore.model.dto.kobo.KoboAuthentication;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Service;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.nio.charset.StandardCharsets;
import java.util.UUID;

@Slf4j
@Service
@RequiredArgsConstructor
public class KoboDeviceAuthService {

    private final ObjectMapper objectMapper;
    private final KoboServerProxy koboServerProxy;

    /**
     * Authenticate a Kobo device. Tries to proxy to the real Kobo store first
     * (mirrors Komga's authDevice pattern), falling back to dummy auth data
     * to keep the device happy.
     */
    public ResponseEntity<KoboAuthentication> authenticateDevice(JsonNode requestBody) {
        if (requestBody == null || requestBody.get("UserKey") == null) {
            throw new IllegalArgumentException("UserKey is required");
        }

        // Try proxy to real Kobo store first (mirrors Komga's pattern)
        try {
            var proxyResponse = koboServerProxy.proxyCurrentRequest(requestBody, false);
            if (proxyResponse != null && proxyResponse.getBody() != null) {
                KoboAuthentication proxyAuth = objectMapper.treeToValue(proxyResponse.getBody(), KoboAuthentication.class);
                if (proxyAuth != null && proxyAuth.getAccessToken() != null) {
                    log.debug("Kobo device auth proxied successfully");
                    return ResponseEntity.ok(proxyAuth);
                }
            }
        } catch (Exception e) {
            log.warn("Failed to proxy auth to Kobo store, using fallback: {}", e.getMessage());
        }

        // Fallback: return dummy data to keep the device happy (like Komga)
        log.debug("Kobo device authentication using local fallback");

        KoboAuthentication auth = new KoboAuthentication();
        auth.setAccessToken(RandomStringUtils.secure().nextAlphanumeric(24));
        auth.setRefreshToken(RandomStringUtils.secure().nextAlphanumeric(24));
        auth.setTrackingId(UUID.randomUUID().toString());
        auth.setUserKey(requestBody.get("UserKey").asText());

        return ResponseEntity.ok()
                .header("Content-Type", "application/json; charset=utf-8")
                .header("Content-Length", String.valueOf(toJsonBytes(auth).length))
                .body(auth);
    }

    public byte[] toJsonBytes(KoboAuthentication KoboAuthentication) {
        try {
            return objectMapper.writeValueAsString(KoboAuthentication).getBytes(StandardCharsets.UTF_8);
        } catch (JacksonException e) {
            log.error("Failed to serialize AuthDto to JSON", e);
            throw new RuntimeException("Failed to serialize AuthDto", e);
        }
    }
}
