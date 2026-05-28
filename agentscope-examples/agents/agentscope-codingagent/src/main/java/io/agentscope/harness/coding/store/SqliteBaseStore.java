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
package io.agentscope.harness.coding.store;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.agentscope.harness.agent.store.BaseStore;
import io.agentscope.harness.agent.store.StoreItem;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * SQLite-backed implementation of {@link BaseStore}.
 *
 * <p>All items are stored in a single table with columns: {@code namespace} (text), {@code key}
 * (text), {@code value} (JSON text), {@code version} (integer, monotonically increasing). The
 * combination of namespace + key is the primary key.
 *
 * <p>Namespace lists are serialized as {@code /}-joined strings (e.g. {@code ["threads", "abc"]}
 * → {@code "threads/abc"}).
 *
 * <p>Used as the unified backend for:
 *
 * <ul>
 *   <li>{@code ["threads", thread_id]} — thread metadata
 *   <li>{@code ["queue", thread_id]} — message queue
 *   <li>{@code ["deliveries"]} — webhook delivery dedup
 *   <li>{@code ["findings", thread_id]} — reviewer findings
 * </ul>
 */
public class SqliteBaseStore implements BaseStore, AutoCloseable {

    private static final TypeReference<Map<String, Object>> MAP_TYPE = new TypeReference<>() {};

    private final Connection connection;
    private final ObjectMapper mapper = new ObjectMapper();

    public SqliteBaseStore(String dbPath) throws SQLException {
        this.connection = DriverManager.getConnection("jdbc:sqlite:" + dbPath);
        this.connection.setAutoCommit(true);
        init();
    }

    private void init() throws SQLException {
        try (Statement stmt = connection.createStatement()) {
            stmt.execute(
                    """
                    CREATE TABLE IF NOT EXISTS store (
                        namespace TEXT NOT NULL,
                        key       TEXT NOT NULL,
                        value     TEXT NOT NULL,
                        version   INTEGER NOT NULL DEFAULT 1,
                        updated_at INTEGER NOT NULL DEFAULT (strftime('%s', 'now')),
                        PRIMARY KEY (namespace, key)
                    )
                    """);
            stmt.execute("CREATE INDEX IF NOT EXISTS idx_store_ns ON store(namespace)");
            // Migrate older databases that predate the version column.
            if (!hasColumn("store", "version")) {
                stmt.execute("ALTER TABLE store ADD COLUMN version INTEGER NOT NULL DEFAULT 1");
            }
        }
    }

    private boolean hasColumn(String table, String column) throws SQLException {
        try (PreparedStatement ps =
                        connection.prepareStatement("PRAGMA table_info(" + table + ")");
                ResultSet rs = ps.executeQuery()) {
            while (rs.next()) {
                if (column.equalsIgnoreCase(rs.getString("name"))) {
                    return true;
                }
            }
        }
        return false;
    }

    @Override
    public StoreItem get(List<String> namespace, String key) {
        String ns = toNs(namespace);
        try (PreparedStatement ps =
                connection.prepareStatement(
                        "SELECT value, version FROM store WHERE namespace=? AND key=?")) {
            ps.setString(1, ns);
            ps.setString(2, key);
            try (ResultSet rs = ps.executeQuery()) {
                if (rs.next()) {
                    return new StoreItem(
                            key, mapper.readValue(rs.getString(1), MAP_TYPE), rs.getLong(2));
                }
            }
        } catch (Exception e) {
            throw new RuntimeException("SqliteBaseStore.get failed", e);
        }
        return null;
    }

    @Override
    public void put(List<String> namespace, String key, Map<String, Object> value) {
        String ns = toNs(namespace);
        try {
            String json = mapper.writeValueAsString(value);
            // UPSERT: insert with version=1, or bump the existing version on conflict.
            try (PreparedStatement ps =
                    connection.prepareStatement(
                            "INSERT INTO store(namespace, key, value, version, updated_at)"
                                    + " VALUES(?,?,?,1,strftime('%s','now'))"
                                    + " ON CONFLICT(namespace, key) DO UPDATE SET"
                                    + " value=excluded.value,"
                                    + " version=store.version+1,"
                                    + " updated_at=strftime('%s','now')")) {
                ps.setString(1, ns);
                ps.setString(2, key);
                ps.setString(3, json);
                ps.executeUpdate();
            }
        } catch (Exception e) {
            throw new RuntimeException("SqliteBaseStore.put failed", e);
        }
    }

    /**
     * Atomic compare-and-swap backed by SQLite. When {@code expectedVersion == 0}, the operation
     * succeeds only if the row does not yet exist (create-if-absent). Otherwise it succeeds only
     * if the stored row has {@code version == expectedVersion}.
     *
     * <p>Single-statement UPDATE / INSERT-OR-IGNORE guarantees server-side atomicity, so concurrent
     * writers either all see the same {@code true} once and the rest see {@code false}.
     */
    @Override
    public boolean putIfVersion(
            List<String> namespace, String key, Map<String, Object> value, long expectedVersion) {
        String ns = toNs(namespace);
        try {
            String json = mapper.writeValueAsString(value);
            if (expectedVersion == 0L) {
                try (PreparedStatement ps =
                        connection.prepareStatement(
                                "INSERT OR IGNORE INTO store(namespace, key, value, version,"
                                        + " updated_at)"
                                        + " VALUES(?,?,?,1,strftime('%s','now'))")) {
                    ps.setString(1, ns);
                    ps.setString(2, key);
                    ps.setString(3, json);
                    return ps.executeUpdate() == 1;
                }
            }
            try (PreparedStatement ps =
                    connection.prepareStatement(
                            "UPDATE store SET value=?, version=version+1,"
                                    + " updated_at=strftime('%s','now')"
                                    + " WHERE namespace=? AND key=? AND version=?")) {
                ps.setString(1, json);
                ps.setString(2, ns);
                ps.setString(3, key);
                ps.setLong(4, expectedVersion);
                return ps.executeUpdate() == 1;
            }
        } catch (Exception e) {
            throw new RuntimeException("SqliteBaseStore.putIfVersion failed", e);
        }
    }

    @Override
    public List<StoreItem> search(List<String> namespace, int limit, int offset) {
        String ns = toNs(namespace);
        List<StoreItem> result = new ArrayList<>();
        try (PreparedStatement ps =
                connection.prepareStatement(
                        "SELECT key, value, version FROM store WHERE namespace=?"
                                + " ORDER BY updated_at DESC LIMIT ? OFFSET ?")) {
            ps.setString(1, ns);
            ps.setInt(2, limit > 0 ? limit : Integer.MAX_VALUE);
            ps.setInt(3, offset >= 0 ? offset : 0);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    result.add(
                            new StoreItem(
                                    rs.getString(1),
                                    mapper.readValue(rs.getString(2), MAP_TYPE),
                                    rs.getLong(3)));
                }
            }
        } catch (Exception e) {
            throw new RuntimeException("SqliteBaseStore.search failed", e);
        }
        return result;
    }

    @Override
    public void delete(List<String> namespace, String key) {
        String ns = toNs(namespace);
        try (PreparedStatement ps =
                connection.prepareStatement("DELETE FROM store WHERE namespace=? AND key=?")) {
            ps.setString(1, ns);
            ps.setString(2, key);
            ps.executeUpdate();
        } catch (SQLException e) {
            throw new RuntimeException("SqliteBaseStore.delete failed", e);
        }
    }

    /** Counts items in a namespace. */
    public int count(List<String> namespace) {
        String ns = toNs(namespace);
        try (PreparedStatement ps =
                connection.prepareStatement("SELECT COUNT(*) FROM store WHERE namespace=?")) {
            ps.setString(1, ns);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? rs.getInt(1) : 0;
            }
        } catch (SQLException e) {
            throw new RuntimeException("SqliteBaseStore.count failed", e);
        }
    }

    @Override
    public void close() throws SQLException {
        if (connection != null && !connection.isClosed()) {
            connection.close();
        }
    }

    private static String toNs(List<String> namespace) {
        if (namespace == null || namespace.isEmpty()) return "/";
        return String.join("/", namespace);
    }
}
