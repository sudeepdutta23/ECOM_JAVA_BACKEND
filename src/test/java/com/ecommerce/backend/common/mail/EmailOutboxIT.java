package com.ecommerce.backend.common.mail;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import com.ecommerce.backend.AbstractIntegrationTest;
import com.ecommerce.backend.support.RecordingEmailSender;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.support.TransactionOperations;

/** The outbox against a real database: commit, rollback, retries, giving up, and parallel relays. */
class EmailOutboxIT extends AbstractIntegrationTest {

    private static final String DOMAIN = "@outbox.test";

    @Autowired
    private EmailOutbox outbox;

    @Autowired
    private EmailOutboxRelay relay;

    @Autowired
    private RecordingEmailSender mail;

    @Autowired
    private JdbcTemplate jdbc;

    @Autowired
    private TransactionOperations transactions;

    @AfterEach
    void cleanUp() {
        jdbc.update("DELETE FROM email_outbox WHERE recipient LIKE ?", "%" + DOMAIN);
    }

    private static String address() {
        return "o-" + UUID.randomUUID() + DOMAIN;
    }

    private Map<String, Object> row(String to) {
        return jdbc.queryForMap("SELECT status, attempts, last_error FROM email_outbox WHERE recipient = ?", to);
    }

    private long rows(String to) {
        return jdbc.queryForObject("SELECT count(*) FROM email_outbox WHERE recipient = ?", Long.class, to);
    }

    @Test
    void anEmailEnqueuedInACommittedTransactionIsSentAndItsRowDeleted() {
        String to = address();
        transactions.executeWithoutResult(status -> outbox.enqueue(new EmailMessage(to, "Hello", "Body")));

        await().atMost(Duration.ofSeconds(10)).until(() -> mail.sentTo(to).size() == 1);
        await().atMost(Duration.ofSeconds(5)).until(() -> rows(to) == 0);
        assertThat(mail.sentTo(to).get(0).subject()).isEqualTo("Hello");
    }

    @Test
    void aRolledBackTransactionLeavesNoEmail() {
        String to = address();
        transactions.executeWithoutResult(status -> {
            outbox.enqueue(new EmailMessage(to, "Never", "Body"));
            status.setRollbackOnly();
        });

        assertThat(rows(to)).isZero();
        relay.dispatch();
        assertThat(mail.sentTo(to)).isEmpty();
    }

    @Test
    void aFailedSendIsRetriedLaterAndGivenUpAfterTheLastAttempt() {
        String to = address();
        mail.failFor(to);
        try {
            outbox.enqueue(new EmailMessage(to, "Down", "Body"));
            await().atMost(Duration.ofSeconds(10)).until(() -> ((Number) row(to).get("attempts")).intValue() == 1);
            assertThat(row(to)).containsEntry("status", "PENDING").containsEntry("last_error", "EmailDeliveryException");
            assertThat(jdbc.queryForObject("SELECT next_attempt_at > now() + interval '20 seconds' FROM email_outbox WHERE recipient = ?",
                    Boolean.class, to)).as("backed off").isTrue();

            jdbc.update("UPDATE email_outbox SET attempts = ?, next_attempt_at = now() WHERE recipient = ?",
                    EmailOutboxRelay.MAX_ATTEMPTS - 1, to);
            relay.dispatch();
            assertThat(row(to)).containsEntry("status", "FAILED").containsEntry("attempts", EmailOutboxRelay.MAX_ATTEMPTS);

            jdbc.update("UPDATE email_outbox SET next_attempt_at = now() - interval '1 second' WHERE recipient = ?", to);
            relay.dispatch();
            assertThat(row(to)).as("a FAILED row is never claimed again").containsEntry("attempts", EmailOutboxRelay.MAX_ATTEMPTS);

            jdbc.update("UPDATE email_outbox SET created_at = now() - interval '8 days' WHERE recipient = ?", to);
            relay.purgeFailed();
            assertThat(rows(to)).isZero();
        } finally {
            mail.recover(to);
        }
    }

    /** A server that crashed mid-send left a claimed row: once its lease ends, another server sends it. */
    @Test
    void aRowClaimedByACrashedServerIsSentAfterItsLease() {
        String to = address();
        jdbc.update("""
                INSERT INTO email_outbox (id, recipient, subject, body, attempts, next_attempt_at)
                VALUES (?, ?, 'Recovered', 'Body', 1, now() + interval '5 minutes')""", UUID.randomUUID(), to);
        relay.dispatch();
        assertThat(mail.sentTo(to)).as("still leased").isEmpty();

        jdbc.update("UPDATE email_outbox SET next_attempt_at = now() - interval '1 second' WHERE recipient = ?", to);
        relay.dispatch();
        assertThat(mail.sentTo(to)).hasSize(1);
        assertThat(rows(to)).isZero();
    }

    @Test
    void parallelRelaysSendEveryEmailExactlyOnce() throws Exception {
        List<String> recipients = new ArrayList<>();
        for (int i = 0; i < 35; i++) {
            String to = address();
            recipients.add(to);
            jdbc.update("INSERT INTO email_outbox (id, recipient, subject, body) VALUES (?, ?, 'Many', 'Body')",
                    UUID.randomUUID(), to);
        }
        ExecutorService pool = Executors.newFixedThreadPool(4);
        try {
            List<Callable<Integer>> relays = List.of(relay::dispatch, relay::dispatch, relay::dispatch, relay::dispatch);
            pool.invokeAll(relays);
        } finally {
            pool.shutdown();
        }
        assertThat(recipients).allSatisfy(to -> assertThat(mail.sentTo(to)).hasSize(1));
        assertThat(recipients).allSatisfy(to -> assertThat(rows(to)).isZero());
    }

    @Test
    void erasureForgetsWhatIsStillWaitingForTheAddress() {
        String to = address();
        mail.failFor(to);
        try {
            outbox.enqueue(new EmailMessage(to, "Waiting", "Body"));
            assertThat(outbox.forget(to.toUpperCase())).isOne();
            assertThat(rows(to)).isZero();
        } finally {
            mail.recover(to);
        }
    }
}
