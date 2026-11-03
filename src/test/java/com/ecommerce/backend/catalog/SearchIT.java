package com.ecommerce.backend.catalog;

import static com.ecommerce.backend.support.AuthTestClient.PASSWORD;
import static com.ecommerce.backend.support.AuthTestClient.accessToken;
import static org.assertj.core.api.Assertions.assertThat;

import com.ecommerce.backend.AbstractIntegrationTest;
import com.ecommerce.backend.support.AuthTestClient;
import com.ecommerce.backend.support.RecordingEmailSender;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.net.URI;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * The storefront search over real HTTP, against one approved seller with a known catalogue. Each
 * query is scoped to that seller, so products of other tests cannot interfere.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class SearchIT extends AbstractIntegrationTest {

    private static final String DOMAIN = "@search.test";

    @Autowired
    private TestRestTemplate rest;

    @Autowired
    private RecordingEmailSender mail;

    @Autowired
    private JdbcTemplate jdbc;

    @Autowired
    private ObjectMapper mapper;

    private String slug;
    private String inName;
    private String inDescription;
    private String cheap;
    private String soldOut;

    @BeforeAll
    void catalogue() {
        AuthTestClient client = new AuthTestClient(rest, mail);
        String admin = account(client, "ADMIN");
        String owner = account(client);
        slug = "it-search-" + UUID.randomUUID().toString().substring(0, 8);
        String shop = data(client.postJson("/api/v1/organizations", Map.of("slug", slug, "name", "Search Shop"), owner))
                .get("id").asText();
        data(client.send(HttpMethod.POST, "/api/v1/admin/organizations/" + shop + "/approve", null, admin));
        inName = product(client, shop, owner, "Blue enamel mug", "Hand made.", "12.00", 5);
        inDescription = product(client, shop, owner, "Breakfast set", "Plate, bowl and a blue enamel mug.", "40.00", 5);
        cheap = product(client, shop, owner, "Red teapot", "Ceramic.", "3.50", 5);
        soldOut = product(client, shop, owner, "Green enamel mug", "Out of stock.", "15.00", 0);
        product(client, shop, owner, "Hidden mug draft", "Never published.", "1.00", -1);
    }

    @AfterAll
    void cleanUp() {
        jdbc.update("DELETE FROM products WHERE organization_id = (SELECT id FROM organizations WHERE slug = ?)", slug);
        jdbc.update("DELETE FROM organizations WHERE slug = ?", slug);
        jdbc.update("DELETE FROM users WHERE email LIKE '%" + DOMAIN + "'");
    }

    private String account(AuthTestClient client, String... roles) {
        String email = "s-" + UUID.randomUUID() + DOMAIN;
        client.registerAndVerify(email);
        for (String role : roles) {
            jdbc.update("INSERT INTO user_roles (user_id, role_id) SELECT u.id, r.id FROM users u, roles r "
                    + "WHERE u.email = ? AND r.code = ?", email, role);
        }
        return accessToken(client.login(email, PASSWORD));
    }

    /** @param stock below zero: leave it as a draft */
    private String product(AuthTestClient client, String shop, String owner, String name, String description, String price,
                           int stock) {
        String base = "/api/v1/organizations/" + shop + "/products";
        String id = data(client.postJson(base, Map.of("sku", "S-" + UUID.randomUUID().toString().substring(0, 8),
                "name", name, "description", description, "price", price), owner)).get("id").asText();
        if (stock >= 0) {
            data(client.send(HttpMethod.POST, base + "/" + id + "/publish", null, owner));
        }
        if (stock > 0) {
            data(client.postJson(base + "/" + id + "/stock", Map.of("delta", stock), owner));
        }
        return id;
    }

    private JsonNode data(ResponseEntity<String> response) {
        assertThat(response.getStatusCode().is2xxSuccessful()).as(response.getBody()).isTrue();
        try {
            return mapper.readTree(response.getBody()).get("data");
        } catch (JsonProcessingException e) {
            throw new IllegalStateException(e);
        }
    }

    /** A ready URI: a String would be treated as a template and encoded a second time ("%22" becoming "%2522"). */
    private ResponseEntity<String> search(String query) {
        return rest.getForEntity(URI.create(rest.getRootUri() + "/api/v1/catalog/products?seller=" + slug + "&" + query),
                String.class);
    }

    private List<String> ids(String query) {
        return data(search(query)).get("content").findValuesAsText("id");
    }

    private static String q(String text) {
        return "q=" + URLEncoder.encode(text, StandardCharsets.UTF_8);
    }

    @Test
    void aMatchInTheNameRanksAboveAMatchInTheDescription() {
        List<String> found = ids(q("enamel mug"));

        assertThat(found).hasSize(3);
        assertThat(found.subList(0, 2)).as("both name matches first").containsExactlyInAnyOrder(inName, soldOut);
        assertThat(found.get(2)).as("the description match last").isEqualTo(inDescription);
    }

    @Test
    void phrasesExclusionsAndCaseWorkLikeASearchBox() {
        assertThat(ids(q("\"blue enamel\""))).containsExactlyInAnyOrder(inName, inDescription);
        assertThat(ids(q("mug -breakfast"))).containsExactlyInAnyOrder(inName, soldOut);
        assertThat(ids(q("TEAPOT"))).containsExactly(cheap);
        assertThat(ids(q("draft"))).as("drafts are never found").isEmpty();
    }

    @Test
    void anyTextIsSafeNeverASyntaxError() {
        for (String text : List.of("'; DROP TABLE products; --", "mug & | ! :* ( )", "\"unclosed", "-", "%_\\")) {
            assertThat(search(q(text)).getStatusCode()).as(text).isEqualTo(HttpStatus.OK);
        }
        assertThat(jdbc.queryForObject("SELECT count(*) FROM products WHERE id = ?::uuid", Long.class, cheap)).isOne();
    }

    @Test
    void priceAndStockFiltersAndPriceSorting() {
        assertThat(ids("minPrice=10&maxPrice=20&sort=PRICE_ASC")).containsExactly(inName, soldOut);
        assertThat(ids("inStock=true&sort=PRICE_DESC")).containsExactly(inDescription, inName, cheap);
        assertThat(ids("sort=PRICE_ASC").get(0)).isEqualTo(cheap);
        JsonNode page = data(search("sort=PRICE_ASC&size=2&page=1"));
        assertThat(page.get("content").findValuesAsText("id")).containsExactly(soldOut, inDescription);
        assertThat(page.get("totalElements").asLong()).isEqualTo(4);
    }

    @Test
    void badParametersAreValidationErrors() {
        for (String query : List.of("minPrice=20&maxPrice=10", "sort=CHEAPEST", "minPrice=-1", "minPrice=1.001",
                "page=200&size=100", "q=" + "x".repeat(101))) {
            ResponseEntity<String> response = search(query);
            assertThat(response.getStatusCode()).as(query).isEqualTo(HttpStatus.BAD_REQUEST);
            assertThat(response.getBody()).contains("VALIDATION_FAILED");
        }
    }
}
