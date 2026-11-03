package com.ecommerce.backend.notification;

import static com.ecommerce.backend.support.AuthTestClient.PASSWORD;
import static com.ecommerce.backend.support.AuthTestClient.accessToken;
import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import com.ecommerce.backend.AbstractIntegrationTest;
import com.ecommerce.backend.common.mail.EmailMessage;
import com.ecommerce.backend.support.AuthTestClient;
import com.ecommerce.backend.support.RecordingEmailSender;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
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

/** Orders producing inbox entries and emails, preferences, read state, isolation and erasure. */
class NotificationIT extends AbstractIntegrationTest {

    private static final String DOMAIN = "@notifications.test";

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
    private Person seller;
    private Person customer;
    private String shopId;
    private String productId;

    record Person(UUID id, String email, String token) {
    }

    @BeforeEach
    void setUp() {
        client = new AuthTestClient(rest, mail);
        Person admin = person("admin", "ADMIN");
        seller = person("seller");
        customer = person("customer");
        String slug = "it-nt-" + UUID.randomUUID().toString().substring(0, 8);
        slugs.add(slug);
        shopId = data(client.postJson("/api/v1/organizations", Map.of("slug", slug, "name", "Note Shop"), seller.token()))
                .get("id").asText();
        data(client.send(HttpMethod.POST, "/api/v1/admin/organizations/" + shopId + "/approve", null, admin.token()));
        String base = "/api/v1/organizations/" + shopId + "/products";
        productId = data(client.postJson(base, Map.of("sku", "NOTE-1", "name", "Notebook", "price", "4.00"), seller.token()))
                .get("id").asText();
        data(client.send(HttpMethod.POST, base + "/" + productId + "/publish", null, seller.token()));
        data(client.postJson(base + "/" + productId + "/stock", Map.of("delta", 10), seller.token()));
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
        created.forEach(id -> jdbc.update("DELETE FROM users WHERE id = ?", id)); // cascades to notifications
    }

    private Person person(String name, String... roles) {
        String email = name + "-" + UUID.randomUUID() + DOMAIN;
        client.registerAndVerify(email);
        UUID id = jdbc.queryForObject("SELECT id FROM users WHERE email = ?", UUID.class, email);
        created.add(id);
        for (String role : roles) {
            jdbc.update("INSERT INTO user_roles (user_id, role_id) SELECT ?, id FROM roles WHERE code = ?", id, role);
        }
        return new Person(id, email, accessToken(client.login(email, PASSWORD)));
    }

    private JsonNode data(ResponseEntity<String> response) {
        assertThat(response.getStatusCode().is2xxSuccessful()).as(response.getBody()).isTrue();
        try {
            return response.getBody() == null ? null : mapper.readTree(response.getBody()).get("data");
        } catch (JsonProcessingException e) {
            throw new IllegalStateException(e);
        }
    }

    /** Buys one notebook and pays; returns the order ID. */
    private String paidOrder() {
        String address = data(client.postJson("/api/v1/profile/addresses", Map.of("recipientName", "Customer",
                "line1", "Note Street 1", "city", "Berlin", "postalCode", "10117", "countryCode", "DE"), customer.token()))
                .get("id").asText();
        data(client.send(HttpMethod.PUT, "/api/v1/cart/items/" + productId, Map.of("quantity", 1), customer.token()));
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        headers.setBearerAuth(customer.token());
        headers.set("Idempotency-Key", UUID.randomUUID().toString());
        JsonNode checkout = data(rest.exchange("/api/v1/checkout", HttpMethod.POST, new HttpEntity<>(Map.of(
                "shippingAddressId", address, "expectedTotal", "4.00"), headers), String.class));
        data(client.postJson("/api/v1/checkouts/" + checkout.get("id").asText() + "/pay", Map.of("paymentToken", "tok_visa"),
                customer.token()));
        return checkout.at("/orders/0/id").asText();
    }

    private JsonNode inbox(Person person) {
        return data(client.get("/api/v1/notifications", person.token())).get("content");
    }

    private List<EmailMessage> emails(Person person, String subject) {
        return mail.sentTo(person.email()).stream().filter(m -> m.subject().equals(subject)).toList();
    }

    @Test
    void aPaidOrderReachesBothSidesInTheInboxAndByEmail() {
        String orderId = paidOrder();

        assertThat(inbox(customer).findValuesAsText("type")).containsExactly("ORDER_CONFIRMED");
        assertThat(inbox(seller).findValuesAsText("type")).containsExactly("NEW_ORDER");
        assertThat(inbox(seller).get(0).get("link").asText()).isEqualTo("/organizations/" + shopId + "/orders/" + orderId);
        await().atMost(Duration.ofSeconds(10)).until(() -> !emails(customer, "Order confirmed").isEmpty()
                && !emails(seller, "New order to ship").isEmpty());
        assertThat(emails(customer, "Order confirmed").get(0).body()).contains("/orders/" + orderId)
                .doesNotContain("Note Street");

        // Shipping tells the customer; the unread badge counts both.
        data(client.postJson("/api/v1/organizations/" + shopId + "/orders/" + orderId + "/ship",
                Map.of("trackingNumber", "TRACK-9"), seller.token()));
        assertThat(data(client.get("/api/v1/notifications/unread-count", customer.token())).get("unread").asLong())
                .isEqualTo(2);
        assertThat(inbox(customer).get(0).get("body").asText()).contains("TRACK-9");
    }

    @Test
    void readStateIsPerPersonAndSomeoneElsesNotificationIs404() {
        paidOrder();
        String notification = inbox(customer).get(0).get("id").asText();

        assertThat(client.send(HttpMethod.POST, "/api/v1/notifications/" + notification + "/read", null, seller.token())
                .getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(client.send(HttpMethod.POST, "/api/v1/notifications/" + notification + "/read", null, customer.token())
                .getStatusCode()).isEqualTo(HttpStatus.NO_CONTENT);
        assertThat(client.send(HttpMethod.POST, "/api/v1/notifications/" + notification + "/read", null, customer.token())
                .getStatusCode()).as("idempotent").isEqualTo(HttpStatus.NO_CONTENT);
        assertThat(inbox(customer).get(0).get("read").asBoolean()).isTrue();
        assertThat(data(client.get("/api/v1/notifications?unreadOnly=true", seller.token())).get("totalElements").asLong())
                .as("the seller's own notification is still unread").isOne();
        assertThat(data(client.postJson("/api/v1/notifications/read-all", null, seller.token())).get("marked").asInt()).isOne();
    }

    @Test
    void switchedOffEmailsAreNotSentButTheInboxStillFills() {
        assertThat(data(client.get("/api/v1/notifications/preferences", customer.token())).get("orderUpdatesEmail")
                .asBoolean()).isTrue();
        data(client.send(HttpMethod.PUT, "/api/v1/notifications/preferences",
                Map.of("orderUpdatesEmail", false, "sellerOrdersEmail", true), customer.token()));

        paidOrder();

        await().atMost(Duration.ofSeconds(10)).until(() -> !emails(seller, "New order to ship").isEmpty());
        assertThat(emails(customer, "Order confirmed")).isEmpty();
        assertThat(inbox(customer).findValuesAsText("type")).containsExactly("ORDER_CONFIRMED");
    }

    @Test
    void anErasedCustomerLosesTheInboxAndGetsNothingNew() {
        String orderId = paidOrder();
        assertThat(client.postJson("/api/v1/auth/me/erase", Map.of("password", PASSWORD), customer.token()).getStatusCode())
                .isEqualTo(HttpStatus.NO_CONTENT);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM notifications WHERE user_id = ?", Long.class, customer.id()))
                .isZero();

        // The seller still ships the paid order; nobody notifies an erased account.
        data(client.postJson("/api/v1/organizations/" + shopId + "/orders/" + orderId + "/ship", Map.of(), seller.token()));
        assertThat(jdbc.queryForObject("SELECT count(*) FROM notifications WHERE user_id = ?", Long.class, customer.id()))
                .isZero();
    }
}
