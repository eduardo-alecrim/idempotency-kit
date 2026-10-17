// SPDX-License-Identifier: Apache-2.0
package io.github.eduardoalecrim.idempotency.store.jdbc;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.github.eduardoalecrim.idempotency.spi.IdempotencyStoreException;
import io.github.eduardoalecrim.idempotency.spi.OwnerToken;
import io.github.eduardoalecrim.idempotency.spi.ScopeKey;
import io.github.eduardoalecrim.idempotency.spi.StoredResponse.Header;
import java.sql.SQLException;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;

class JdbcStoreUnitTest {

    @ParameterizedTest
    @CsvSource({
        "PostgreSQL, POSTGRESQL",
        "MySQL, MYSQL",
        "MariaDB, MARIADB",
        "Oracle, ORACLE",
        "Microsoft SQL Server, SQLSERVER"
    })
    void detectsDialectsFromProductNames(String productName, JdbcDialect dialect) {
        assertThat(JdbcDialect.fromProductName(productName)).hasValue(dialect);
    }

    @Test
    void rejectsUnknownProducts() {
        assertThat(JdbcDialect.fromProductName("H2")).isEmpty();
    }

    @Test
    void storesHeadersAsFieldLines() {
        List<Header> headers = List.of(
                new Header("Content-Type", "application/json"), new Header("X-Empty", ""), new Header("X-A", "b: c"));

        String lines = HeaderLines.encode(headers);

        assertThat(lines).isEqualTo("Content-Type: application/json\r\nX-Empty: \r\nX-A: b: c");
        assertThat(HeaderLines.decode(lines)).isEqualTo(headers);
        assertThat(HeaderLines.decode("")).isEmpty();
        assertThat(HeaderLines.decode(null)).isEmpty();
        assertThatIllegalArgumentException().isThrownBy(() -> HeaderLines.decode("broken"));
    }

    @ParameterizedTest
    @ValueSource(strings = {"idempotency_record", "app.idempotency_record", "_t1"})
    void acceptsTableNames(String name) {
        assertThat(JdbcIdempotencyStore.builder(new NoDataSource())
                        .tableName(name)
                        .build()
                        .tableName())
                .isEqualTo(name);
    }

    @ParameterizedTest
    @ValueSource(strings = {"", "1table", "t; DROP TABLE x", "t-1", "\"t\""})
    void rejectsUnsafeTableNames(String name) {
        assertThatIllegalArgumentException()
                .isThrownBy(
                        () -> JdbcIdempotencyStore.builder(new NoDataSource()).tableName(name));
    }

    @Test
    void splitsShippedScriptsIntoStatements() {
        String script = "-- comment\nCREATE TABLE t (\n    a INT,\n    b INT\n);\n\nCREATE INDEX i ON t (a);\n";

        assertThat(JdbcSchema.statements(script))
                .containsExactly("CREATE TABLE t (\n    a INT,\n    b INT\n)", "CREATE INDEX i ON t (a)");
    }

    @ParameterizedTest
    @CsvSource({"POSTGRESQL, schema-postgresql.sql", "SQLSERVER, schema-sqlserver.sql"})
    void namesTheScriptOfEachDialect(JdbcDialect dialect, String file) {
        assertThat(JdbcSchema.scriptPath(dialect)).isEqualTo("db/idempotency/" + file);
    }

    @Test
    void createsOnlyUnqualifiedTables() {
        assertThatIllegalArgumentException()
                .isThrownBy(() -> JdbcSchema.createIfMissing(new NoDataSource(), JdbcDialect.POSTGRESQL, "app.t"));
    }

    @Test
    void rejectsNonPositiveTimeouts() {
        assertThatIllegalArgumentException()
                .isThrownBy(
                        () -> JdbcIdempotencyStore.builder(new NoDataSource()).timeout(Duration.ZERO));
    }

    @Test
    void reportsAnUnreachableDatabaseAsDown() {
        JdbcIdempotencyStore store = JdbcIdempotencyStore.builder(new NoDataSource())
                .dialect(JdbcDialect.POSTGRESQL)
                .build();

        assertThat(store.health().up()).isFalse();
        assertThat(store.dialect()).isEqualTo(JdbcDialect.POSTGRESQL);
    }

    @Test
    @DisplayName("NFR-10: a hung data source fails the call within the timeout")
    void hungDataSourceFailsWithinTheTimeout() {
        JdbcIdempotencyStore store = JdbcIdempotencyStore.builder(new NoDataSource(Duration.ofSeconds(30)))
                .dialect(JdbcDialect.POSTGRESQL)
                .timeout(Duration.ofMillis(200))
                .build();

        long start = System.nanoTime();
        assertThatThrownBy(() -> store.release(new ScopeKey("a".repeat(64)), OwnerToken.random()))
                .isInstanceOf(IdempotencyStoreException.class)
                .hasMessage("JDBC store did not release a record within PT0.2S");
        assertThat(Duration.ofNanos(System.nanoTime() - start)).isLessThan(Duration.ofMillis(250));
        assertThat(store.health().detail()).startsWith("jdbc: JDBC store did not check its health within");
    }

    @Test
    @DisplayName("a failing data source fails the call with the cause")
    void failingDataSourceFailsWithTheCause() {
        JdbcIdempotencyStore store = JdbcIdempotencyStore.builder(new NoDataSource())
                .dialect(JdbcDialect.POSTGRESQL)
                .build();

        assertThatThrownBy(() -> store.purgeExpired(Instant.now(), 10))
                .isInstanceOf(IdempotencyStoreException.class)
                .hasMessage("JDBC store failed to purge expired records")
                .hasCauseInstanceOf(SQLException.class);
    }

    @Test
    @DisplayName("calls beyond the pool and its queue fail at once")
    void saturatedStoreFailsAtOnce() throws Exception {
        JdbcIdempotencyStore store = JdbcIdempotencyStore.builder(new NoDataSource(Duration.ofSeconds(30)))
                .dialect(JdbcDialect.POSTGRESQL)
                .timeout(Duration.ofSeconds(5))
                .maxConcurrentCalls(1)
                .build();
        ExecutorService callers = Executors.newFixedThreadPool(5);
        try {
            // One call runs and four wait in the queue.
            for (int i = 0; i < 5; i++) {
                Future<?> ignored = callers.submit(() -> store.health());
            }
            Thread.sleep(300);

            long start = System.nanoTime();
            assertThat(store.health().detail())
                    .isEqualTo("jdbc: JDBC store is saturated and could not check its health");
            assertThat(Duration.ofNanos(System.nanoTime() - start)).isLessThan(Duration.ofMillis(100));
        } finally {
            callers.shutdownNow();
        }
    }

    @Test
    void rejectsNonPositiveConcurrency() {
        assertThatIllegalArgumentException()
                .isThrownBy(
                        () -> JdbcIdempotencyStore.builder(new NoDataSource()).maxConcurrentCalls(0));
    }
}
