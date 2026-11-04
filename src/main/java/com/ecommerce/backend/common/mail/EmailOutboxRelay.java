package com.ecommerce.backend.common.mail;

import com.ecommerce.backend.common.util.Masking;
import io.micrometer.core.instrument.MeterRegistry;
import java.time.Duration;
import java.util.List;
import java.util.UUID;
import net.javacrumbs.shedlock.spring.annotation.SchedulerLock;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Async;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Sends what {@link EmailOutbox} stored. Delivery is <b>at least once</b>: a server that dies
 * after sending but before deleting the row sends it again when its lease runs out.
 *
 * <ul>
 *   <li><b>Claim</b>: one statement takes up to {@value #BATCH} due rows with
 *       {@code FOR UPDATE SKIP LOCKED}, counts the attempt and moves {@code next_attempt_at} to the
 *       end of a lease. Servers and threads never claim the same row, so no ShedLock is needed and
 *       every server helps.</li>
 *   <li><b>Send outside any transaction</b>: a slow mail server holds no database lock.</li>
 *   <li><b>Then</b> delete the row, or schedule a retry with exponential backoff (30 s doubling,
 *       at most 1 h); after {@value #MAX_ATTEMPTS} attempts the row is marked FAILED for an
 *       operator, and deleted after 7 days.</li>
 * </ul>
 */
@Component
public class EmailOutboxRelay {

    private static final Logger log = LoggerFactory.getLogger(EmailOutboxRelay.class);
    static final int BATCH = 10;
    static final int MAX_ATTEMPTS = 8;
    /** Above a batch's worst case (10 sends with 5 s SMTP timeouts each). */
    static final Duration LEASE = Duration.ofMinutes(5);
    private static final Duration FIRST_RETRY = Duration.ofSeconds(30);
    private static final Duration LAST_RETRY = Duration.ofHours(1);

    private final JdbcTemplate jdbc;
    private final EmailSender sender;
    private final MeterRegistry meters;

    public EmailOutboxRelay(JdbcTemplate jdbc, EmailSender sender, MeterRegistry meters) {
        this.jdbc = jdbc;
        this.sender = sender;
        this.meters = meters;
    }

    private record Claimed(UUID id, String recipient, String subject, String body, int attempts) {
    }

    /** After a commit: send now, on a pool thread, so the request does not wait for SMTP. */
    @Async
    public void dispatchSoon() {
        dispatch();
    }

    /** The safety net: retries, and rows a crashed server had claimed. */
    @Scheduled(fixedDelayString = "${app.mail.outbox.poll-interval:PT10S}", initialDelayString = "PT10S")
    public void poll() {
        dispatch();
    }

    @Scheduled(fixedDelayString = "${app.jobs.cleanup-interval:PT1H}", initialDelayString = "PT4M")
    @SchedulerLock(name = "email-outbox-purge", lockAtMostFor = "PT30M")
    public void purge() {
        int deleted = purgeFailed();
        if (deleted > 0) {
            log.info("Failed outbox emails deleted: count={}", deleted);
        }
    }

    /** @return the emails sent */
    public int dispatch() {
        int sent = 0;
        List<Claimed> batch;
        do {
            batch = claim();
            for (Claimed message : batch) {
                if (deliver(message)) {
                    sent++;
                }
            }
        } while (batch.size() == BATCH);
        return sent;
    }

    public int purgeFailed() {
        return jdbc.update("DELETE FROM email_outbox WHERE status = 'FAILED' AND created_at < now() - interval '7 days'");
    }

    private List<Claimed> claim() {
        return jdbc.query("""
                UPDATE email_outbox SET attempts = attempts + 1, next_attempt_at = now() + make_interval(secs => ?)
                WHERE id IN (
                    SELECT id FROM email_outbox WHERE status = 'PENDING' AND next_attempt_at <= now()
                    ORDER BY next_attempt_at LIMIT ? FOR UPDATE SKIP LOCKED)
                RETURNING id, recipient, subject, body, attempts""",
                (rs, i) -> new Claimed(rs.getObject("id", UUID.class), rs.getString("recipient"),
                        rs.getString("subject"), rs.getString("body"), rs.getInt("attempts")),
                (double) LEASE.toSeconds(), BATCH);
    }

    private boolean deliver(Claimed message) {
        try {
            sender.send(new EmailMessage(message.recipient(), message.subject(), message.body()));
        } catch (RuntimeException e) {
            failed(message, e);
            return false;
        }
        jdbc.update("DELETE FROM email_outbox WHERE id = ?", message.id());
        meters.counter("emails.sent").increment();
        return true;
    }

    private void failed(Claimed message, RuntimeException e) {
        String error = e.getClass().getSimpleName();
        boolean last = message.attempts() >= MAX_ATTEMPTS;
        meters.counter("emails.failed", "final", String.valueOf(last)).increment();
        if (last) {
            jdbc.update("UPDATE email_outbox SET status = 'FAILED', last_error = ? WHERE id = ?", error, message.id());
            log.error("Email given up after {} attempts: id={}, to={}, subject={}", message.attempts(), message.id(),
                    Masking.email(message.recipient()), message.subject(), e);
            return;
        }
        Duration retryIn = backoff(message.attempts());
        jdbc.update("UPDATE email_outbox SET next_attempt_at = now() + make_interval(secs => ?), last_error = ? WHERE id = ?",
                (double) retryIn.toSeconds(), error, message.id());
        log.warn("Email failed, retry in {}: id={}, attempt={}, to={}, error={}", retryIn, message.id(), message.attempts(),
                Masking.email(message.recipient()), error);
    }

    /** 30 s after the first attempt, doubling: 1 min, 2 min, ... at most 1 h. */
    static Duration backoff(int attempts) {
        Duration delay = FIRST_RETRY.multipliedBy(1L << Math.min(attempts - 1, 16));
        return delay.compareTo(LAST_RETRY) > 0 ? LAST_RETRY : delay;
    }
}
