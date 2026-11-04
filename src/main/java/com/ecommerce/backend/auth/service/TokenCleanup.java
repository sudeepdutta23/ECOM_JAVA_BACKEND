package com.ecommerce.backend.auth.service;

import java.sql.Timestamp;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import net.javacrumbs.shedlock.spring.annotation.SchedulerLock;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Deletes refresh tokens and email links that can never be used again: expired for a day, or
 * consumed for a day. They would otherwise grow forever, and every one of them holds an IP address
 * or a user agent nobody needs any more (data minimisation).
 *
 * <p>A day of grace, not zero: an expired refresh token presented once more is still recognised
 * (and refused) as such, rather than as an unknown token, which keeps the logs readable. Reuse
 * detection is unaffected: it compares live tokens of a family.
 *
 * <p>In batches of {@value #BATCH} rows, each its own short statement: one huge DELETE would hold
 * locks and bloat the WAL.
 */
@Component
public class TokenCleanup {

    private static final Logger log = LoggerFactory.getLogger(TokenCleanup.class);
    static final int BATCH = 1_000;
    private static final Duration GRACE = Duration.ofDays(1);

    private final JdbcTemplate jdbc;
    private final Clock clock;

    public TokenCleanup(JdbcTemplate jdbc, Clock clock) {
        this.jdbc = jdbc;
        this.clock = clock;
    }

    @Scheduled(fixedDelayString = "${app.jobs.cleanup-interval:PT1H}", initialDelayString = "PT2M")
    @SchedulerLock(name = "token-cleanup", lockAtMostFor = "PT30M")
    public void run() {
        Result result = cleanUp();
        if (result.refreshTokens() + result.accountTokens() > 0) {
            log.info("Spent tokens deleted: refreshTokens={}, accountTokens={}", result.refreshTokens(), result.accountTokens());
        }
    }

    public record Result(int refreshTokens, int accountTokens) {
    }

    public Result cleanUp() {
        Instant cutoff = Instant.now(clock).minus(GRACE);
        int refresh = deleteInBatches("""
                DELETE FROM refresh_tokens WHERE id IN (
                    SELECT id FROM refresh_tokens WHERE expires_at < ? LIMIT ?)""", cutoff);
        int links = deleteInBatches("""
                DELETE FROM account_tokens WHERE id IN (
                    SELECT id FROM account_tokens WHERE expires_at < ? LIMIT ?)""", cutoff)
                + deleteInBatches("""
                DELETE FROM account_tokens WHERE id IN (
                    SELECT id FROM account_tokens WHERE consumed_at < ? LIMIT ?)""", cutoff);
        return new Result(refresh, links);
    }

    private int deleteInBatches(String sql, Instant cutoff) {
        int total = 0;
        int deleted;
        do {
            deleted = jdbc.update(sql, Timestamp.from(cutoff), BATCH);
            total += deleted;
        } while (deleted == BATCH);
        return total;
    }
}
