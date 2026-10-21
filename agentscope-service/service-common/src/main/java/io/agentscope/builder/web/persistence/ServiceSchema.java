/*
 * Copyright 2024-2026 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package io.agentscope.builder.web.persistence;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Duration;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import javax.sql.DataSource;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.datasource.init.ScriptUtils;

/** Explicit Service schema migrations. Ordinary PostgreSQL replicas only read the ledger. */
public final class ServiceSchema {
    private static final Map<String, List<String>> MIGRATIONS =
            Map.of(
                    "portal",
                            List.of(
                                    "V0001__portal.sql",
                                    "V0002__artifact_publication.sql",
                                    "V0003__webhooks.sql",
                                    "V0004__configuration_overlay.sql",
                                    "V0005__session_budgets.sql",
                                    "V0006__checkpoints.sql",
                                    "V0007__session_api.sql",
                                    "V0008__native_webhook_events.sql"),
                    "dataplane", List.of("V0001__dataplane.sql"),
                    "managed",
                            List.of(
                                    "managed-runtime-v2.sql",
                                    "managed-subagents.sql",
                                    "managed-private-objects.sql",
                                    "managed-hands.sql",
                                    "managed-session-protocol.sql",
                                    "managed-turn-ack.sql",
                                    "managed-mcp-batch.sql",
                                    "managed-tool-publication-lease.sql",
                                    "managed-tool-submission.sql"));

    private ServiceSchema() {}

    /** Embedded development fixtures continue to use their isolated Spring schema setup. */
    public static void validatePostgres(DataSource source, String component) {
        try (Connection connection = source.getConnection()) {
            if (isPostgres(connection)) {
                validate(connection, component);
            }
        } catch (SQLException | IOException ex) {
            throw new IllegalStateException("Service schema validation failed: " + component, ex);
        }
    }

    public static void validate(Connection connection, String component)
            throws SQLException, IOException {
        checkVersions(readVersions(connection, component), expected(component), true);
    }

    /** Runs as a database owner, with no HTTP server, execution beans, or credentials loaded. */
    public static void migrate(Connection connection, String component, String schema)
            throws SQLException, IOException, InterruptedException {
        Map<String, String> expected = expected(component);
        if (!schema.matches("[A-Za-z_][A-Za-z0-9_]{0,62}")) {
            throw new IllegalArgumentException("schema must be a plain SQL identifier");
        }
        if ("managed".equals(component) && !"ma".equals(schema)) {
            throw new IllegalArgumentException("Managed runtime requires schema ma");
        }
        if (!isPostgres(connection)) {
            throw new IllegalArgumentException("Service migration jobs require PostgreSQL");
        }
        long lock =
                switch (component) {
                    case "portal" -> 0x4153504f525441L;
                    case "managed" -> 0x41534d414e4147L;
                    default -> 0x415344415441L;
                };
        long deadline = System.nanoTime() + Duration.ofMinutes(2).toNanos();
        while (true) {
            try (PreparedStatement query =
                    connection.prepareStatement("SELECT pg_try_advisory_lock(?)")) {
                query.setLong(1, lock);
                try (ResultSet rows = query.executeQuery()) {
                    if (rows.next() && rows.getBoolean(1)) {
                        break;
                    }
                }
            }
            if (System.nanoTime() >= deadline) {
                throw new IllegalStateException("Service migration lock timeout: " + component);
            }
            Thread.sleep(50);
        }
        try {
            try (Statement statement = connection.createStatement()) {
                statement.execute("CREATE SCHEMA IF NOT EXISTS \"" + schema + "\"");
            }
            connection.setSchema(schema);
            try (Statement statement = connection.createStatement()) {
                statement.execute(
                        "CREATE TABLE IF NOT EXISTS builder_schema_migration"
                                + " (component VARCHAR(32) NOT NULL, version VARCHAR(128) NOT NULL,"
                                + " checksum VARCHAR(64) NOT NULL, applied_at TIMESTAMPTZ NOT NULL"
                                + " DEFAULT now(), PRIMARY KEY(component,version))");
            }
            Map<String, String> applied = readVersions(connection, component);
            checkVersions(applied, expected, false);
            if ("managed".equals(component) && applied.isEmpty()) {
                try (Statement query = connection.createStatement();
                        ResultSet rows =
                                query.executeQuery(
                                        "SELECT EXISTS (SELECT 1 FROM pg_class c JOIN pg_namespace"
                                            + " n ON n.oid=c.relnamespace WHERE n.nspname='ma' AND"
                                            + " c.relkind IN ('r','p') AND"
                                            + " c.relname<>'builder_schema_migration')")) {
                    if (rows.next() && rows.getBoolean(1)) {
                        throw new IllegalStateException(
                                "Unversioned Managed schema cannot be adopted; use a fresh data"
                                        + " space");
                    }
                }
            }
            for (Map.Entry<String, String> migration : expected.entrySet()) {
                if (applied.containsKey(migration.getKey())) {
                    continue;
                }
                connection.setAutoCommit(false);
                try {
                    ScriptUtils.executeSqlScript(
                            connection, resource(component, migration.getKey()));
                    try (PreparedStatement insert =
                            connection.prepareStatement(
                                    "INSERT INTO builder_schema_migration"
                                            + " (component,version,checksum) VALUES(?,?,?)")) {
                        insert.setString(1, component);
                        insert.setString(2, migration.getKey());
                        insert.setString(3, migration.getValue());
                        insert.executeUpdate();
                    }
                    connection.commit();
                } catch (RuntimeException | SQLException ex) {
                    connection.rollback();
                    throw ex;
                } finally {
                    connection.setAutoCommit(true);
                }
            }
        } finally {
            try (PreparedStatement unlock =
                    connection.prepareStatement("SELECT pg_advisory_unlock(?)")) {
                unlock.setLong(1, lock);
                unlock.execute();
            }
        }
    }

    private static boolean isPostgres(Connection connection) throws SQLException {
        return connection.getMetaData().getDatabaseProductName().equals("PostgreSQL");
    }

    private static ClassPathResource resource(String component, String version) {
        return new ClassPathResource(
                "managed".equals(component)
                        ? version
                        : "db/migration/" + component + "/" + version);
    }

    private static Map<String, String> expected(String component) throws IOException {
        List<String> versions = MIGRATIONS.get(component);
        if (versions == null) {
            throw new IllegalArgumentException("component must be portal, dataplane, or managed");
        }
        Map<String, String> result = new LinkedHashMap<>();
        try {
            for (String version : versions) {
                byte[] sql =
                        resource(component, version)
                                .getContentAsString(StandardCharsets.UTF_8)
                                .getBytes(StandardCharsets.UTF_8);
                result.put(
                        version,
                        HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(sql)));
            }
        } catch (NoSuchAlgorithmException ex) {
            throw new IllegalStateException(ex);
        }
        return result;
    }

    private static Map<String, String> readVersions(Connection connection, String component)
            throws SQLException {
        try (PreparedStatement query =
                connection.prepareStatement(
                        "SELECT version,checksum FROM "
                                + ("managed".equals(component) ? "ma." : "")
                                + "builder_schema_migration WHERE component=?"
                                + " ORDER BY version LIMIT 1025")) {
            query.setString(1, component);
            try (ResultSet rows = query.executeQuery()) {
                Map<String, String> result = new LinkedHashMap<>();
                while (rows.next()) {
                    result.put(rows.getString(1), rows.getString(2));
                }
                return result;
            }
        } catch (SQLException ex) {
            throw new SQLException("Service schema is not initialized; run the migration job", ex);
        }
    }

    private static void checkVersions(
            Map<String, String> applied, Map<String, String> expected, boolean complete) {
        for (Map.Entry<String, String> version : applied.entrySet()) {
            if (!expected.containsKey(version.getKey())) {
                throw new IllegalStateException(
                        "Unsupported Service schema version: " + version.getKey());
            }
            if (!version.getValue().equals(expected.get(version.getKey()))) {
                throw new IllegalStateException(
                        "Service schema checksum differs: " + version.getKey());
            }
        }
        if (complete) {
            for (String version : expected.keySet()) {
                if (!applied.containsKey(version)) {
                    throw new IllegalStateException(
                            "Missing Service schema version: "
                                    + version
                                    + "; run the migration job");
                }
            }
        }
    }
}
