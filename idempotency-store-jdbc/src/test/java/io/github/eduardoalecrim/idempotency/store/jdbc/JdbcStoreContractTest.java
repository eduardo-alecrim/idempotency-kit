// SPDX-License-Identifier: Apache-2.0
package io.github.eduardoalecrim.idempotency.store.jdbc;

import static org.assertj.core.api.Assertions.assertThat;

import com.zaxxer.hikari.HikariDataSource;
import io.github.eduardoalecrim.idempotency.spi.ClaimRequest;
import io.github.eduardoalecrim.idempotency.spi.Fingerprint;
import io.github.eduardoalecrim.idempotency.spi.IdempotencyStore;
import io.github.eduardoalecrim.idempotency.spi.ScopeKey;
import io.github.eduardoalecrim.idempotency.spi.ScopeParts;
import io.github.eduardoalecrim.idempotency.tck.IdempotencyStoreContract;
import java.security.SecureRandom;
import java.sql.Connection;
import java.sql.Statement;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;

/** FR-24: the store TCK on every database, each skipped unless selected by {@code idempotency.tck.databases}. */
@DisplayName("FR-24: JDBC store")
class JdbcStoreContractTest {

    @Nested
    @DisplayName("PostgreSQL")
    class PostgreSql extends OnDatabase {
        PostgreSql() {
            super(TestDatabase.POSTGRESQL);
        }

        @Test
        @DisplayName("FR-26: a purge deletes one batch even when the plan rescans the batch query")
        void purgeStaysWithinTheBatch() throws Exception {
            String schema = "s_" + UUID.randomUUID().toString().replace("-", "").substring(0, 12);
            // Leaves a nested loop that rescans its inner side as the only plan for an IN subquery.
            try (HikariDataSource dataSource = TestDatabase.freshPostgreSqlSchema(
                    schema,
                    "SET enable_material = off",
                    "SET enable_hashjoin = off",
                    "SET enable_mergejoin = off",
                    "SET enable_sort = off",
                    "SET enable_hashagg = off")) {
                JdbcSchema.createIfMissing(dataSource, JdbcDialect.POSTGRESQL, JdbcIdempotencyStore.DEFAULT_TABLE_NAME);
                JdbcIdempotencyStore store =
                        JdbcIdempotencyStore.builder(dataSource).build();
                Instant now = Instant.parse("2026-10-17T10:00:00Z");
                for (int i = 0; i < 6; i++) {
                    store.claim(request(now.minus(Duration.ofHours(2)), now.minusSeconds(i + 1)));
                }
                // Mostly live rows, so the planner reads the expired ones through the expires_at index.
                try (Connection connection = dataSource.getConnection();
                        Statement statement = connection.createStatement()) {
                    statement.execute("INSERT INTO " + JdbcIdempotencyStore.DEFAULT_TABLE_NAME
                            + " (scope_key, idempotency_key, client_id, http_method, route, fingerprint, state,"
                            + " created_at, expires_at) SELECT lpad(g::text, 64, '0'), 'k', ' ', 'POST', '/o',"
                            + " lpad(g::text, 64, '0'), 'COMPLETED', now(), now() + interval '1 day'"
                            + " FROM generate_series(1, 20000) g");
                    statement.execute("ANALYZE " + JdbcIdempotencyStore.DEFAULT_TABLE_NAME);
                }

                assertThat(store.purgeExpired(now, 2)).isEqualTo(2);
            }
        }
    }

    @Nested
    @DisplayName("MySQL")
    class MySql extends OnDatabase {
        MySql() {
            super(TestDatabase.MYSQL);
        }
    }

    @Nested
    @DisplayName("MariaDB")
    class MariaDb extends OnDatabase {
        MariaDb() {
            super(TestDatabase.MARIADB);
        }
    }

    @Nested
    @DisplayName("Oracle")
    class Oracle extends OnDatabase {
        Oracle() {
            super(TestDatabase.ORACLE);
        }
    }

    @Nested
    @DisplayName("SQL Server")
    class SqlServer extends OnDatabase {
        SqlServer() {
            super(TestDatabase.SQLSERVER);
        }
    }

    @TestInstance(TestInstance.Lifecycle.PER_CLASS)
    abstract static class OnDatabase extends IdempotencyStoreContract {

        private static final SecureRandom RANDOM = new SecureRandom();

        private final TestDatabase database;

        OnDatabase(TestDatabase database) {
            this.database = database;
        }

        @BeforeAll
        void selected() {
            Assumptions.assumeTrue(database.selected(), database + " is not in idempotency.tck.databases");
        }

        @Override
        protected IdempotencyStore createStore(Clock clock) {
            return JdbcIdempotencyStore.builder(database.dataSource()).build();
        }

        @Test
        void detectsTheDialect() {
            assertThat(JdbcIdempotencyStore.builder(database.dataSource())
                            .build()
                            .dialect())
                    .isEqualTo(database.dialect());
        }

        @Test
        @DisplayName("FR-25: the table from the shipped script passes validation")
        void validatesTheShippedSchema() {
            assertThat(JdbcSchema.problems(database.dataSource(), JdbcIdempotencyStore.DEFAULT_TABLE_NAME))
                    .isEmpty();
            assertThat(JdbcSchema.problems(database.dataSource(), "no_such_table"))
                    .containsExactly("table no_such_table does not exist");
        }

        @Test
        @DisplayName("FR-26: concurrent purges delete each expired row once and never a live one")
        void concurrentPurgesDeleteEachRowOnce() throws Exception {
            String table = "purge_" + UUID.randomUUID().toString().substring(0, 8);
            JdbcSchema.createIfMissing(database.dataSource(), database.dialect(), table);
            JdbcIdempotencyStore store = JdbcIdempotencyStore.builder(database.dataSource())
                    .tableName(table)
                    .build();
            Instant now = Instant.parse("2026-10-17T10:00:00Z");
            int expired = 300;
            for (int i = 0; i < expired; i++) {
                Instant created = now.minus(Duration.ofHours(2));
                store.claim(request(created, now.minusSeconds(i + 1)));
            }
            List<ClaimRequest> live = new ArrayList<>();
            for (int i = 0; i < 20; i++) {
                live.add(request(now, now.plus(Duration.ofHours(1))));
                store.claim(live.get(i));
            }

            CountDownLatch start = new CountDownLatch(1);
            Callable<Integer> purger = () -> {
                start.await();
                int deleted = 0;
                int batch;
                do {
                    batch = store.purgeExpired(now, 25);
                    assertThat(batch).isBetween(0, 25);
                    deleted += batch;
                } while (batch > 0);
                return deleted;
            };
            ExecutorService executor = Executors.newFixedThreadPool(2);
            try {
                Future<Integer> first = executor.submit(purger);
                Future<Integer> second = executor.submit(purger);
                start.countDown();

                assertThat(first.get() + second.get()).isEqualTo(expired);
            } finally {
                executor.shutdownNow();
            }
            assertThat(live)
                    .allSatisfy(
                            claim -> assertThat(store.find(claim.key(), now)).isPresent());
        }

        static ClaimRequest request(Instant now, Instant expiresAt) {
            Instant leaseUntil = now.plusSeconds(30).isBefore(expiresAt) ? now.plusSeconds(30) : expiresAt;
            return new ClaimRequest(
                    new ScopeKey(randomHex()),
                    new ScopeParts("k", "", "POST", "/orders"),
                    new Fingerprint(randomHex()),
                    now,
                    leaseUntil,
                    expiresAt);
        }

        private static String randomHex() {
            byte[] bytes = new byte[32];
            RANDOM.nextBytes(bytes);
            return HexFormat.of().formatHex(bytes);
        }
    }
}
