// SPDX-License-Identifier: Apache-2.0
package io.github.eduardoalecrim.idempotency.store.jdbc;

import io.github.eduardoalecrim.idempotency.spi.ClaimRequest;
import io.github.eduardoalecrim.idempotency.spi.ClaimResult;
import io.github.eduardoalecrim.idempotency.spi.Fingerprint;
import io.github.eduardoalecrim.idempotency.spi.IdempotencyRecord;
import io.github.eduardoalecrim.idempotency.spi.IdempotencyStore;
import io.github.eduardoalecrim.idempotency.spi.IdempotencyStoreException;
import io.github.eduardoalecrim.idempotency.spi.IdempotencyStoreInspector;
import io.github.eduardoalecrim.idempotency.spi.OwnerToken;
import io.github.eduardoalecrim.idempotency.spi.RecordState;
import io.github.eduardoalecrim.idempotency.spi.RecordSummary;
import io.github.eduardoalecrim.idempotency.spi.ScopeKey;
import io.github.eduardoalecrim.idempotency.spi.ScopeParts;
import io.github.eduardoalecrim.idempotency.spi.StoreHealth;
import io.github.eduardoalecrim.idempotency.spi.StoredResponse;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.regex.Pattern;
import javax.sql.DataSource;
import org.jspecify.annotations.Nullable;

/**
 * Keeps records in one table of a relational database (SPEC §5.7, §6.7).
 *
 * <p>Every statement touches one row, runs on its own auto-commit connection taken from the data source, and is safe
 * under {@code READ COMMITTED}; records stay outside the application's transactions (ADR 0004). Time comparisons use
 * the instants passed in, never the database clock (ADR 0006).
 */
public final class JdbcIdempotencyStore implements IdempotencyStore, IdempotencyStoreInspector {

    /** Default table name. */
    public static final String DEFAULT_TABLE_NAME = "idempotency_record";

    private static final Pattern TABLE_NAME = Pattern.compile("[A-Za-z_][A-Za-z0-9_.]*");
    private static final String IN_PROGRESS = "IN_PROGRESS";
    private static final String COMPLETED = "COMPLETED";
    // Oracle stores an empty string as NULL, so the anonymous client is written as a single space.
    private static final String ANONYMOUS_CLIENT = " ";
    private static final int CLAIM_ROUNDS = 3;
    private static final String COLUMNS =
            "scope_key, idempotency_key, client_id, http_method, route, fingerprint, state,"
                    + " owner_token, lease_until, response_status, response_headers, response_body, body_omitted,"
                    + " created_at, completed_at, expires_at";

    private final DataSource dataSource;
    private final String table;
    private final int queryTimeoutSeconds;
    private volatile @Nullable JdbcDialect dialect;

    private JdbcIdempotencyStore(Builder builder) {
        this.dataSource = builder.dataSource;
        this.table = builder.tableName;
        this.dialect = builder.dialect;
        long millis = builder.queryTimeout.toMillis();
        this.queryTimeoutSeconds = (int) Math.max(1, (millis + 999) / 1000);
    }

    /** Returns a builder for a store on {@code dataSource}. */
    public static Builder builder(DataSource dataSource) {
        return new Builder(dataSource);
    }

    /** Returns the table name. */
    public String tableName() {
        return table;
    }

    /**
     * Returns the dialect, detecting it from the database on first use.
     *
     * @throws IdempotencyStoreException if the database is unreachable or not supported
     */
    public JdbcDialect dialect() {
        JdbcDialect known = dialect;
        if (known != null) {
            return known;
        }
        try (Connection connection = dataSource.getConnection()) {
            String product = connection.getMetaData().getDatabaseProductName();
            JdbcDialect detected = JdbcDialect.fromProductName(product)
                    .orElseThrow(() -> new IdempotencyStoreException(
                            "Unsupported database '" + product + "'; set the dialect explicitly"));
            dialect = detected;
            return detected;
        } catch (SQLException e) {
            throw new IdempotencyStoreException("JDBC store could not detect the database dialect", e);
        }
    }

    @Override
    public ClaimResult claim(ClaimRequest request) {
        JdbcDialect dialect = dialect();
        try (Connection connection = connection()) {
            for (int round = 0; round < CLAIM_ROUNDS; round++) {
                OwnerToken token = OwnerToken.random();
                if (insert(connection, dialect, request, token) || takeOver(connection, dialect, request, token)) {
                    return new ClaimResult.Acquired(token);
                }
                IdempotencyRecord existing = select(connection, dialect, request.key());
                if (existing != null) {
                    return new ClaimResult.Existing(existing);
                }
                // Purged between the update and the select: start again.
            }
            throw new IdempotencyStoreException(
                    "JDBC store could not claim a record after " + CLAIM_ROUNDS + " rounds");
        } catch (SQLException e) {
            throw new IdempotencyStoreException("JDBC store failed to claim a record", e);
        }
    }

    @Override
    public boolean complete(
            ScopeKey key, OwnerToken token, StoredResponse response, Instant completedAt, Instant expiresAt) {
        JdbcDialect dialect = dialect();
        String sql = "UPDATE " + table + " SET state = '" + COMPLETED + "', owner_token = NULL, lease_until = NULL,"
                + " response_status = ?, response_headers = ?, response_body = ?, body_omitted = ?,"
                + " completed_at = ?, expires_at = ? WHERE scope_key = ? AND owner_token = ? AND state = '"
                + IN_PROGRESS + "'";
        try (Connection connection = connection();
                PreparedStatement statement = prepare(connection, sql)) {
            statement.setInt(1, response.status());
            statement.setString(2, HeaderLines.encode(response.headers()));
            statement.setBytes(3, response.body());
            statement.setBoolean(4, response.bodyOmitted());
            dialect.setInstant(statement, 5, completedAt);
            dialect.setInstant(statement, 6, expiresAt);
            statement.setString(7, key.value());
            statement.setString(8, token.value());
            return statement.executeUpdate() == 1;
        } catch (SQLException e) {
            throw new IdempotencyStoreException("JDBC store failed to complete a record", e);
        }
    }

    @Override
    public void release(ScopeKey key, OwnerToken token) {
        String sql =
                "DELETE FROM " + table + " WHERE scope_key = ? AND owner_token = ? AND state = '" + IN_PROGRESS + "'";
        try (Connection connection = connection();
                PreparedStatement statement = prepare(connection, sql)) {
            statement.setString(1, key.value());
            statement.setString(2, token.value());
            statement.executeUpdate();
        } catch (SQLException e) {
            throw new IdempotencyStoreException("JDBC store failed to release a record", e);
        }
    }

    @Override
    public Optional<IdempotencyRecord> find(ScopeKey key, Instant now) {
        JdbcDialect dialect = dialect();
        try (Connection connection = connection()) {
            IdempotencyRecord record = select(connection, dialect, key);
            return record == null || record.isExpired(now) ? Optional.empty() : Optional.of(record);
        } catch (SQLException e) {
            throw new IdempotencyStoreException("JDBC store failed to read a record", e);
        }
    }

    @Override
    public int purgeExpired(Instant now, int batchSize) {
        JdbcDialect dialect = dialect();
        try (Connection connection = connection();
                PreparedStatement statement = dialect.purge(connection, table, now, batchSize)) {
            statement.setQueryTimeout(queryTimeoutSeconds);
            return statement.executeUpdate();
        } catch (SQLException e) {
            throw new IdempotencyStoreException("JDBC store failed to purge expired records", e);
        }
    }

    @Override
    public StoreHealth health() {
        try (Connection connection = connection();
                PreparedStatement statement = prepare(connection, dialect().ping());
                ResultSet rows = statement.executeQuery()) {
            if (!rows.next()) {
                return StoreHealth.down("jdbc: the ping returned no row");
            }
            return StoreHealth.up("jdbc, " + connection.getMetaData().getDatabaseProductName());
        } catch (SQLException | IdempotencyStoreException e) {
            return StoreHealth.down("jdbc: " + e.getMessage());
        }
    }

    @Override
    public List<RecordSummary> recent(int limit) {
        JdbcDialect dialect = dialect();
        String sql = dialect.limit("SELECT " + COLUMNS + " FROM " + table + " ORDER BY created_at DESC");
        try (Connection connection = connection();
                PreparedStatement statement = prepare(connection, sql)) {
            statement.setInt(1, limit);
            List<RecordSummary> summaries = new ArrayList<>();
            try (ResultSet rows = statement.executeQuery()) {
                while (rows.next()) {
                    summaries.add(RecordSummary.of(map(rows, dialect)));
                }
            }
            return summaries;
        } catch (SQLException e) {
            throw new IdempotencyStoreException("JDBC store failed to list records", e);
        }
    }

    private boolean insert(Connection connection, JdbcDialect dialect, ClaimRequest request, OwnerToken token)
            throws SQLException {
        String sql = dialect.insert(
                table,
                "scope_key, idempotency_key, client_id, http_method, route, fingerprint, state, owner_token,"
                        + " lease_until, body_omitted, created_at, expires_at",
                "?, ?, ?, ?, ?, ?, '" + IN_PROGRESS + "', ?, ?, ?, ?, ?");
        try (PreparedStatement statement = prepare(connection, sql)) {
            ScopeParts parts = request.parts();
            statement.setString(1, request.key().value());
            statement.setString(2, truncate(parts.idempotencyKey(), 255));
            statement.setString(3, truncate(clientColumn(parts.clientId()), 255));
            statement.setString(4, parts.method());
            statement.setString(5, truncate(parts.route(), 512));
            statement.setString(6, request.fingerprint().value());
            statement.setString(7, token.value());
            dialect.setInstant(statement, 8, request.leaseUntil());
            statement.setBoolean(9, false);
            dialect.setInstant(statement, 10, request.now());
            dialect.setInstant(statement, 11, request.expiresAt());
            return statement.executeUpdate() == 1;
        } catch (SQLException e) {
            // Class 23 is an integrity violation: the row exists (MySQL 1062, Oracle ORA-00001, SQL Server 2627/2601).
            String state = e.getSQLState();
            if (state != null && state.startsWith("23")) {
                return false;
            }
            throw e;
        }
    }

    private boolean takeOver(Connection connection, JdbcDialect dialect, ClaimRequest request, OwnerToken token)
            throws SQLException {
        String sql = "UPDATE " + table + " SET state = '" + IN_PROGRESS + "', idempotency_key = ?, client_id = ?,"
                + " http_method = ?, route = ?, owner_token = ?, fingerprint = ?, lease_until = ?, created_at = ?,"
                + " expires_at = ?, response_status = NULL, response_headers = NULL, response_body = NULL,"
                + " body_omitted = ?, completed_at = NULL WHERE scope_key = ?"
                + " AND ((state = '" + IN_PROGRESS + "' AND lease_until < ?) OR expires_at < ?)";
        try (PreparedStatement statement = prepare(connection, sql)) {
            ScopeParts parts = request.parts();
            statement.setString(1, truncate(parts.idempotencyKey(), 255));
            statement.setString(2, truncate(clientColumn(parts.clientId()), 255));
            statement.setString(3, parts.method());
            statement.setString(4, truncate(parts.route(), 512));
            statement.setString(5, token.value());
            statement.setString(6, request.fingerprint().value());
            dialect.setInstant(statement, 7, request.leaseUntil());
            dialect.setInstant(statement, 8, request.now());
            dialect.setInstant(statement, 9, request.expiresAt());
            statement.setBoolean(10, false);
            statement.setString(11, request.key().value());
            dialect.setInstant(statement, 12, request.now());
            dialect.setInstant(statement, 13, request.now());
            return statement.executeUpdate() == 1;
        }
    }

    private @Nullable IdempotencyRecord select(Connection connection, JdbcDialect dialect, ScopeKey key)
            throws SQLException {
        try (PreparedStatement statement =
                prepare(connection, "SELECT " + COLUMNS + " FROM " + table + " WHERE scope_key = ?")) {
            statement.setString(1, key.value());
            try (ResultSet rows = statement.executeQuery()) {
                return rows.next() ? map(rows, dialect) : null;
            }
        }
    }

    private static IdempotencyRecord map(ResultSet row, JdbcDialect dialect) throws SQLException {
        RecordState state = RecordState.valueOf(row.getString("state").trim());
        ScopeParts parts = new ScopeParts(
                row.getString("idempotency_key"),
                fromClientColumn(row.getString("client_id")),
                row.getString("http_method"),
                row.getString("route"));
        StoredResponse response = null;
        if (state == RecordState.COMPLETED) {
            byte[] body = row.getBytes("response_body");
            response = new StoredResponse(
                    row.getInt("response_status"),
                    HeaderLines.decode(row.getString("response_headers")),
                    body == null ? new byte[0] : body,
                    row.getBoolean("body_omitted"));
        }
        String owner = row.getString("owner_token");
        return new IdempotencyRecord(
                new ScopeKey(row.getString("scope_key").trim()),
                parts,
                new Fingerprint(row.getString("fingerprint").trim()),
                state,
                owner == null ? null : new OwnerToken(owner.trim()),
                dialect.getInstant(row, "lease_until"),
                response,
                Objects.requireNonNull(dialect.getInstant(row, "created_at")),
                dialect.getInstant(row, "completed_at"),
                Objects.requireNonNull(dialect.getInstant(row, "expires_at")));
    }

    private Connection connection() throws SQLException {
        Connection connection = dataSource.getConnection();
        if (!connection.getAutoCommit()) {
            connection.setAutoCommit(true);
        }
        return connection;
    }

    private PreparedStatement prepare(Connection connection, String sql) throws SQLException {
        PreparedStatement statement = connection.prepareStatement(sql);
        statement.setQueryTimeout(queryTimeoutSeconds);
        return statement;
    }

    private static String clientColumn(String clientId) {
        return clientId.isEmpty() ? ANONYMOUS_CLIENT : clientId;
    }

    private static String fromClientColumn(@Nullable String column) {
        return column == null || column.equals(ANONYMOUS_CLIENT) ? "" : column;
    }

    // The diagnostic columns have fixed widths; the scope key alone identifies the record.
    private static String truncate(String value, int maxChars) {
        return value.length() <= maxChars ? value : value.substring(0, maxChars);
    }

    /** Builds a {@link JdbcIdempotencyStore}. */
    public static final class Builder {

        private final DataSource dataSource;
        private String tableName = DEFAULT_TABLE_NAME;
        private @Nullable JdbcDialect dialect;
        private Duration queryTimeout = Duration.ofSeconds(2);

        private Builder(DataSource dataSource) {
            this.dataSource = dataSource;
        }

        /**
         * Sets the table, optionally schema-qualified; default {@value #DEFAULT_TABLE_NAME}.
         *
         * @throws IllegalArgumentException unless it matches {@code [A-Za-z_][A-Za-z0-9_.]*}
         */
        public Builder tableName(String tableName) {
            if (!TABLE_NAME.matcher(tableName).matches()) {
                throw new IllegalArgumentException("Invalid table name '" + tableName + "'");
            }
            this.tableName = tableName;
            return this;
        }

        /** Sets the dialect; detected from the database when not set. */
        public Builder dialect(JdbcDialect dialect) {
            this.dialect = dialect;
            return this;
        }

        /** Sets the timeout of each statement, rounded up to whole seconds; default 2 seconds. */
        public Builder queryTimeout(Duration queryTimeout) {
            if (queryTimeout.isNegative() || queryTimeout.isZero()) {
                throw new IllegalArgumentException("queryTimeout must be positive");
            }
            this.queryTimeout = queryTimeout;
            return this;
        }

        /** Builds the store. */
        public JdbcIdempotencyStore build() {
            return new JdbcIdempotencyStore(this);
        }
    }
}
