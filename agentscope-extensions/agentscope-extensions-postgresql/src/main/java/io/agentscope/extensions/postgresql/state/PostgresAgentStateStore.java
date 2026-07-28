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
package io.agentscope.extensions.postgresql.state;

import io.agentscope.core.state.AgentStateStore;
import io.agentscope.core.state.ListHashUtil;
import io.agentscope.core.state.State;
import io.agentscope.core.util.JsonUtils;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Pattern;
import javax.sql.DataSource;

/**
 * PostgreSQL database-based {@link AgentStateStore} implementation.
 *
 * <p>Stores session state in PostgreSQL tables with the following structure:
 *
 * <pre>
 * CREATE TABLE IF NOT EXISTS "agentscope"."agentscope_sessions" (
 *     session_id VARCHAR(255) NOT NULL,
 *     state_key  VARCHAR(255) NOT NULL,
 *     item_index INT          NOT NULL DEFAULT 0,
 *     state_data TEXT         NOT NULL,
 *     created_at TIMESTAMP    DEFAULT CURRENT_TIMESTAMP,
 *     updated_at TIMESTAMP    DEFAULT CURRENT_TIMESTAMP,
 *     PRIMARY KEY (session_id, state_key, item_index)
 * );
 * </pre>
 *
 * <ul>
 *   <li>Single state: stored as JSON with item_index = 0</li>
 *   <li>List state: each item stored in a separate row with item_index = 0, 1, 2, ...</li>
 * </ul>
 */
public class PostgresAgentStateStore implements AgentStateStore {

    private static final String DEFAULT_SCHEMA_NAME = "agentscope";
    private static final String DEFAULT_TABLE_NAME = "agentscope_sessions";

    /**
     * Suffix for hash storage keys.
     */
    private static final String HASH_KEY_SUFFIX = ":_hash";

    /**
     * item_index value for single state values.
     */
    private static final int SINGLE_STATE_INDEX = 0;

    private static final Pattern IDENTIFIER_PATTERN = Pattern.compile("^[a-zA-Z_][a-zA-Z0-9_]*$");
    private static final int MAX_IDENTIFIER_LENGTH = 63;

    private final DataSource dataSource;
    private final String schemaName;
    private final String tableName;

    @FunctionalInterface
    private interface SqlOperation {
        void execute() throws Exception;
    }

    /**
     * Creates a PostgresAgentStateStore with default settings.
     *
     * <p>Uses default schema ({@code agentscope}) and table ({@code agentscope_sessions}) and does
     * NOT auto-create the schema or table.
     */
    public PostgresAgentStateStore(DataSource dataSource) {
        this(dataSource, DEFAULT_SCHEMA_NAME, DEFAULT_TABLE_NAME, false);
    }

    /**
     * Creates a PostgresAgentStateStore with optional auto-creation.
     *
     * <p>Uses default schema and table. If {@code createIfNotExist} is true, the schema and table
     * are created automatically if they don't exist.
     */
    public PostgresAgentStateStore(DataSource dataSource, boolean createIfNotExist) {
        this(dataSource, DEFAULT_SCHEMA_NAME, DEFAULT_TABLE_NAME, createIfNotExist);
    }

    /**
     * Creates a PostgresAgentStateStore with custom schema/table and optional auto-creation.
     */
    public PostgresAgentStateStore(
            DataSource dataSource, String schemaName, String tableName, boolean createIfNotExist) {
        if (dataSource == null) {
            throw new IllegalArgumentException("DataSource cannot be null");
        }

        this.dataSource = dataSource;
        this.schemaName =
                (schemaName == null || schemaName.trim().isEmpty())
                        ? DEFAULT_SCHEMA_NAME
                        : schemaName.trim();
        this.tableName =
                (tableName == null || tableName.trim().isEmpty())
                        ? DEFAULT_TABLE_NAME
                        : tableName.trim();

        validateIdentifier(this.schemaName, "Schema name");
        validateIdentifier(this.tableName, "Table name");

        if (createIfNotExist) {
            createSchemaIfNotExist();
            createTableIfNotExist();
        } else {
            verifySchemaExists();
            verifyTableExists();
        }
    }

    private void createSchemaIfNotExist() {
        String sql = "CREATE SCHEMA IF NOT EXISTS \"" + schemaName + "\"";
        try (Connection conn = dataSource.getConnection();
                PreparedStatement stmt = conn.prepareStatement(sql)) {
            stmt.execute();
        } catch (SQLException e) {
            throw new RuntimeException("Failed to create schema: " + schemaName, e);
        }
    }

    private void createTableIfNotExist() {
        String createTableSql =
                """
                CREATE TABLE IF NOT EXISTS %s (
                    session_id VARCHAR(255) NOT NULL,
                    state_key  VARCHAR(255) NOT NULL,
                    item_index INT          NOT NULL DEFAULT 0,
                    state_data TEXT         NOT NULL,
                    created_at TIMESTAMP    DEFAULT CURRENT_TIMESTAMP,
                    updated_at TIMESTAMP    DEFAULT CURRENT_TIMESTAMP,
                    PRIMARY KEY (session_id, state_key, item_index)
                )
                """
                        .formatted(getFullTableName());
        try (Connection conn = dataSource.getConnection();
                PreparedStatement stmt = conn.prepareStatement(createTableSql)) {
            stmt.execute();
        } catch (SQLException e) {
            throw new RuntimeException("Failed to create session table: " + tableName, e);
        }
    }

    private void verifySchemaExists() {
        String sql = "SELECT SCHEMA_NAME FROM INFORMATION_SCHEMA.SCHEMATA WHERE SCHEMA_NAME = ?";
        try (Connection conn = dataSource.getConnection();
                PreparedStatement stmt = conn.prepareStatement(sql)) {
            stmt.setString(1, schemaName);
            try (ResultSet rs = stmt.executeQuery()) {
                if (!rs.next()) {
                    throw new IllegalStateException(
                            "Schema does not exist: "
                                    + schemaName
                                    + ". Use PostgresAgentStateStore(dataSource, true) or"
                                    + " builder(dataSource).createIfNotExist(true).build() to"
                                    + " auto-create.");
                }
            }
        } catch (SQLException e) {
            throw new RuntimeException("Failed to check schema existence: " + schemaName, e);
        }
    }

    private void verifyTableExists() {
        String sql =
                """
                SELECT TABLE_NAME FROM INFORMATION_SCHEMA.TABLES
                WHERE TABLE_SCHEMA = ? AND TABLE_NAME = ?
                """;
        try (Connection conn = dataSource.getConnection();
                PreparedStatement stmt = conn.prepareStatement(sql)) {
            stmt.setString(1, schemaName);
            stmt.setString(2, tableName);
            try (ResultSet rs = stmt.executeQuery()) {
                if (!rs.next()) {
                    throw new IllegalStateException(
                            "Table does not exist: "
                                    + schemaName
                                    + "."
                                    + tableName
                                    + ". Use PostgresAgentStateStore(dataSource, true) or"
                                    + " builder(dataSource).createIfNotExist(true).build() to"
                                    + " auto-create.");
                }
            }
        } catch (SQLException e) {
            throw new RuntimeException("Failed to check table existence: " + tableName, e);
        }
    }

    private String getFullTableName() {
        return "\"" + schemaName + "\".\"" + tableName + "\"";
    }

    private void executeInWriteTransaction(Connection conn, SqlOperation operation)
            throws Exception {
        boolean originalAutoCommit = conn.getAutoCommit();
        if (originalAutoCommit) {
            conn.setAutoCommit(false);
        }
        try {
            operation.execute();
            conn.commit();
        } catch (Exception e) {
            try {
                conn.rollback();
            } catch (SQLException rollbackException) {
                e.addSuppressed(rollbackException);
            }
            throw e;
        } finally {
            if (conn.getAutoCommit() != originalAutoCommit) {
                conn.setAutoCommit(originalAutoCommit);
            }
        }
    }

    @Override
    public void save(String userId, String sessionId, String key, State value) {
        String slotId = slotId(userId, sessionId);
        validateSessionId(slotId);
        validateStateKey(key);

        String upsertSql =
                """
                INSERT INTO %s (session_id, state_key, item_index, state_data, updated_at)
                VALUES (?, ?, ?, ?, ?)
                ON CONFLICT (session_id, state_key, item_index) DO UPDATE SET
                    state_data = EXCLUDED.state_data,
                    updated_at = EXCLUDED.updated_at
                """
                        .formatted(getFullTableName());
        Timestamp now = new Timestamp(System.currentTimeMillis());

        try (Connection conn = dataSource.getConnection()) {
            executeInWriteTransaction(
                    conn,
                    () -> {
                        try (PreparedStatement stmt = conn.prepareStatement(upsertSql)) {
                            String json = JsonUtils.getJsonCodec().toJson(value);
                            stmt.setString(1, slotId);
                            stmt.setString(2, key);
                            stmt.setInt(3, SINGLE_STATE_INDEX);
                            stmt.setString(4, json);
                            stmt.setTimestamp(5, now);
                            stmt.executeUpdate();
                        }
                    });
        } catch (Exception e) {
            throw new RuntimeException("Failed to save state: " + key, e);
        }
    }

    @Override
    public void save(String userId, String sessionId, String key, List<? extends State> values) {
        String slotId = slotId(userId, sessionId);
        validateSessionId(slotId);
        validateStateKey(key);

        if (values.isEmpty()) {
            return;
        }

        String hashKey = key + HASH_KEY_SUFFIX;

        try (Connection conn = dataSource.getConnection()) {
            executeInWriteTransaction(
                    conn,
                    () -> {
                        String currentHash = ListHashUtil.computeHash(values);
                        String storedHash = getStoredHash(conn, slotId, hashKey);
                        int existingCount = getListCount(conn, slotId, key);
                        boolean needsFullRewrite =
                                ListHashUtil.needsFullRewrite(values, storedHash, existingCount);

                        if (needsFullRewrite) {
                            deleteListItems(conn, slotId, key);
                            insertAllItems(conn, slotId, key, values);
                            saveHash(conn, slotId, hashKey, currentHash);
                        } else if (values.size() > existingCount) {
                            List<? extends State> newItems =
                                    values.subList(existingCount, values.size());
                            insertItems(conn, slotId, key, newItems, existingCount);
                            saveHash(conn, slotId, hashKey, currentHash);
                        }
                    });
        } catch (Exception e) {
            throw new RuntimeException("Failed to save list: " + key, e);
        }
    }

    private String getStoredHash(Connection conn, String sessionId, String hashKey)
            throws SQLException {
        String sql =
                "SELECT state_data FROM "
                        + getFullTableName()
                        + " WHERE session_id = ? AND state_key = ? AND item_index = ?";
        try (PreparedStatement stmt = conn.prepareStatement(sql)) {
            stmt.setString(1, sessionId);
            stmt.setString(2, hashKey);
            stmt.setInt(3, SINGLE_STATE_INDEX);
            try (ResultSet rs = stmt.executeQuery()) {
                if (rs.next()) {
                    return rs.getString("state_data");
                }
                return null;
            }
        }
    }

    private void saveHash(Connection conn, String sessionId, String hashKey, String hash)
            throws SQLException {
        String upsertSql =
                """
                INSERT INTO %s (session_id, state_key, item_index, state_data, updated_at)
                VALUES (?, ?, ?, ?, ?)
                ON CONFLICT (session_id, state_key, item_index) DO UPDATE SET
                    state_data = EXCLUDED.state_data,
                    updated_at = EXCLUDED.updated_at
                """
                        .formatted(getFullTableName());
        Timestamp now = new Timestamp(System.currentTimeMillis());
        try (PreparedStatement stmt = conn.prepareStatement(upsertSql)) {
            stmt.setString(1, sessionId);
            stmt.setString(2, hashKey);
            stmt.setInt(3, SINGLE_STATE_INDEX);
            stmt.setString(4, hash);
            stmt.setTimestamp(5, now);
            stmt.executeUpdate();
        }
    }

    private void deleteListItems(Connection conn, String sessionId, String key)
            throws SQLException {
        String sql =
                "DELETE FROM " + getFullTableName() + " WHERE session_id = ? AND state_key = ?";
        try (PreparedStatement stmt = conn.prepareStatement(sql)) {
            stmt.setString(1, sessionId);
            stmt.setString(2, key);
            stmt.executeUpdate();
        }
    }

    private void insertAllItems(
            Connection conn, String sessionId, String key, List<? extends State> values)
            throws Exception {
        insertItems(conn, sessionId, key, values, 0);
    }

    private void insertItems(
            Connection conn,
            String sessionId,
            String key,
            List<? extends State> items,
            int startIndex)
            throws Exception {
        String sql =
                "INSERT INTO "
                        + getFullTableName()
                        + " (session_id, state_key, item_index, state_data) VALUES (?, ?, ?, ?)";
        try (PreparedStatement stmt = conn.prepareStatement(sql)) {
            int index = startIndex;
            for (State item : items) {
                String json = JsonUtils.getJsonCodec().toJson(item);
                stmt.setString(1, sessionId);
                stmt.setString(2, key);
                stmt.setInt(3, index);
                stmt.setString(4, json);
                stmt.addBatch();
                index++;
            }
            stmt.executeBatch();
        }
    }

    private int getListCount(Connection conn, String sessionId, String key) throws SQLException {
        String sql =
                "SELECT MAX(item_index) as max_index FROM "
                        + getFullTableName()
                        + " WHERE session_id = ? AND state_key = ?";
        try (PreparedStatement stmt = conn.prepareStatement(sql)) {
            stmt.setString(1, sessionId);
            stmt.setString(2, key);
            try (ResultSet rs = stmt.executeQuery()) {
                if (rs.next()) {
                    int maxIndex = rs.getInt("max_index");
                    if (rs.wasNull()) {
                        return 0;
                    }
                    return maxIndex + 1;
                }
                return 0;
            }
        }
    }

    @Override
    public <T extends State> Optional<T> get(
            String userId, String sessionId, String key, Class<T> type) {
        String slotId = slotId(userId, sessionId);
        validateSessionId(slotId);
        validateStateKey(key);

        String sql =
                "SELECT state_data FROM "
                        + getFullTableName()
                        + " WHERE session_id = ? AND state_key = ? AND item_index = ?";
        try (Connection conn = dataSource.getConnection();
                PreparedStatement stmt = conn.prepareStatement(sql)) {
            stmt.setString(1, slotId);
            stmt.setString(2, key);
            stmt.setInt(3, SINGLE_STATE_INDEX);
            try (ResultSet rs = stmt.executeQuery()) {
                if (rs.next()) {
                    String json = rs.getString("state_data");
                    return Optional.of(JsonUtils.getJsonCodec().fromJson(json, type));
                }
                return Optional.empty();
            }
        } catch (Exception e) {
            throw new RuntimeException("Failed to get state: " + key, e);
        }
    }

    @Override
    public <T extends State> List<T> getList(
            String userId, String sessionId, String key, Class<T> itemType) {
        String slotId = slotId(userId, sessionId);
        validateSessionId(slotId);
        validateStateKey(key);

        String sql =
                "SELECT state_data FROM "
                        + getFullTableName()
                        + " WHERE session_id = ? AND state_key = ? ORDER BY item_index";
        try (Connection conn = dataSource.getConnection();
                PreparedStatement stmt = conn.prepareStatement(sql)) {
            stmt.setString(1, slotId);
            stmt.setString(2, key);
            try (ResultSet rs = stmt.executeQuery()) {
                List<T> result = new ArrayList<>();
                while (rs.next()) {
                    String json = rs.getString("state_data");
                    result.add(JsonUtils.getJsonCodec().fromJson(json, itemType));
                }
                return result;
            }
        } catch (Exception e) {
            throw new RuntimeException("Failed to get list: " + key, e);
        }
    }

    @Override
    public boolean exists(String userId, String sessionId) {
        String slotId = slotId(userId, sessionId);
        validateSessionId(slotId);

        String sql = "SELECT 1 FROM " + getFullTableName() + " WHERE session_id = ? LIMIT 1";
        try (Connection conn = dataSource.getConnection();
                PreparedStatement stmt = conn.prepareStatement(sql)) {
            stmt.setString(1, slotId);
            try (ResultSet rs = stmt.executeQuery()) {
                return rs.next();
            }
        } catch (SQLException e) {
            throw new RuntimeException("Failed to check session existence: " + slotId, e);
        }
    }

    @Override
    public void delete(String userId, String sessionId) {
        String slotId = slotId(userId, sessionId);
        validateSessionId(slotId);

        String sql = "DELETE FROM " + getFullTableName() + " WHERE session_id = ?";
        try (Connection conn = dataSource.getConnection()) {
            executeInWriteTransaction(
                    conn,
                    () -> {
                        try (PreparedStatement stmt = conn.prepareStatement(sql)) {
                            stmt.setString(1, slotId);
                            stmt.executeUpdate();
                        }
                    });
        } catch (Exception e) {
            throw new RuntimeException("Failed to delete session: " + slotId, e);
        }
    }

    @Override
    public Set<String> listSessionIds(String userId) {
        String userSegment = normalizeUser(userId);
        String prefix = userSegment + ":";
        String sql =
                "SELECT DISTINCT session_id FROM "
                        + getFullTableName()
                        + " WHERE session_id LIKE ? ORDER BY session_id";
        try (Connection conn = dataSource.getConnection();
                PreparedStatement stmt = conn.prepareStatement(sql)) {
            stmt.setString(1, prefix + "%");
            try (ResultSet rs = stmt.executeQuery()) {
                Set<String> sessionIds = new HashSet<>();
                while (rs.next()) {
                    String slot = rs.getString("session_id");
                    sessionIds.add(slot.substring(prefix.length()));
                }
                return sessionIds;
            }
        } catch (SQLException e) {
            throw new RuntimeException("Failed to list sessions", e);
        }
    }

    private static final String ANON_USER = "__anon__";

    private static String normalizeUser(String userId) {
        return userId == null || userId.isBlank() ? ANON_USER : userId;
    }

    private static String slotId(String userId, String sessionId) {
        if (sessionId == null || sessionId.isBlank()) {
            throw new IllegalArgumentException("sessionId must not be blank");
        }
        return normalizeUser(userId) + ":" + sessionId;
    }

    @Override
    public void close() {
        // DataSource is managed externally, so we don't close it here
    }

    /**
     * Truncate session table from the database (for testing or cleanup).
     *
     * <p>This method clears all session records by executing a {@code TRUNCATE TABLE} statement on
     * the sessions table. TRUNCATE is faster than DELETE as it resets the table without logging
     * individual row deletions and reclaims storage space immediately.
     *
     * <p><strong>Note:</strong> In PostgreSQL, {@code TRUNCATE TABLE} is DDL and triggers an
     * implicit commit. For that reason, this method executes the statement directly instead of
     * routing it through {@link #executeInWriteTransaction(Connection, SqlOperation)}.
     *
     * @return typically 0 if successful
     */
    public int truncateAllSessions() {
        String clearSql = "TRUNCATE TABLE " + getFullTableName();

        try (Connection conn = dataSource.getConnection();
                PreparedStatement stmt = conn.prepareStatement(clearSql)) {
            return stmt.executeUpdate();
        } catch (SQLException e) {
            throw new RuntimeException("Failed to truncate sessions", e);
        }
    }

    public String getSchemaName() {
        return schemaName;
    }

    public String getTableName() {
        return tableName;
    }

    public DataSource getDataSource() {
        return dataSource;
    }

    protected void validateSessionId(String sessionId) {
        if (sessionId == null || sessionId.trim().isEmpty()) {
            throw new IllegalArgumentException("AgentStateStore ID cannot be null or empty");
        }
        if (sessionId.contains("/") || sessionId.contains("\\")) {
            throw new IllegalArgumentException("AgentStateStore ID cannot contain path separators");
        }
        if (sessionId.length() > 255) {
            throw new IllegalArgumentException("AgentStateStore ID cannot exceed 255 characters");
        }
    }

    private void validateStateKey(String key) {
        if (key == null || key.trim().isEmpty()) {
            throw new IllegalArgumentException("State key cannot be null or empty");
        }
        if (key.length() > 255) {
            throw new IllegalArgumentException("State key cannot exceed 255 characters");
        }
    }

    private void validateIdentifier(String identifier, String identifierType) {
        if (identifier.length() > MAX_IDENTIFIER_LENGTH) {
            throw new IllegalArgumentException(
                    identifierType + " cannot exceed " + MAX_IDENTIFIER_LENGTH + " characters");
        }
        if (!IDENTIFIER_PATTERN.matcher(identifier).matches()) {
            throw new IllegalArgumentException(
                    identifierType
                            + " contains invalid characters. Only alphanumeric characters and"
                            + " underscores are allowed, and it must start with a letter or"
                            + " underscore. Invalid value: "
                            + identifier);
        }
    }

    /**
     * Builder for {@link PostgresAgentStateStore}.
     */
    public static final class Builder {

        private final DataSource dataSource;
        private String schemaName = DEFAULT_SCHEMA_NAME;
        private String tableName = DEFAULT_TABLE_NAME;
        private boolean createIfNotExist = true;

        private Builder(DataSource dataSource) {
            if (dataSource == null) {
                throw new IllegalArgumentException("DataSource cannot be null");
            }
            this.dataSource = dataSource;
        }

        public Builder schemaName(String schemaName) {
            this.schemaName = schemaName;
            return this;
        }

        public Builder tableName(String tableName) {
            this.tableName = tableName;
            return this;
        }

        public Builder createIfNotExist(boolean createIfNotExist) {
            this.createIfNotExist = createIfNotExist;
            return this;
        }

        public PostgresAgentStateStore build() {
            return new PostgresAgentStateStore(dataSource, schemaName, tableName, createIfNotExist);
        }
    }

    public static Builder builder(DataSource dataSource) {
        return new Builder(dataSource);
    }
}
