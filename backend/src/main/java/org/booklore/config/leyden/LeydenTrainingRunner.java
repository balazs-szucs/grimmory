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
import java.util.Map;

@Slf4j
@Component
@RequiredArgsConstructor
@ConditionalOnProperty(name = "app.leyden-training.enabled", havingValue = "true")
public class LeydenTrainingRunner {

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

            LeydenTrainingScenario scenario = new LeydenTrainingScenario(objectMapper);
            scenario.execute(new JdkRequestClient(client), baseUri);

            log.info("Leyden training workload completed successfully");
            exitCode = 0;
        } catch (Exception exception) {
            log.error("Leyden training workload failed", exception);
        }

        int finalExitCode = exitCode;
        SpringApplication.exit(applicationContext, () -> finalExitCode);
        System.exit(finalExitCode);
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

    private String sendJson(HttpClient client, URI uri, String method, Object payload, String accessToken) throws IOException, InterruptedException {
        HttpRequest.Builder requestBuilder = HttpRequest.newBuilder(uri)
                .timeout(Duration.ofSeconds(15))
                .header("Content-Type", MediaType.APPLICATION_JSON_VALUE)
                .method(method, HttpRequest.BodyPublishers.ofString(objectMapper.writeValueAsString(payload)));

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

    private final class JdkRequestClient implements LeydenTrainingScenario.RequestClient {
        private final HttpClient client;

        private JdkRequestClient(HttpClient client) {
            this.client = client;
        }

        @Override
        public String get(URI uri, String accessToken) throws IOException, InterruptedException {
            return sendGet(client, uri, accessToken);
        }

        @Override
        public String postJson(URI uri, Object payload, String accessToken) throws IOException, InterruptedException {
            return sendJson(client, uri, "POST", payload, accessToken);
        }

        @Override
        public String putJson(URI uri, Object payload, String accessToken) throws IOException, InterruptedException {
            return sendJson(client, uri, "PUT", payload, accessToken);
        }
    }
}