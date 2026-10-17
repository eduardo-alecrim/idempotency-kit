// SPDX-License-Identifier: Apache-2.0
package io.github.eduardoalecrim.idempotency.store.jdbc;

import java.io.PrintWriter;
import java.sql.Connection;
import java.sql.SQLException;
import java.util.logging.Logger;
import javax.sql.DataSource;
import org.jspecify.annotations.Nullable;

/** A data source whose database is never reachable. */
final class NoDataSource implements DataSource {

    @Override
    public Connection getConnection() throws SQLException {
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
