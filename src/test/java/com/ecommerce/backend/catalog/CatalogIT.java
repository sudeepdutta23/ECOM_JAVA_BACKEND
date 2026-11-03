package com.ecommerce.backend.catalog;

import static com.ecommerce.backend.support.AuthTestClient.PASSWORD;
import static com.ecommerce.backend.support.AuthTestClient.accessToken;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.ecommerce.backend.AbstractIntegrationTest;
import com.ecommerce.backend.org.OrgPermission;
import com.ecommerce.backend.org.service.OrganizationGuard;
import com.ecommerce.backend.support.AuthTestClient;
import com.ecommerce.backend.support.RecordingEmailSender;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.support.TransactionTemplate;

/** Sellers building a catalogue, customers browsing it, and the seller's status deciding what is on sale. */
class CatalogIT extends AbstractIntegrationTest {

    private static final String DOMAIN = "@catalog.test";

    @Autowired
    private TestRestTemplate rest;

    @Autowired
    private RecordingEmailSender mail;

    @Autowired
    private JdbcTemplate jdbc;

    @Autowired
    private ObjectMapper mapper;

    @Autowired
    private TransactionTemplate tx;

    @Autowired
    private OrganizationGuard guard;

    private AuthTestClient client;
    private final List<String> slugs = new ArrayList<>();
    private final List<String> categorySlugs = new ArrayList<>();
    private String ownerToken;
    private UUID ownerId;
    private String adminToken;
    private String orgId;
    private String slug;

    @BeforeEach
    void setUp() {
        client = new AuthTestClient(rest, mail);
        String owner = account();
        ownerId = jdbc.queryForObject("SELECT id FROM users WHERE email = ?", UUID.class, owner);
        ownerToken = token(owner);
        String admin = account();
        jdbc.update("INSERT INTO user_roles (user_id, role_id) SELECT u.id, r.id FROM users u, roles r "
                + "WHERE u.email = ? AND r.code = 'ADMIN'", admin);
        adminToken = token(admin);
        slug = "it-cat-" + UUID.randomUUID().toString().substring(0, 8);
        slugs.add(slug);
        orgId = data(client.postJson("/api/v1/organizations", Map.of("slug", slug, "name", "Mug Shop"), ownerToken))
                .get("id").asText();
    }

    @AfterEach
    void cleanUp() {
        slugs.forEach(s -> {
            jdbc.update("DELETE FROM products WHERE organization_id = (SELECT id FROM organizations WHERE slug = ?)", s);
            jdbc.update("DELETE FROM organizations WHERE slug = ?", s);
        });
        categorySlugs.forEach(s -> jdbc.update("DELETE FROM categories WHERE slug = ?", s));
        jdbc.update("DELETE FROM users WHERE email LIKE '%" + DOMAIN + "'");
    }

    private String account() {
        String email = "c-" + UUID.randomUUID() + DOMAIN;
        client.registerAndVerify(email);
        return email;
    }

    private String token(String email) {
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

    private String products() {
        return "/api/v1/organizations/" + orgId + "/products";
    }

    private String publishedProduct(String sku, int stock) {
        String id = data(client.postJson(products(), Map.of("sku", sku, "name", "Mug " + sku, "price", "12.50"), ownerToken))
                .get("id").asText();
        data(client.send(HttpMethod.POST, products() + "/" + id + "/publish", null, ownerToken));
        if (stock > 0) {
            data(client.postJson(products() + "/" + id + "/stock", Map.of("delta", stock), ownerToken));
        }
        return id;
    }

    private ResponseEntity<String> storefront(String productId) {
        return client.get("/api/v1/catalog/products/" + productId, null);
    }

    private void admin(String action) {
        Object body = action.equals("suspend") ? Map.of("reason", "Review") : null;
        data(client.send(HttpMethod.POST, "/api/v1/admin/organizations/" + orgId + "/" + action, body, adminToken));
    }

    @Test
    void onlyPublishedProductsOfApprovedActiveSellersAreOnSale() {
        String product = publishedProduct("MUG-1", 3);
        assertThat(storefront(product).getStatusCode()).as("seller not approved yet").isEqualTo(HttpStatus.NOT_FOUND);

        admin("approve");
        JsonNode onSale = data(storefront(product));
        assertThat(onSale.get("price").decimalValue()).isEqualByComparingTo("12.50");
        assertThat(onSale.get("inStock").asBoolean()).isTrue();
        assertThat(onSale.has("stock")).as("exact stock stays private").isFalse();
        assertThat(onSale.at("/seller/slug").asText()).isEqualTo(slug);

        admin("suspend");
        assertThat(storefront(product).getStatusCode()).as("suspended seller").isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(client.get("/api/v1/catalog/products?seller=" + slug, null).getBody()).doesNotContain(product);

        admin("reactivate");
        assertThat(storefront(product).getStatusCode()).isEqualTo(HttpStatus.OK);

        data(client.send(HttpMethod.POST, products() + "/" + product + "/archive", null, ownerToken));
        assertThat(storefront(product).getStatusCode()).as("archived").isEqualTo(HttpStatus.NOT_FOUND);

        admin("close");
        assertThat(data(client.get(products(), ownerToken)).get("totalElements").asLong())
                .as("a closed seller still reads its catalogue").isOne();
        assertThat(client.send(HttpMethod.POST, products() + "/" + product + "/publish", null, ownerToken).getBody())
                .as("a closed seller cannot sell again").contains("ORG_INVALID_STATUS");
    }

    @Test
    void theStorefrontFiltersByCategoryAndSellerAndUnknownSlugsGiveEmptyPages() {
        admin("approve");
        String categorySlug = "it-cat-" + UUID.randomUUID().toString().substring(0, 8);
        categorySlugs.add(categorySlug);
        String categoryId = data(client.postJson("/api/v1/admin/categories", Map.of("slug", categorySlug, "name", "Mugs"),
                adminToken)).get("id").asText();
        String inCategory = data(client.postJson(products(), Map.of("sku", "CAT-1", "name", "Categorised", "price", 5,
                "categoryId", categoryId), ownerToken)).get("id").asText();
        data(client.send(HttpMethod.POST, products() + "/" + inCategory + "/publish", null, ownerToken));
        String other = publishedProduct("OTHER-1", 0);

        JsonNode byCategory = data(client.get("/api/v1/catalog/products?category=" + categorySlug, null));
        assertThat(byCategory.get("content").findValuesAsText("id")).containsExactly(inCategory);
        assertThat(byCategory.at("/content/0/category/name").asText()).isEqualTo("Mugs");
        assertThat(data(client.get("/api/v1/catalog/products?seller=" + slug, null)).get("content").findValuesAsText("id"))
                .containsExactlyInAnyOrder(inCategory, other);
        assertThat(data(client.get("/api/v1/catalog/products?category=no-such-category", null)).get("totalElements").asLong())
                .isZero();

        assertThat(client.send(HttpMethod.DELETE, "/api/v1/admin/categories/" + categoryId, null, adminToken).getBody())
                .contains("CATALOG_CATEGORY_IN_USE");
    }

    @Test
    void staffManageTheCatalogueButOutsidersAndCustomersCannot() {
        String staff = account();
        jdbc.update("INSERT INTO organization_members (organization_id, user_id, role) "
                + "SELECT ?::uuid, id, 'STAFF' FROM users WHERE email = ?", orgId, staff);
        String staffToken = token(staff);
        String outsiderToken = token(account());

        assertThat(client.postJson(products(), Map.of("sku", "STAFF-1", "name", "By staff", "price", 1), staffToken)
                .getStatusCode()).isEqualTo(HttpStatus.CREATED);
        assertThat(client.postJson(products(), Map.of("sku", "OUT-1", "name", "By outsider", "price", 1), outsiderToken)
                .getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(client.postJson(products(), Map.of("sku", "staff-1", "name", "Duplicate", "price", 1), ownerToken)
                .getBody()).as("SKUs are case-insensitive per seller").contains("CATALOG_SKU_TAKEN");
    }

    /** Ten parallel sales of one unit against a stock of five: exactly five succeed, stock ends at zero. */
    @Test
    void parallelStockChangesAllCountAndStockNeverGoesNegative() throws Exception {
        String product = publishedProduct("FEW-1", 5);
        ExecutorService pool = Executors.newFixedThreadPool(10);
        try {
            List<Callable<ResponseEntity<String>>> removals = new ArrayList<>();
            for (int i = 0; i < 10; i++) {
                removals.add(() -> client.postJson(products() + "/" + product + "/stock", Map.of("delta", -1), ownerToken));
            }
            int succeeded = 0;
            for (Future<ResponseEntity<String>> result : pool.invokeAll(removals)) {
                succeeded += result.get().getStatusCode() == HttpStatus.OK ? 1 : 0;
            }
            assertThat(succeeded).isEqualTo(5);
        } finally {
            pool.shutdownNow();
        }
        assertThat(jdbc.queryForObject("SELECT stock FROM products WHERE id = ?::uuid", Integer.class, product)).isZero();
    }

    /**
     * The Phase 4 accepted risk, closed: a seller write in progress holds the organisation FOR SHARE,
     * so a suspension waits for it, and its products leave the storefront in the suspension's transaction.
     */
    @Test
    void aSuspensionWaitsForASellerWriteInProgressAndThenHidesWhatItWrote() throws Exception {
        admin("approve");
        CountDownLatch written = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        ExecutorService pool = Executors.newFixedThreadPool(2);
        UUID productId = UUID.randomUUID();
        try {
            // What ProductService.create and publish do, paused before the commit.
            Future<?> write = pool.submit(() -> tx.executeWithoutResult(status -> {
                guard.requireForSellerWrite(UUID.fromString(orgId), ownerId, OrgPermission.CATALOG_MANAGE);
                jdbc.update("""
                        INSERT INTO products (id, organization_id, sku, name, price_minor, currency, stock, status, seller_active)
                        VALUES (?, ?::uuid, 'RACE-1', 'Race', 100, 'EUR', 1, 'ACTIVE', true)""", productId, orgId);
                written.countDown();
                try {
                    assertThat(release.await(10, TimeUnit.SECONDS)).isTrue();
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    throw new IllegalStateException(e);
                }
            }));
            assertThat(written.await(10, TimeUnit.SECONDS)).isTrue();

            Future<?> suspension = pool.submit(() -> admin("suspend"));
            assertThatThrownBy(() -> suspension.get(1, TimeUnit.SECONDS)).as("the suspension waits for the lock")
                    .isInstanceOf(TimeoutException.class);

            release.countDown();
            write.get(10, TimeUnit.SECONDS);
            suspension.get(10, TimeUnit.SECONDS);
        } finally {
            release.countDown();
            pool.shutdownNow();
        }
        assertThat(jdbc.queryForObject("SELECT seller_active FROM products WHERE id = ?", Boolean.class, productId))
                .as("the product written meanwhile left the storefront with the suspension").isFalse();
        assertThat(storefront(productId.toString()).getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
    }
}
