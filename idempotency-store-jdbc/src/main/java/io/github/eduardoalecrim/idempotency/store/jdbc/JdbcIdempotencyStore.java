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
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Future;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.regex.Pattern;
import javax.sql.DataSource;
import org.jspecify.annotations.Nullable;

/**
 * Keeps records in one table of a relational database (SPEC §5.7, §6.7).
 *
 * <p>Every statement touches one row, runs on its own auto-commit connection taken from the data source, and is safe
 * under {@code READ COMMITTED}; records stay outside the application's transactions (ADR 0004). Time comparisons use
 * the instants passed in, never the database clock (ADR 0006).
 *
 * <p>A caller waits at most the {@linkplain Builder#timeout(Duration) timeout} for each call, which runs on a bounded pool of
 * store threads; a pool or network that hangs fails the call instead of holding the caller (FR-21, NFR-10).
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

    private static final AtomicInteger THREADS = new AtomicInteger();

    private final DataSource dataSource;
    private final String table;
    private final Duration timeout;
    private final int queryTimeoutSeconds;
    private final ExecutorService executor;
    private volatile @Nullable JdbcDialect dialect;

    private JdbcIdempotencyStore(Builder builder) {
        this.dataSource = builder.dataSource;
        this.table = builder.tableName;
        this.dialect = builder.dialect;
        this.timeout = builder.timeout;
        this.queryTimeoutSeconds = (int) Math.max(1, (timeout.toMillis() + 999) / 1000);
        int threads = builder.maxConcurrentCalls;
        ThreadPoolExecutor pool = new ThreadPoolExecutor(
                threads, threads, 60, TimeUnit.SECONDS, new LinkedBlockingQueue<>(threads * 4), task -> {
                    Thread thread = new Thread(task, "idempotency-jdbc-" + THREADS.incrementAndGet());
                    thread.setDaemon(true);
                    return thread;
                });
        pool.allowCoreThreadTimeOut(true);
        this.executor = pool;
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
        return call(
                "claim a record",
                () -> withConnection(connection -> {
                    JdbcDialect dialect = dialect();
                    for (int round = 0; round < CLAIM_ROUNDS; round++) {
                        OwnerToken token = OwnerToken.random();
                        if (insert(connection, dialect, request, token)
                                || takeOver(connection, dialect, request, token)) {
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
                }));
    }

    @Override
    public boolean complete(
            ScopeKey key, OwnerToken token, StoredResponse response, Instant completedAt, Instant expiresAt) {
        String sql = "UPDATE " + table + " SET state = '" + COMPLETED + "', owner_token = NULL, lease_until = NULL,"
                + " response_status = ?, response_headers = ?, response_body = ?, body_omitted = ?,"
                + " completed_at = ?, expires_at = ? WHERE scope_key = ? AND owner_token = ? AND state = '"
                + IN_PROGRESS + "'";
        return call(
                "complete a record",
                () -> withConnection(connection -> {
                    JdbcDialect dialect = dialect();
                    try (PreparedStatement statement = prepare(connection, sql)) {
                        statement.setInt(1, response.status());
                        statement.setString(2, HeaderLines.encode(response.headers()));
                        statement.setBytes(3, response.body());
                        statement.setBoolean(4, response.bodyOmitted());
                        dialect.setInstant(statement, 5, completedAt);
                        dialect.setInstant(statement, 6, expiresAt);
                        statement.setString(7, key.value());
                        statement.setString(8, token.value());
                        return statement.executeUpdate() == 1;
                    }
                }));
    }

    @Override
    public void release(ScopeKey key, OwnerToken token) {
        String sql =
                "DELETE FROM " + table + " WHERE scope_key = ? AND owner_token = ? AND state = '" + IN_PROGRESS + "'";
        call(
                "release a record",
                () -> withConnection(connection -> {
                    try (PreparedStatement statement = prepare(connection, sql)) {
                        statement.setString(1, key.value());
                        statement.setString(2, token.value());
                        return statement.executeUpdate();
                    }
                }));
    }

    @Override
    public Optional<IdempotencyRecord> find(ScopeKey key, Instant now) {
        Optional<IdempotencyRecord> record = call(
                "read a record",
                () -> withConnection(connection -> Optional.ofNullable(select(connection, dialect(), key))));
        return record.filter(found -> !found.isExpired(now));
    }

    @Override
    public int purgeExpired(Instant now, int batchSize) {
        return call(
                "purge expired records",
                () -> withConnection(connection -> {
                    try (PreparedStatement statement = dialect().purge(connection, table, now, batchSize)) {
                        statement.setQueryTimeout(queryTimeoutSeconds);
                        return statement.executeUpdate();
                    }
                }));
    }

    @Override
    public StoreHealth health() {
        try {
            return call(
                    "check its health",
                    () -> withConnection(connection -> {
                        try (PreparedStatement statement =
                                        prepare(connection, dialect().ping());
                                ResultSet rows = statement.executeQuery()) {
                            if (!rows.next()) {
                                return StoreHealth.down("jdbc: the ping returned no row");
                            }
                            return StoreHealth.up(
                                    "jdbc, " + connection.getMetaData().getDatabaseProductName());
                        }
                    }));
        } catch (IdempotencyStoreException e) {
            return StoreHealth.down("jdbc: " + e.getMessage());
        }
    }

    @Override
    public List<RecordSummary> recent(int limit) {
        return call(
                "list records",
                () -> withConnection(connection -> {
                    JdbcDialect dialect = dialect();
                    String sql = dialect.limit("SELECT " + COLUMNS + " FROM " + table + " ORDER BY created_at DESC");
                    try (PreparedStatement statement = prepare(connection, sql)) {
                        statement.setInt(1, limit);
                        List<RecordSummary> summaries = new ArrayList<>();
                        try (ResultSet rows = statement.executeQuery()) {
                            while (rows.next()) {
                                summaries.add(RecordSummary.of(map(rows, dialect)));
                            }
                        }
                        return summaries;
                    }
                }));
    }

    /**
     * Runs {@code work} on a store thread and waits for it at most the timeout, so that a hung pool or network never
     * holds the caller longer (NFR-10). The work goes on until its own query and network timeouts end it.
     */
    private <T> T call(String action, Callable<T> work) {
        Future<T> future;
        try {
            future = executor.submit(work);
        } catch (RejectedExecutionException e) {
            throw new IdempotencyStoreException("JDBC store is saturated and could not " + action, e);
        }
        try {
            return future.get(timeout.toMillis(), TimeUnit.MILLISECONDS);
        } catch (TimeoutException e) {
            future.cancel(true);
            throw new IdempotencyStoreException("JDBC store did not " + action + " within " + timeout, e);
        } catch (InterruptedException e) {
            future.cancel(true);
            Thread.currentThread().interrupt();
            throw new IdempotencyStoreException("Interrupted while the JDBC store tried to " + action, e);
        } catch (ExecutionException e) {
            Throwable cause = e.getCause();
            if (cause instanceof IdempotencyStoreException storeFailure) {
                throw storeFailure;
            }
            if (cause instanceof RuntimeException runtime) {
                throw runtime;
            }
            if (cause instanceof Error error) {
                throw error;
            }
            throw new IdempotencyStoreException("JDBC store failed to " + action, cause == null ? e : cause);
        }
    }

    /**
     * Runs {@code work} on an auto-commit connection whose network timeout is the store timeout, restoring the previous
     * network timeout before the connection goes back to the pool.
     */
    private <T> T withConnection(SqlFunction<T> work) throws SQLException {
        try (Connection connection = dataSource.getConnection()) {
            if (!connection.getAutoCommit()) {
                connection.setAutoCommit(true);
            }
            int previous = connection.getNetworkTimeout();
            connection.setNetworkTimeout(Runnable::run, (int) Math.min(Integer.MAX_VALUE, timeout.toMillis()));
            T result;
            try {
                result = work.apply(connection);
            } catch (SQLException | RuntimeException e) {
                try {
                    connection.setNetworkTimeout(Runnable::run, previous);
                } catch (SQLException restore) {
                    e.addSuppressed(restore);
                }
                throw e;
            }
            connection.setNetworkTimeout(Runnable::run, previous);
            return result;
        }
    }

    @FunctionalInterface
    private interface SqlFunction<T> {
        T apply(Connection connection) throws SQLException;
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
        private Duration timeout = Duration.ofSeconds(2);
        private int maxConcurrentCalls = 64;

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

        /**
         * Sets how long a caller waits for one store call; default 2 seconds. Statements get it as their query timeout,
         * rounded up to whole seconds, and connections as their network timeout.
         */
        public Builder timeout(Duration timeout) {
            if (timeout.isNegative() || timeout.isZero()) {
                throw new IllegalArgumentException("timeout must be positive");
            }
            this.timeout = timeout;
            return this;
        }

        /**
         * Sets how many store calls run at once; default 64. A few more wait in a queue, and calls beyond that fail at
         * once, so that a database outage cannot pile up threads.
         */
        public Builder maxConcurrentCalls(int maxConcurrentCalls) {
            if (maxConcurrentCalls < 1) {
                throw new IllegalArgumentException("maxConcurrentCalls must be positive");
            }
            this.maxConcurrentCalls = maxConcurrentCalls;
            return this;
        }

        /** Builds the store. */
        public JdbcIdempotencyStore build() {
            return new JdbcIdempotencyStore(this);
        }
    }
}
