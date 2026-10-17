// SPDX-License-Identifier: Apache-2.0
package io.github.eduardoalecrim.idempotency.store.jdbc;

import static org.assertj.core.api.Assertions.assertThat;

import com.zaxxer.hikari.HikariDataSource;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.Statement;
import java.time.Instant;
import java.util.Objects;
import java.util.UUID;
import liquibase.Scope;
import liquibase.command.CommandScope;
import liquibase.command.core.UpdateCommandStep;
import liquibase.command.core.helpers.DbUrlConnectionArgumentsCommandStep;
import liquibase.database.Database;
import liquibase.database.DatabaseFactory;
import liquibase.database.jvm.JdbcConnection;
import liquibase.resource.ClassLoaderResourceAccessor;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** FR-25: the shipped scripts, changelog and validation, on PostgreSQL. */
@DisplayName("FR-25: JDBC schema")
class JdbcSchemaTest {

    private static final String TABLE = JdbcIdempotencyStore.DEFAULT_TABLE_NAME;

    @BeforeAll
    static void selected() {
        Assumptions.assumeTrue(TestDatabase.POSTGRESQL.selected(), "postgresql is not in idempotency.tck.databases");
    }

    @Test
    @DisplayName("the shipped script creates a table that passes validation, once")
    void scriptCreatesAValidTable() throws Exception {
        try (HikariDataSource dataSource = TestDatabase.freshPostgreSqlSchema(schema())) {
            assertThat(JdbcSchema.problems(dataSource, TABLE)).containsExactly("table " + TABLE + " does not exist");

            assertThat(JdbcSchema.createIfMissing(dataSource, JdbcDialect.POSTGRESQL, TABLE))
                    .isTrue();
            assertThat(JdbcSchema.createIfMissing(dataSource, JdbcDialect.POSTGRESQL, TABLE))
                    .isFalse();

            assertThat(JdbcSchema.problems(dataSource, TABLE)).isEmpty();
        }
    }

    @Test
    @DisplayName("the script creates the table under another name, and a store works on it")
    void scriptCreatesARenamedTable() throws Exception {
        try (HikariDataSource dataSource = TestDatabase.freshPostgreSqlSchema(schema())) {
            JdbcSchema.createIfMissing(dataSource, JdbcDialect.POSTGRESQL, "order_keys");

            assertThat(JdbcSchema.problems(dataSource, "order_keys")).isEmpty();
            JdbcIdempotencyStore store = JdbcIdempotencyStore.builder(dataSource)
                    .tableName("order_keys")
                    .build();
            assertThat(store.purgeExpired(Instant.now(), 10)).isZero();
        }
    }

    @Test
    @DisplayName("a schema-qualified name is validated in that schema")
    void validatesQualifiedNames() throws Exception {
        String schema = schema();
        try (HikariDataSource dataSource = TestDatabase.freshPostgreSqlSchema(schema)) {
            JdbcSchema.createIfMissing(dataSource, JdbcDialect.POSTGRESQL, TABLE);
        }

        assertThat(JdbcSchema.problems(TestDatabase.POSTGRESQL.dataSource(), schema + "." + TABLE))
                .isEmpty();
        assertThat(JdbcSchema.problems(TestDatabase.POSTGRESQL.dataSource(), schema + ".other"))
                .containsExactly("table " + schema + ".other does not exist");
    }

    @Test
    @DisplayName("validation names each missing column")
    void namesMissingColumns() throws Exception {
        try (HikariDataSource dataSource = TestDatabase.freshPostgreSqlSchema(schema())) {
            JdbcSchema.createIfMissing(dataSource, JdbcDialect.POSTGRESQL, TABLE);
            try (Connection connection = dataSource.getConnection();
                    Statement statement = connection.createStatement()) {
                statement.execute("ALTER TABLE " + TABLE + " DROP COLUMN fingerprint");
                statement.execute("ALTER TABLE " + TABLE + " DROP COLUMN lease_until");
            }

            assertThat(JdbcSchema.problems(dataSource, TABLE))
                    .containsExactly(
                            "table " + TABLE + " has no column fingerprint",
                            "table " + TABLE + " has no column lease_until");
        }
    }

    @Test
    @DisplayName("the Liquibase changelog creates a valid table and applies once")
    void liquibaseChangelogCreatesTheTable() throws Exception {
        try (HikariDataSource dataSource = TestDatabase.freshPostgreSqlSchema(schema())) {
            liquibaseUpdate(dataSource);
            liquibaseUpdate(dataSource);

            assertThat(JdbcSchema.problems(dataSource, TABLE)).isEmpty();
        }
    }

    @Test
    @DisplayName("the Flyway script, copied under an application version, creates a valid table")
    void flywayScriptCreatesTheTable(@TempDir Path migrations) throws Exception {
        try (InputStream script = Objects.requireNonNull(
                getClass().getClassLoader().getResourceAsStream("db/idempotency/flyway/schema-postgresql.sql"))) {
            Files.copy(script, migrations.resolve("V7__idempotency_record.sql"));
        }
        try (HikariDataSource dataSource = TestDatabase.freshPostgreSqlSchema(schema())) {
            Flyway.configure()
                    .dataSource(dataSource)
                    .locations("filesystem:" + migrations)
                    .load()
                    .migrate();

            assertThat(JdbcSchema.problems(dataSource, TABLE)).isEmpty();
        }
    }

    private static void liquibaseUpdate(HikariDataSource dataSource) throws Exception {
        try (Connection connection = dataSource.getConnection()) {
            Database database =
                    DatabaseFactory.getInstance().findCorrectDatabaseImplementation(new JdbcConnection(connection));
            Scope.child(
                    Scope.Attr.resourceAccessor,
                    new ClassLoaderResourceAccessor(),
                    () -> new CommandScope(UpdateCommandStep.COMMAND_NAME)
                            .addArgumentValue(DbUrlConnectionArgumentsCommandStep.DATABASE_ARG, database)
                            .addArgumentValue(UpdateCommandStep.CHANGELOG_FILE_ARG, "db/idempotency/changelog.yaml")
                            .execute());
        }
    }

    private static String schema() {
        return "s_" + UUID.randomUUID().toString().replace("-", "").substring(0, 12);
    }
}
