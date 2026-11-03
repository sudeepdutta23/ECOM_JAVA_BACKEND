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
import org.junit.jupiter.api.AfterEach;
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

/**
 * Phase 6 as one story: two sellers open shops and list products, a customer buys from both in one
 * checkout and pays once, one seller ships and delivers, the other is suspended and cancels with a
 * refund, and the money and stock add up. Then everything logged is audited.
 */
@ExtendWith(OutputCaptureExtension.class)
class MarketplaceJourneyIT extends AbstractIntegrationTest {

    private static final String DOMAIN = "@marketplace-journey.test";

    @Autowired
    private TestRestTemplate rest;

    @Autowired
    private RecordingEmailSender mail;

    @Autowired
    private JdbcTemplate jdbc;

    @Autowired
    private ObjectMapper mapper;

    private AuthTestClient client;
    private final List<UUID> created = new ArrayList<>();
    private final List<String> slugs = new ArrayList<>();

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

    private String person(String name, String... roles) {
        String email = name + "-" + UUID.randomUUID() + DOMAIN;
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

    private JsonNode post(String path, Object body, String token) {
        return data(client.send(HttpMethod.POST, path, body, token));
    }

    private String openShop(String ownerToken, String adminToken, String name) {
        String slug = "it-mj-" + UUID.randomUUID().toString().substring(0, 8);
        slugs.add(slug);
        String id = post("/api/v1/organizations", Map.of("slug", slug, "name", name), ownerToken).get("id").asText();
        post("/api/v1/admin/organizations/" + id + "/approve", null, adminToken);
        return id;
    }

    private String list(String shopId, String ownerToken, String sku, String price, int stock) {
        String base = "/api/v1/organizations/" + shopId + "/products";
        String id = post(base, Map.of("sku", sku, "name", sku + " item", "price", price), ownerToken).get("id").asText();
        post(base + "/" + id + "/publish", null, ownerToken);
        post(base + "/" + id + "/stock", Map.of("delta", stock), ownerToken);
        return id;
    }

    private int stock(String productId) {
        return jdbc.queryForObject("SELECT stock FROM products WHERE id = ?::uuid", Integer.class, productId);
    }

    @Test
    void twoSellersOneCustomerOneCheckoutAndEverythingAddsUp(CapturedOutput logs) {
        client = new AuthTestClient(rest, mail);
        String admin = person("admin", "ADMIN");
        String lena = person("lena");   // owns the lamp shop
        String omar = person("omar");   // owns the book shop
        String ada = person("ada");     // the customer
        String street = "Journey Street " + UUID.randomUUID().toString().substring(0, 6);

        // 1. Two sellers open shops and list products; customers can browse them without an account.
        String lamps = openShop(lena, admin, "Lena's Lamps");
        String books = openShop(omar, admin, "Omar's Books");
        String lamp = list(lamps, lena, "LAMP-1", "45.00", 3);
        String book = list(books, omar, "BOOK-1", "12.50", 10);
        assertThat(data(client.get("/api/v1/catalog/products/" + lamp, null)).at("/seller/name").asText())
                .isEqualTo("Lena's Lamps");

        // 2. Ada fills her address book and her cart from both shops.
        String address = post("/api/v1/profile/addresses", Map.of("recipientName", "Ada Lovelace", "line1", street,
                "city", "London", "postalCode", "SW1A 1AA", "countryCode", "GB"), ada).get("id").asText();
        data(client.send(HttpMethod.PUT, "/api/v1/cart/items/" + lamp, Map.of("quantity", 1), ada));
        JsonNode cart = data(client.send(HttpMethod.PUT, "/api/v1/cart/items/" + book, Map.of("quantity", 2), ada));
        assertThat(cart.get("total").decimalValue()).isEqualByComparingTo("70.00");

        // 3. One checkout, two orders; one payment for both.
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        headers.setBearerAuth(ada);
        headers.set("Idempotency-Key", UUID.randomUUID().toString());
        JsonNode checkout = data(rest.exchange("/api/v1/checkout", HttpMethod.POST, new HttpEntity<>(Map.of(
                "shippingAddressId", address, "expectedTotal", "70.00"), headers), String.class));
        assertThat(checkout.get("orders")).hasSize(2);
        String checkoutId = checkout.get("id").asText();
        JsonNode paid = post("/api/v1/checkouts/" + checkoutId + "/pay", Map.of("paymentToken", "tok_visa"), ada);
        assertThat(paid.get("orders").findValuesAsText("status")).containsOnly("PAID");
        String lampOrder = jdbc.queryForObject("SELECT id FROM orders WHERE checkout_id = ?::uuid AND organization_id = ?::uuid",
                String.class, checkoutId, lamps);
        String bookOrder = jdbc.queryForObject("SELECT id FROM orders WHERE checkout_id = ?::uuid AND organization_id = ?::uuid",
                String.class, checkoutId, books);
        assertThat(stock(lamp)).isEqualTo(2);
        assertThat(stock(book)).isEqualTo(8);

        // 4. Each seller sees only its own order. Lena ships and delivers hers.
        assertThat(data(client.get("/api/v1/organizations/" + lamps + "/orders", lena)).get("content").findValuesAsText("id"))
                .containsExactly(lampOrder);
        assertThat(client.get("/api/v1/organizations/" + lamps + "/orders/" + bookOrder, lena).getStatusCode())
                .isEqualTo(HttpStatus.NOT_FOUND);
        post("/api/v1/organizations/" + lamps + "/orders/" + lampOrder + "/ship", Map.of("trackingNumber", "LAMP-TRACK"), lena);
        post("/api/v1/organizations/" + lamps + "/orders/" + lampOrder + "/deliver", null, lena);

        // 5. Omar's shop is suspended: nothing new sells, and he cancels the paid order with a refund.
        post("/api/v1/admin/organizations/" + books + "/suspend", Map.of("reason", "Counterfeit report"), admin);
        assertThat(client.get("/api/v1/catalog/products/" + book, null).getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
        post("/api/v1/organizations/" + books + "/orders/" + bookOrder + "/cancel",
                Map.of("reason", "Cannot fulfil during review"), omar);
        assertThat(stock(book)).as("the units went back").isEqualTo(10);

        // 6. Ada's history tells the whole story.
        JsonNode history = data(client.get("/api/v1/orders", ada));
        assertThat(history.get("content").findValuesAsText("status")).containsExactlyInAnyOrder("DELIVERED", "CANCELLED");
        assertThat(data(client.get("/api/v1/orders/" + bookOrder, ada)).get("cancelReason").asText())
                .isEqualTo("Cannot fulfil during review");

        // 7. Log audit: the story is visible by ID; addresses, tokens, reasons and names are not.
        String output = logs.getAll();
        assertThat(output).contains("Checkout completed: checkoutId=" + checkoutId)
                .contains("Checkout paid: checkoutId=" + checkoutId)
                .contains("Order shipped: organizationId=" + lamps + ", orderId=" + lampOrder)
                .contains("Seller products taken off the storefront: organizationId=" + books)
                .contains("Order cancelled by the seller: organizationId=" + books + ", orderId=" + bookOrder)
                .contains("Simulated refund");
        for (String secret : List.of(street, "Ada Lovelace", "tok_visa", "Counterfeit report", "Cannot fulfil during review")) {
            assertThat(output).as("logs must not contain personal data, tokens or reasons").doesNotContain(secret);
        }
    }
}
