package com.ecommerce.backend;

import static com.ecommerce.backend.support.AuthTestClient.PASSWORD;
import static com.ecommerce.backend.support.AuthTestClient.accessToken;
import static org.assertj.core.api.Assertions.assertThat;

import com.ecommerce.backend.auth.service.TokenCleanup;
import com.ecommerce.backend.notification.service.NotificationRetention;
import com.ecommerce.backend.order.service.UnpaidOrderExpiry;
import com.ecommerce.backend.support.AuthTestClient;
import com.ecommerce.backend.support.RecordingEmailSender;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import javax.sql.DataSource;
import net.javacrumbs.shedlock.core.DefaultLockingTaskExecutor;
import net.javacrumbs.shedlock.core.LockConfiguration;
import net.javacrumbs.shedlock.provider.jdbctemplate.JdbcTemplateLockProvider;
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

/** The background jobs, called directly (scheduling is off in tests), against real data. */
class JobsIT extends AbstractIntegrationTest {

    private static final String DOMAIN = "@jobs.test";

    @Autowired
    private TestRestTemplate rest;

    @Autowired
    private RecordingEmailSender mail;

    @Autowired
    private JdbcTemplate jdbc;

    @Autowired
    private ObjectMapper mapper;

    @Autowired
    private UnpaidOrderExpiry unpaidOrders;

    @Autowired
    private TokenCleanup tokens;

    @Autowired
    private NotificationRetention notifications;

    @Autowired
    private DataSource dataSource;

    private AuthTestClient client;
    private final List<UUID> created = new ArrayList<>();
    private String slug;
    private String productId;
    private String customerToken;
    private UUID customerId;
    private String addressId;

    @BeforeEach
    void setUp() {
        client = new AuthTestClient(rest, mail);
        String admin = account("ADMIN");
        String owner = account();
        customerToken = account();
        customerId = created.get(created.size() - 1);
        slug = "it-jobs-" + UUID.randomUUID().toString().substring(0, 8);
        String shop = data(client.postJson("/api/v1/organizations", Map.of("slug", slug, "name", "Jobs Shop"), owner))
                .get("id").asText();
        data(client.send(HttpMethod.POST, "/api/v1/admin/organizations/" + shop + "/approve", null, admin));
        String base = "/api/v1/organizations/" + shop + "/products";
        productId = data(client.postJson(base, Map.of("sku", "JOB-1", "name", "Lamp", "price", "10.00"), owner)).get("id").asText();
        data(client.send(HttpMethod.POST, base + "/" + productId + "/publish", null, owner));
        data(client.postJson(base + "/" + productId + "/stock", Map.of("delta", 10), owner));
        addressId = data(client.postJson("/api/v1/profile/addresses", Map.of("recipientName", "Jobs", "line1", "Street 1",
                "city", "Berlin", "postalCode", "10117", "countryCode", "DE"), customerToken)).get("id").asText();
    }

    @AfterEach
    void cleanUp() {
        created.forEach(id -> {
            jdbc.update("DELETE FROM orders WHERE customer_id = ?", id);
            jdbc.update("DELETE FROM checkouts WHERE customer_id = ?", id);
            jdbc.update("DELETE FROM cart_items WHERE user_id = ?", id);
        });
        jdbc.update("DELETE FROM products WHERE organization_id = (SELECT id FROM organizations WHERE slug = ?)", slug);
        jdbc.update("DELETE FROM organizations WHERE slug = ?", slug);
        created.forEach(id -> jdbc.update("DELETE FROM users WHERE id = ?", id));
        jdbc.update("DELETE FROM shedlock WHERE name LIKE 'it-jobs-%'");
    }

    private String account(String... roles) {
        String email = "j-" + UUID.randomUUID() + DOMAIN;
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

    private JsonNode checkout(int quantity) {
        data(client.send(HttpMethod.PUT, "/api/v1/cart/items/" + productId, Map.of("quantity", quantity), customerToken));
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        headers.setBearerAuth(customerToken);
        headers.set("Idempotency-Key", UUID.randomUUID().toString());
        return data(rest.exchange("/api/v1/checkout", HttpMethod.POST, new HttpEntity<>(Map.of("shippingAddressId", addressId,
                "expectedTotal", String.valueOf(10 * quantity)), headers), String.class));
    }

    private void age(String orderId, Duration by) {
        jdbc.update("UPDATE orders SET created_at = created_at - make_interval(secs => ?) WHERE id = ?::uuid",
                (double) by.toSeconds(), orderId);
    }

    private String status(String orderId) {
        return jdbc.queryForObject("SELECT status FROM orders WHERE id = ?::uuid", String.class, orderId);
    }

    private int stock() {
        return jdbc.queryForObject("SELECT stock FROM products WHERE id = ?::uuid", Integer.class, productId);
    }

    @Test
    void anUnpaidOrderIsCancelledAfterTheTimeoutAndItsStockReturnsWhilePaidAndFreshOnesStay() {
        String abandoned = checkout(3).at("/orders/0/id").asText();
        JsonNode paidCheckout = checkout(1);
        data(client.postJson("/api/v1/checkouts/" + paidCheckout.get("id").asText() + "/pay",
                Map.of("paymentToken", "tok_visa"), customerToken));
        String paid = paidCheckout.at("/orders/0/id").asText();
        String fresh = checkout(2).at("/orders/0/id").asText();
        age(abandoned, Duration.ofMinutes(31));
        age(paid, Duration.ofMinutes(31));
        assertThat(stock()).isEqualTo(4);

        assertThat(unpaidOrders.expireDue()).isGreaterThanOrEqualTo(1);

        assertThat(status(abandoned)).isEqualTo("CANCELLED");
        assertThat(status(paid)).isEqualTo("PAID");
        assertThat(status(fresh)).isEqualTo("PENDING_PAYMENT");
        assertThat(stock()).as("the abandoned units are back").isEqualTo(7);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM notifications WHERE user_id = ? AND type = 'ORDER_EXPIRED'",
                Long.class, customerId)).isOne();
        assertThat(unpaidOrders.expireDue()).as("idempotent: nothing left to expire").isZero();
    }

    @Test
    void spentTokensAreDeletedAndLiveOnesKept() {
        UUID user = created.get(0);
        UUID family = UUID.randomUUID();
        UUID expired = UUID.randomUUID();
        UUID live = UUID.randomUUID();
        jdbc.update("""
                INSERT INTO refresh_tokens (id, user_id, family_id, token_hash, issued_at, expires_at)
                VALUES (?, ?, ?, encode(sha256(convert_to(?::text, 'UTF8')), 'hex'), now() - interval '10 days', now() - interval '2 days')""",
                expired, user, family, expired);
        jdbc.update("""
                INSERT INTO refresh_tokens (id, user_id, family_id, token_hash, issued_at, expires_at)
                VALUES (?, ?, ?, encode(sha256(convert_to(?::text, 'UTF8')), 'hex'), now(), now() + interval '7 days')""",
                live, user, family, live);

        tokens.cleanUp();

        assertThat(jdbc.queryForObject("SELECT count(*) FROM refresh_tokens WHERE id = ?", Long.class, expired)).isZero();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM refresh_tokens WHERE id = ?", Long.class, live)).isOne();
    }

    @Test
    void notificationsOlderThanTheRetentionAreDeleted() {
        JsonNode order = checkout(1);
        data(client.postJson("/api/v1/checkouts/" + order.get("id").asText() + "/pay", Map.of("paymentToken", "tok_visa"),
                customerToken));
        jdbc.update("UPDATE notifications SET created_at = now() - interval '91 days' WHERE user_id = ?", customerId);
        jdbc.update("""
                INSERT INTO notifications (id, user_id, type, title, body, link)
                VALUES (?, ?, 'ORDER_SHIPPED', 'Recent', 'Recent', '/orders')""", UUID.randomUUID(), customerId);

        notifications.deleteExpired();

        assertThat(jdbc.queryForList("SELECT title FROM notifications WHERE user_id = ?", String.class, customerId))
                .containsExactly("Recent");
    }

    /** With two servers, only one runs a job's round: the other finds the lock row taken. */
    @Test
    void aJobRoundRunsOnOneServerOnly() throws Exception {
        JdbcTemplateLockProvider provider = new JdbcTemplateLockProvider(JdbcTemplateLockProvider.Configuration.builder()
                .withJdbcTemplate(new JdbcTemplate(dataSource)).usingDbTime().build());
        DefaultLockingTaskExecutor serverA = new DefaultLockingTaskExecutor(provider);
        DefaultLockingTaskExecutor serverB = new DefaultLockingTaskExecutor(provider);
        String name = "it-jobs-" + UUID.randomUUID().toString().substring(0, 8);
        LockConfiguration lock = new LockConfiguration(Instant.now(), name, Duration.ofMinutes(1), Duration.ZERO);
        AtomicInteger runs = new AtomicInteger();
        CountDownLatch inside = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);

        ExecutorService pool = Executors.newFixedThreadPool(1);
        try {
            pool.submit(() -> {
                serverA.executeWithLock((Runnable) () -> {
                    runs.incrementAndGet();
                    inside.countDown();
                    try {
                        release.await(10, TimeUnit.SECONDS);
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                    }
                }, lock);
                return null;
            });
            assertThat(inside.await(10, TimeUnit.SECONDS)).isTrue();
            serverB.executeWithLock((Runnable) runs::incrementAndGet, lock);
            assertThat(runs).as("server B skipped the round").hasValue(1);
        } finally {
            release.countDown();
            pool.shutdown();
        }
    }
}
