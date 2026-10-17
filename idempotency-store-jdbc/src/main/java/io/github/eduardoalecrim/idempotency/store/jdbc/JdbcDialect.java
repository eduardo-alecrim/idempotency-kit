// SPDX-License-Identifier: Apache-2.0
package io.github.eduardoalecrim.idempotency.store.jdbc;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.Locale;
import java.util.Optional;
import org.jspecify.annotations.Nullable;

/** What differs between the supported databases. */
public enum JdbcDialect {
    /** PostgreSQL: {@code ON CONFLICT DO NOTHING}, {@code TIMESTAMP WITH TIME ZONE}. */
    POSTGRESQL(true, true) {
        @Override
        PreparedStatement purge(Connection connection, String table, Instant now, int batchSize) throws SQLException {
            // SKIP LOCKED lets several instances purge at once without waiting for each other.
            PreparedStatement statement = connection.prepareStatement("DELETE FROM " + table
                    + " WHERE expires_at < ? AND scope_key IN (SELECT scope_key FROM " + table
                    + " WHERE expires_at < ? ORDER BY expires_at LIMIT ? FOR UPDATE SKIP LOCKED)");
            setInstant(statement, 1, now);
            setInstant(statement, 2, now);
            statement.setInt(3, batchSize);
            return statement;
        }
    },
    /** MySQL: {@code DATETIME(6)} holding UTC. */
    MYSQL(false, false) {
        @Override
        PreparedStatement purge(Connection connection, String table, Instant now, int batchSize) throws SQLException {
            return limitedDelete(this, connection, table, now, batchSize);
        }
    },
    /** MariaDB: {@code DATETIME(6)} holding UTC. */
    MARIADB(false, false) {
        @Override
        PreparedStatement purge(Connection connection, String table, Instant now, int batchSize) throws SQLException {
            return limitedDelete(this, connection, table, now, batchSize);
        }
    },
    /** Oracle: {@code TIMESTAMP WITH TIME ZONE}; empty strings are {@code NULL}. */
    ORACLE(false, true) {
        @Override
        PreparedStatement purge(Connection connection, String table, Instant now, int batchSize) throws SQLException {
            PreparedStatement statement =
                    connection.prepareStatement("DELETE FROM " + table + " WHERE expires_at < ? AND ROWNUM <= ?");
            setInstant(statement, 1, now);
            statement.setInt(2, batchSize);
            return statement;
        }

        @Override
        String ping() {
            return "SELECT 1 FROM DUAL";
        }

        @Override
        String limit(String select) {
            return select + " OFFSET 0 ROWS FETCH NEXT ? ROWS ONLY";
        }
    },
    /** SQL Server: {@code DATETIMEOFFSET(6)}. */
    SQLSERVER(false, true) {
        @Override
        PreparedStatement purge(Connection connection, String table, Instant now, int batchSize) throws SQLException {
            PreparedStatement statement =
                    connection.prepareStatement("DELETE TOP (?) FROM " + table + " WHERE expires_at < ?");
            statement.setInt(1, batchSize);
            setInstant(statement, 2, now);
            return statement;
        }

        @Override
        String limit(String select) {
            return select + " OFFSET 0 ROWS FETCH NEXT ? ROWS ONLY";
        }
    };

    private final boolean insertIgnoresConflicts;
    private final boolean offsetTimestamps;

    JdbcDialect(boolean insertIgnoresConflicts, boolean offsetTimestamps) {
        this.insertIgnoresConflicts = insertIgnoresConflicts;
        this.offsetTimestamps = offsetTimestamps;
    }

    /** Returns the dialect of a {@code DatabaseMetaData.getDatabaseProductName()} value. */
    public static Optional<JdbcDialect> fromProductName(String productName) {
        String name = productName.toLowerCase(Locale.ROOT);
        if (name.contains("postgresql")) {
            return Optional.of(POSTGRESQL);
        }
        if (name.contains("mariadb")) {
            return Optional.of(MARIADB);
        }
        if (name.contains("mysql")) {
            return Optional.of(MYSQL);
        }
        if (name.contains("oracle")) {
            return Optional.of(ORACLE);
        }
        if (name.contains("sql server")) {
            return Optional.of(SQLSERVER);
        }
        return Optional.empty();
    }

    /** Returns the bounded delete of expired rows, bound and ready to execute. */
    abstract PreparedStatement purge(Connection connection, String table, Instant now, int batchSize)
            throws SQLException;

    /** Returns the insert of a new in-progress row; on PostgreSQL a conflict inserts nothing instead of failing. */
    String insert(String table, String columns, String values) {
        String insert = "INSERT INTO " + table + " (" + columns + ") VALUES (" + values + ")";
        return insertIgnoresConflicts ? insert + " ON CONFLICT (scope_key) DO NOTHING" : insert;
    }

    /** Returns a cheap statement that proves the database answers. */
    String ping() {
        return "SELECT 1";
    }

    /** Appends a row limit, bound as the last parameter. */
    String limit(String select) {
        return select + " LIMIT ?";
    }

    /** Binds an instant: with offset UTC, or as a UTC local date-time where the column has no zone. */
    void setInstant(PreparedStatement statement, int index, Instant instant) throws SQLException {
        if (offsetTimestamps) {
            statement.setObject(index, OffsetDateTime.ofInstant(instant, ZoneOffset.UTC));
        } else {
            statement.setObject(index, LocalDateTime.ofInstant(instant, ZoneOffset.UTC));
        }
    }

    /** Reads an instant written by {@link #setInstant}. */
    @Nullable
    Instant getInstant(ResultSet row, String column) throws SQLException {
        if (offsetTimestamps) {
            OffsetDateTime value = row.getObject(column, OffsetDateTime.class);
            return value == null ? null : value.toInstant();
        }
        LocalDateTime value = row.getObject(column, LocalDateTime.class);
        return value == null ? null : value.toInstant(ZoneOffset.UTC);
    }

    private static PreparedStatement limitedDelete(
            JdbcDialect dialect, Connection connection, String table, Instant now, int batchSize) throws SQLException {
        PreparedStatement statement = connection.prepareStatement(
                "DELETE FROM " + table + " WHERE expires_at < ? ORDER BY expires_at LIMIT ?");
        dialect.setInstant(statement, 1, now);
        statement.setInt(2, batchSize);
        return statement;
    }
}
