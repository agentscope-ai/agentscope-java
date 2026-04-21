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
package io.agentscope.harness.agent.memory;

import io.agentscope.harness.agent.workspace.WorkspaceManager;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Stream;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * SQLite FTS5-backed index for long-term memory files ({@code MEMORY.md} and
 * {@code memory/*.md}).
 *
 * <p>Each line of every indexed file is stored as a separate row so that search
 * results can reference specific line numbers. The index is stored at
 * {@code {workspace}/.agentscope/memory_index.db}.
 */
public class MemoryIndex implements AutoCloseable {

    private static final Logger log = LoggerFactory.getLogger(MemoryIndex.class);

    private final Path dbPath;
    private Connection connection;

    public MemoryIndex(Path workspaceAgentScopeDir) {
        this.dbPath = workspaceAgentScopeDir.resolve("memory_index.db");
    }

    /**
     * Opens the SQLite database and creates the FTS5 virtual table if needed.
     */
    public synchronized void open() throws SQLException {
        if (connection != null && !connection.isClosed()) {
            return;
        }
        try {
            Files.createDirectories(dbPath.getParent());
        } catch (IOException e) {
            throw new SQLException("Cannot create directory for memory_index.db", e);
        }
        connection = DriverManager.getConnection("jdbc:sqlite:" + dbPath.toAbsolutePath());
        try (Statement stmt = connection.createStatement()) {
            stmt.execute(
                    "CREATE VIRTUAL TABLE IF NOT EXISTS memory_fts "
                            + "USING fts5(path, line_number, content)");
        }
    }

    /**
     * Re-indexes a single file: deletes old rows for that path and inserts new ones.
     */
    public synchronized void indexFile(Path file) throws SQLException, IOException {
        ensureOpen();
        String relativePath = file.getFileName().toString();
        Path parent = file.getParent();
        if (parent != null) {
            Path parentName = parent.getFileName();
            if (parentName != null && "memory".equals(parentName.toString())) {
                relativePath = "memory/" + file.getFileName();
            }
        }

        try (PreparedStatement del =
                connection.prepareStatement("DELETE FROM memory_fts WHERE path = ?")) {
            del.setString(1, relativePath);
            del.executeUpdate();
        }

        if (!Files.exists(file)) {
            return;
        }

        List<String> lines = Files.readAllLines(file);
        insertLines(relativePath, lines);
        log.debug("Indexed {} lines from {}", lines.size(), relativePath);
    }

    /**
     * Re-indexes from in-memory content (same logical paths as {@link #indexFile(Path)}).
     */
    public synchronized void indexFromString(String relativePath, String content)
            throws SQLException, IOException {
        ensureOpen();
        if (relativePath == null || relativePath.isBlank()) {
            return;
        }
        try (PreparedStatement del =
                connection.prepareStatement("DELETE FROM memory_fts WHERE path = ?")) {
            del.setString(1, relativePath);
            del.executeUpdate();
        }
        if (content == null || content.isBlank()) {
            return;
        }
        List<String> lines = java.util.Arrays.asList(content.split("\n", -1));
        insertLines(relativePath, lines);
        log.debug("Indexed {} lines from {}", lines.size(), relativePath);
    }

    private void insertLines(String relativePath, List<String> lines) throws SQLException {
        try (PreparedStatement ins =
                connection.prepareStatement(
                        "INSERT INTO memory_fts(path, line_number, content) VALUES (?, ?, ?)")) {
            for (int i = 0; i < lines.size(); i++) {
                String line = lines.get(i).strip();
                if (line.isEmpty()) {
                    continue;
                }
                ins.setString(1, relativePath);
                ins.setString(2, String.valueOf(i + 1));
                ins.setString(3, line);
                ins.addBatch();
            }
            ins.executeBatch();
        }
    }

    /**
     * Full-text search across indexed memory files. Returns up to {@code maxResults} hits
     * ranked by FTS5 relevance.
     */
    public synchronized List<SearchHit> search(String query, int maxResults) throws SQLException {
        ensureOpen();
        List<SearchHit> results = new ArrayList<>();
        String ftsQuery = sanitizeFtsQuery(query);
        if (ftsQuery.isBlank()) {
            return results;
        }
        try (PreparedStatement ps =
                connection.prepareStatement(
                        "SELECT path, line_number, content, rank "
                                + "FROM memory_fts WHERE memory_fts MATCH ? "
                                + "ORDER BY rank LIMIT ?")) {
            ps.setString(1, ftsQuery);
            ps.setInt(2, maxResults);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    results.add(
                            new SearchHit(
                                    rs.getString("path"),
                                    rs.getInt("line_number"),
                                    rs.getString("content"),
                                    rs.getDouble("rank")));
                }
            }
        }
        return results;
    }

    /**
     * Backend-agnostic indexing: discovers all memory files and session log files via
     * {@link WorkspaceManager} and indexes their content. Works for Local, Store, and
     * Sandbox backends.
     */
    public void indexAllFromWorkspace(WorkspaceManager wsManager) throws SQLException, IOException {
        List<String> paths = wsManager.listMemoryFilePaths();
        for (String relativePath : paths) {
            String content = wsManager.readManagedWorkspaceFileUtf8(relativePath);
            indexFromString(relativePath, content);
        }

        indexSessionLogFiles(wsManager);
    }

    /**
     * Indexes session log files ({@code .log.jsonl}) for full-text search across
     * session history. Uses {@link WorkspaceManager#listSessionLogFiles()} to discover
     * files from both the filesystem layer and local disk.
     */
    private void indexSessionLogFiles(WorkspaceManager wsManager) {
        List<String> logPaths = wsManager.listSessionLogFiles();
        for (String relativePath : logPaths) {
            try {
                String content = wsManager.readManagedWorkspaceFileUtf8(relativePath);
                indexFromString(relativePath, content);
            } catch (Exception e) {
                log.debug("Failed to index session log {}: {}", relativePath, e.getMessage());
            }
        }
    }

    /**
     * Indexes {@code MEMORY.md} and all {@code memory/*.md} files under the given workspace.
     *
     * @deprecated Use {@link #indexAllFromWorkspace(WorkspaceManager)} for backend-agnostic
     *     indexing.
     */
    @Deprecated
    public void indexAll(Path workspace) throws SQLException, IOException {
        Path memoryMd = workspace.resolve("MEMORY.md");
        if (Files.exists(memoryMd)) {
            indexFile(memoryMd);
        }

        Path memoryDir = workspace.resolve("memory");
        if (Files.isDirectory(memoryDir)) {
            try (Stream<Path> files = Files.list(memoryDir)) {
                List<Path> mdFiles = files.filter(p -> p.toString().endsWith(".md")).toList();
                for (Path f : mdFiles) {
                    indexFile(f);
                }
            }
        }
    }

    @Override
    public synchronized void close() {
        if (connection != null) {
            try {
                connection.close();
            } catch (SQLException e) {
                log.warn("Error closing memory index db: {}", e.getMessage());
            }
            connection = null;
        }
    }

    private void ensureOpen() throws SQLException {
        if (connection == null || connection.isClosed()) {
            open();
        }
    }

    /**
     * Sanitizes a user query for FTS5 MATCH syntax. Wraps each token in double quotes
     * so special characters are treated as literals.
     */
    static String sanitizeFtsQuery(String raw) {
        if (raw == null) {
            return "";
        }
        String[] tokens = raw.strip().split("\\s+");
        StringBuilder sb = new StringBuilder();
        for (String tok : tokens) {
            if (tok.isEmpty()) {
                continue;
            }
            if (sb.length() > 0) {
                sb.append(' ');
            }
            sb.append('"').append(tok.replace("\"", "")).append('"');
        }
        return sb.toString();
    }

    /**
     * A single search result.
     */
    public record SearchHit(String path, int lineNumber, String content, double rank) {}
}
