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
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;

/** The cart over real HTTP: many sellers, current prices, lines that stop being buyable, erasure. */
class CartIT extends AbstractIntegrationTest {

    private static final String DOMAIN = "@cart.test";
    private static final String CART = "/api/v1/cart";

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

    private record Account(UUID id, String token) {
    }

    private record Shop(String id, String ownerToken) {
    }

    @BeforeEach
    void setUp() {
        client = new AuthTestClient(rest, mail);
        Account admin = account();
        jdbc.update("INSERT INTO user_roles (user_id, role_id) SELECT ?, id FROM roles WHERE code = 'ADMIN'", admin.id());
        adminToken = accessToken(client.login(emailOf(admin), PASSWORD));
    }

    @AfterEach
    void cleanUp() {
        created.forEach(id -> jdbc.update("DELETE FROM cart_items WHERE user_id = ?", id));
        slugs.forEach(s -> {
            jdbc.update("DELETE FROM cart_items WHERE product_id IN (SELECT p.id FROM products p JOIN organizations o "
                    + "ON o.id = p.organization_id WHERE o.slug = ?)", s);
            jdbc.update("DELETE FROM products WHERE organization_id = (SELECT id FROM organizations WHERE slug = ?)", s);
            jdbc.update("DELETE FROM organizations WHERE slug = ?", s);
        });
        created.forEach(id -> jdbc.update("DELETE FROM users WHERE id = ?", id));
    }

    private Account account() {
        String email = "cart-" + UUID.randomUUID() + DOMAIN;
        client.registerAndVerify(email);
        UUID id = jdbc.queryForObject("SELECT id FROM users WHERE email = ?", UUID.class, email);
        created.add(id);
        return new Account(id, accessToken(client.login(email, PASSWORD)));
    }

    private String emailOf(Account account) {
        return jdbc.queryForObject("SELECT email FROM users WHERE id = ?", String.class, account.id());
    }

    private JsonNode data(ResponseEntity<String> response) {
        assertThat(response.getStatusCode().is2xxSuccessful()).as(response.getBody()).isTrue();
        try {
            return mapper.readTree(response.getBody()).get("data");
        } catch (JsonProcessingException e) {
            throw new IllegalStateException(e);
        }
    }

    /** An approved seller. */
    private Shop shop() {
        Account owner = account();
        String slug = "it-cart-" + UUID.randomUUID().toString().substring(0, 8);
        slugs.add(slug);
        String id = data(client.postJson("/api/v1/organizations", Map.of("slug", slug, "name", "Shop " + slug),
                owner.token())).get("id").asText();
        data(client.send(HttpMethod.POST, "/api/v1/admin/organizations/" + id + "/approve", null, adminToken));
        return new Shop(id, owner.token());
    }

    private String product(Shop shop, String price, int stock) {
        String base = "/api/v1/organizations/" + shop.id() + "/products";
        String id = data(client.postJson(base, Map.of("sku", "SKU-" + UUID.randomUUID().toString().substring(0, 6),
                "name", "Product", "price", price), shop.ownerToken())).get("id").asText();
        data(client.send(HttpMethod.POST, base + "/" + id + "/publish", null, shop.ownerToken()));
        data(client.postJson(base + "/" + id + "/stock", Map.of("delta", stock), shop.ownerToken()));
        return id;
    }

    private ResponseEntity<String> put(Account customer, String productId, int quantity) {
        return client.send(HttpMethod.PUT, CART + "/items/" + productId, Map.of("quantity", quantity), customer.token());
    }

    @Test
    void aCartHoldsProductsOfManySellersAtCurrentPrices() {
        Shop mugs = shop();
        Shop books = shop();
        String mug = product(mugs, "12.50", 10);
        String book = product(books, "20.00", 10);
        Account customer = account();

        data(put(customer, mug, 2));
        JsonNode cart = data(put(customer, book, 1));
        assertThat(cart.get("total").decimalValue()).isEqualByComparingTo("45.00");
        assertThat(cart.get("readyForCheckout").asBoolean()).isTrue();
        assertThat(cart.get("lines").findValuesAsText("productId")).containsExactly(mug, book);

        // Setting the quantity again replaces it; it does not add up.
        assertThat(data(put(customer, mug, 3)).get("itemCount").asInt()).isEqualTo(4);

        // The seller raises the price: the cart shows the new one at once.
        String mugsPath = "/api/v1/organizations/" + mugs.id() + "/products/" + mug;
        long version = data(client.get(mugsPath, mugs.ownerToken())).get("version").asLong();
        data(client.send(HttpMethod.PUT, mugsPath, Map.of("sku", "MUG-NEW", "name", "Mug", "price", "15.00",
                "version", version), mugs.ownerToken()));
        assertThat(data(client.get(CART, customer.token())).get("total").decimalValue()).isEqualByComparingTo("65.00");
    }

    @Test
    void linesThatStopBeingBuyableStayMarkedAndOutOfTheTotal() {
        Shop shop = shop();
        String scarce = product(shop, "5.00", 1);
        String plenty = product(shop, "1.00", 10);
        Account customer = account();
        data(put(customer, scarce, 2));
        data(put(customer, plenty, 1));

        JsonNode cart = data(client.get(CART, customer.token()));
        assertThat(cart.at("/lines/0/problem").asText()).isEqualTo("NOT_ENOUGH_STOCK");
        assertThat(cart.get("total").decimalValue()).isEqualByComparingTo("1.00");
        assertThat(cart.get("readyForCheckout").asBoolean()).isFalse();

        data(client.send(HttpMethod.POST, "/api/v1/admin/organizations/" + shop.id() + "/suspend",
                Map.of("reason", "Review"), adminToken));
        JsonNode suspended = data(client.get(CART, customer.token()));
        assertThat(suspended.get("lines").findValuesAsText("problem")).containsOnly("NOT_ON_SALE");
        assertThat(suspended.get("total").decimalValue()).isEqualByComparingTo("0.00");
        assertThat(put(customer, plenty, 2).getStatusCode()).as("nothing new from a suspended seller")
                .isEqualTo(HttpStatus.NOT_FOUND);
    }

    @Test
    void productsNotOnSaleCannotBeAddedAndRemovingIsIdempotent() {
        Shop shop = shop();
        String product = product(shop, "1.00", 1);
        Account customer = account();
        data(client.send(HttpMethod.POST, "/api/v1/organizations/" + shop.id() + "/products/" + product + "/archive",
                null, shop.ownerToken()));

        assertThat(put(customer, product, 1).getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(put(customer, UUID.randomUUID().toString(), 1).getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(client.send(HttpMethod.DELETE, CART + "/items/" + product, null, customer.token()).getStatusCode())
                .isEqualTo(HttpStatus.OK);
        assertThat(put(customer, product, 100).getStatusCode()).as("at most 99 per line").isEqualTo(HttpStatus.BAD_REQUEST);
    }

    @Test
    void eachCustomerSeesOnlyTheirOwnCartAndErasureEmptiesIt() {
        Shop shop = shop();
        String product = product(shop, "1.00", 5);
        Account ada = account();
        Account bob = account();
        data(put(ada, product, 2));

        assertThat(data(client.get(CART, bob.token())).get("lines")).isEmpty();

        assertThat(client.postJson("/api/v1/auth/me/erase", Map.of("password", PASSWORD), ada.token()).getStatusCode())
                .isEqualTo(HttpStatus.NO_CONTENT);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM cart_items WHERE user_id = ?", Long.class, ada.id())).isZero();
        assertThat(put(ada, product, 1).getStatusCode()).as("the old token cannot refill it").isEqualTo(HttpStatus.UNAUTHORIZED);
    }
}
