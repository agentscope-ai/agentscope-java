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
package io.agentscope.extensions.durable.jdbc;

import com.fasterxml.jackson.databind.JsonNode;
import io.agentscope.durable.DurableException;
import io.agentscope.durable.DurableJson;
import io.agentscope.durable.store.RecordStore;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Properties;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Function;

/** SQLite/PostgreSQL transactions, with database time and no platform schema dependencies. */
public final class JdbcRecordStore implements RecordStore {
    public enum Dialect {
        SQLITE,
        POSTGRESQL
    }

    @FunctionalInterface
    public interface Connections {
        Connection open() throws SQLException;
    }

    private final Connections connections;
    private final Dialect dialect;
    private final AtomicBoolean closed = new AtomicBoolean();

    @Override
    public Capabilities capabilities() {
        return new Capabilities(
                dialect == Dialect.SQLITE ? "sqlite" : "postgresql",
                true,
                dialect == Dialect.POSTGRESQL);
    }

    public JdbcRecordStore(Connections connections, Dialect dialect) {
        this(connections, dialect, true);
    }

    /** Connections are borrowed and returned after each operation; the factory/pool is not closed. */
    public JdbcRecordStore(Connections connections, Dialect dialect, boolean initializeSchema) {
        this.connections = Objects.requireNonNull(connections);
        this.dialect = Objects.requireNonNull(dialect);
        try (Connection c = connect();
                var statement = c.createStatement()) {
            if (initializeSchema) {
                // Pools may default to manual commit; schema initialization must survive return.
                c.setAutoCommit(true);
                statement.executeUpdate(
                        "CREATE TABLE IF NOT EXISTS durable_records (record_key VARCHAR(512)"
                                + " PRIMARY KEY, payload TEXT)");
                statement.executeUpdate(
                        "CREATE TABLE IF NOT EXISTS durable_parts (record_key VARCHAR(512) NOT"
                                + " NULL, part_key VARCHAR(512) NOT NULL, payload TEXT NOT NULL,"
                                + " PRIMARY KEY(record_key,part_key))");
                statement.executeUpdate(
                        "CREATE TABLE IF NOT EXISTS durable_work (queue VARCHAR(256) NOT NULL,"
                            + " record_key VARCHAR(512) NOT NULL, work_id VARCHAR(512) NOT NULL,"
                            + " ready_at BIGINT NOT NULL, payload TEXT NOT NULL, PRIMARY"
                            + " KEY(queue,record_key,work_id))");
            }
            try {
                statement
                        .executeQuery("SELECT record_key, payload FROM durable_records WHERE 1=0")
                        .close();
                statement
                        .executeQuery("SELECT part_key, payload FROM durable_parts WHERE 1=0")
                        .close();
                statement
                        .executeQuery(
                                "SELECT queue, record_key, work_id, ready_at, payload FROM"
                                        + " durable_work WHERE 1=0")
                        .close();
            } catch (SQLException incompatible) {
                throw new DurableException(
                        DurableException.Code.INCOMPATIBLE,
                        "Record store schema does not support scoped work identities",
                        incompatible);
            }
            if (initializeSchema) {
                statement.executeUpdate(
                        "CREATE INDEX IF NOT EXISTS durable_work_ready ON"
                                + " durable_work(queue,ready_at,record_key,work_id)");
            }
        } catch (SQLException e) {
            throw unavailable(e);
        }
    }

    public static JdbcRecordStore sqlite(Path file) {
        Path absolute = file.toAbsolutePath().normalize();
        try {
            Files.createDirectories(absolute.getParent());
        } catch (IOException e) {
            throw new DurableException(
                    DurableException.Code.UNAVAILABLE, "Cannot create database directory", e);
        }
        return new JdbcRecordStore(
                () -> DriverManager.getConnection("jdbc:sqlite:" + absolute), Dialect.SQLITE);
    }

    public static JdbcRecordStore postgres(String url, Properties credentials) {
        if (!url.startsWith("jdbc:postgresql:"))
            throw new IllegalArgumentException("PostgreSQL URL required");
        Properties copy = new Properties();
        copy.putAll(credentials);
        return new JdbcRecordStore(
                () -> DriverManager.getConnection(url, copy), Dialect.POSTGRESQL);
    }

    private Connection connect() throws SQLException {
        if (closed.get()) throw new IllegalStateException("Record store closed");
        Connection c = connections.open();
        try {
            if (dialect == Dialect.SQLITE)
                try (var s = c.createStatement()) {
                    s.execute("PRAGMA busy_timeout=10000");
                    s.execute("PRAGMA synchronous=FULL");
                }
            return c;
        } catch (SQLException e) {
            c.close();
            throw e;
        }
    }

    /** Prevents new operations; existing borrowed connections finish and return normally. */
    @Override
    public void close() {
        closed.set(true);
    }

    @Override
    public <T> T transaction(String key, Function<Transaction, T> action) {
        if (key == null || key.length() > 512)
            throw new IllegalArgumentException("Invalid record key");
        try (Connection c = connect()) {
            c.setAutoCommit(false);
            try {
                try (var s =
                        c.prepareStatement(
                                "INSERT INTO durable_records(record_key,payload) VALUES (?,NULL) ON"
                                        + " CONFLICT(record_key) DO NOTHING")) {
                    s.setString(1, key);
                    s.executeUpdate();
                }
                try (var s =
                        c.prepareStatement(
                                "SELECT payload FROM durable_records WHERE record_key=?"
                                        + (dialect == Dialect.POSTGRESQL ? " FOR UPDATE" : ""))) {
                    s.setString(1, key);
                    try (var rs = s.executeQuery()) {
                        if (!rs.next()) throw new SQLException("Missing transaction record");
                    }
                }
                T result = action.apply(new Parts(c, key, time(c)));
                c.commit();
                return result;
            } catch (SQLException | RuntimeException | Error e) {
                try {
                    c.rollback();
                } catch (SQLException rollback) {
                    e.addSuppressed(rollback);
                }
                throw e;
            }
        } catch (SQLException e) {
            throw unavailable(e);
        }
    }

    private long time(Connection c) throws SQLException {
        String sql =
                dialect == Dialect.SQLITE
                        ? "SELECT CAST((julianday('now') - 2440587.5)*86400000 AS INTEGER)"
                        : "SELECT CAST(EXTRACT(EPOCH FROM clock_timestamp())*1000 AS BIGINT)";
        try (var s = c.createStatement();
                var rs = s.executeQuery(sql)) {
            rs.next();
            return rs.getLong(1);
        }
    }

    private static final class Parts implements Transaction {
        private final Connection connection;
        private final String scope;
        private final long now;

        Parts(Connection connection, String scope, long now) {
            this.connection = connection;
            this.scope = scope;
            this.now = now;
        }

        public long now() {
            return now;
        }

        public JsonNode get(String part) {
            try (var s =
                    connection.prepareStatement(
                            part.isEmpty()
                                    ? "SELECT payload FROM durable_records WHERE record_key=?"
                                    : "SELECT payload FROM durable_parts WHERE record_key=? AND"
                                            + " part_key=?")) {
                s.setString(1, scope);
                if (!part.isEmpty()) s.setString(2, part);
                try (var rs = s.executeQuery()) {
                    if (!rs.next()) return null;
                    String value = rs.getString(1);
                    return value == null ? null : DurableJson.parse(value);
                }
            } catch (SQLException e) {
                throw unavailable(e);
            }
        }

        public void put(String part, JsonNode value) {
            if (part.length() > 512)
                throw new IllegalArgumentException("Part key exceeds 512 characters");
            boolean absent = value == null || value.isNull();
            String sql =
                    part.isEmpty()
                            ? "UPDATE durable_records SET payload=? WHERE record_key=?"
                            : absent
                                    ? "DELETE FROM durable_parts WHERE record_key=? AND part_key=?"
                                    : "INSERT INTO durable_parts(record_key,part_key,payload)"
                                            + " VALUES (?,?,?) ON CONFLICT(record_key,part_key) DO"
                                            + " UPDATE SET payload=excluded.payload";
            try (var s = connection.prepareStatement(sql)) {
                if (part.isEmpty()) {
                    s.setString(1, absent ? null : value.toString());
                    s.setString(2, scope);
                } else {
                    s.setString(1, scope);
                    s.setString(2, part);
                    if (!absent) s.setString(3, value.toString());
                }
                s.executeUpdate();
            } catch (SQLException e) {
                throw unavailable(e);
            }
        }

        public List<String> keys(String prefix, String after, int limit) {
            checkLimit(limit);
            try (var s =
                    connection.prepareStatement(
                            "SELECT part_key FROM durable_parts WHERE record_key=? AND part_key>=?"
                                    + " AND part_key<? AND part_key>? ORDER BY part_key LIMIT ?")) {
                s.setString(1, scope);
                s.setString(2, prefix);
                s.setString(3, prefix + "\uffff");
                s.setString(4, after == null ? "" : after);
                s.setInt(5, limit);
                try (var rs = s.executeQuery()) {
                    var keys = new ArrayList<String>();
                    while (rs.next()) keys.add(rs.getString(1));
                    return List.copyOf(keys);
                }
            } catch (SQLException e) {
                throw unavailable(e);
            }
        }

        public void schedule(String queue, String workId, long readyAt, JsonNode value) {
            checkKey(queue, 256, "queue");
            checkKey(workId, 512, "workId");
            if (readyAt >= 0) Objects.requireNonNull(value, "work value");
            try (var s =
                    connection.prepareStatement(
                            readyAt < 0
                                    ? "DELETE FROM durable_work WHERE queue=? AND record_key=?"
                                            + " AND work_id=?"
                                    : "INSERT INTO"
                                          + " durable_work(queue,record_key,work_id,ready_at,payload)"
                                          + " VALUES (?,?,?,?,?) ON"
                                          + " CONFLICT(queue,record_key,work_id) DO UPDATE SET"
                                          + " ready_at=excluded.ready_at,payload=excluded.payload")) {
                s.setString(1, queue);
                s.setString(2, scope);
                s.setString(3, workId);
                if (readyAt >= 0) {
                    s.setLong(4, readyAt);
                    s.setString(5, value.toString());
                }
                s.executeUpdate();
            } catch (SQLException e) {
                throw unavailable(e);
            }
        }
    }

    @Override
    public List<Work> ready(String queue, int limit) {
        checkKey(queue, 256, "queue");
        checkLimit(limit);
        try (var c = connect();
                var s =
                        c.prepareStatement(
                                "SELECT record_key,work_id,ready_at,payload FROM durable_work"
                                        + " WHERE queue=? AND ready_at<=?"
                                        + " ORDER BY ready_at,record_key,work_id LIMIT ?")) {
            s.setString(1, queue);
            s.setLong(2, time(c));
            s.setInt(3, limit);
            try (var rs = s.executeQuery()) {
                var result = new ArrayList<Work>();
                while (rs.next())
                    result.add(
                            new Work(
                                    rs.getString(1),
                                    rs.getString(2),
                                    rs.getLong(3),
                                    DurableJson.parse(rs.getString(4))));
                return List.copyOf(result);
            }
        } catch (SQLException e) {
            throw unavailable(e);
        }
    }

    private static void checkLimit(int limit) {
        if (limit < 1 || limit > 1000) throw new IllegalArgumentException("Limit must be 1..1000");
    }

    private static void checkKey(String value, int limit, String name) {
        if (value == null || value.isBlank() || value.length() > limit)
            throw new IllegalArgumentException("Invalid " + name);
    }

    @Override
    public List<String> keys(String prefix, String after, int limit) {
        if (limit < 1 || limit > 1000) throw new IllegalArgumentException("Limit must be 1..1000");
        // Lexical bounds avoid LIKE wildcard interpretation in namespaces.
        try (Connection c = connect();
                var s =
                        c.prepareStatement(
                                "SELECT record_key FROM durable_records WHERE payload IS NOT NULL"
                                    + " AND record_key>=? AND record_key<? AND record_key>? ORDER"
                                    + " BY record_key LIMIT ?")) {
            s.setString(1, prefix);
            s.setString(2, prefix + "\uffff");
            s.setString(3, after == null ? "" : after);
            s.setInt(4, limit);
            try (var rs = s.executeQuery()) {
                List<String> result = new ArrayList<>();
                while (rs.next()) result.add(rs.getString(1));
                return List.copyOf(result);
            }
        } catch (SQLException e) {
            throw unavailable(e);
        }
    }

    private static DurableException unavailable(SQLException cause) {
        return new DurableException(
                DurableException.Code.UNAVAILABLE, "Durable database transaction failed", cause);
    }
}
