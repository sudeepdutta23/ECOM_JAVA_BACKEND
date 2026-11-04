package com.ecommerce.backend.common.mail;

import com.ecommerce.backend.common.util.UuidV7;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/**
 * Durable email (transactional outbox). {@link #enqueue} writes the message to {@code email_outbox}
 * in the caller's transaction, so it exists exactly when the change that caused it committed: a
 * rolled-back payment sends nothing, and a crash right after commit loses nothing. After commit,
 * {@link EmailOutboxRelay} is nudged to send it at once; its poller catches whatever a crash or a
 * mail outage left behind.
 *
 * <p><b>Not for one-time links.</b> A verification, reset or invitation email carries a raw token;
 * storing it here would undo the hashing of those tokens. Those emails stay best effort, and the
 * person can ask for a new link.
 */
@Component
public class EmailOutbox {

    private final JdbcTemplate jdbc;
    private final EmailOutboxRelay relay;

    public EmailOutbox(JdbcTemplate jdbc, EmailOutboxRelay relay) {
        this.jdbc = jdbc;
        this.relay = relay;
    }

    public void enqueue(EmailMessage message) {
        jdbc.update("INSERT INTO email_outbox (id, recipient, subject, body) VALUES (?, ?, ?, ?)",
                UuidV7.generate(), message.to(), message.subject(), message.body());
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override
                public void afterCommit() {
                    relay.dispatchSoon();
                }
            });
        } else {
            relay.dispatchSoon(); // no transaction: the row is already committed
        }
    }

    /** Account erasure: nothing still waiting may keep the address. */
    public int forget(String email) {
        return jdbc.update("DELETE FROM email_outbox WHERE lower(recipient) = lower(?)", email);
    }
}
