// SPDX-License-Identifier: Apache-2.0
package io.github.eduardoalecrim.idempotency.store.jdbc;

import java.io.PrintWriter;
import java.sql.Connection;
import java.sql.SQLException;
import java.time.Duration;
import java.util.logging.Logger;
import javax.sql.DataSource;
import org.jspecify.annotations.Nullable;

/** A data source whose database is never reachable, after an optional hang. */
final class NoDataSource implements DataSource {

    private final Duration hang;

    /** Fails at once. */
    NoDataSource() {
        this(Duration.ZERO);
    }

    /** Hangs for {@code hang}, or until interrupted, before failing. */
    NoDataSource(Duration hang) {
        this.hang = hang;
    }

    @Override
    public Connection getConnection() throws SQLException {
        try {
            Thread.sleep(hang.toMillis());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        throw new SQLException("unreachable", "08001");
    }

    @Override
    public Connection getConnection(String username, String password) throws SQLException {
        return getConnection();
    }

    @Override
    public @Nullable PrintWriter getLogWriter() {
        return null;
    }

    @Override
    public void setLogWriter(@Nullable PrintWriter out) {}

    @Override
    public void setLoginTimeout(int seconds) {}

    @Override
    public int getLoginTimeout() {
        return 0;
    }

    @Override
    public Logger getParentLogger() {
        return Logger.getGlobal();
    }

    @Override
    public <T> T unwrap(Class<T> type) throws SQLException {
        throw new SQLException("not a wrapper");
    }

    @Override
    public boolean isWrapperFor(Class<?> type) {
        return false;
    }
}
