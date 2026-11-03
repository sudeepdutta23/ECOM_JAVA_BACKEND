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
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;

/** Checkout over real HTTP, checked down to orders, stock and cart rows. */
class CheckoutIT extends AbstractIntegrationTest {

    private static final String DOMAIN = "@checkout.test";

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

    record Account(UUID id, String token, String addressId) {
    }

    record Shop(String id, String ownerToken) {
    }

    @BeforeEach
    void setUp() {
        client = new AuthTestClient(rest, mail);
        String email = "admin-" + UUID.randomUUID() + DOMAIN;
        client.registerAndVerify(email);
        UUID id = jdbc.queryForObject("SELECT id FROM users WHERE email = ?", UUID.class, email);
        created.add(id);
        jdbc.update("INSERT INTO user_roles (user_id, role_id) SELECT ?, id FROM roles WHERE code = 'ADMIN'", id);
        adminToken = accessToken(client.login(email, PASSWORD));
    }

    @AfterEach
    void cleanUp() {
        created.forEach(id -> {
            jdbc.update("DELETE FROM orders WHERE customer_id = ?", id);
            jdbc.update("DELETE FROM checkouts WHERE customer_id = ?", id);
        });
        created.forEach(id -> jdbc.update("DELETE FROM cart_items WHERE user_id = ?", id));
        slugs.forEach(s -> {
            jdbc.update("DELETE FROM products WHERE organization_id = (SELECT id FROM organizations WHERE slug = ?)", s);
            jdbc.update("DELETE FROM organizations WHERE slug = ?", s);
        });
        created.forEach(id -> jdbc.update("DELETE FROM users WHERE id = ?", id));
    }

    // ------------------------------------------------------------------ fixtures

    private JsonNode data(ResponseEntity<String> response) {
        assertThat(response.getStatusCode().is2xxSuccessful()).as(response.getBody()).isTrue();
        try {
            return mapper.readTree(response.getBody()).get("data");
        } catch (JsonProcessingException e) {
            throw new IllegalStateException(e);
        }
    }

    private Account customer(String street) {
        String email = "buyer-" + UUID.randomUUID() + DOMAIN;
        client.registerAndVerify(email);
        UUID id = jdbc.queryForObject("SELECT id FROM users WHERE email = ?", UUID.class, email);
        created.add(id);
        String token = accessToken(client.login(email, PASSWORD));
        String address = data(client.postJson("/api/v1/profile/addresses", Map.of("recipientName", "Ada Buyer",
                "line1", street, "city", "Berlin", "postalCode", "10117", "countryCode", "DE"), token)).get("id").asText();
        return new Account(id, token, address);
    }

    private Shop shop() {
        String email = "seller-" + UUID.randomUUID() + DOMAIN;
        client.registerAndVerify(email);
        created.add(jdbc.queryForObject("SELECT id FROM users WHERE email = ?", UUID.class, email));
        String token = accessToken(client.login(email, PASSWORD));
        String slug = "it-co-" + UUID.randomUUID().toString().substring(0, 8);
        slugs.add(slug);
        String id = data(client.postJson("/api/v1/organizations", Map.of("slug", slug, "name", "Shop " + slug), token))
                .get("id").asText();
        data(client.send(HttpMethod.POST, "/api/v1/admin/organizations/" + id + "/approve", null, adminToken));
        return new Shop(id, token);
    }

    private String product(Shop shop, String price, int stock) {
        String base = "/api/v1/organizations/" + shop.id() + "/products";
        String id = data(client.postJson(base, Map.of("sku", "SKU-" + UUID.randomUUID().toString().substring(0, 6),
                "name", "Product " + price, "price", price), shop.ownerToken())).get("id").asText();
        data(client.send(HttpMethod.POST, base + "/" + id + "/publish", null, shop.ownerToken()));
        data(client.postJson(base + "/" + id + "/stock", Map.of("delta", stock), shop.ownerToken()));
        return id;
    }

    private void addToCart(Account customer, String productId, int quantity) {
        data(client.send(HttpMethod.PUT, "/api/v1/cart/items/" + productId, Map.of("quantity", quantity), customer.token()));
    }

    private ResponseEntity<String> checkout(Account customer, String key, String expectedTotal) {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        headers.setBearerAuth(customer.token());
        if (key != null) {
            headers.set("Idempotency-Key", key);
        }
        Map<String, Object> body = new HashMap<>(Map.of("shippingAddressId", customer.addressId(), "expectedTotal", expectedTotal));
        return rest.exchange("/api/v1/checkout", HttpMethod.POST, new HttpEntity<>(body, headers), String.class);
    }

    private int stock(String productId) {
        return jdbc.queryForObject("SELECT stock FROM products WHERE id = ?::uuid", Integer.class, productId);
    }

    private long count(String table, Account customer) {
        String column = table.equals("cart_items") ? "user_id" : "customer_id";
        return jdbc.queryForObject("SELECT count(*) FROM " + table + " WHERE " + column + " = ?", Long.class, customer.id());
    }

    private static String key() {
        return UUID.randomUUID().toString();
    }

    // ------------------------------------------------------------------ tests

    @Test
    void aCartOfTwoSellersBecomesTwoOrdersWithSnapshotsAndTheStockIsTaken() {
        Shop mugs = shop();
        Shop books = shop();
        String mug = product(mugs, "12.50", 5);
        String book = product(books, "20.00", 5);
        Account ada = customer("Friedrichstraße 1");
        addToCart(ada, mug, 2);
        addToCart(ada, book, 1);

        ResponseEntity<String> response = checkout(ada, key(), "45.00");

        assertThat(response.getStatusCode()).as(response.getBody()).isEqualTo(HttpStatus.CREATED);
        JsonNode placed = data(response);
        assertThat(placed.get("total").decimalValue()).isEqualByComparingTo("45.00");
        assertThat(placed.get("orders")).hasSize(2);
        assertThat(placed.get("orders").findValuesAsText("status")).containsOnly("PENDING_PAYMENT");
        assertThat(placed.get("orders").findValuesAsText("organizationId")).containsExactlyInAnyOrder(mugs.id(), books.id());
        assertThat(stock(mug)).isEqualTo(3);
        assertThat(stock(book)).isEqualTo(4);
        assertThat(count("cart_items", ada)).as("the cart is empty").isZero();

        // Later changes to the product and the address do not reach the order.
        String mugPath = "/api/v1/organizations/" + mugs.id() + "/products/" + mug;
        long version = data(client.get(mugPath, mugs.ownerToken())).get("version").asLong();
        data(client.send(HttpMethod.PUT, mugPath, Map.of("sku", "RENAMED", "name", "Renamed", "price", "99.00",
                "version", version), mugs.ownerToken()));
        assertThat(client.send(HttpMethod.DELETE, "/api/v1/profile/addresses/" + ada.addressId(), null, ada.token())
                .getStatusCode()).isEqualTo(HttpStatus.NO_CONTENT);
        JsonNode again = data(client.get("/api/v1/checkouts/" + placed.get("id").asText(), ada.token()));
        JsonNode mugOrder = again.get("orders").get(again.at("/orders/0/organizationId").asText().equals(mugs.id()) ? 0 : 1);
        assertThat(mugOrder.at("/lines/0/unitPrice").decimalValue()).isEqualByComparingTo("12.50");
        assertThat(mugOrder.at("/lines/0/name").asText()).isEqualTo("Product 12.50");
        assertThat(mugOrder.at("/shippingAddress/line1").asText()).isEqualTo("Friedrichstraße 1");
        assertThat(mugOrder.get("sellerName").asText()).startsWith("Shop it-co-");
    }

    @Test
    void retryingWithTheSameKeyReturnsTheSameCheckoutAndBuysNothingTwice() {
        Shop shop = shop();
        String product = product(shop, "10.00", 5);
        Account ada = customer("Street 1");
        addToCart(ada, product, 1);
        String key = key();

        ResponseEntity<String> first = checkout(ada, key, "10.00");
        ResponseEntity<String> retry = checkout(ada, key, "10.00");

        assertThat(first.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        assertThat(retry.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(retry.getHeaders().getFirst("Idempotent-Replayed")).isEqualTo("true");
        assertThat(data(retry).get("id").asText()).isEqualTo(data(first).get("id").asText());
        assertThat(stock(product)).isEqualTo(4);
        assertThat(count("orders", ada)).isOne();
    }

    /** Five parallel retries of one purchase (a nervous double click, a flaky network): one checkout. */
    @Test
    void parallelRetriesWithOneKeyBuyOnce() throws Exception {
        Shop shop = shop();
        String product = product(shop, "10.00", 5);
        Account ada = customer("Street 1");
        addToCart(ada, product, 1);
        String key = key();

        ExecutorService pool = Executors.newFixedThreadPool(5);
        try {
            List<Callable<ResponseEntity<String>>> attempts = new ArrayList<>();
            for (int i = 0; i < 5; i++) {
                attempts.add(() -> checkout(ada, key, "10.00"));
            }
            List<String> ids = new ArrayList<>();
            for (Future<ResponseEntity<String>> result : pool.invokeAll(attempts)) {
                ids.add(data(result.get()).get("id").asText());
            }
            assertThat(ids).as("every attempt sees the same checkout").containsOnly(ids.get(0));
        } finally {
            pool.shutdownNow();
        }
        assertThat(stock(product)).isEqualTo(4);
        assertThat(count("checkouts", ada)).isOne();
    }

    /** Two customers, one unit left, at the same moment: exactly one gets it, nobody gets a phantom order. */
    @Test
    void theLastUnitCannotBeSoldTwice() throws Exception {
        Shop shop = shop();
        String product = product(shop, "10.00", 1);
        Account ada = customer("Street 1");
        Account bob = customer("Street 2");
        addToCart(ada, product, 1);
        addToCart(bob, product, 1);

        ExecutorService pool = Executors.newFixedThreadPool(2);
        try {
            Future<ResponseEntity<String>> first = pool.submit(() -> checkout(ada, key(), "10.00"));
            Future<ResponseEntity<String>> second = pool.submit(() -> checkout(bob, key(), "10.00"));
            List<HttpStatus> statuses = List.of(HttpStatus.valueOf(first.get().getStatusCode().value()),
                    HttpStatus.valueOf(second.get().getStatusCode().value()));
            assertThat(statuses).containsExactlyInAnyOrder(HttpStatus.CREATED, HttpStatus.CONFLICT);
        } finally {
            pool.shutdownNow();
        }
        assertThat(stock(product)).isZero();
        assertThat(count("orders", ada) + count("orders", bob)).isOne();
    }

    @Test
    void aFailedCheckoutChangesNothing() {
        Shop shop = shop();
        String plenty = product(shop, "10.00", 5);
        String scarce = product(shop, "5.00", 5);
        Account ada = customer("Street 1");
        addToCart(ada, plenty, 2);
        addToCart(ada, scarce, 1);

        // The total the customer saw is stale.
        ResponseEntity<String> stale = checkout(ada, key(), "20.00");
        assertThat(stale.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
        assertThat(stale.getBody()).contains("ORDER_TOTAL_CHANGED").contains("25.00");

        // A product runs short: the line is not ready.
        jdbc.update("UPDATE products SET stock = 0 WHERE id = ?::uuid", scarce);
        assertThat(checkout(ada, key(), "25.00").getBody()).contains("ORDER_CART_NOT_READY");

        assertThat(stock(plenty)).as("nothing reserved").isEqualTo(5);
        assertThat(count("orders", ada)).isZero();
        assertThat(count("cart_items", ada)).as("the cart is kept").isEqualTo(2);
    }

    @Test
    void aSuspendedSellerSellsNothingAndTheWholeCheckoutIsRefused() {
        Shop open = shop();
        Shop suspended = shop();
        String fine = product(open, "10.00", 5);
        String blocked = product(suspended, "10.00", 5);
        Account ada = customer("Street 1");
        addToCart(ada, fine, 1);
        addToCart(ada, blocked, 1);
        data(client.send(HttpMethod.POST, "/api/v1/admin/organizations/" + suspended.id() + "/suspend",
                Map.of("reason", "Review"), adminToken));

        assertThat(checkout(ada, key(), "20.00").getBody()).contains("ORDER_CART_NOT_READY");
        assertThat(stock(fine)).isEqualTo(5);
        assertThat(count("orders", ada)).isZero();
    }

    @Test
    void theRequestMustNameTheCustomersOwnAddressAndCarryAKey() {
        Shop shop = shop();
        String product = product(shop, "10.00", 5);
        Account ada = customer("Street 1");
        Account mallory = customer("Street 2");
        addToCart(mallory, product, 1);

        assertThat(checkout(mallory, null, "10.00").getBody()).contains("VALIDATION_FAILED").contains("Idempotency-Key");
        Account malloryWithAdasAddress = new Account(mallory.id(), mallory.token(), ada.addressId());
        ResponseEntity<String> foreign = checkout(malloryWithAdasAddress, key(), "10.00");
        assertThat(foreign.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(foreign.getBody()).contains("shippingAddressId").doesNotContain("Street 1");
        assertThat(checkout(ada, key(), "10.00").getBody()).as("Ada's cart is empty").contains("ORDER_CART_NOT_READY");
    }

    @Test
    void onlyTheCustomerSeesTheirCheckoutAndErasureKeepsTheOrders() {
        Shop shop = shop();
        String product = product(shop, "10.00", 5);
        Account ada = customer("Street 1");
        Account bob = customer("Street 2");
        addToCart(ada, product, 1);
        String checkoutId = data(checkout(ada, key(), "10.00")).get("id").asText();

        assertThat(client.get("/api/v1/checkouts/" + checkoutId, bob.token()).getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);

        assertThat(client.postJson("/api/v1/auth/me/erase", Map.of("password", PASSWORD), ada.token()).getStatusCode())
                .isEqualTo(HttpStatus.NO_CONTENT);
        assertThat(count("orders", ada)).as("orders are kept for tax law, with the anonymous ID").isOne();
    }
}
