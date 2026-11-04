package com.ecommerce.backend.common.mail;

import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.binder.MeterBinder;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

/**
 * The outbox's health, read on each scrape: a growing backlog or an old waiting email means the mail
 * server is down or slow; FAILED rows need an operator. Each gauge is one query on a small table.
 * If the database cannot be read, the gauges report NaN instead of failing the scrape.
 */
@Component
class EmailOutboxMetrics implements MeterBinder {

    private static final Logger log = LoggerFactory.getLogger(EmailOutboxMetrics.class);

    private final JdbcTemplate jdbc;

    EmailOutboxMetrics(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public void bindTo(MeterRegistry registry) {
        Gauge.builder("emails.outbox.pending", () -> query("SELECT count(*) FROM email_outbox WHERE status = 'PENDING'"))
                .description("Emails waiting to be sent").register(registry);
        Gauge.builder("emails.outbox.failed", () -> query("SELECT count(*) FROM email_outbox WHERE status = 'FAILED'"))
                .description("Emails given up after every retry").register(registry);
        Gauge.builder("emails.outbox.oldest.age", () -> query("""
                        SELECT coalesce(extract(epoch FROM now() - min(created_at)), 0)
                        FROM email_outbox WHERE status = 'PENDING'"""))
                .baseUnit("seconds").description("Age of the oldest waiting email").register(registry);
    }

    private double query(String sql) {
        try {
            Number value = jdbc.queryForObject(sql, Number.class);
            return value == null ? 0 : value.doubleValue();
        } catch (RuntimeException e) {
            log.warn("Outbox metrics unavailable: {}", e.getClass().getSimpleName());
            return Double.NaN;
        }
    }
}
