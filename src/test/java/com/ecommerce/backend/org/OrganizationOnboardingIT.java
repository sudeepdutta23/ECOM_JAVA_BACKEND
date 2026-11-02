package com.ecommerce.backend.org;

import static com.ecommerce.backend.support.AuthTestClient.PASSWORD;
import static com.ecommerce.backend.support.AuthTestClient.accessToken;
import static org.assertj.core.api.Assertions.assertThat;

import com.ecommerce.backend.AbstractIntegrationTest;
import com.ecommerce.backend.support.AuthTestClient;
import com.ecommerce.backend.support.RecordingEmailSender;
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
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;

/** Seller onboarding over real HTTP: create, list, view, rename, and the rules around them. */
class OrganizationOnboardingIT extends AbstractIntegrationTest {

    private static final String ORGS = "/api/v1/organizations";
    private static final String DOMAIN = "@org-onboarding.test";

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

    private record Account(UUID id, String token) {
    }

    @BeforeEach
    void setUp() {
        client = new AuthTestClient(rest, mail);
    }

    @AfterEach
    void cleanUp() {
        slugs.forEach(slug -> jdbc.update("DELETE FROM organizations WHERE slug = ?", slug));
        jdbc.update("DELETE FROM users WHERE email LIKE '%" + DOMAIN + "'");
    }

    private Account account() {
        String email = "o-" + UUID.randomUUID() + DOMAIN;
        client.registerAndVerify(email);
        UUID id = jdbc.queryForObject("SELECT id FROM users WHERE email = ?", UUID.class, email);
        return new Account(id, accessToken(client.login(email, PASSWORD)));
    }

    private String newSlug() {
        String slug = "it-onb-" + UUID.randomUUID().toString().substring(0, 8);
        slugs.add(slug);
        return slug;
    }

    private ResponseEntity<String> create(Account caller, String slug) {
        return client.postJson(ORGS, Map.of("slug", slug, "name", "Shop " + slug), caller.token());
    }

    private JsonNode data(ResponseEntity<String> response) throws Exception {
        return mapper.readTree(response.getBody()).get("data");
    }

    @Test
    void aFounderCreatesAPendingOrganizationOwnsItAndFindsItInTheirList() throws Exception {
        Account founder = account();
        String slug = newSlug();

        ResponseEntity<String> created = create(founder, slug.toUpperCase());

        assertThat(created.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        JsonNode organization = data(created);
        assertThat(organization.get("slug").asText()).isEqualTo(slug); // normalized
        assertThat(organization.get("status").asText()).isEqualTo("PENDING_APPROVAL");
        assertThat(organization.get("myRole").asText()).isEqualTo("OWNER");
        assertThat(created.getHeaders().getLocation()).hasToString(ORGS + "/" + organization.get("id").asText());
        assertThat(jdbc.queryForObject("SELECT role FROM organization_members WHERE user_id = ?", String.class,
                founder.id())).isEqualTo("OWNER");

        JsonNode mine = data(client.get(ORGS, founder.token()));
        assertThat(mine.get("totalElements").asLong()).isEqualTo(1);
        assertThat(mine.at("/content/0/slug").asText()).isEqualTo(slug);
        assertThat(mine.at("/content/0/myRole").asText()).isEqualTo("OWNER");
    }

    @Test
    void outsidersCannotSeeOrChangeAnOrganizationAndCannotTellItExists() throws Exception {
        Account founder = account();
        Account outsider = account();
        String id = data(create(founder, newSlug())).get("id").asText();

        ResponseEntity<String> peek = client.get(ORGS + "/" + id, outsider.token());
        ResponseEntity<String> unknown = client.get(ORGS + "/" + UUID.randomUUID(), outsider.token());
        ResponseEntity<String> rename = client.send(HttpMethod.PUT, ORGS + "/" + id,
                Map.of("name", "Hijacked", "version", 0), outsider.token());

        assertThat(peek.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(rename.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
        // The same answer as for an ID that does not exist: code and wording are identical.
        JsonNode peekProblem = mapper.readTree(peek.getBody());
        JsonNode unknownProblem = mapper.readTree(unknown.getBody());
        assertThat(peekProblem.get("code")).isEqualTo(unknownProblem.get("code"));
        assertThat(peekProblem.get("detail")).isEqualTo(unknownProblem.get("detail"));
        assertThat(data(client.get(ORGS, outsider.token())).get("totalElements").asLong()).isZero();
    }

    @Test
    void slugsAreUniqueCaseInsensitivelyAndReservedOnesAreRefused() {
        Account first = account();
        Account second = account();
        String slug = newSlug();
        create(first, slug);

        ResponseEntity<String> taken = create(second, slug.toUpperCase());
        ResponseEntity<String> reserved = create(second, "support");

        assertThat(taken.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
        assertThat(taken.getBody()).contains("ORG_SLUG_TAKEN");
        assertThat(reserved.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(reserved.getBody()).contains("reserved");
    }

    @Test
    void parallelCreationsCannotExceedTheOwnedLimit() throws Exception {
        Account founder = account();
        ExecutorService pool = Executors.newFixedThreadPool(6);
        try {
            List<Future<ResponseEntity<String>>> attempts = new ArrayList<>();
            for (int i = 0; i < 6; i++) {
                String slug = newSlug();
                attempts.add(pool.submit(() -> create(founder, slug)));
            }
            int created = 0;
            int limited = 0;
            for (Future<ResponseEntity<String>> attempt : attempts) {
                ResponseEntity<String> response = attempt.get();
                if (response.getStatusCode() == HttpStatus.CREATED) {
                    created++;
                } else if (response.getBody().contains("ORG_LIMIT_REACHED")) {
                    limited++;
                }
            }
            assertThat(created).as("the limit is 3").isEqualTo(3);
            assertThat(limited).isEqualTo(3);
        } finally {
            pool.shutdownNow();
        }
    }

    @Test
    void ownersAndManagersRenameWithTheCurrentVersionStaffCannot() throws Exception {
        Account founder = account();
        Account staff = account();
        String slug = newSlug();
        String id = data(create(founder, slug)).get("id").asText();
        jdbc.update("INSERT INTO organization_members (organization_id, user_id, role) VALUES (?::uuid, ?, 'STAFF')",
                id, staff.id());

        assertThat(client.get(ORGS + "/" + id, staff.token()).getBody()).contains("\"myRole\":\"STAFF\"");
        assertThat(client.send(HttpMethod.PUT, ORGS + "/" + id, Map.of("name", "Staff's shop", "version", 0),
                staff.token()).getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);

        ResponseEntity<String> renamed = client.send(HttpMethod.PUT, ORGS + "/" + id,
                Map.of("name", "Acme Berlin", "version", 0), founder.token());
        assertThat(data(renamed).get("name").asText()).isEqualTo("Acme Berlin");
        assertThat(data(renamed).get("version").asLong()).isEqualTo(1);
        assertThat(data(renamed).get("slug").asText()).isEqualTo(slug);

        ResponseEntity<String> stale = client.send(HttpMethod.PUT, ORGS + "/" + id,
                Map.of("name", "Lost update", "version", 0), founder.token());
        assertThat(stale.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
        assertThat(stale.getBody()).contains("CONCURRENT_MODIFICATION");
    }

    @Test
    void aSuspendedOrganizationIsReadOnlyAndDoesNotRevealTheReason() throws Exception {
        Account founder = account();
        String id = data(create(founder, newSlug())).get("id").asText();
        jdbc.update("UPDATE organizations SET status = 'SUSPENDED', status_reason = 'Counterfeit goods' WHERE id = ?::uuid", id);

        ResponseEntity<String> view = client.get(ORGS + "/" + id, founder.token());
        ResponseEntity<String> rename = client.send(HttpMethod.PUT, ORGS + "/" + id,
                Map.of("name", "Fresh start", "version", 0), founder.token());

        assertThat(view.getBody()).contains("SUSPENDED").doesNotContain("Counterfeit");
        assertThat(rename.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
        assertThat(rename.getBody()).contains("ORG_INVALID_STATUS");
    }
}
