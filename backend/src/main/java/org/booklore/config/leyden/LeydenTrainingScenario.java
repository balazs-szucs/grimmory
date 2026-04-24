package org.booklore.config.leyden;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.net.URI;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

final class LeydenTrainingScenario {

    private static final String TRAINING_USERNAME = "leyden-admin";
    private static final String TRAINING_PASSWORD = "leyden-pass-123";
    private static final String TRAINING_EMAIL = "leyden@example.invalid";
    private static final String TRAINING_NAME = "Leyden Trainer";
    private static final int DEFAULT_DASHBOARD_MAX_ITEMS = 20;

    private final ObjectMapper objectMapper;

    LeydenTrainingScenario(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    void execute(RequestClient client, URI baseUri) throws IOException, InterruptedException {
        warmPublicEntry(client, baseUri);

        String accessToken = authenticate(client, baseUri);
        JsonNode currentUser = parseJson(client.get(baseUri.resolve("/api/v1/users/me"), accessToken));
        Long currentUserId = extractLong(currentUser, "id");

        client.get(baseUri.resolve("/api/v1/libraries/health"), accessToken);
        warmMainUiHotPaths(client, baseUri, accessToken, currentUser, currentUserId);
        warmAppHotPaths(client, baseUri, accessToken);
    }

    private void warmPublicEntry(RequestClient client, URI baseUri) throws IOException, InterruptedException {
        client.get(baseUri.resolve("/api/v1/healthcheck"), null);
        client.get(baseUri.resolve("/api/v1/public-settings"), null);
        client.get(baseUri.resolve("/api/v1/setup/status"), null);
        client.postJson(baseUri.resolve("/api/v1/setup"), Map.of(
                "username", TRAINING_USERNAME,
                "email", TRAINING_EMAIL,
                "name", TRAINING_NAME,
                "password", TRAINING_PASSWORD
        ), null);
    }

    private String authenticate(RequestClient client, URI baseUri) throws IOException, InterruptedException {
        JsonNode tokens = parseJson(client.postJson(baseUri.resolve("/api/v1/auth/login"), Map.of(
                "username", TRAINING_USERNAME,
                "password", TRAINING_PASSWORD
        ), null));

        String accessToken = tokens.path("accessToken").asText();
        if (accessToken == null || accessToken.isBlank()) {
            throw new IllegalStateException("Leyden training login did not return an access token");
        }

        return accessToken;
    }

    private void warmMainUiHotPaths(RequestClient client, URI baseUri, String accessToken, JsonNode currentUser, Long currentUserId) throws IOException, InterruptedException {
        client.get(baseUri.resolve("/api/v1/settings"), accessToken);

        String librariesResponse = client.get(baseUri.resolve("/api/v1/libraries"), accessToken);
        String booksResponse = client.get(baseUri.resolve("/api/v1/books?stripForListView=false"), accessToken);
        client.get(baseUri.resolve("/api/v1/books/page?page=0&size=50&sort=metadata.title,asc"), accessToken);
        client.get(baseUri.resolve("/api/v1/books/page?page=0&size=50&sort=addedOn,desc"), accessToken);
        client.get(baseUri.resolve("/api/v1/books/page?page=1&size=50&sort=metadata.title,asc"), accessToken);

        String magicShelvesResponse = client.get(baseUri.resolve("/api/magic-shelves"), accessToken);

        if (currentUserId != null) {
            client.putJson(baseUri.resolve("/api/v1/users/" + currentUserId + "/settings"), Map.of(
                    "key", "dashboardConfig",
                    "value", dashboardConfigValue(currentUser)
            ), accessToken);
        }

        Long firstLibraryId = extractFirstId(librariesResponse);
        if (firstLibraryId != null) {
            client.get(baseUri.resolve("/api/v1/libraries/" + firstLibraryId + "/format-counts"), accessToken);
        }

        Long firstMagicShelfId = extractFirstId(magicShelvesResponse);
        if (firstMagicShelfId != null) {
            client.get(baseUri.resolve("/api/magic-shelves/" + firstMagicShelfId), accessToken);
        }

        Long firstBookId = extractFirstId(booksResponse);
        Long firstBookFileId = extractFirstLong(booksResponse, "primaryFile", "id");
        if (firstBookId != null) {
            client.get(baseUri.resolve("/api/v1/books/" + firstBookId + "?withDescription=true"), accessToken);
            client.get(baseUri.resolve("/api/v1/books/" + firstBookId + "/recommendations?limit=20"), accessToken);
        }
        if (firstBookId != null && firstBookFileId != null) {
            client.get(baseUri.resolve("/api/v1/books/" + firstBookId + "/viewer-setting?bookFileId=" + firstBookFileId), accessToken);
        }
    }

    private void warmAppHotPaths(RequestClient client, URI baseUri, String accessToken) throws IOException, InterruptedException {
        String librariesResponse = client.get(baseUri.resolve("/api/v1/app/libraries"), accessToken);
        client.get(baseUri.resolve("/api/v1/app/filter-options"), accessToken);
        client.get(baseUri.resolve("/api/v1/app/books?page=0&size=50&sort=addedOn&dir=desc"), accessToken);
        client.get(baseUri.resolve("/api/v1/app/books?page=1&size=50&sort=addedOn&dir=desc"), accessToken);
        client.get(baseUri.resolve("/api/v1/app/books?page=0&size=50&sort=title&dir=asc"), accessToken);
        client.get(baseUri.resolve("/api/v1/app/books?status=READING&fileType=EPUB&filterMode=and&page=0&size=50&sort=addedOn&dir=desc"), accessToken);
        client.get(baseUri.resolve("/api/v1/app/books?search=leyden&page=0&size=50&sort=addedOn&dir=desc"), accessToken);
        client.get(baseUri.resolve("/api/v1/app/books?unshelved=true&page=0&size=50&sort=addedOn&dir=desc"), accessToken);

        String bookIdsResponse = client.get(baseUri.resolve("/api/v1/app/books/ids"), accessToken);
        client.get(baseUri.resolve("/api/v1/app/books/search?q=leyden&page=0&size=20"), accessToken);
        client.get(baseUri.resolve("/api/v1/app/books/random?page=0&size=20"), accessToken);
        client.get(baseUri.resolve("/api/v1/app/books/continue-reading?limit=10"), accessToken);
        client.get(baseUri.resolve("/api/v1/app/books/continue-listening?limit=10"), accessToken);
        client.get(baseUri.resolve("/api/v1/app/books/recently-added?limit=10"), accessToken);
        client.get(baseUri.resolve("/api/v1/app/books/recently-scanned?limit=10"), accessToken);
        client.get(baseUri.resolve("/api/v1/app/authors?page=0&size=30&sort=name&dir=asc"), accessToken);
        client.get(baseUri.resolve("/api/v1/app/series?page=0&size=20&sort=recentlyAdded&dir=desc"), accessToken);
        client.get(baseUri.resolve("/api/v1/app/notebook/books?page=0&size=20"), accessToken);
        client.get(baseUri.resolve("/api/v1/app/shelves"), accessToken);

        String magicShelfListResponse = client.get(baseUri.resolve("/api/v1/app/shelves/magic"), accessToken);

        Long firstLibraryId = extractFirstId(librariesResponse);
        if (firstLibraryId != null) {
            client.get(baseUri.resolve("/api/v1/app/filter-options?libraryId=" + firstLibraryId), accessToken);
            client.get(baseUri.resolve("/api/v1/app/books?page=0&size=50&sort=addedOn&dir=desc&libraryId=" + firstLibraryId), accessToken);
            client.get(baseUri.resolve("/api/v1/app/books?page=0&size=50&sort=title&dir=asc&libraryId=" + firstLibraryId), accessToken);
            client.get(baseUri.resolve("/api/v1/app/authors?page=0&size=30&sort=name&dir=asc&libraryId=" + firstLibraryId), accessToken);
            client.get(baseUri.resolve("/api/v1/app/series?page=0&size=20&sort=recentlyAdded&dir=desc&libraryId=" + firstLibraryId), accessToken);
            client.get(baseUri.resolve("/api/v1/app/books/random?page=0&size=20&libraryId=" + firstLibraryId), accessToken);
        }

        Long firstMagicShelfId = extractFirstId(magicShelfListResponse);
        if (firstMagicShelfId != null) {
            client.get(baseUri.resolve("/api/v1/app/filter-options?magicShelfId=" + firstMagicShelfId), accessToken);
            client.get(baseUri.resolve("/api/v1/app/books?page=0&size=50&sort=title&dir=asc&magicShelfId=" + firstMagicShelfId), accessToken);
            client.get(baseUri.resolve("/api/v1/app/shelves/magic/" + firstMagicShelfId + "/books?page=0&size=20"), accessToken);
        }

        List<Long> bookIds = extractLongList(bookIdsResponse);
        if (!bookIds.isEmpty()) {
            Long firstBookId = bookIds.getFirst();
            client.get(baseUri.resolve("/api/v1/app/books/" + firstBookId), accessToken);
            client.get(baseUri.resolve("/api/v1/app/books/" + firstBookId + "/progress"), accessToken);
            client.get(baseUri.resolve("/api/v1/app/notebook/books/" + firstBookId + "/entries?page=0&size=20"), accessToken);
        }
    }

    private Object dashboardConfigValue(JsonNode currentUser) {
        JsonNode dashboardConfig = currentUser.path("userSettings").path("dashboardConfig");
        if (!dashboardConfig.isMissingNode() && !dashboardConfig.isNull()) {
            return dashboardConfig;
        }

        return Map.of("scrollers", List.of(
                Map.of(
                        "id", "1",
                        "type", "lastListened",
                        "title", "dashboard.scroller.continueListening",
                        "enabled", true,
                        "order", 1,
                        "maxItems", DEFAULT_DASHBOARD_MAX_ITEMS
                ),
                Map.of(
                        "id", "2",
                        "type", "lastRead",
                        "title", "dashboard.scroller.continueReading",
                        "enabled", true,
                        "order", 2,
                        "maxItems", DEFAULT_DASHBOARD_MAX_ITEMS
                ),
                Map.of(
                        "id", "3",
                        "type", "latestAdded",
                        "title", "dashboard.scroller.recentlyAdded",
                        "enabled", true,
                        "order", 3,
                        "maxItems", DEFAULT_DASHBOARD_MAX_ITEMS
                ),
                Map.of(
                        "id", "4",
                        "type", "random",
                        "title", "dashboard.scroller.discoverNew",
                        "enabled", true,
                        "order", 4,
                        "maxItems", DEFAULT_DASHBOARD_MAX_ITEMS
                )
        ));
    }

    private Long extractFirstId(String responseBody) throws IOException {
        return extractFirstLong(responseBody, "id");
    }

    private Long extractFirstLong(String responseBody, String... fieldPath) throws IOException {
        JsonNode root = parseJson(responseBody);
        if (!root.isArray() || root.isEmpty()) {
            return null;
        }

        return extractLong(root.get(0), fieldPath);
    }

    private List<Long> extractLongList(String responseBody) throws IOException {
        JsonNode root = parseJson(responseBody);
        if (!root.isArray()) {
            return List.of();
        }

        List<Long> values = new ArrayList<>();
        for (JsonNode node : root) {
            if (node != null && node.isNumber()) {
                values.add(node.longValue());
            }
        }
        return values;
    }

    private Long extractLong(JsonNode node, String... fieldPath) {
        JsonNode current = node;
        for (String field : fieldPath) {
            if (current == null || current.isMissingNode() || current.isNull()) {
                return null;
            }
            current = current.path(field);
        }

        if (current == null || current.isMissingNode() || current.isNull() || !current.isNumber()) {
            return null;
        }

        return current.longValue();
    }

    private JsonNode parseJson(String responseBody) throws IOException {
        return objectMapper.readTree(responseBody);
    }

    interface RequestClient {
        String get(URI uri, String accessToken) throws IOException, InterruptedException;

        String postJson(URI uri, Object payload, String accessToken) throws IOException, InterruptedException;

        String putJson(URI uri, Object payload, String accessToken) throws IOException, InterruptedException;
    }
}