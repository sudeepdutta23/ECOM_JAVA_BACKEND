package com.ecommerce.backend.notification.service;

import java.sql.Timestamp;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import net.javacrumbs.shedlock.spring.annotation.SchedulerLock;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Deletes notifications older than {@code app.jobs.notification-retention} (90 days): an inbox is a
 * recent history, and every entry tells something about a person's orders. In batches, like
 * {@code TokenCleanup}, served by {@code idx_notifications_created_at}.
 */
@Component
public class NotificationRetention {

    private static final Logger log = LoggerFactory.getLogger(NotificationRetention.class);
    static final int BATCH = 1_000;

    private final JdbcTemplate jdbc;
    private final Clock clock;
    private final Duration retention;

    public NotificationRetention(JdbcTemplate jdbc, Clock clock,
                          @Value("${app.jobs.notification-retention:P90D}") Duration retention) {
        this.jdbc = jdbc;
        this.clock = clock;
        this.retention = retention;
    }

    @Scheduled(fixedDelayString = "${app.jobs.cleanup-interval:PT1H}", initialDelayString = "PT3M")
    @SchedulerLock(name = "notification-retention", lockAtMostFor = "PT30M")
    public void run() {
        int deleted = deleteExpired();
        if (deleted > 0) {
            log.info("Old notifications deleted: count={}", deleted);
        }
    }

    public int deleteExpired() {
        Timestamp cutoff = Timestamp.from(Instant.now(clock).minus(retention));
        int total = 0;
        int deleted;
        do {
            deleted = jdbc.update("""
                    DELETE FROM notifications WHERE id IN (
                        SELECT id FROM notifications WHERE created_at < ? LIMIT ?)""", cutoff, BATCH);
            total += deleted;
        } while (deleted == BATCH);
        return total;
    }
}
