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
package io.agentscope.harness.agent.workspace;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Best-effort SQLite index for the local workspace.
 *
 * <p>Tracks files that have been materialized locally under two path prefixes:
 * {@code agents/&#42;/sessions/&#42;&#42;} and {@code memory/&#42;&#42;}. The index is used to speed up
 * {@code ls / glob / exists / grep} in remote-backed workspace mode by avoiding full-store
 * key scans when enumerating paths under a prefix. File <em>content</em> is never stored in
 * the index — {@code grep} still fetches each candidate file from the remote store
 * authoritatively.
 *
 * <p><strong>Consistency model:</strong> the index is best-effort and may lag remote changes.
 * Remote writes remain authoritative. Index update failures are silently logged and never
 * propagate to callers.
 *
 * <p><strong>Thread-safety:</strong> SQLite serialises concurrent writers through its own
 * transaction machinery. No external locks are required.
 */
public class WorkspaceIndex implements AutoCloseable {

    private static final Logger log = LoggerFactory.getLogger(WorkspaceIndex.class);

    /** Schema version stored in index_state; bump when the schema changes. */
    private static final int SCHEMA_VERSION = 1;

    private static final String INDEX_DIR = ".index";
    private static final String INDEX_DB = "workspace.db";

    /**
     * Path prefixes (workspace-relative) that are eligible for indexing.
     * Only files under one of these prefixes will be tracked.
     */
    private static final List<String> INDEXED_PREFIXES =
            List.of(WorkspaceConstants.AGENTS_DIR + "/", WorkspaceConstants.MEMORY_DIR + "/");

    private final Connection conn;

    // -------------------------------------------------------------------------
    //  Factory
    // -------------------------------------------------------------------------

    /**
     * Opens (or creates) the workspace index for the given workspace root.
     *
     * <p>Returns {@code null} if the index cannot be initialized — callers should treat a
     * {@code null} index as "unavailable" and fall back to remote scan.
     *
     * @param workspaceRoot absolute path to the workspace root directory
     * @return a ready-to-use {@link WorkspaceIndex}, or {@code null} on failure
     */
    public static WorkspaceIndex open(Path workspaceRoot) {
        try {
            Path indexDir = workspaceRoot.resolve(INDEX_DIR);
            Files.createDirectories(indexDir);
            Path dbFile = indexDir.resolve(INDEX_DB);
            String url = "jdbc:sqlite:" + dbFile.toAbsolutePath();
            Connection c = DriverManager.getConnection(url);
            WorkspaceIndex idx = new WorkspaceIndex(c);
            idx.initSchema();
            return idx;
        } catch (Exception e) {
            log.warn("WorkspaceIndex unavailable (non-fatal): {}", e.getMessage());
            return null;
        }
    }

    private WorkspaceIndex(Connection conn) {
        this.conn = conn;
    }

    // -------------------------------------------------------------------------
    //  Schema
    // -------------------------------------------------------------------------

    private void initSchema() throws SQLException {
        try (Statement st = conn.createStatement()) {
            st.executeUpdate(
                    "CREATE TABLE IF NOT EXISTS files ("
                            + "path         TEXT PRIMARY KEY,"
                            + "size_bytes   INTEGER,"
                            + "modified_at  TEXT,"
                            + "content_type TEXT,"
                            + "encoding     TEXT,"
                            + "present_local INTEGER DEFAULT 1"
                            + ")");
            st.executeUpdate(
                    "CREATE TABLE IF NOT EXISTS index_state ("
                            + "key TEXT PRIMARY KEY,"
                            + "value TEXT"
                            + ")");
            // Record schema version (INSERT OR IGNORE so we only write it once)
            st.executeUpdate(
                    "INSERT OR IGNORE INTO index_state(key, value) VALUES ('schema_version', '"
                            + SCHEMA_VERSION
                            + "')");
        }
    }

    // -------------------------------------------------------------------------
    //  Write operations
    // -------------------------------------------------------------------------

    /**
     * Upserts a file entry in the index. Silently no-ops if the path is not under an indexed
     * prefix, or if any error occurs.
     *
     * @param path workspace-relative path (forward slashes)
     * @param sizeBytes file size in bytes; pass {@code -1} if unknown
     * @param modifiedAt ISO-8601 timestamp string; pass {@code null} to use current time
     */
    public void upsert(String path, long sizeBytes, String modifiedAt) {
        if (!isIndexable(path)) {
            return;
        }
        try {
            String ts = modifiedAt != null ? modifiedAt : Instant.now().toString();
            try (PreparedStatement ps =
                    conn.prepareStatement(
                            "INSERT INTO files(path, size_bytes, modified_at, present_local)"
                                    + " VALUES(?,?,?,1)"
                                    + " ON CONFLICT(path) DO UPDATE SET"
                                    + "  size_bytes=excluded.size_bytes,"
                                    + "  modified_at=excluded.modified_at,"
                                    + "  present_local=1")) {
                ps.setString(1, path);
                ps.setLong(2, sizeBytes);
                ps.setString(3, ts);
                ps.executeUpdate();
            }
        } catch (Exception e) {
            log.debug("Index upsert failed for '{}' (non-fatal): {}", path, e.getMessage());
        }
    }

    /**
     * Convenience overload that reads file size from the local file if it exists.
     *
     * @param path workspace-relative path
     * @param localFile absolute path on disk (used for size / mtime); may not exist
     */
    public void upsertFromLocalFile(String path, Path localFile) {
        if (!isIndexable(path)) {
            return;
        }
        try {
            long size = -1;
            String mtime = null;
            if (Files.isRegularFile(localFile)) {
                size = Files.size(localFile);
                mtime = Files.getLastModifiedTime(localFile).toInstant().toString();
            }
            upsert(path, size, mtime);
        } catch (IOException e) {
            log.debug("Index upsert (stat) failed for '{}' (non-fatal): {}", path, e.getMessage());
        }
    }

    /**
     * Removes a file entry from the index. Silently no-ops on errors.
     *
     * @param path workspace-relative path
     */
    public void remove(String path) {
        if (!isIndexable(path)) {
            return;
        }
        try (PreparedStatement ps = conn.prepareStatement("DELETE FROM files WHERE path=?")) {
            ps.setString(1, path);
            ps.executeUpdate();
        } catch (Exception e) {
            log.debug("Index remove failed for '{}' (non-fatal): {}", path, e.getMessage());
        }
    }

    /**
     * Renames (moves) an index entry from {@code fromPath} to {@code toPath}. Silently no-ops on
     * errors.
     */
    public void rename(String fromPath, String toPath) {
        if (!isIndexable(fromPath) && !isIndexable(toPath)) {
            return;
        }
        try (PreparedStatement ps = conn.prepareStatement("UPDATE files SET path=? WHERE path=?")) {
            ps.setString(1, toPath);
            ps.setString(2, fromPath);
            ps.executeUpdate();
        } catch (Exception e) {
            log.debug(
                    "Index rename failed '{}' -> '{}' (non-fatal): {}",
                    fromPath,
                    toPath,
                    e.getMessage());
        }
    }

    // -------------------------------------------------------------------------
    //  Read operations
    // -------------------------------------------------------------------------

    /**
     * Returns {@code true} if the index contains an entry for the given path with
     * {@code present_local = 1}.
     */
    public boolean exists(String path) {
        if (!isIndexable(path)) {
            return false;
        }
        try (PreparedStatement ps =
                conn.prepareStatement(
                        "SELECT 1 FROM files WHERE path=? AND present_local=1 LIMIT 1")) {
            ps.setString(1, path);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next();
            }
        } catch (Exception e) {
            log.debug("Index exists failed for '{}' (non-fatal): {}", path, e.getMessage());
            return false;
        }
    }

    /**
     * Returns all locally-present paths that start with the given prefix.
     *
     * @param prefix workspace-relative directory prefix (e.g. {@code agents/a1/sessions/})
     * @return list of matching paths, may be empty
     */
    public List<String> listByPrefix(String prefix) {
        List<String> result = new ArrayList<>();
        if (prefix == null) {
            return result;
        }
        try (PreparedStatement ps =
                conn.prepareStatement(
                        "SELECT path FROM files WHERE path LIKE ? AND present_local=1")) {
            ps.setString(1, prefix + "%");
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    result.add(rs.getString(1));
                }
            }
        } catch (Exception e) {
            log.debug("Index listByPrefix failed for '{}' (non-fatal): {}", prefix, e.getMessage());
        }
        return result;
    }

    /**
     * Returns true if the index has any entries under the given prefix. Faster than
     * {@link #listByPrefix} when only presence is needed.
     */
    public boolean hasPrefix(String prefix) {
        try (PreparedStatement ps =
                conn.prepareStatement(
                        "SELECT 1 FROM files WHERE path LIKE ? AND present_local=1 LIMIT 1")) {
            ps.setString(1, prefix + "%");
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next();
            }
        } catch (Exception e) {
            log.debug("Index hasPrefix failed (non-fatal): {}", e.getMessage());
            return false;
        }
    }

    // -------------------------------------------------------------------------
    //  Rebuild
    // -------------------------------------------------------------------------

    /**
     * Rebuilds the index by walking the local workspace directories
     * ({@code agents/&#42;/sessions} and {@code memory}). Existing entries are replaced;
     * stale entries for files that no longer exist are removed.
     *
     * <p>This is a best-effort operation: errors are logged and do not throw.
     *
     * @param workspaceRoot absolute path to workspace root
     */
    public void rebuildFromDisk(Path workspaceRoot) {
        try {
            // Clear existing entries
            try (Statement st = conn.createStatement()) {
                st.executeUpdate("DELETE FROM files");
            }

            for (String prefix : INDEXED_PREFIXES) {
                Path dir = workspaceRoot.resolve(prefix);
                if (!Files.isDirectory(dir)) {
                    continue;
                }
                try (var stream = Files.walk(dir)) {
                    stream.filter(Files::isRegularFile)
                            .forEach(
                                    file -> {
                                        Path rel =
                                                workspaceRoot
                                                        .toAbsolutePath()
                                                        .relativize(file.toAbsolutePath());
                                        String relStr = rel.toString().replace('\\', '/');
                                        upsertFromLocalFile(relStr, file);
                                    });
                }
            }
            log.info("WorkspaceIndex rebuilt from local disk");
        } catch (Exception e) {
            log.warn("WorkspaceIndex rebuild failed (non-fatal): {}", e.getMessage());
        }
    }

    // -------------------------------------------------------------------------
    //  Lifecycle
    // -------------------------------------------------------------------------

    @Override
    public void close() {
        try {
            conn.close();
        } catch (SQLException e) {
            log.debug("WorkspaceIndex close error (non-fatal): {}", e.getMessage());
        }
    }

    // -------------------------------------------------------------------------
    //  Helpers
    // -------------------------------------------------------------------------

    /**
     * Returns {@code true} if the given workspace-relative path falls under one of the
     * indexed directory prefixes.
     */
    static boolean isIndexable(String path) {
        if (path == null || path.isBlank()) {
            return false;
        }
        for (String prefix : INDEXED_PREFIXES) {
            if (path.startsWith(prefix)) {
                return true;
            }
        }
        return false;
    }
}
