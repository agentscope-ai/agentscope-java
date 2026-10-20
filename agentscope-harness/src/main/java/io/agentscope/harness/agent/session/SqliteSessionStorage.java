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
package io.agentscope.harness.agent.session;

import io.agentscope.core.session.AtomicSessionStorage;
import io.agentscope.core.session.SessionLogException;
import java.io.IOException;
import java.nio.file.FileAlreadyExistsException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import org.sqlite.SQLiteConfig;

/** Windows local journal storage, using atomic SQL writes and synchronous rollback journals. */
final class SqliteSessionStorage implements AtomicSessionStorage, AutoCloseable {
    private final Path database;
    private Connection connection;

    SqliteSessionStorage(Path root) {
        this.database = root.resolve("journal.sqlite3");
    }

    /** Releases the long-lived connection so the database file is no longer held open. */
    @Override
    public synchronized void close() {
        if (connection != null) {
            try {
                connection.close();
            } catch (SQLException cleanup) {
                // The driver releases the handle on failure as well; nothing to surface here.
            }
            connection = null;
        }
    }

    /**
     * One long-lived connection per instance, rebuilt after a failed operation. Every operation
     * holds the instance monitor, so in-process writers serialize here exactly as SQLite file
     * locks serialize them across processes; statement-level autocommit keeps each write atomic.
     * Connection setup (file creation, schema) pays once per instance instead of once per call,
     * which dominates iteration-heavy workloads on Windows.
     */
    private synchronized Connection connection() throws SQLException, IOException {
        if (connection != null) {
            try {
                if (!connection.isClosed()) return connection;
            } catch (SQLException closed) {
                // Fall through and rebuild.
            }
            connection = null;
        }
        connection = open();
        return connection;
    }

    private Connection open() throws SQLException, IOException {
        for (String suffix : List.of("", "-journal", "-wal", "-shm")) {
            if (Files.isSymbolicLink(database.resolveSibling(database.getFileName() + suffix)))
                throw new SessionLogException("Journal symlink paths are not supported");
        }
        Files.createDirectories(database.getParent());
        // The JDBC driver probes a missing database with createNewFile/delete. Reserve the
        // empty file atomically first so concurrent initial opens cannot delete another's file.
        try {
            Files.createFile(database);
        } catch (FileAlreadyExistsException alreadyCreated) {
            // Another writer owns initialization; SQLite serializes its schema transaction.
        }
        var config = new SQLiteConfig();
        config.setBusyTimeout(10_000);
        config.setJournalMode(SQLiteConfig.JournalMode.DELETE);
        config.setSynchronous(SQLiteConfig.SynchronousMode.FULL);
        Connection opened =
                DriverManager.getConnection("jdbc:sqlite:" + database, config.toProperties());
        try {
            try (var statement = opened.createStatement()) {
                statement.execute("PRAGMA synchronous=EXTRA");
                statement.executeUpdate(
                        "CREATE TABLE IF NOT EXISTS session_objects (path TEXT PRIMARY KEY, version"
                                + " INTEGER NOT NULL CHECK(version > 0), payload BLOB NOT NULL)");
            }
            return opened;
        } catch (SQLException error) {
            try {
                opened.close();
            } catch (SQLException cleanup) {
                error.addSuppressed(cleanup);
            }
            throw error;
        }
    }

    private SessionLogException invalidate(Exception error, String message) {
        if (connection != null) {
            try {
                connection.close();
            } catch (SQLException cleanup) {
                error.addSuppressed(cleanup);
            }
            connection = null;
        }
        return new SessionLogException(message + ": " + database, error);
    }

    @Override
    public synchronized Value read(String path) {
        if (!Files.exists(database)) return null;
        try {
            var statement =
                    connection()
                            .prepareStatement(
                                    "SELECT version, payload FROM session_objects WHERE path = ?");
            try (statement) {
                statement.setString(1, path);
                try (var result = statement.executeQuery()) {
                    return result.next() ? new Value(result.getLong(1), result.getBytes(2)) : null;
                }
            }
        } catch (SQLException | IOException error) {
            throw invalidate(error, "Journal read failed");
        }
    }

    @Override
    public synchronized List<String> listPaths(String prefix) {
        if (!Files.exists(database)) return List.of();
        try {
            var statement =
                    connection()
                            .prepareStatement(
                                    "SELECT path FROM session_objects WHERE substr(path, 1,"
                                            + " length(?)) = ? ORDER BY path");
            try (statement) {
                statement.setString(1, prefix);
                statement.setString(2, prefix);
                try (var result = statement.executeQuery()) {
                    var paths = new ArrayList<String>();
                    while (result.next()) paths.add(result.getString(1));
                    return List.copyOf(paths);
                }
            }
        } catch (SQLException | IOException error) {
            throw invalidate(error, "Journal discovery failed");
        }
    }

    @Override
    public synchronized boolean compareAndSet(String path, long expected, byte[] bytes) {
        if (expected < 0 || expected == Long.MAX_VALUE)
            throw new IllegalArgumentException("Invalid journal object version");
        // One SQL statement is the entire transaction: readers never see a partial write, and
        // SQLite serializes competing writers across connections and processes.
        String sql =
                expected == 0
                        ? "INSERT INTO session_objects(path, version, payload) VALUES (?, 1, ?)"
                                + " ON CONFLICT(path) DO NOTHING"
                        : "UPDATE session_objects SET version = version + 1, payload = ?"
                                + " WHERE path = ? AND version = ?";
        try {
            var statement = connection().prepareStatement(sql);
            try (statement) {
                if (expected == 0) {
                    statement.setString(1, path);
                    statement.setBytes(2, bytes);
                } else {
                    statement.setBytes(1, bytes);
                    statement.setString(2, path);
                    statement.setLong(3, expected);
                }
                return statement.executeUpdate() == 1;
            }
        } catch (SQLException | IOException error) {
            throw invalidate(error, "Journal CAS failed");
        }
    }

    @Override
    public synchronized void deletePaths(String prefix) {
        if (!Files.exists(database)) return;
        // One statement covers the prefix object itself and everything below it; SQLite
        // serializes this against concurrent readers and writers like any other write.
        try {
            var statement =
                    connection()
                            .prepareStatement(
                                    "DELETE FROM session_objects WHERE path = ?"
                                            + " OR substr(path, 1, length(?) + 1) = ? || '/'");
            try (statement) {
                statement.setString(1, prefix);
                statement.setString(2, prefix);
                statement.setString(3, prefix);
                statement.executeUpdate();
            }
        } catch (SQLException | IOException error) {
            throw invalidate(error, "Journal delete failed");
        }
    }
}
