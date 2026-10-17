// SPDX-License-Identifier: Apache-2.0
package io.github.eduardoalecrim.idempotency.store.jdbc;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.eduardoalecrim.idempotency.spi.IdempotencyStore;
import io.github.eduardoalecrim.idempotency.tck.IdempotencyStoreContract;
import java.time.Clock;
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
    }
}
