package com.ecommerce.backend;

import static com.ecommerce.backend.support.AuthTestClient.PASSWORD;
import static com.ecommerce.backend.support.AuthTestClient.accessToken;
import static org.assertj.core.api.Assertions.assertThat;

import com.ecommerce.backend.support.AuthTestClient;
import com.ecommerce.backend.support.RecordingEmailSender;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.persistence.EntityManagerFactory;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.hibernate.SessionFactory;
import org.hibernate.stat.Statistics;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * Guards the hot read endpoints against N+1 queries: each is called with one row per page and with
 * a full page, and must prepare the same number of SQL statements, at most its budget. A lazy
 * association touched per row would add statements with every row and fail here.
 *
 * <p>Counted with Hibernate statistics ({@code generate_statistics} in the test profile only): every
 * statement Hibernate prepares, native queries included. The budgets include the request's own
 * overhead (the caller's account check), so they are the whole cost of one request.
 */
class QueryCountIT extends AbstractIntegrationTest {

    private static final String DOMAIN = "@querycount.test";

    @Autowired
    private TestRestTemplate rest;

    @Autowired
    private RecordingEmailSender mail;

    @Autowired
    private JdbcTemplate jdbc;

    @Autowired
    private ObjectMapper mapper;

    @Autowired
    private EntityManagerFactory entityManagerFactory;

    private AuthTestClient client;
    private Statistics statistics;
    private final List<UUID> created = new ArrayList<>();
    private final List<String> products = new ArrayList<>();
    private String slug;
    private String shop;
    private String owner;
    private String customer;
    private String addressId;

    @BeforeEach
    void setUp() {
        statistics = entityManagerFactory.unwrap(SessionFactory.class).getStatistics();
        client = new AuthTestClient(rest, mail);
        String admin = account("ADMIN");
        owner = account();
        customer = account();
        slug = "it-qc-" + UUID.randomUUID().toString().substring(0, 8);
        shop = data(client.postJson("/api/v1/organizations", Map.of("slug", slug, "name", "Query Count Shop"), owner))
                .get("id").asText();
        data(client.send(HttpMethod.POST, "/api/v1/admin/organizations/" + shop + "/approve", null, admin));
        String base = "/api/v1/organizations/" + shop + "/products";
        for (int i = 0; i < 8; i++) {
            String id = data(client.postJson(base, Map.of("sku", "QC-" + i, "name", "Item " + i, "price", "10.00"), owner))
                    .get("id").asText();
            data(client.send(HttpMethod.POST, base + "/" + id + "/publish", null, owner));
            data(client.postJson(base + "/" + id + "/stock", Map.of("delta", 50), owner));
            products.add(id);
        }
        addressId = data(client.postJson("/api/v1/profile/addresses", Map.of("recipientName", "Query Count",
                "line1", "Street 1", "city", "Berlin", "postalCode", "10117", "countryCode", "DE"), customer))
                .get("id").asText();
        // Four paid orders of two lines each: order lists, seller lists and both inboxes have rows.
        for (int i = 0; i < 4; i++) {
            data(client.send(HttpMethod.PUT, "/api/v1/cart/items/" + products.get(2 * i), Map.of("quantity", 1), customer));
            data(client.send(HttpMethod.PUT, "/api/v1/cart/items/" + products.get(2 * i + 1), Map.of("quantity", 2), customer));
            JsonNode checkout = checkout("30");
            data(client.postJson("/api/v1/checkouts/" + checkout.get("id").asText() + "/pay",
                    Map.of("paymentToken", "tok_visa"), customer));
        }
    }

    @AfterEach
    void cleanUp() {
        statistics.setStatisticsEnabled(false);
        created.forEach(id -> {
            jdbc.update("DELETE FROM orders WHERE customer_id = ?", id);
            jdbc.update("DELETE FROM checkouts WHERE customer_id = ?", id);
            jdbc.update("DELETE FROM cart_items WHERE user_id = ?", id);
        });
        jdbc.update("DELETE FROM products WHERE organization_id = ?::uuid", shop);
        jdbc.update("DELETE FROM organizations WHERE id = ?::uuid", shop);
        created.forEach(id -> jdbc.update("DELETE FROM users WHERE id = ?", id));
    }

    private String account(String... roles) {
        String email = "qc-" + UUID.randomUUID() + DOMAIN;
        client.registerAndVerify(email);
        UUID id = jdbc.queryForObject("SELECT id FROM users WHERE email = ?", UUID.class, email);
        created.add(id);
        for (String role : roles) {
            jdbc.update("INSERT INTO user_roles (user_id, role_id) SELECT ?, id FROM roles WHERE code = ?", id, role);
        }
        return accessToken(client.login(email, PASSWORD));
    }

    private JsonNode data(ResponseEntity<String> response) {
        assertThat(response.getStatusCode().is2xxSuccessful()).as(response.getBody()).isTrue();
        try {
            return mapper.readTree(response.getBody()).get("data");
        } catch (JsonProcessingException e) {
            throw new IllegalStateException(e);
        }
    }

    private JsonNode checkout(String expectedTotal) {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        headers.setBearerAuth(customer);
        headers.set("Idempotency-Key", UUID.randomUUID().toString());
        return data(rest.exchange("/api/v1/checkout", HttpMethod.POST, new HttpEntity<>(Map.of("shippingAddressId", addressId,
                "expectedTotal", expectedTotal), headers), String.class));
    }

    /** Statements prepared while serving one GET, and how many rows it returned. */
    private record Cost(long statements, int rows) {
    }

    private Cost get(String path, String token) {
        statistics.clear();
        JsonNode body = data(token == null ? rest.getForEntity(path, String.class)
                : client.send(HttpMethod.GET, path, null, token));
        long statements = statistics.getPrepareStatementCount();
        JsonNode rows = body.has("content") ? body.get("content") : body.get("lines");
        return new Cost(statements, rows == null ? 0 : rows.size());
    }

    private void assertFlat(String path, String token, int fullPage, long budget) {
        statistics.setStatisticsEnabled(true);
        String separator = path.contains("?") ? "&" : "?";
        Cost one = get(path + separator + "size=1", token);
        Cost full = get(path + separator + "size=" + fullPage, token);

        assertThat(one.rows()).as(path + " with size=1").isEqualTo(1);
        assertThat(full.rows()).as(path + " returns a full page").isGreaterThan(1);
        assertThat(full.statements()).as(path + ": statements must not grow with the rows (" + full.rows() + " rows)")
                .isEqualTo(one.statements());
        assertThat(full.statements()).as(path + ": statement budget").isLessThanOrEqualTo(budget);
    }

    @Test
    void theStorefrontListCostsTheSameForOneProductAndForAFullPage() {
        // The search, then the page's products, sellers and images, each loaded in one batch by ID.
        assertFlat("/api/v1/catalog/products?seller=" + slug, null, 8, 4);
    }

    @Test
    void aCustomersOrderListCostsTheSameForOneOrderAndForAFullPage() {
        assertFlat("/api/v1/orders", customer, 4, 4);
    }

    @Test
    void aSellersOrderListCostsTheSameForOneOrderAndForAFullPage() {
        assertFlat("/api/v1/organizations/" + shop + "/orders", owner, 4, 5);
    }

    @Test
    void aSellersProductListCostsTheSameForOneProductAndForAFullPage() {
        assertFlat("/api/v1/organizations/" + shop + "/products", owner, 8, 5);
    }

    @Test
    void theInboxCostsTheSameForOneNotificationAndForAFullPage() {
        assertFlat("/api/v1/notifications", customer, 4, 3);
    }

    @Test
    void theCartCostsTheSameForOneLineAndForSix() {
        statistics.setStatisticsEnabled(true);
        data(client.send(HttpMethod.PUT, "/api/v1/cart/items/" + products.get(0), Map.of("quantity", 1), customer));
        Cost one = get("/api/v1/cart", customer);
        for (int i = 1; i < 6; i++) {
            data(client.send(HttpMethod.PUT, "/api/v1/cart/items/" + products.get(i), Map.of("quantity", 1), customer));
        }
        Cost six = get("/api/v1/cart", customer);

        assertThat(one.rows()).isEqualTo(1);
        assertThat(six.rows()).isEqualTo(6);
        assertThat(six.statements()).as("cart statements must not grow with its lines").isEqualTo(one.statements());
        assertThat(six.statements()).as("cart statement budget").isLessThanOrEqualTo(4);
    }
}
