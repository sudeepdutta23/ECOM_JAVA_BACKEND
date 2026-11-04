package com.ecommerce.backend;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.Statement;
import javax.sql.DataSource;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.support.TransactionOperations;

/** The server-side limits every pooled connection carries (spring.datasource.hikari.data-source-properties). */
class DatabaseGuardsIT extends AbstractIntegrationTest {

    @Autowired
    private JdbcTemplate jdbc;

    @Autowired
    private TransactionOperations transactions;

    @Autowired
    private DataSource dataSource;

    @Test
    void everyConnectionHasAStatementAndAnIdleTransactionTimeout() {
        assertThat(jdbc.queryForObject("SHOW statement_timeout", String.class)).isEqualTo("15s");
        assertThat(jdbc.queryForObject("SHOW idle_in_transaction_session_timeout", String.class)).isEqualTo("1min");
    }

    @Test
    void aStatementOverItsTimeoutIsCancelledByTheServer() {
        assertThatThrownBy(() -> transactions.executeWithoutResult(status -> {
            jdbc.execute("SET LOCAL statement_timeout = '100ms'");
            jdbc.queryForObject("SELECT pg_sleep(2)", Object.class);
        })).rootCause().hasMessageContaining("canceling statement due to statement timeout");
    }

    /** What a long migration does: SET LOCAL ends with its transaction, and the connection keeps the guard. */
    @Test
    void liftingTheTimeoutForOneTransactionDoesNotOutliveIt() throws Exception {
        try (Connection connection = dataSource.getConnection(); Statement statement = connection.createStatement()) {
            connection.setAutoCommit(false);
            statement.execute("SET LOCAL statement_timeout = 0");
            assertThat(show(statement)).isEqualTo("0");
            connection.commit();

            assertThat(show(statement)).as("back to the guard after the transaction").isEqualTo("15s");
            connection.setAutoCommit(true);
        }
    }

    private static String show(Statement statement) throws Exception {
        try (ResultSet rows = statement.executeQuery("SHOW statement_timeout")) {
            rows.next();
            return rows.getString(1);
        }
    }
}
