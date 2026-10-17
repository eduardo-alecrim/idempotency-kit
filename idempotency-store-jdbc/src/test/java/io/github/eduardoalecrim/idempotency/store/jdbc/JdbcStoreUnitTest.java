// SPDX-License-Identifier: Apache-2.0
package io.github.eduardoalecrim.idempotency.store.jdbc;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;

import io.github.eduardoalecrim.idempotency.spi.StoredResponse.Header;
import java.time.Duration;
import java.util.List;
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
                        () -> JdbcIdempotencyStore.builder(new NoDataSource()).queryTimeout(Duration.ZERO));
    }

    @Test
    void reportsAnUnreachableDatabaseAsDown() {
        JdbcIdempotencyStore store = JdbcIdempotencyStore.builder(new NoDataSource())
                .dialect(JdbcDialect.POSTGRESQL)
                .build();

        assertThat(store.health().up()).isFalse();
        assertThat(store.dialect()).isEqualTo(JdbcDialect.POSTGRESQL);
    }
}
