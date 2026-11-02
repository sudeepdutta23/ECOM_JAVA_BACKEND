package com.ecommerce.backend.org;

import static com.ecommerce.backend.support.AuthTestClient.PASSWORD;
import static com.ecommerce.backend.support.AuthTestClient.accessToken;
import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import com.ecommerce.backend.AbstractIntegrationTest;
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
import java.util.concurrent.CompletableFuture;
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

/** Invitations and memberships over real HTTP, with the invitee's real email link. */
@ExtendWith(OutputCaptureExtension.class)
class OrganizationMembersIT extends AbstractIntegrationTest {

    private static final String ORGS = "/api/v1/organizations";
    private static final String DOMAIN = "@org-members.test";

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
    private final List<String> secrets = new ArrayList<>();

    private record Account(UUID id, String email, String token) {
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

    private static String newEmail(String name) {
        return name + "-" + UUID.randomUUID() + DOMAIN;
    }

    private Account account(String email) {
        client.registerAndVerify(email);
        secrets.add(email);
        UUID id = jdbc.queryForObject("SELECT id FROM users WHERE email = ?", UUID.class, email);
        String token = accessToken(client.login(email, PASSWORD));
        secrets.add(token);
        return new Account(id, email, token);
    }

    private String organization(Account owner) {
        String slug = "it-mem-" + UUID.randomUUID().toString().substring(0, 8);
        slugs.add(slug);
        ResponseEntity<String> created = client.postJson(ORGS, Map.of("slug", slug, "name", "Shop"), owner.token());
        return data(created).get("id").asText();
    }

    private void addMember(String orgId, Account account, String role) {
        jdbc.update("INSERT INTO organization_members (organization_id, user_id, role) VALUES (?::uuid, ?, ?)",
                orgId, account.id(), role);
    }

    private ResponseEntity<String> invite(Account caller, String orgId, String email, String role) {
        return client.postJson(ORGS + "/" + orgId + "/invitations", Map.of("email", email, "role", role), caller.token());
    }

    private long invitationEmails(String email) {
        return mail.sentTo(email).stream().filter(m -> m.subject().contains("invited")).count();
    }

    /** The token of the newest invitation email to this address. */
    private String invitationToken(String email) {
        await().atMost(Duration.ofSeconds(10)).until(() -> invitationEmails(email) > 0);
        String token = RecordingEmailSender.tokenFrom(mail.sentTo(email).stream()
                .filter(m -> m.subject().contains("invited")).reduce((first, second) -> second).orElseThrow());
        secrets.add(token);
        return token;
    }

    private ResponseEntity<String> accept(Account caller, String token) {
        return client.postJson(ORGS + "/invitations/accept", Map.of("token", token), caller.token());
    }

    private ResponseEntity<String> setRole(Account caller, String orgId, Account member, String role) {
        return client.send(HttpMethod.PUT, ORGS + "/" + orgId + "/members/" + member.id(), Map.of("role", role), caller.token());
    }

    private long owners(String orgId) {
        return jdbc.queryForObject("SELECT count(*) FROM organization_members WHERE organization_id = ?::uuid AND role = 'OWNER'",
                Long.class, orgId);
    }

    private JsonNode data(ResponseEntity<String> response) {
        try {
            return mapper.readTree(response.getBody()).get("data");
        } catch (JsonProcessingException e) {
            throw new IllegalStateException(e);
        }
    }

    // ------------------------------------------------------------------ invitations

    @Test
    void anInviteeWithoutAnAccountRegistersAcceptsOnceAndAppearsInTheMemberList(CapturedOutput logs) {
        Account owner = account(newEmail("owner"));
        String orgId = organization(owner);
        String bobEmail = newEmail("bob");

        ResponseEntity<String> invited = invite(owner, orgId, bobEmail.toUpperCase(), "STAFF");
        assertThat(invited.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        assertThat(invited.getBody()).doesNotContain("token");
        String link = invitationToken(bobEmail);
        Account bob = account(bobEmail); // registers after being invited

        ResponseEntity<String> accepted = accept(bob, link);

        assertThat(accepted.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(data(accepted).get("myRole").asText()).isEqualTo("STAFF");
        JsonNode members = data(client.get(ORGS + "/" + orgId + "/members", bob.token()));
        assertThat(members.get("totalElements").asLong()).isEqualTo(2);
        assertThat(members.at("/content/0/role").asText()).isEqualTo("OWNER"); // longest-serving first
        assertThat(members.at("/content/1/email").asText()).isEqualTo(bobEmail);
        assertThat(members.at("/content/1/addedBy").asText()).isEqualTo(owner.id().toString());
        assertThat(data(client.get(ORGS + "/" + orgId + "/invitations", owner.token())).get("totalElements").asLong()).isZero();
        assertThat(accept(bob, link).getBody()).contains("ORG_INVALID_INVITATION"); // single use

        String output = logs.getAll();
        assertThat(output).contains("Invitation accepted: organizationId=" + orgId);
        for (String secret : secrets) {
            assertThat(output).as("no email or token in logs").doesNotContain(secret);
        }
    }

    @Test
    void aForwardedLinkFailsForAnotherAccountAndStillWorksForTheInvitee() {
        Account owner = account(newEmail("owner"));
        Account mallory = account(newEmail("mallory"));
        Account bob = account(newEmail("bob"));
        String orgId = organization(owner);
        invite(owner, orgId, bob.email(), "MANAGER");
        String link = invitationToken(bob.email());

        assertThat(accept(mallory, link).getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(accept(bob, link).getStatusCode()).isEqualTo(HttpStatus.OK);
    }

    @Test
    void reInvitingReplacesTheLinkAndRevokingKillsIt() {
        Account owner = account(newEmail("owner"));
        Account bob = account(newEmail("bob"));
        String orgId = organization(owner);
        invite(owner, orgId, bob.email(), "STAFF");
        String first = invitationToken(bob.email());
        String invitationId = data(invite(owner, orgId, bob.email(), "MANAGER")).get("id").asText();
        await().atMost(Duration.ofSeconds(10)).until(() -> invitationEmails(bob.email()) == 2);
        String second = invitationToken(bob.email());

        assertThat(data(client.get(ORGS + "/" + orgId + "/invitations", owner.token())).get("totalElements").asLong())
                .isEqualTo(1);
        assertThat(accept(bob, first).getBody()).contains("ORG_INVALID_INVITATION");
        assertThat(client.send(HttpMethod.DELETE, ORGS + "/" + orgId + "/invitations/" + invitationId, null,
                owner.token()).getStatusCode()).isEqualTo(HttpStatus.NO_CONTENT);
        assertThat(accept(bob, second).getBody()).contains("ORG_INVALID_INVITATION");
    }

    @Test
    void managersInviteAndManageStaffOnlyAndMembersCannotBeInvitedTwice() {
        Account owner = account(newEmail("owner"));
        Account manager = account(newEmail("manager"));
        String orgId = organization(owner);
        addMember(orgId, manager, "MANAGER");

        assertThat(invite(manager, orgId, newEmail("x"), "MANAGER").getBody()).contains("ORG_RANK_DENIED");
        assertThat(setRole(manager, orgId, owner, "STAFF").getBody()).contains("ORG_RANK_DENIED");
        assertThat(client.send(HttpMethod.DELETE, ORGS + "/" + orgId + "/members/" + owner.id(), null, manager.token())
                .getBody()).contains("ORG_RANK_DENIED");
        assertThat(setRole(manager, orgId, manager, "OWNER").getBody()).contains("ORG_SELF_ACTION_DENIED");
        assertThat(invite(owner, orgId, manager.email(), "STAFF").getBody()).contains("ORG_ALREADY_MEMBER");
        assertThat(invite(manager, orgId, newEmail("y"), "STAFF").getStatusCode()).isEqualTo(HttpStatus.CREATED);
    }

    // ------------------------------------------------------------------ owners

    @Test
    void theLastOwnerCannotLeaveUntilSomeoneElseOwnsTheOrganization() {
        Account owner = account(newEmail("owner"));
        Account bob = account(newEmail("bob"));
        String orgId = organization(owner);
        addMember(orgId, bob, "STAFF");

        ResponseEntity<String> refused = client.send(HttpMethod.POST, ORGS + "/" + orgId + "/leave", null, owner.token());
        assertThat(refused.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
        assertThat(refused.getBody()).contains("ORG_LAST_OWNER");

        assertThat(setRole(owner, orgId, bob, "OWNER").getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(client.send(HttpMethod.POST, ORGS + "/" + orgId + "/leave", null, owner.token()).getStatusCode())
                .isEqualTo(HttpStatus.NO_CONTENT);
        assertThat(client.get(ORGS + "/" + orgId, owner.token()).getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
    }

    /** Phase 4 review finding: an owner who cannot sign in does not count as "another owner". */
    @Test
    void theLastActiveOwnerCannotLeaveAShopWhoseOtherOwnerIsDeactivated() {
        Account alice = account(newEmail("alice"));
        Account bob = account(newEmail("bob"));
        String orgId = organization(alice);
        addMember(orgId, bob, "OWNER");
        assertThat(client.postJson("/api/v1/auth/me/deactivate", Map.of("password", PASSWORD), bob.token())
                .getStatusCode()).as("bob may deactivate: alice is an active owner").isEqualTo(HttpStatus.NO_CONTENT);

        ResponseEntity<String> leave = client.send(HttpMethod.POST, ORGS + "/" + orgId + "/leave", null, alice.token());

        assertThat(leave.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
        assertThat(leave.getBody()).contains("ORG_LAST_OWNER");
        assertThat(owners(orgId)).isEqualTo(2);
    }

    @Test
    void twoOwnersDemotingEachOtherAtOnceLeaveExactlyOneOwner() {
        Account alice = account(newEmail("alice"));
        Account bob = account(newEmail("bob"));
        String orgId = organization(alice);
        addMember(orgId, bob, "OWNER");

        CompletableFuture<ResponseEntity<String>> aliceDemotesBob =
                CompletableFuture.supplyAsync(() -> setRole(alice, orgId, bob, "MANAGER"));
        CompletableFuture<ResponseEntity<String>> bobDemotesAlice =
                CompletableFuture.supplyAsync(() -> setRole(bob, orgId, alice, "MANAGER"));
        List<HttpStatus> outcomes = List.of(
                HttpStatus.valueOf(aliceDemotesBob.join().getStatusCode().value()),
                HttpStatus.valueOf(bobDemotesAlice.join().getStatusCode().value()));

        // The loser waited for the organisation row lock, then either saw one owner left (409) or,
        // having been demoted itself a moment earlier, lacked the rank (403). Never zero owners.
        assertThat(outcomes).containsOnlyOnce(HttpStatus.OK);
        assertThat(owners(orgId)).isEqualTo(1);
    }

    @Test
    void outsidersGet404OnMembersAndInvitations() {
        Account owner = account(newEmail("owner"));
        Account outsider = account(newEmail("outsider"));
        String orgId = organization(owner);

        assertThat(client.get(ORGS + "/" + orgId + "/members", outsider.token()).getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(invite(outsider, orgId, newEmail("z"), "STAFF").getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(client.send(HttpMethod.POST, ORGS + "/" + orgId + "/leave", null, outsider.token()).getStatusCode())
                .isEqualTo(HttpStatus.NOT_FOUND);
    }
}
