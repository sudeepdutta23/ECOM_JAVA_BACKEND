package com.ecommerce.backend;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Iterator;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.web.client.TestRestTemplate;

/**
 * Keeps {@code postman/ecommerce-backend.postman_collection.json} in sync with the code.
 *
 * <p>The live OpenAPI spec is the source of truth. The build fails if an API endpoint exists that
 * the collection lacks, or if the collection still calls an endpoint that no longer exists. Adding
 * an endpoint therefore forces you to add its request to the collection in the same change.
 */
class PostmanCollectionSyncIT extends AbstractIntegrationTest {

    private static final Path COLLECTION = Path.of("postman", "ecommerce-backend.postman_collection.json");
    private static final Set<String> HTTP_METHODS = Set.of("get", "post", "put", "patch", "delete");

    @Autowired
    private TestRestTemplate rest;

    @Autowired
    private ObjectMapper mapper;

    @Test
    void collectionCoversExactlyTheApiEndpoints() throws Exception {
        Set<String> api = apiOperations();
        Set<String> collection = new TreeSet<>();
        collectRequests(mapper.readTree(Files.readString(COLLECTION)).get("item"), collection);
        collection.removeIf(op -> !op.contains(" /api/")); // actuator and Mailpit helpers are extras

        assertThat(collection).as("Missing from the Postman collection").containsAll(api);
        assertThat(api).as("Stale requests in the Postman collection").containsAll(collection);
    }

    private Set<String> apiOperations() throws Exception {
        JsonNode paths = mapper.readTree(rest.getForObject("/v3/api-docs", String.class)).get("paths");
        Set<String> operations = new TreeSet<>();
        for (Iterator<Map.Entry<String, JsonNode>> it = paths.fields(); it.hasNext(); ) {
            Map.Entry<String, JsonNode> path = it.next();
            if (!path.getKey().startsWith("/api/")) {
                continue;
            }
            path.getValue().fieldNames().forEachRemaining(method -> {
                if (HTTP_METHODS.contains(method)) {
                    operations.add(method.toUpperCase(Locale.ROOT) + " " + path.getKey());
                }
            });
        }
        return operations;
    }

    private static void collectRequests(JsonNode items, Set<String> into) {
        for (JsonNode item : items) {
            if (item.has("item")) {
                collectRequests(item.get("item"), into); // folder
                continue;
            }
            JsonNode request = item.get("request");
            JsonNode url = request.get("url");
            String raw = url.isTextual() ? url.asText() : url.get("raw").asText();
            String path = raw.replace("{{baseUrl}}", "").replace("{{mailpitUrl}}", "mailpit:");
            int query = path.indexOf('?');
            into.add(request.get("method").asText() + " " + (query >= 0 ? path.substring(0, query) : path));
        }
    }
}
