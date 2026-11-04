package com.ecommerce.backend.catalog;

import static com.ecommerce.backend.support.AuthTestClient.PASSWORD;
import static com.ecommerce.backend.support.AuthTestClient.accessToken;
import static org.assertj.core.api.Assertions.assertThat;

import com.ecommerce.backend.AbstractIntegrationTest;
import com.ecommerce.backend.support.AuthTestClient;
import com.ecommerce.backend.support.RecordingEmailSender;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;

/** The read cache against a real Redis: what is cached, and that changes take effect at once. */
class CatalogCacheIT extends AbstractIntegrationTest {

    private static final String DOMAIN = "@catalog-cache.test";

    @Autowired
    private TestRestTemplate rest;

    @Autowired
    private RecordingEmailSender mail;

    @Autowired
    private JdbcTemplate jdbc;

    @Autowired
    private ObjectMapper mapper;

    @Autowired
    private StringRedisTemplate redis;

    private AuthTestClient client;
    private String adminToken;
    private String slug;
    private String categorySlug;

    @BeforeEach
    void setUp() {
        client = new AuthTestClient(rest, mail);
        String admin = "admin-" + UUID.randomUUID() + DOMAIN;
        client.registerAndVerify(admin);
        jdbc.update("INSERT INTO user_roles (user_id, role_id) SELECT u.id, r.id FROM users u, roles r "
                + "WHERE u.email = ? AND r.code = 'ADMIN'", admin);
        adminToken = accessToken(client.login(admin, PASSWORD));
    }

    @AfterEach
    void cleanUp() {
        if (slug != null) {
            jdbc.update("DELETE FROM products WHERE organization_id = (SELECT id FROM organizations WHERE slug = ?)", slug);
            jdbc.update("DELETE FROM organizations WHERE slug = ?", slug);
        }
        if (categorySlug != null) {
            jdbc.update("DELETE FROM categories WHERE slug = ?", categorySlug);
        }
        jdbc.update("DELETE FROM users WHERE email LIKE '%" + DOMAIN + "'");
    }

    private String data(ResponseEntity<String> response, String field) {
        assertThat(response.getStatusCode().is2xxSuccessful()).as(response.getBody()).isTrue();
        try {
            return mapper.readTree(response.getBody()).at("/data/" + field).asText();
        } catch (JsonProcessingException e) {
            throw new IllegalStateException(e);
        }
    }

    @Test
    void theCategoryListIsCachedAndAChangeShowsAtOnce() {
        redis.delete("cache:categories");
        rest.getForEntity("/api/v1/catalog/categories", String.class);
        assertThat(redis.hasKey("cache:categories")).as("filled by the first read").isTrue();

        categorySlug = "it-cache-" + UUID.randomUUID().toString().substring(0, 8);
        data(client.postJson("/api/v1/admin/categories", Map.of("slug", categorySlug, "name", "Cached"), adminToken), "id");

        assertThat(redis.hasKey("cache:categories")).as("evicted after the commit").isFalse();
        assertThat(rest.getForEntity("/api/v1/catalog/categories", String.class).getBody()).contains(categorySlug);
    }

    /** Browsers and CDNs may keep the list briefly, then revalidate it for a bodiless 304. */
    @Test
    void theCategoryListIsHttpCacheableAndRevalidatesWithItsEtag() {
        ResponseEntity<String> first = rest.getForEntity("/api/v1/catalog/categories", String.class);
        String etag = first.getHeaders().getETag();
        assertThat(first.getHeaders().getCacheControl()).isEqualTo("max-age=300, public");
        assertThat(etag).startsWith("\"").endsWith("\"");

        HttpHeaders revalidate = new HttpHeaders();
        revalidate.setIfNoneMatch(etag);
        ResponseEntity<String> unchanged = rest.exchange("/api/v1/catalog/categories", HttpMethod.GET,
                new HttpEntity<>(revalidate), String.class);
        assertThat(unchanged.getStatusCode()).isEqualTo(HttpStatus.NOT_MODIFIED);
        assertThat(unchanged.getBody()).isNull();
        assertThat(unchanged.getHeaders().getETag()).isEqualTo(etag);

        categorySlug = "it-cache-" + UUID.randomUUID().toString().substring(0, 8);
        data(client.postJson("/api/v1/admin/categories", Map.of("slug", categorySlug, "name", "Revalidated"), adminToken), "id");
        ResponseEntity<String> changed = rest.exchange("/api/v1/catalog/categories", HttpMethod.GET,
                new HttpEntity<>(revalidate), String.class);
        assertThat(changed.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(changed.getHeaders().getETag()).isNotEqualTo(etag);
        assertThat(changed.getBody()).contains(categorySlug);
    }

    @Test
    void personalReadsAreNeverMarkedPublic() {
        ResponseEntity<String> cart = client.send(HttpMethod.GET, "/api/v1/cart", null, adminToken);
        assertThat(cart.getHeaders().getCacheControl()).contains("no-store");
    }

    @Test
    void aSuspendedSellersCachedProductPageDisappearsAtOnce() {
        String owner = "owner-" + UUID.randomUUID() + DOMAIN;
        client.registerAndVerify(owner);
        String ownerToken = accessToken(client.login(owner, PASSWORD));
        slug = "it-cache-" + UUID.randomUUID().toString().substring(0, 8);
        String shop = data(client.postJson("/api/v1/organizations", Map.of("slug", slug, "name", "Cache Shop"), ownerToken), "id");
        data(client.send(HttpMethod.POST, "/api/v1/admin/organizations/" + shop + "/approve", null, adminToken), "id");
        String base = "/api/v1/organizations/" + shop + "/products";
        String product = data(client.postJson(base, Map.of("sku", "C-1", "name", "Cached mug", "price", 5), ownerToken), "id");
        data(client.send(HttpMethod.POST, base + "/" + product + "/publish", null, ownerToken), "id");

        assertThat(rest.getForEntity("/api/v1/catalog/products/" + product, String.class).getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(redis.hasKey("cache:product:" + product)).isTrue();

        data(client.postJson("/api/v1/admin/organizations/" + shop + "/suspend", Map.of("reason", "Review"), adminToken), "id");

        assertThat(rest.getForEntity("/api/v1/catalog/products/" + product, String.class).getStatusCode())
                .as("not served from the cache for 30 seconds").isEqualTo(HttpStatus.NOT_FOUND);
    }
}
