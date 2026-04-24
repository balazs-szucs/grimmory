package org.booklore.config.leyden;

import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.net.URI;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class LeydenTrainingScenarioTest {

    private static final URI BASE_URI = URI.create("http://127.0.0.1:8080");

    @Test
    void executesRealisticDashboardAndAppWarmupPaths() throws IOException, InterruptedException {
        LeydenTrainingScenario scenario = new LeydenTrainingScenario(new ObjectMapper());
        FakeRequestClient client = new FakeRequestClient(
                "[{\"id\":7}]",
                "[{\"id\":11}]",
                "[{\"id\":101,\"primaryFile\":{\"id\":201}}]",
                "[101]"
        );

        scenario.execute(client, BASE_URI);

        assertTrue(client.requestTargets().contains("GET /api/v1/public-settings"));
        assertTrue(client.requestTargets().contains("POST /api/v1/setup"));
        assertTrue(client.requestTargets().contains("POST /api/v1/auth/login"));
        assertTrue(client.requestTargets().contains("GET /api/v1/settings"));
        assertTrue(client.requestTargets().contains("GET /api/v1/books?stripForListView=false"));
        assertTrue(client.requestTargets().contains("GET /api/v1/books/page?page=0&size=50&sort=metadata.title,asc"));
        assertTrue(client.requestTargets().contains("GET /api/magic-shelves"));
        assertTrue(client.requestTargets().contains("GET /api/v1/libraries/7/format-counts"));
        assertTrue(client.requestTargets().contains("GET /api/v1/books/101?withDescription=true"));
        assertTrue(client.requestTargets().contains("GET /api/v1/books/101/viewer-setting?bookFileId=201"));
        assertTrue(client.requestTargets().contains("GET /api/v1/app/books?page=0&size=50&sort=title&dir=asc"));
        assertTrue(client.requestTargets().contains("GET /api/v1/app/books?status=READING&fileType=EPUB&filterMode=and&page=0&size=50&sort=addedOn&dir=desc"));
        assertTrue(client.requestTargets().contains("GET /api/v1/app/filter-options?magicShelfId=11"));
        assertTrue(client.requestTargets().contains("GET /api/v1/app/shelves/magic/11/books?page=0&size=20"));
        assertTrue(client.requestTargets().contains("GET /api/v1/app/books/101"));

        FakeRequestClient.CapturedRequest dashboardUpdate = client.findRequest("PUT", "/api/v1/users/42/settings");
        assertEquals("token", dashboardUpdate.accessToken());
        @SuppressWarnings("unchecked")
        Map<String, Object> payload = (Map<String, Object>) dashboardUpdate.payload();
        assertEquals("dashboardConfig", payload.get("key"));
    }

    @Test
    void skipsOptionalDetailWarmupsWhenListsAreEmpty() throws IOException, InterruptedException {
        LeydenTrainingScenario scenario = new LeydenTrainingScenario(new ObjectMapper());
        FakeRequestClient client = new FakeRequestClient("[]", "[]", "[]", "[]");

        scenario.execute(client, BASE_URI);

        assertFalse(client.requestTargets().contains("GET /api/v1/libraries/7/format-counts"));
        assertFalse(client.requestTargets().contains("GET /api/v1/books/101?withDescription=true"));
        assertFalse(client.requestTargets().contains("GET /api/v1/app/shelves/magic/11/books?page=0&size=20"));
        assertFalse(client.requestTargets().contains("GET /api/v1/app/books/101"));
    }

    private static final class FakeRequestClient implements LeydenTrainingScenario.RequestClient {
        private final String librariesResponse;
        private final String magicShelvesResponse;
        private final String booksResponse;
        private final String appBookIdsResponse;
        private final List<CapturedRequest> requests = new ArrayList<>();

        private FakeRequestClient(String librariesResponse, String magicShelvesResponse, String booksResponse, String appBookIdsResponse) {
            this.librariesResponse = librariesResponse;
            this.magicShelvesResponse = magicShelvesResponse;
            this.booksResponse = booksResponse;
            this.appBookIdsResponse = appBookIdsResponse;
        }

        @Override
        public String get(URI uri, String accessToken) {
            requests.add(new CapturedRequest("GET", uri, accessToken, null));

            String target = requestTarget(uri);
            return switch (target) {
                case "/api/v1/auth/login" -> throw new IllegalStateException("login must use POST");
                case "/api/v1/users/me" -> "{\"id\":42,\"userSettings\":{\"dashboardConfig\":{\"scrollers\":[]}}}";
                case "/api/v1/libraries", "/api/v1/app/libraries" -> librariesResponse;
                case "/api/magic-shelves", "/api/v1/app/shelves/magic" -> magicShelvesResponse;
                case "/api/v1/books?stripForListView=false" -> booksResponse;
                case "/api/v1/app/books/ids" -> appBookIdsResponse;
                default -> "{}";
            };
        }

        @Override
        public String postJson(URI uri, Object payload, String accessToken) {
            requests.add(new CapturedRequest("POST", uri, accessToken, payload));
            if ("/api/v1/auth/login".equals(requestTarget(uri))) {
                return "{\"accessToken\":\"token\"}";
            }
            return "{}";
        }

        @Override
        public String putJson(URI uri, Object payload, String accessToken) {
            requests.add(new CapturedRequest("PUT", uri, accessToken, payload));
            return "{}";
        }

        private List<String> requestTargets() {
            return requests.stream()
                    .map(request -> request.method() + " " + requestTarget(request.uri()))
                    .toList();
        }

        private CapturedRequest findRequest(String method, String target) {
            return requests.stream()
                    .filter(request -> request.method().equals(method) && requestTarget(request.uri()).equals(target))
                    .findFirst()
                    .orElseThrow();
        }

        private static String requestTarget(URI uri) {
            return uri.getRawQuery() == null ? uri.getPath() : uri.getPath() + "?" + uri.getRawQuery();
        }

        private record CapturedRequest(String method, URI uri, String accessToken, Object payload) {
        }
    }
}