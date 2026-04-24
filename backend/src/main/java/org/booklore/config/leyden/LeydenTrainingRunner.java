package org.booklore.config.leyden;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.ApplicationContext;
import org.springframework.context.event.EventListener;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.List;
import java.util.Map;

@Slf4j
@Component
@RequiredArgsConstructor
@ConditionalOnProperty(name = "app.leyden-training.enabled", havingValue = "true")
public class LeydenTrainingRunner {

    private static final String TRAINING_USERNAME = "leyden-admin";
    private static final String TRAINING_PASSWORD = "leyden-pass-123";
    private static final String TRAINING_EMAIL = "leyden@example.invalid";
    private static final String TRAINING_NAME = "Leyden Trainer";

    private final ApplicationContext applicationContext;
    private final ObjectMapper objectMapper;

    @Value("${server.port}")
    private int serverPort;

    @EventListener(ApplicationReadyEvent.class)
    public void onApplicationReady() {
        Thread.ofVirtual().name("leyden-training-runner").start(this::runTraining);
    }

    private void runTraining() {
        int exitCode = 1;

        try {
            HttpClient client = HttpClient.newBuilder()
                    .connectTimeout(Duration.ofSeconds(5))
                    .build();
            URI baseUri = URI.create("http://127.0.0.1:" + serverPort);

            sendGet(client, baseUri.resolve("/api/v1/healthcheck"), null);
            sendGet(client, baseUri.resolve("/api/v1/public-settings"), null);
            sendGet(client, baseUri.resolve("/api/v1/setup/status"), null);
            sendJson(client, baseUri.resolve("/api/v1/setup"), Map.of(
                    "username", TRAINING_USERNAME,
                    "email", TRAINING_EMAIL,
                    "name", TRAINING_NAME,
                    "password", TRAINING_PASSWORD
            ), null);

            String loginResponse = sendJson(client, baseUri.resolve("/api/v1/auth/login"), Map.of(
                    "username", TRAINING_USERNAME,
                    "password", TRAINING_PASSWORD
            ), null);

            Map<String, String> tokens = objectMapper.readValue(loginResponse, new TypeReference<>() {});
            String accessToken = tokens.get("accessToken");
            if (accessToken == null || accessToken.isBlank()) {
                throw new IllegalStateException("Leyden training login did not return an access token");
            }

            sendGet(client, baseUri.resolve("/api/v1/users/me"), accessToken);
            sendGet(client, baseUri.resolve("/api/v1/libraries/health"), accessToken);
            warmAppHotPaths(client, baseUri, accessToken);

            log.info("Leyden training workload completed successfully");
            exitCode = 0;
        } catch (Exception exception) {
            log.error("Leyden training workload failed", exception);
        }

        int finalExitCode = exitCode;
        SpringApplication.exit(applicationContext, () -> finalExitCode);
        System.exit(finalExitCode);
    }

    private void warmAppHotPaths(HttpClient client, URI baseUri, String accessToken) throws IOException, InterruptedException {
        String librariesResponse = sendGet(client, baseUri.resolve("/api/v1/app/libraries"), accessToken);
        sendGet(client, baseUri.resolve("/api/v1/app/filter-options"), accessToken);
        sendGet(client, baseUri.resolve("/api/v1/app/books?page=0&size=50&sort=addedOn&dir=desc"), accessToken);
        sendGet(client, baseUri.resolve("/api/v1/app/books?page=1&size=50&sort=addedOn&dir=desc"), accessToken);
        sendGet(client, baseUri.resolve("/api/v1/app/books?search=leyden&page=0&size=50&sort=addedOn&dir=desc"), accessToken);
        sendGet(client, baseUri.resolve("/api/v1/app/books?unshelved=true&page=0&size=50&sort=addedOn&dir=desc"), accessToken);

        String bookIdsResponse = sendGet(client, baseUri.resolve("/api/v1/app/books/ids"), accessToken);
        sendGet(client, baseUri.resolve("/api/v1/app/books/search?q=leyden&page=0&size=20"), accessToken);
        sendGet(client, baseUri.resolve("/api/v1/app/books/random?page=0&size=20"), accessToken);
        sendGet(client, baseUri.resolve("/api/v1/app/books/continue-reading?limit=10"), accessToken);
        sendGet(client, baseUri.resolve("/api/v1/app/books/continue-listening?limit=10"), accessToken);
        sendGet(client, baseUri.resolve("/api/v1/app/books/recently-added?limit=10"), accessToken);
        sendGet(client, baseUri.resolve("/api/v1/app/books/recently-scanned?limit=10"), accessToken);
        sendGet(client, baseUri.resolve("/api/v1/app/authors?page=0&size=30&sort=name&dir=asc"), accessToken);
        sendGet(client, baseUri.resolve("/api/v1/app/series?page=0&size=20&sort=recentlyAdded&dir=desc"), accessToken);
        sendGet(client, baseUri.resolve("/api/v1/app/notebook/books?page=0&size=20"), accessToken);

        sendGet(client, baseUri.resolve("/api/v1/app/shelves"), accessToken);
        String magicShelfListResponse = sendGet(client, baseUri.resolve("/api/v1/app/shelves/magic"), accessToken);

        Long firstLibraryId = extractFirstId(librariesResponse);
        if (firstLibraryId != null) {
            sendGet(client, baseUri.resolve("/api/v1/app/filter-options?libraryId=" + firstLibraryId), accessToken);
            sendGet(client, baseUri.resolve("/api/v1/app/books?page=0&size=50&sort=addedOn&dir=desc&libraryId=" + firstLibraryId), accessToken);
            sendGet(client, baseUri.resolve("/api/v1/app/authors?page=0&size=30&sort=name&dir=asc&libraryId=" + firstLibraryId), accessToken);
            sendGet(client, baseUri.resolve("/api/v1/app/series?page=0&size=20&sort=recentlyAdded&dir=desc&libraryId=" + firstLibraryId), accessToken);
            sendGet(client, baseUri.resolve("/api/v1/app/books/random?page=0&size=20&libraryId=" + firstLibraryId), accessToken);
        }

        Long firstMagicShelfId = extractFirstId(magicShelfListResponse);
        if (firstMagicShelfId != null) {
            sendGet(client, baseUri.resolve("/api/v1/app/shelves/magic/" + firstMagicShelfId + "/books?page=0&size=20"), accessToken);
        }

        List<Long> bookIds = objectMapper.readValue(bookIdsResponse, new TypeReference<>() {});
        if (!bookIds.isEmpty()) {
            Long firstBookId = bookIds.get(0);
            sendGet(client, baseUri.resolve("/api/v1/app/books/" + firstBookId), accessToken);
            sendGet(client, baseUri.resolve("/api/v1/app/books/" + firstBookId + "/progress"), accessToken);
            sendGet(client, baseUri.resolve("/api/v1/app/notebook/books/" + firstBookId + "/entries?page=0&size=20"), accessToken);
        }
    }

    private Long extractFirstId(String responseBody) throws IOException {
        List<Map<String, Object>> items = objectMapper.readValue(responseBody, new TypeReference<>() {});
        if (items.isEmpty()) {
            return null;
        }

        Object id = items.get(0).get("id");
        if (id instanceof Number number) {
            return number.longValue();
        }

        return null;
    }

    private String sendGet(HttpClient client, URI uri, String accessToken) throws IOException, InterruptedException {
        HttpRequest.Builder requestBuilder = HttpRequest.newBuilder(uri)
                .timeout(Duration.ofSeconds(15))
                .GET();

        if (accessToken != null) {
            requestBuilder.header("Authorization", "Bearer " + accessToken);
        }

        return send(client, requestBuilder.build());
    }

    private String sendJson(HttpClient client, URI uri, Map<String, String> payload, String accessToken) throws IOException, InterruptedException {
        HttpRequest.Builder requestBuilder = HttpRequest.newBuilder(uri)
                .timeout(Duration.ofSeconds(15))
                .header("Content-Type", MediaType.APPLICATION_JSON_VALUE)
                .POST(HttpRequest.BodyPublishers.ofString(objectMapper.writeValueAsString(payload)));

        if (accessToken != null) {
            requestBuilder.header("Authorization", "Bearer " + accessToken);
        }

        return send(client, requestBuilder.build());
    }

    private String send(HttpClient client, HttpRequest request) throws IOException, InterruptedException {
        HttpResponse<String> response = client.send(request, HttpResponse.BodyHandlers.ofString());
        if (response.statusCode() < 200 || response.statusCode() >= 300) {
            throw new IllegalStateException("Leyden training request failed for " + request.uri() + " with status " + response.statusCode());
        }
        return response.body();
    }
}