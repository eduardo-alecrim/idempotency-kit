// SPDX-License-Identifier: Apache-2.0
package io.github.eduardoalecrim.idempotency.store.jdbc;

import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.Arrays;
import java.util.EnumMap;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.function.Supplier;
import java.util.stream.Collectors;
import javax.sql.DataSource;
import org.testcontainers.containers.JdbcDatabaseContainer;
import org.testcontainers.mariadb.MariaDBContainer;
import org.testcontainers.mssqlserver.MSSQLServerContainer;
import org.testcontainers.mysql.MySQLContainer;
import org.testcontainers.oracle.OracleContainer;
import org.testcontainers.postgresql.PostgreSQLContainer;

/**
 * A database in a container, started once per JVM with the shipped schema. The system property
 * {@value #DATABASES_PROPERTY} lists the databases to test ({@code postgresql,mysql} by default, {@code all} for
 * every one).
 */
@SuppressWarnings("ImmutableEnumChecker") // the container suppliers are stateless
enum TestDatabase {
    POSTGRESQL(JdbcDialect.POSTGRESQL, () -> new PostgreSQLContainer("postgres:18-alpine")),
    MYSQL(JdbcDialect.MYSQL, () -> new MySQLContainer("mysql:8.4")),
    MARIADB(JdbcDialect.MARIADB, () -> new MariaDBContainer("mariadb:11.8")),
    ORACLE(JdbcDialect.ORACLE, () -> new OracleContainer("gvenzl/oracle-free:23-slim-faststart")),
    SQLSERVER(
            JdbcDialect.SQLSERVER,
            () -> new MSSQLServerContainer("mcr.microsoft.com/mssql/server:2022-latest").acceptLicense());

    static final String DATABASES_PROPERTY = "idempotency.tck.databases";

    private static final Map<TestDatabase, HikariDataSource> STARTED = new EnumMap<>(TestDatabase.class);

    private final JdbcDialect dialect;
    private final Supplier<JdbcDatabaseContainer<?>> container;

    TestDatabase(JdbcDialect dialect, Supplier<JdbcDatabaseContainer<?>> container) {
        this.dialect = dialect;
        this.container = container;
    }

    JdbcDialect dialect() {
        return dialect;
    }

    /** Whether this database is selected by {@value #DATABASES_PROPERTY}. */
    boolean selected() {
        String value = System.getProperty(DATABASES_PROPERTY, "postgresql,mysql")
                .trim()
                .toLowerCase(Locale.ROOT);
        if (value.equals("all")) {
            return true;
        }
        Set<String> names =
                Arrays.stream(value.split(",", -1)).map(String::trim).collect(Collectors.toSet());
        return names.contains(name().toLowerCase(Locale.ROOT));
    }

    /** Starts the container on first use and returns a pooled data source with the schema created. */
    synchronized DataSource dataSource() {
        HikariDataSource started = STARTED.get(this);
        if (started != null) {
            return started;
        }
        JdbcDatabaseContainer<?> database = container.get();
        database.start();
        HikariConfig config = new HikariConfig();
        config.setJdbcUrl(database.getJdbcUrl());
        config.setUsername(database.getUsername());
        config.setPassword(database.getPassword());
        config.setMaximumPoolSize(16);
        HikariDataSource dataSource = new HikariDataSource(config);
        JdbcSchema.createIfMissing(dataSource, dialect, JdbcIdempotencyStore.DEFAULT_TABLE_NAME);
        STARTED.put(this, dataSource);
        return dataSource;
    }

    /**
     * Creates an empty PostgreSQL schema and returns a data source whose current schema it is, running {@code settings}
     * on each new connection. Close it after use.
     */
    static HikariDataSource freshPostgreSqlSchema(String schema, String... settings) throws SQLException {
        HikariDataSource shared = (HikariDataSource) POSTGRESQL.dataSource();
        try (Connection connection = shared.getConnection();
                Statement statement = connection.createStatement()) {
            statement.execute("CREATE SCHEMA " + schema);
        }
        HikariConfig config = new HikariConfig();
        String url = shared.getJdbcUrl();
        config.setJdbcUrl(url + (url.contains("?") ? "&" : "?") + "currentSchema=" + schema);
        config.setUsername(shared.getUsername());
        config.setPassword(shared.getPassword());
        config.setMaximumPoolSize(2);
        if (settings.length > 0) {
            config.setConnectionInitSql(String.join("; ", settings));
        }
        return new HikariDataSource(config);
    }
}
