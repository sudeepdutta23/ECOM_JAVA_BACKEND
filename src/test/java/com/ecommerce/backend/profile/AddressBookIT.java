package com.ecommerce.backend.profile;

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
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;

/** The address book over real HTTP, down to the rows: defaults, ownership, the limit under load, erasure. */
@ExtendWith(OutputCaptureExtension.class)
class AddressBookIT extends AbstractIntegrationTest {

    private static final String ADDRESSES = "/api/v1/profile/addresses";
    private static final String DOMAIN = "@address-book.test";

    @Autowired
    private TestRestTemplate rest;

    @Autowired
    private RecordingEmailSender mail;

    @Autowired
    private JdbcTemplate jdbc;

    @Autowired
    private ObjectMapper mapper;

    private AuthTestClient client;
    /** Erased rows no longer carry the test domain, so cleanup goes by ID. */
    private final List<UUID> created = new ArrayList<>();

    private record Account(UUID id, String token) {
    }

    @BeforeEach
    void setUp() {
        client = new AuthTestClient(rest, mail);
    }

    @AfterEach
    void cleanUp() {
        created.forEach(id -> jdbc.update("DELETE FROM users WHERE id = ?", id)); // cascades to the addresses
        jdbc.update("DELETE FROM users WHERE email LIKE '%" + DOMAIN + "'");
    }

    private Account account() {
        String email = "a-" + UUID.randomUUID() + DOMAIN;
        client.registerAndVerify(email);
        UUID id = jdbc.queryForObject("SELECT id FROM users WHERE email = ?", UUID.class, email);
        created.add(id);
        return new Account(id, accessToken(client.login(email, PASSWORD)));
    }

    private static Map<String, Object> address(String street, Object... flagsAndVersion) {
        Map<String, Object> body = new HashMap<>(Map.of("recipientName", "Ada Lovelace", "line1", street,
                "city", "Berlin", "postalCode", "10117", "countryCode", "DE"));
        for (int i = 0; i < flagsAndVersion.length; i += 2) {
            body.put((String) flagsAndVersion[i], flagsAndVersion[i + 1]);
        }
        return body;
    }

    private ResponseEntity<String> create(Account account, Map<String, Object> body) {
        return client.postJson(ADDRESSES, body, account.token());
    }

    private JsonNode data(ResponseEntity<String> response) {
        assertThat(response.getStatusCode().is2xxSuccessful()).as(response.getBody()).isTrue();
        try {
            return mapper.readTree(response.getBody()).get("data");
        } catch (JsonProcessingException e) {
            throw new IllegalStateException(e);
        }
    }

    private long rows(Account account) {
        return jdbc.queryForObject("SELECT count(*) FROM user_addresses WHERE user_id = ?", Long.class, account.id());
    }

    @Test
    void defaultsMoveBetweenAddressesAndAreNeverDoubled(CapturedOutput logs) {
        Account ada = account();
        String street = "Friedrichstraße " + UUID.randomUUID().toString().substring(0, 6);

        JsonNode home = data(create(ada, address(street)));
        assertThat(home.get("defaultShipping").asBoolean()).as("the first address is the default").isTrue();
        assertThat(home.get("defaultBilling").asBoolean()).isTrue();
        assertThat(home.get("version").asLong()).isZero();

        JsonNode office = data(create(ada, address("Unter den Linden 1", "defaultShipping", true)));
        assertThat(office.get("defaultShipping").asBoolean()).isTrue();
        assertThat(office.get("defaultBilling").asBoolean()).isFalse();

        JsonNode homeNow = data(client.get(ADDRESSES + "/" + home.get("id").asText(), ada.token()));
        assertThat(homeNow.get("defaultShipping").asBoolean()).as("moved to the office").isFalse();
        assertThat(homeNow.get("defaultBilling").asBoolean()).as("billing stays at home").isTrue();

        // Newest first.
        JsonNode list = data(client.get(ADDRESSES, ada.token()));
        assertThat(list.findValuesAsText("id")).containsExactly(office.get("id").asText(), home.get("id").asText());

        // The old version of home no longer works: clearing its flag bumped it.
        ResponseEntity<String> stale = client.send(HttpMethod.PUT, ADDRESSES + "/" + home.get("id").asText(),
                address(street, "version", 0), ada.token());
        assertThat(stale.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);

        // Deleting the shipping default leaves none; billing is untouched.
        assertThat(client.send(HttpMethod.DELETE, ADDRESSES + "/" + office.get("id").asText(), null, ada.token())
                .getStatusCode()).isEqualTo(HttpStatus.NO_CONTENT);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM user_addresses WHERE user_id = ? AND default_shipping",
                Long.class, ada.id())).isZero();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM user_addresses WHERE user_id = ? AND default_billing",
                Long.class, ada.id())).isOne();

        assertThat(logs.getAll()).contains("Address created: userId=" + ada.id()).doesNotContain(street)
                .doesNotContain("Ada Lovelace");
    }

    @Test
    void anotherPersonsAddressAnswersExactlyLikeAnUnknownOne() {
        Account ada = account();
        Account mallory = account();
        String adas = data(create(ada, address("Secret Street 1"))).get("id").asText();
        String unknown = UUID.randomUUID().toString();

        for (String id : List.of(adas, unknown)) {
            ResponseEntity<String> read = client.get(ADDRESSES + "/" + id, mallory.token());
            ResponseEntity<String> replace = client.send(HttpMethod.PUT, ADDRESSES + "/" + id,
                    address("Mallory Road 1", "version", 0), mallory.token());
            ResponseEntity<String> remove = client.send(HttpMethod.DELETE, ADDRESSES + "/" + id, null, mallory.token());
            assertThat(List.of(read, replace, remove)).allSatisfy(response -> {
                assertThat(response.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
                assertThat(response.getBody()).doesNotContain("Secret");
            });
        }
        assertThat(data(client.get(ADDRESSES, mallory.token()))).isEmpty();
        assertThat(jdbc.queryForObject("SELECT line1 FROM user_addresses WHERE id = ?::uuid", String.class, adas))
                .isEqualTo("Secret Street 1");
    }

    @Test
    void countryRulesAreAppliedOverHttp() {
        Account ada = account();
        Map<String, Object> us = new HashMap<>(Map.of("recipientName", "Ada", "line1", "1 Market St",
                "city", "San Francisco", "postalCode", "941", "countryCode", "US"));

        ResponseEntity<String> refused = create(ada, us);

        assertThat(refused.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(refused.getBody()).contains("postalCode").contains("region");
        us.putAll(Map.of("postalCode", "94105", "region", "CA"));
        assertThat(create(ada, us).getStatusCode()).isEqualTo(HttpStatus.CREATED);
    }

    /** Six requests at once with room for two: the address-book lock lets exactly two through. */
    @Test
    void parallelRequestsCannotPassTheLimitTogether() throws Exception {
        Account ada = account();
        for (int i = 0; i < 18; i++) {
            data(create(ada, address("Street " + i)));
        }

        ExecutorService pool = Executors.newFixedThreadPool(6);
        try {
            List<Callable<ResponseEntity<String>>> requests = new ArrayList<>();
            for (int i = 0; i < 6; i++) {
                String street = "Parallel " + i;
                requests.add(() -> create(ada, address(street, "defaultShipping", true)));
            }
            List<HttpStatus> statuses = new ArrayList<>();
            for (Future<ResponseEntity<String>> result : pool.invokeAll(requests)) {
                statuses.add(HttpStatus.valueOf(result.get().getStatusCode().value()));
            }
            assertThat(statuses).filteredOn(HttpStatus.CREATED::equals).hasSize(2);
            assertThat(statuses).filteredOn(HttpStatus.CONFLICT::equals).hasSize(4);
        } finally {
            pool.shutdownNow();
        }
        assertThat(rows(ada)).isEqualTo(20);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM user_addresses WHERE user_id = ? AND default_shipping",
                Long.class, ada.id())).as("never two defaults").isOne();
        assertThat(create(ada, address("One too many")).getBody()).contains("PROFILE_ADDRESS_LIMIT");
    }

    @Test
    void erasureDeletesTheAddressBookAndTheOldTokenCannotRefillIt() {
        Account ada = account();
        data(create(ada, address("Street 1")));
        data(create(ada, address("Street 2")));

        assertThat(client.postJson("/api/v1/auth/me/erase", Map.of("password", PASSWORD), ada.token()).getStatusCode())
                .isEqualTo(HttpStatus.NO_CONTENT);

        assertThat(rows(ada)).isZero();
        assertThat(create(ada, address("Street 3")).getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
        assertThat(rows(ada)).isZero();
    }
}
