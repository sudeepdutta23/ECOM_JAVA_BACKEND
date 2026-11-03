package com.ecommerce.backend.order;

import static com.ecommerce.backend.support.AuthTestClient.PASSWORD;
import static com.ecommerce.backend.support.AuthTestClient.accessToken;
import static org.assertj.core.api.Assertions.assertThat;

import com.ecommerce.backend.AbstractIntegrationTest;
import com.ecommerce.backend.support.AuthTestClient;
import com.ecommerce.backend.support.RecordingEmailSender;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;

/** Payment, fulfilment and cancellation over real HTTP, checked down to statuses, stock and logs. */
@ExtendWith(OutputCaptureExtension.class)
class OrderLifecycleIT extends AbstractIntegrationTest {

    private static final String DOMAIN = "@order-lifecycle.test";

    @Autowired
    private TestRestTemplate rest;

    @Autowired
    private RecordingEmailSender mail;

    @Autowired
    private JdbcTemplate jdbc;

    @Autowired
    private ObjectMapper mapper;

    private AuthTestClient client;
    private final List<String> slugs = new ArrayList<>();
    private final List<UUID> created = new ArrayList<>();
    private String adminToken;
    private String shopId;
    private String ownerToken;
    private String staffToken;
    private String productId;

    record Customer(UUID id, String token, String addressId) {
    }

    @BeforeEach
    void setUp() {
        client = new AuthTestClient(rest, mail);
        adminToken = token(account(), "ADMIN");
        UUID owner = account();
        ownerToken = token(owner);
        String slug = "it-ol-" + UUID.randomUUID().toString().substring(0, 8);
        slugs.add(slug);
        shopId = data(client.postJson("/api/v1/organizations", Map.of("slug", slug, "name", "Lifecycle Shop"), ownerToken))
                .get("id").asText();
        data(client.send(HttpMethod.POST, "/api/v1/admin/organizations/" + shopId + "/approve", null, adminToken));
        UUID staff = account();
        jdbc.update("INSERT INTO organization_members (organization_id, user_id, role) VALUES (?::uuid, ?, 'STAFF')", shopId, staff);
        staffToken = token(staff);
        String base = "/api/v1/organizations/" + shopId + "/products";
        productId = data(client.postJson(base, Map.of("sku", "LIFE-1", "name", "Lamp", "price", "30.00"), ownerToken))
                .get("id").asText();
        data(client.send(HttpMethod.POST, base + "/" + productId + "/publish", null, ownerToken));
        data(client.postJson(base + "/" + productId + "/stock", Map.of("delta", 10), ownerToken));
    }

    @AfterEach
    void cleanUp() {
        created.forEach(id -> {
            jdbc.update("DELETE FROM orders WHERE customer_id = ?", id);
            jdbc.update("DELETE FROM checkouts WHERE customer_id = ?", id);
            jdbc.update("DELETE FROM cart_items WHERE user_id = ?", id);
        });
        slugs.forEach(s -> {
            jdbc.update("DELETE FROM products WHERE organization_id = (SELECT id FROM organizations WHERE slug = ?)", s);
            jdbc.update("DELETE FROM organizations WHERE slug = ?", s);
        });
        created.forEach(id -> jdbc.update("DELETE FROM users WHERE id = ?", id));
    }

    private UUID account() {
        String email = "ol-" + UUID.randomUUID() + DOMAIN;
        client.registerAndVerify(email);
        UUID id = jdbc.queryForObject("SELECT id FROM users WHERE email = ?", UUID.class, email);
        created.add(id);
        return id;
    }

    private String token(UUID id, String... roles) {
        for (String role : roles) {
            jdbc.update("INSERT INTO user_roles (user_id, role_id) SELECT ?, id FROM roles WHERE code = ?", id, role);
        }
        String email = jdbc.queryForObject("SELECT email FROM users WHERE id = ?", String.class, id);
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

    private Customer customer() {
        UUID id = account();
        String token = token(id);
        String address = data(client.postJson("/api/v1/profile/addresses", Map.of("recipientName", "Customer",
                "line1", "Lamp Street 1", "city", "Berlin", "postalCode", "10117", "countryCode", "DE"), token)).get("id").asText();
        return new Customer(id, token, address);
    }

    /** A placed checkout of {@code quantity} lamps; returns its JSON. */
    private JsonNode buy(Customer customer, int quantity) {
        data(client.send(HttpMethod.PUT, "/api/v1/cart/items/" + productId, Map.of("quantity", quantity), customer.token()));
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        headers.setBearerAuth(customer.token());
        headers.set("Idempotency-Key", UUID.randomUUID().toString());
        return data(rest.exchange("/api/v1/checkout", HttpMethod.POST, new HttpEntity<>(Map.of("shippingAddressId",
                customer.addressId(), "expectedTotal", String.valueOf(30 * quantity)), headers), String.class));
    }

    private ResponseEntity<String> pay(Customer customer, String checkoutId, String token) {
        return client.postJson("/api/v1/checkouts/" + checkoutId + "/pay", Map.of("paymentToken", token), customer.token());
    }

    private String seller(String orderId, String action) {
        return "/api/v1/organizations/" + shopId + "/orders/" + orderId + "/" + action;
    }

    private int stock() {
        return jdbc.queryForObject("SELECT stock FROM products WHERE id = ?::uuid", Integer.class, productId);
    }

    @Test
    void anOrderIsPaidShippedAndDeliveredAndEachSideSeesItsPart(CapturedOutput logs) {
        Customer ada = customer();
        JsonNode checkout = buy(ada, 2);
        String checkoutId = checkout.get("id").asText();
        String orderId = checkout.at("/orders/0/id").asText();

        assertThat(client.postJson(seller(orderId, "ship"), Map.of(), staffToken).getBody())
                .as("unpaid orders are not shipped").contains("ORDER_INVALID_STATUS");

        JsonNode paid = data(pay(ada, checkoutId, "tok_visa"));
        assertThat(paid.at("/orders/0/status").asText()).isEqualTo("PAID");
        assertThat(data(pay(ada, checkoutId, "tok_visa")).at("/orders/0/status").asText())
                .as("paying twice is idempotent").isEqualTo("PAID");

        // The seller's list shows it; staff ship it with a tracking number; the customer sees that.
        assertThat(data(client.get("/api/v1/organizations/" + shopId + "/orders?status=PAID", staffToken))
                .get("content").findValuesAsText("id")).containsExactly(orderId);
        JsonNode shipped = data(client.postJson(seller(orderId, "ship"), Map.of("trackingNumber", "TRACK-123"), staffToken));
        assertThat(shipped.at("/shippingAddress/line1").asText()).as("the seller sees where to ship").isEqualTo("Lamp Street 1");
        assertThat(data(client.get("/api/v1/orders/" + orderId, ada.token())).get("trackingNumber").asText())
                .isEqualTo("TRACK-123");
        assertThat(client.postJson("/api/v1/orders/" + orderId + "/cancel", Map.of(), ada.token()).getBody())
                .as("shipped orders are not cancelled").contains("ORDER_INVALID_STATUS");

        data(client.send(HttpMethod.POST, seller(orderId, "deliver"), null, staffToken));
        JsonNode mine = data(client.get("/api/v1/orders", ada.token()));
        assertThat(mine.at("/content/0/status").asText()).isEqualTo("DELIVERED");
        assertThat(stock()).isEqualTo(8);
        assertThat(logs.getAll()).contains("Checkout paid: checkoutId=" + checkoutId).doesNotContain("tok_visa")
                .doesNotContain("Lamp Street 1");
    }

    @Test
    void aDeclinedPaymentChangesNothingAndCanBeRetried() {
        Customer ada = customer();
        JsonNode checkout = buy(ada, 1);
        String checkoutId = checkout.get("id").asText();

        ResponseEntity<String> declined = pay(ada, checkoutId, "tok_decline");
        assertThat(declined.getStatusCode()).isEqualTo(HttpStatus.PAYMENT_REQUIRED);
        assertThat(jdbc.queryForObject("SELECT status FROM orders WHERE checkout_id = ?::uuid", String.class, checkoutId))
                .isEqualTo("PENDING_PAYMENT");

        assertThat(data(pay(ada, checkoutId, "tok_visa")).at("/orders/0/status").asText()).isEqualTo("PAID");
    }

    @Test
    void cancellingReturnsTheStockAndPaidOrdersAreRefunded(CapturedOutput logs) {
        Customer ada = customer();
        JsonNode unpaid = buy(ada, 3);
        assertThat(stock()).isEqualTo(7);
        data(client.postJson("/api/v1/orders/" + unpaid.at("/orders/0/id").asText() + "/cancel",
                Map.of("reason", "Changed my mind"), ada.token()));
        assertThat(stock()).as("units back on the shelf").isEqualTo(10);

        JsonNode paid = buy(ada, 1);
        data(pay(ada, paid.get("id").asText(), "tok_visa"));
        String orderId = paid.at("/orders/0/id").asText();
        assertThat(client.postJson(seller(orderId, "cancel"), Map.of(), staffToken).getBody())
                .as("the seller must say why").contains("reason");
        JsonNode cancelled = data(client.postJson(seller(orderId, "cancel"), Map.of("reason", "Damaged in storage"), staffToken));
        assertThat(cancelled.get("status").asText()).isEqualTo("CANCELLED");
        assertThat(data(client.get("/api/v1/orders/" + orderId, ada.token())).get("cancelReason").asText())
                .isEqualTo("Damaged in storage");
        assertThat(stock()).isEqualTo(10);
        assertThat(logs.getAll()).contains("Simulated refund").doesNotContain("Damaged in storage");
    }

    @Test
    void ordersAreVisibleOnlyToTheirCustomerAndTheirSeller() {
        Customer ada = customer();
        Customer mallory = customer();
        String orderId = buy(ada, 1).at("/orders/0/id").asText();
        String outsiderShop = data(client.postJson("/api/v1/organizations", Map.of("slug", "it-ol-" + UUID.randomUUID()
                .toString().substring(0, 8), "name", "Other"), mallory.token())).get("id").asText();
        slugs.add(jdbc.queryForObject("SELECT slug FROM organizations WHERE id = ?::uuid", String.class, outsiderShop));

        assertThat(client.get("/api/v1/orders/" + orderId, mallory.token()).getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(client.postJson("/api/v1/orders/" + orderId + "/cancel", Map.of(), mallory.token()).getStatusCode())
                .isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(client.get("/api/v1/organizations/" + outsiderShop + "/orders/" + orderId, mallory.token())
                .getStatusCode()).as("another seller's own path").isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(client.get("/api/v1/organizations/" + shopId + "/orders/" + orderId, mallory.token())
                .getStatusCode()).as("not a member").isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(client.postJson("/api/v1/checkouts/" + buy(ada, 1).get("id").asText() + "/pay",
                Map.of("paymentToken", "tok_visa"), mallory.token()).getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
    }

    @Test
    void aSuspendedSellerTakesNoNewOrdersButStillShipsPaidOnes() {
        Customer ada = customer();
        JsonNode checkout = buy(ada, 1);
        data(pay(ada, checkout.get("id").asText(), "tok_visa"));
        data(client.send(HttpMethod.POST, "/api/v1/admin/organizations/" + shopId + "/suspend",
                Map.of("reason", "Review"), adminToken));

        assertThat(client.send(HttpMethod.PUT, "/api/v1/cart/items/" + productId, Map.of("quantity", 1), ada.token())
                .getStatusCode()).as("nothing new").isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(data(client.postJson(seller(checkout.at("/orders/0/id").asText(), "ship"), Map.of(), staffToken))
                .get("status").asText()).as("customers who paid still get their goods").isEqualTo("SHIPPED");
    }

    /** A customer cancels while staff ship the same order: the row lock lets exactly one win. */
    @Test
    void cancelAndShipAtOnceNeverBothSucceed() throws Exception {
        Customer ada = customer();
        JsonNode checkout = buy(ada, 1);
        data(pay(ada, checkout.get("id").asText(), "tok_visa"));
        String orderId = checkout.at("/orders/0/id").asText();

        ExecutorService pool = Executors.newFixedThreadPool(2);
        try {
            Future<ResponseEntity<String>> cancel = pool.submit(() ->
                    client.postJson("/api/v1/orders/" + orderId + "/cancel", Map.of(), ada.token()));
            Future<ResponseEntity<String>> ship = pool.submit(() -> client.postJson(seller(orderId, "ship"), Map.of(), staffToken));
            assertThat(List.of(cancel.get().getStatusCode().value(), ship.get().getStatusCode().value()))
                    .containsExactlyInAnyOrder(200, 409);
        } finally {
            pool.shutdownNow();
        }
        String status = jdbc.queryForObject("SELECT status FROM orders WHERE id = ?::uuid", String.class, orderId);
        assertThat(stock()).as("stock matches the winner").isEqualTo(status.equals("CANCELLED") ? 10 : 9);
    }
}
