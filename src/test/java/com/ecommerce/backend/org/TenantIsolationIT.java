package com.ecommerce.backend.org;

import static com.ecommerce.backend.support.AuthTestClient.PASSWORD;
import static com.ecommerce.backend.support.AuthTestClient.accessToken;
import static org.assertj.core.api.Assertions.assertThat;

import com.ecommerce.backend.AbstractIntegrationTest;
import com.ecommerce.backend.support.AuthTestClient;
import com.ecommerce.backend.support.RecordingEmailSender;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
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

/**
 * Tenant isolation for EVERY organisation-scoped endpoint, discovered from the live OpenAPI spec.
 *
 * <p>Seller endpoints added in later phases (products, stock, orders) are covered without editing
 * this class: any path containing {@code {orgId}} is called. Each other path variable needs a
 * fixture here, a real ID belonging to the target seller; an endpoint with a new variable fails
 * the test until one is added, so it can never pass vacuously on random IDs.
 *
 * <ol>
 *   <li><b>Outsiders:</b> a signed-in account that is not a member gets 404 on every operation,
 *       with the same body as for an organisation that does not exist, and nothing changes.</li>
 *   <li><b>Another seller's IDs:</b> the owner of seller B, using B's own path with seller A's
 *       child IDs, gets 404: every repository lookup is scoped by organisation, not just guarded.</li>
 * </ol>
 */
class TenantIsolationIT extends AbstractIntegrationTest {

    private static final String DOMAIN = "@tenant-isolation.test";
    private static final Pattern PATH_VARIABLE = Pattern.compile("\\{([^}]+)}");
    private static final Set<String> BODY_METHODS = Set.of("post", "put", "patch");
    /** The org endpoints of this phase; later phases only add. Guards against discovering nothing. */
    private static final int KNOWN_ORG_OPERATIONS = 22;

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
    private String outsiderToken;
    private UUID target;
    private UUID otherSeller;
    /** Path variable name -> an ID that belongs to the target seller. Add one per new variable. */
    private Map<String, String> targetFixtures;

    private record Operation(String method, String path, JsonNode spec) {
        @Override
        public String toString() {
            return method.toUpperCase(Locale.ROOT) + " " + path;
        }
    }

    @BeforeEach
    void setUp() {
        client = new AuthTestClient(rest, mail);
        String ownerToken = token(account("owner"));
        target = organization(ownerToken);
        String staff = account("staff");
        jdbc.update("INSERT INTO organization_members (organization_id, user_id, role) "
                + "SELECT ?, id, 'STAFF' FROM users WHERE email = ?", target, staff);
        UUID staffId = jdbc.queryForObject("SELECT id FROM users WHERE email = ?", UUID.class, staff);
        String invitation = client.postJson("/api/v1/organizations/" + target + "/invitations",
                Map.of("email", "invitee-" + UUID.randomUUID() + DOMAIN, "role", "STAFF"), ownerToken).getBody();
        String product = client.postJson("/api/v1/organizations/" + target + "/products",
                Map.of("sku", "ISO-1", "name", "Target product", "price", 10), ownerToken).getBody();
        String productId = read(product).at("/data/id").asText();
        targetFixtures = Map.of(
                "userId", staffId.toString(),
                "invitationId", read(invitation).at("/data/id").asText(),
                "productId", productId,
                "orderId", paidOrder(target, staffId, UUID.fromString(productId)).toString());

        outsiderToken = token(account("outsider"));
        otherSeller = organization(outsiderToken); // the outsider owns a seller of their own
    }

    @AfterEach
    void cleanUp() {
        slugs.forEach(slug -> {
            // Orders and products restrict deleting their seller (organisations are never deleted by the application).
            jdbc.update("DELETE FROM orders WHERE organization_id = (SELECT id FROM organizations WHERE slug = ?)", slug);
            jdbc.update("DELETE FROM products WHERE organization_id = (SELECT id FROM organizations WHERE slug = ?)", slug);
            jdbc.update("DELETE FROM organizations WHERE slug = ?", slug);
        });
        jdbc.update("DELETE FROM checkouts WHERE idempotency_key LIKE 'iso-%'");
        jdbc.update("DELETE FROM users WHERE email LIKE '%" + DOMAIN + "'");
    }

    @Test
    void outsidersGetTheSame404AsForANonexistentOrganizationOnEveryOperationAndChangeNothing() {
        String before = snapshot(target);
        List<Operation> operations = organizationOperations();

        for (Operation operation : operations) {
            ResponseEntity<String> real = call(operation, target, targetFixtures);
            ResponseEntity<String> ghost = call(operation, UUID.randomUUID(), targetFixtures);

            assertThat(real.getStatusCode()).as(operation + " as an outsider: " + real.getBody())
                    .isEqualTo(HttpStatus.NOT_FOUND);
            assertThat(read(real.getBody()).get("code")).as(operation.toString()).isEqualTo(read(ghost.getBody()).get("code"));
            assertThat(read(real.getBody()).get("detail")).as(operation.toString()).isEqualTo(read(ghost.getBody()).get("detail"));
        }
        assertThat(snapshot(target)).as("the target seller is untouched").isEqualTo(before);
    }

    @Test
    void anotherSellersOwnerCannotReachTheTargetsDataThroughTheirOwnOrganization() {
        String before = snapshot(target);
        List<Operation> scopedByChildId = organizationOperations().stream()
                .filter(op -> PATH_VARIABLE.matcher(op.path().replace("{orgId}", "")).find())
                .toList();
        assertThat(scopedByChildId).as("operations with a child ID").isNotEmpty();

        for (Operation operation : scopedByChildId) {
            ResponseEntity<String> response = call(operation, otherSeller, targetFixtures);

            assertThat(response.getStatusCode()).as(operation + " with another seller's IDs: " + response.getBody())
                    .isEqualTo(HttpStatus.NOT_FOUND);
        }
        assertThat(snapshot(target)).as("the target seller is untouched").isEqualTo(before);
    }

    // ------------------------------------------------------------------ discovery and calls

    private List<Operation> organizationOperations() {
        JsonNode spec = read(rest.getForObject("/v3/api-docs", String.class));
        List<Operation> operations = new ArrayList<>();
        for (Iterator<Map.Entry<String, JsonNode>> paths = spec.get("paths").fields(); paths.hasNext(); ) {
            Map.Entry<String, JsonNode> path = paths.next();
            if (!path.getKey().contains("{orgId}")) {
                continue;
            }
            path.getValue().fields().forEachRemaining(method -> operations.add(
                    new Operation(method.getKey(), path.getKey(), method.getValue())));
        }
        assertThat(operations.size()).as("organisation operations discovered").isGreaterThanOrEqualTo(KNOWN_ORG_OPERATIONS);
        return operations;
    }

    private ResponseEntity<String> call(Operation operation, UUID organizationId, Map<String, String> fixtures) {
        String url = fill(operation.path(), organizationId, fixtures);
        HttpHeaders headers = new HttpHeaders();
        headers.setBearerAuth(outsiderToken);
        headers.setContentType(MediaType.APPLICATION_JSON);
        String body = BODY_METHODS.contains(operation.method()) ? sampleBody(operation.spec()) : null;
        return rest.exchange(url, HttpMethod.valueOf(operation.method().toUpperCase(Locale.ROOT)),
                new HttpEntity<>(body, headers), String.class);
    }

    private static String fill(String path, UUID organizationId, Map<String, String> fixtures) {
        Matcher matcher = PATH_VARIABLE.matcher(path);
        StringBuilder url = new StringBuilder();
        while (matcher.find()) {
            String name = matcher.group(1);
            String value = name.equals("orgId") ? organizationId.toString() : fixtures.get(name);
            assertThat(value).as("TenantIsolationIT needs a fixture for {" + name + "} in " + path
                    + ": a real ID of the target seller, so the call would reach real data without the guard").isNotNull();
            matcher.appendReplacement(url, value);
        }
        return matcher.appendTail(url).toString();
    }

    /**
     * A valid body built from the schema's examples, so the request gets past validation and the
     * guard is what answers. An invalid body would be rejected with 400 before the guard runs.
     */
    private String sampleBody(JsonNode operationSpec) {
        JsonNode schema = operationSpec.at("/requestBody/content/application~1json/schema");
        if (schema.isMissingNode()) {
            return null;
        }
        JsonNode spec = read(rest.getForObject("/v3/api-docs", String.class));
        if (schema.has("$ref")) {
            schema = spec.at(schema.get("$ref").asText().substring(1));
        }
        ObjectNode body = mapper.createObjectNode();
        schema.path("properties").fields().forEachRemaining(property -> {
            JsonNode definition = property.getValue();
            if (definition.has("example")) {
                body.set(property.getKey(), definition.get("example"));
            } else if (definition.has("enum")) {
                body.set(property.getKey(), definition.get("enum").get(0));
            } else if ("integer".equals(definition.path("type").asText())) {
                body.put(property.getKey(), 0);
            } else {
                body.put(property.getKey(), "isolation-test");
            }
        });
        return body.toString();
    }

    // ------------------------------------------------------------------ fixtures

    /** Everything an attacker could change, in one comparable string. */
    private String snapshot(UUID organizationId) {
        return jdbc.queryForObject("""
                SELECT o.name || '|' || o.status || '|' || o.version
                       || '|' || (SELECT string_agg(m.user_id || ':' || m.role, ',' ORDER BY m.user_id)
                                  FROM organization_members m WHERE m.organization_id = o.id)
                       || '|' || (SELECT count(*) FROM organization_invitations i
                                  WHERE i.organization_id = o.id AND i.accepted_at IS NULL AND i.revoked_at IS NULL)
                       || '|' || (SELECT string_agg(p.id || ':' || p.sku || ':' || p.name || ':' || p.price_minor
                                         || ':' || p.stock || ':' || p.status || ':' || p.version, ',' ORDER BY p.id)
                                  FROM products p WHERE p.organization_id = o.id)
                       || '|' || (SELECT string_agg(x.id || ':' || x.status || ':' || x.version, ',' ORDER BY x.id)
                                  FROM orders x WHERE x.organization_id = o.id)
                FROM organizations o WHERE o.id = ?""", String.class, organizationId);
    }

    /** A paid order of the seller, inserted directly: the isolation test needs one, not a whole purchase. */
    private UUID paidOrder(UUID organizationId, UUID customerId, UUID productId) {
        UUID checkoutId = UUID.randomUUID();
        UUID orderId = UUID.randomUUID();
        String address = "{\"recipientName\": \"Iso\", \"line1\": \"Street 1\", \"city\": \"Berlin\", \"countryCode\": \"DE\"}";
        jdbc.update("INSERT INTO checkouts (id, customer_id, idempotency_key, total_minor, currency) VALUES (?, ?, ?, 1000, 'EUR')",
                checkoutId, customerId, "iso-" + checkoutId);
        jdbc.update("""
                INSERT INTO orders (id, checkout_id, organization_id, seller_name, customer_id, status, currency, total_minor,
                                    shipping_address, billing_address, paid_at)
                VALUES (?, ?, ?, 'Shop', ?, 'PAID', 'EUR', 1000, ?::jsonb, ?::jsonb, now())""",
                orderId, checkoutId, organizationId, customerId, address, address);
        jdbc.update("INSERT INTO order_items (order_id, line_no, product_id, sku, name, unit_price_minor, quantity) "
                + "VALUES (?, 0, ?, 'ISO-1', 'Target product', 1000, 1)", orderId, productId);
        return orderId;
    }

    private String account(String name) {
        String email = name + "-" + UUID.randomUUID() + DOMAIN;
        client.registerAndVerify(email);
        return email;
    }

    private String token(String email) {
        return accessToken(client.login(email, PASSWORD));
    }

    private UUID organization(String ownerToken) {
        String slug = "it-iso-" + UUID.randomUUID().toString().substring(0, 8);
        slugs.add(slug);
        String created = client.postJson("/api/v1/organizations", Map.of("slug", slug, "name", "Shop"), ownerToken).getBody();
        return UUID.fromString(read(created).at("/data/id").asText());
    }

    private JsonNode read(String json) {
        try {
            return mapper.readTree(json);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException(e);
        }
    }
}
