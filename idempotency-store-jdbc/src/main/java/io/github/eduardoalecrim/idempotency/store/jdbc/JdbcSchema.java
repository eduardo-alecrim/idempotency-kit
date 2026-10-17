// SPDX-License-Identifier: Apache-2.0
package io.github.eduardoalecrim.idempotency.store.jdbc;

import io.github.eduardoalecrim.idempotency.spi.IdempotencyStoreException;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.DatabaseMetaData;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import javax.sql.DataSource;
import org.jspecify.annotations.Nullable;

/** Checks and creates the record table with the shipped scripts ({@code db/idempotency/schema-{vendor}.sql}). */
public final class JdbcSchema {

    /** Columns of the record table (SPEC §6.7). */
    static final List<String> COLUMNS = List.of(
            "scope_key",
            "idempotency_key",
            "client_id",
            "http_method",
            "route",
            "fingerprint",
            "state",
            "owner_token",
            "lease_until",
            "response_status",
            "response_headers",
            "response_body",
            "body_omitted",
            "codec_key_id",
            "created_at",
            "completed_at",
            "expires_at");

    private JdbcSchema() {}

    /** Returns the class path location of the DDL for {@code dialect}. */
    public static String scriptPath(JdbcDialect dialect) {
        return "db/idempotency/schema-" + dialect.name().toLowerCase(Locale.ROOT) + ".sql";
    }

    /**
     * Returns what is wrong with the table, or an empty list when it has every column.
     *
     * @throws IdempotencyStoreException if the database cannot be read
     */
    public static List<String> problems(DataSource dataSource, String table) {
        try (Connection connection = dataSource.getConnection()) {
            Set<String> columns = columns(connection, table);
            if (columns.isEmpty()) {
                return List.of("table " + table + " does not exist");
            }
            List<String> problems = new ArrayList<>();
            for (String column : COLUMNS) {
                if (!columns.contains(column)) {
                    problems.add("table " + table + " has no column " + column);
                }
            }
            return problems;
        } catch (SQLException e) {
            throw new IdempotencyStoreException("Cannot read the columns of " + table, e);
        }
    }

    /**
     * Creates the table with the shipped script when it does not exist.
     *
     * @return whether the table was created
     * @throws IllegalArgumentException if {@code table} is schema-qualified; the script names objects after it
     * @throws IdempotencyStoreException if the script fails
     */
    public static boolean createIfMissing(DataSource dataSource, JdbcDialect dialect, String table) {
        if (table.contains(".")) {
            throw new IllegalArgumentException(
                    "The table can be created only under an unqualified name, not '" + table + "'");
        }
        String script = script(dialect).replace(JdbcIdempotencyStore.DEFAULT_TABLE_NAME, table);
        try (Connection connection = dataSource.getConnection()) {
            if (!columns(connection, table).isEmpty()) {
                return false;
            }
            try (Statement statement = connection.createStatement()) {
                for (String sql : statements(script)) {
                    statement.execute(sql);
                }
            }
            if (!connection.getAutoCommit()) {
                connection.commit();
            }
            return true;
        } catch (SQLException e) {
            throw new IdempotencyStoreException("Cannot create " + table + " with " + scriptPath(dialect), e);
        }
    }

    /** Splits a shipped script into statements: they end with a semicolon at the end of a line. */
    static List<String> statements(String script) {
        List<String> statements = new ArrayList<>();
        StringBuilder current = new StringBuilder();
        for (String line : script.split("\n", -1)) {
            String trimmed = line.trim();
            if (trimmed.startsWith("--") || trimmed.isEmpty()) {
                continue;
            }
            if (trimmed.endsWith(";")) {
                current.append(trimmed, 0, trimmed.length() - 1);
                statements.add(current.toString().trim());
                current.setLength(0);
            } else {
                current.append(line).append('\n');
            }
        }
        if (current.toString().trim().length() > 0) {
            statements.add(current.toString().trim());
        }
        return statements;
    }

    private static String script(JdbcDialect dialect) {
        String path = scriptPath(dialect);
        try (InputStream in = JdbcSchema.class.getClassLoader().getResourceAsStream(path)) {
            if (in == null) {
                throw new IdempotencyStoreException("Missing " + path + " on the class path");
            }
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new IdempotencyStoreException("Cannot read " + path, e);
        }
    }

    // Unquoted names are stored in upper case by Oracle and in lower case by PostgreSQL, so both are tried.
    // An unqualified table is looked up in the connection's current schema only.
    private static Set<String> columns(Connection connection, String table) throws SQLException {
        int dot = table.lastIndexOf('.');
        String name = dot < 0 ? table : table.substring(dot + 1);
        @Nullable String qualifier = dot < 0 ? null : table.substring(0, dot);
        @Nullable String current = connection.getSchema();
        DatabaseMetaData metaData = connection.getMetaData();
        String escape = metaData.getSearchStringEscape();
        for (int variant = 0; variant < 3; variant++) {
            String candidate = spell(name, variant);
            @Nullable String schema = qualifier == null ? current : spell(qualifier, variant);
            Set<String> columns = new LinkedHashSet<>();
            try (ResultSet rows = metaData.getColumns(
                    connection.getCatalog(),
                    schema == null ? null : pattern(schema, escape),
                    pattern(candidate, escape),
                    null)) {
                while (rows.next()) {
                    columns.add(rows.getString("COLUMN_NAME").toLowerCase(Locale.ROOT));
                }
            }
            if (!columns.isEmpty()) {
                return columns;
            }
        }
        return Set.of();
    }

    private static String spell(String name, int variant) {
        return switch (variant) {
            case 0 -> name;
            case 1 -> name.toLowerCase(Locale.ROOT);
            default -> name.toUpperCase(Locale.ROOT);
        };
    }

    // Metadata arguments are LIKE patterns, where '_' matches any character.
    private static String pattern(String name, @Nullable String escape) {
        if (escape == null || escape.isEmpty()) {
            return name;
        }
        return name.replace(escape, escape + escape).replace("_", escape + "_").replace("%", escape + "%");
    }
}
