// SPDX-License-Identifier: Apache-2.0
package io.github.eduardoalecrim.idempotency.spring;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import io.github.eduardoalecrim.idempotency.IdempotencyEngine;
import io.github.eduardoalecrim.idempotency.spi.ClaimRequest;
import io.github.eduardoalecrim.idempotency.spi.Fingerprint;
import io.github.eduardoalecrim.idempotency.spi.IdempotencyStore;
import io.github.eduardoalecrim.idempotency.spi.ScopeKey;
import io.github.eduardoalecrim.idempotency.spi.ScopeParts;
import io.github.eduardoalecrim.idempotency.store.jdbc.JdbcDialect;
import io.github.eduardoalecrim.idempotency.store.jdbc.JdbcIdempotencyStore;
import io.github.eduardoalecrim.idempotency.store.jdbc.JdbcSchema;
import io.github.eduardoalecrim.idempotency.store.memory.InMemoryIdempotencyStore;
import java.security.SecureRandom;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Duration;
import java.time.Instant;
import java.util.HexFormat;
import java.util.Objects;
import java.util.UUID;
import javax.sql.DataSource;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.postgresql.ds.PGSimpleDataSource;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.diagnostics.FailureAnalysis;
import org.springframework.boot.test.context.assertj.AssertableApplicationContext;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.testcontainers.postgresql.PostgreSQLContainer;

/** FR-25: the JDBC store auto-configuration on PostgreSQL, one fresh schema per test. */
@DisplayName("FR-25: JDBC store auto-configuration")
class IdempotencyJdbcStoreAutoConfigurationTest {

    private static final String TABLE = JdbcIdempotencyStore.DEFAULT_TABLE_NAME;
    private static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer("postgres:18-alpine");

    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withConfiguration(AutoConfigurations.of(
                    IdempotencyJdbcStoreAutoConfiguration.class,
                    IdempotencyJdbcCleanupAutoConfiguration.class,
                    IdempotencyInMemoryStoreAutoConfiguration.class,
                    IdempotencyAutoConfiguration.class));

    @BeforeAll
    static void startDatabase() {
        POSTGRES.start();
    }

    @AfterAll
    static void stopDatabase() {
        POSTGRES.stop();
    }

    @Test
    @DisplayName("with one DataSource, auto picks the JDBC store on the validated table")
    void autoPicksTheJdbcStore() throws Exception {
        DataSource dataSource = freshSchema();
        JdbcSchema.createIfMissing(dataSource, JdbcDialect.POSTGRESQL, TABLE);

        runner.withBean(DataSource.class, () -> dataSource).run(context -> {
            assertThat(context).hasSingleBean(IdempotencyEngine.class);
            assertThat(context).hasSingleBean(JdbcIdempotencyStore.class);
            assertThat(context).doesNotHaveBean(InMemoryIdempotencyStore.class);
            assertThat(context.getBean(JdbcIdempotencyStore.class).dialect()).isEqualTo(JdbcDialect.POSTGRESQL);
        });
    }

    @Test
    @DisplayName("without a DataSource, auto keeps the in-memory store")
    void autoWithoutDataSourceKeepsMemory() {
        runner.run(context -> {
            assertThat(context).hasSingleBean(InMemoryIdempotencyStore.class);
            assertThat(context).doesNotHaveBean(JdbcIdempotencyStore.class);
        });
    }

    @Test
    @DisplayName("an application store replaces the JDBC store")
    void applicationStoreWins() {
        IdempotencyStore own = new InMemoryIdempotencyStore();

        runner.withBean(DataSource.class, IdempotencyJdbcStoreAutoConfigurationTest::unreachable)
                .withBean(IdempotencyStore.class, () -> own)
                .run(context ->
                        assertThat(context.getBean(IdempotencyStore.class)).isSameAs(own));
    }

    @Test
    @DisplayName("type=jdbc without a DataSource fails startup")
    void explicitJdbcNeedsADataSource() {
        runner.withPropertyValues("idempotency.store.type=jdbc")
                .run(context -> assertThat(analyze(context).getDescription())
                        .contains("idempotency.store.type is jdbc", "DataSource"));
    }

    @Test
    @DisplayName("a missing column fails startup with the script to run")
    void missingColumnFailsStartup() throws Exception {
        DataSource dataSource = freshSchema();
        JdbcSchema.createIfMissing(dataSource, JdbcDialect.POSTGRESQL, TABLE);
        execute(dataSource, "ALTER TABLE " + TABLE + " DROP COLUMN fingerprint");

        runner.withBean(DataSource.class, () -> dataSource).run(context -> {
            FailureAnalysis analysis = analyze(context);
            assertThat(analysis.getDescription()).contains("table " + TABLE + " has no column fingerprint");
            assertThat(analysis.getAction())
                    .contains("db/idempotency/schema-postgresql.sql", "db/idempotency/changelog.yaml");
        });
    }

    @Test
    @DisplayName("a missing table fails startup unless initialize-schema creates it")
    void missingTable() throws Exception {
        DataSource dataSource = freshSchema();
        ApplicationContextRunner withData = runner.withBean(DataSource.class, () -> dataSource);

        withData.run(context ->
                assertThat(analyze(context).getDescription()).contains("table " + TABLE + " does not exist"));
        withData.withPropertyValues("idempotency.store.jdbc.initialize-schema=embedded")
                .run(context -> assertThat(analyze(context).getDescription()).contains("does not exist"));
        withData.withPropertyValues(
                        "idempotency.store.jdbc.initialize-schema=always",
                        "idempotency.store.jdbc.table-name=order_keys")
                .run(context -> assertThat(context).hasSingleBean(JdbcIdempotencyStore.class));

        assertThat(JdbcSchema.problems(dataSource, "order_keys")).isEmpty();
    }

    @Test
    @DisplayName("with validation off and the dialect set, startup does not touch the database")
    void noDatabaseAccessWithoutValidation() {
        runner.withBean(DataSource.class, IdempotencyJdbcStoreAutoConfigurationTest::unreachable)
                .withPropertyValues(
                        "idempotency.store.jdbc.validate-schema=false",
                        "idempotency.store.jdbc.dialect=sqlserver",
                        "idempotency.store.jdbc.table-name=app.keys",
                        "idempotency.store.timeout=500ms")
                .run(context -> {
                    JdbcIdempotencyStore store = context.getBean(JdbcIdempotencyStore.class);
                    assertThat(store.dialect()).isEqualTo(JdbcDialect.SQLSERVER);
                    assertThat(store.tableName()).isEqualTo("app.keys");
                });
    }

    @Test
    @DisplayName("an unreachable database fails validation with a hint")
    void unreachableDatabaseFailsValidation() {
        runner.withBean(DataSource.class, IdempotencyJdbcStoreAutoConfigurationTest::unreachable)
                .run(context -> {
                    FailureAnalysis analysis = analyze(context);
                    assertThat(analysis.getDescription()).startsWith("The idempotency table could not be validated");
                    assertThat(analysis.getAction()).contains("idempotency.store.jdbc.validate-schema=false");
                });
    }

    @Test
    @DisplayName("FR-26: the purge job deletes expired rows on schedule and keeps live ones")
    void purgesExpiredRowsOnSchedule() throws Exception {
        DataSource dataSource = freshSchema();
        JdbcSchema.createIfMissing(dataSource, JdbcDialect.POSTGRESQL, TABLE);
        Instant now = Instant.now();
        JdbcIdempotencyStore direct = JdbcIdempotencyStore.builder(dataSource).build();
        for (int i = 0; i < 5; i++) {
            direct.claim(claim(now.minus(Duration.ofHours(2)), now.minusSeconds(i + 1)));
        }
        direct.claim(claim(now, now.plus(Duration.ofHours(1))));

        runner.withBean(DataSource.class, () -> dataSource)
                .withPropertyValues(
                        "idempotency.store.jdbc.cleanup.interval=100ms", "idempotency.store.jdbc.cleanup.batch-size=2")
                .run(context -> {
                    assertThat(context.getBean(IdempotencyJdbcCleanup.class).isRunning())
                            .isTrue();
                    await().atMost(Duration.ofSeconds(10)).until(() -> rows(dataSource) == 1);
                });
    }

    @Test
    @DisplayName("the purge job is off with cleanup.enabled=false, and a failed purge is only logged")
    void purgeJobSwitchAndFailures() {
        runner.withBean(DataSource.class, IdempotencyJdbcStoreAutoConfigurationTest::unreachable)
                .withPropertyValues(
                        "idempotency.store.jdbc.validate-schema=false", "idempotency.store.jdbc.dialect=postgresql")
                .run(context -> assertThat(
                                context.getBean(IdempotencyJdbcCleanup.class).purge())
                        .isZero());
        runner.withBean(DataSource.class, IdempotencyJdbcStoreAutoConfigurationTest::unreachable)
                .withPropertyValues(
                        "idempotency.store.jdbc.validate-schema=false",
                        "idempotency.store.jdbc.dialect=postgresql",
                        "idempotency.store.jdbc.cleanup.enabled=false")
                .run(context -> assertThat(context).doesNotHaveBean(IdempotencyJdbcCleanup.class));
        runner.run(context -> assertThat(context).doesNotHaveBean(IdempotencyJdbcCleanup.class));
    }

    @Test
    void rejectsInvalidValues() {
        ApplicationContextRunner withData =
                runner.withBean(DataSource.class, IdempotencyJdbcStoreAutoConfigurationTest::unreachable);

        expectInvalid(withData, "idempotency.store.jdbc.table-name", "idempotency.store.jdbc.table-name=t;x");
        expectInvalid(withData, "idempotency.store.timeout", "idempotency.store.timeout=0s");
        expectInvalid(
                withData,
                "idempotency.store.jdbc.initialize-schema",
                "idempotency.store.jdbc.initialize-schema=always",
                "idempotency.store.jdbc.table-name=app.keys");

        ApplicationContextRunner unvalidated = withData.withPropertyValues(
                "idempotency.store.jdbc.validate-schema=false", "idempotency.store.jdbc.dialect=postgresql");
        expectInvalid(
                unvalidated, "idempotency.store.jdbc.cleanup.interval", "idempotency.store.jdbc.cleanup.interval=0s");
        expectInvalid(
                unvalidated,
                "idempotency.store.jdbc.cleanup.batch-size",
                "idempotency.store.jdbc.cleanup.batch-size=0");
    }

    private static ClaimRequest claim(Instant now, Instant expiresAt) {
        byte[] bytes = new byte[64];
        new SecureRandom().nextBytes(bytes);
        HexFormat hex = HexFormat.of();
        Instant leaseUntil = now.plusSeconds(30).isBefore(expiresAt) ? now.plusSeconds(30) : expiresAt;
        return new ClaimRequest(
                new ScopeKey(hex.formatHex(bytes, 0, 32)),
                new ScopeParts("k", "", "POST", "/orders"),
                new Fingerprint(hex.formatHex(bytes, 32, 64)),
                now,
                leaseUntil,
                expiresAt);
    }

    private static int rows(DataSource dataSource) throws SQLException {
        try (Connection connection = dataSource.getConnection();
                Statement statement = connection.createStatement();
                ResultSet result = statement.executeQuery("SELECT COUNT(*) FROM " + TABLE)) {
            result.next();
            return result.getInt(1);
        }
    }

    private static void expectInvalid(ApplicationContextRunner runner, String property, String... values) {
        runner.withPropertyValues(values)
                .run(context -> assertThat(analyze(context).getDescription())
                        .startsWith("Invalid value of '" + property + "'"));
    }

    private static FailureAnalysis analyze(AssertableApplicationContext context) {
        assertThat(context).hasFailed();
        Throwable failure = Objects.requireNonNull(context.getStartupFailure());
        return Objects.requireNonNull(new IdempotencyFailureAnalyzer().analyze(failure), "no analysis");
    }

    private static DataSource freshSchema() throws SQLException {
        String schema = "s_" + UUID.randomUUID().toString().replace("-", "").substring(0, 12);
        try (Connection connection = POSTGRES.createConnection("");
                Statement statement = connection.createStatement()) {
            statement.execute("CREATE SCHEMA " + schema);
        }
        String url = POSTGRES.getJdbcUrl();
        return dataSource(url + (url.contains("?") ? "&" : "?") + "currentSchema=" + schema);
    }

    // Connects only when a test makes it, and fails at once.
    private static DataSource unreachable() {
        return dataSource("jdbc:postgresql://127.0.0.1:1/none");
    }

    private static DataSource dataSource(String url) {
        PGSimpleDataSource dataSource = new PGSimpleDataSource();
        dataSource.setUrl(url);
        dataSource.setUser(POSTGRES.getUsername());
        dataSource.setPassword(POSTGRES.getPassword());
        dataSource.setConnectTimeout(1);
        return dataSource;
    }

    private static void execute(DataSource dataSource, String sql) throws SQLException {
        try (Connection connection = dataSource.getConnection();
                Statement statement = connection.createStatement()) {
            statement.execute(sql);
        }
    }
}
