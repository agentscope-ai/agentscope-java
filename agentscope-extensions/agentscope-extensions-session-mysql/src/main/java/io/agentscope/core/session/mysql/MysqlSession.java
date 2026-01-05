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
package io.agentscope.core.session.mysql;

import io.agentscope.core.session.ListHashUtil;
import io.agentscope.core.session.Session;
import io.agentscope.core.state.SessionKey;
import io.agentscope.core.state.SimpleSessionKey;
import io.agentscope.core.state.State;
import io.agentscope.core.util.JsonUtils;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Pattern;
import javax.sql.DataSource;

/**
 * MySQL database-based session implementation.
 *
 * <p>This implementation stores session state in MySQL database tables with the following
 * structure:
 *
 * <ul>
 *   <li>Single state: stored as JSON with item_index = 0
 *   <li>List state: each item stored in a separate row with item_index = 0, 1, 2, ...
 * </ul>
 *
 * <p>Table Schema (auto-created if createIfNotExist=true):
 *
 * <pre>
 * CREATE TABLE IF NOT EXISTS agentscope_sessions (
 *     session_id VARCHAR(255) NOT NULL,
 *     state_key VARCHAR(255) NOT NULL,
 *     item_index INT NOT NULL DEFAULT 0,
 *     state_data LONGTEXT NOT NULL,
 *     created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
 *     updated_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
 *     PRIMARY KEY (session_id, state_key, item_index)
 * ) DEFAULT CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci;
 * </pre>
 *
 * <p>Features:
 *
 * <ul>
 *   <li>True incremental list storage (only INSERTs new items, no read-modify-write)
 *   <li>Type-safe state serialization using Jackson
 *   <li>Automatic table creation
 *   <li>SQL injection prevention through parameterized queries
 * </ul>
 */
public class MysqlSession implements Session {

    private static final String DEFAULT_DATABASE_NAME = "agentscope";
    private static final String DEFAULT_TABLE_NAME = "agentscope_sessions";

    /** Suffix for hash storage keys. */
    private static final String HASH_KEY_SUFFIX = ":_hash";

    /** item_index value for single state values. */
    private static final int SINGLE_STATE_INDEX = 0;

    /**
     * Pattern for validating database and table names. Only allows alphanumeric characters and
     * underscores, must start with letter or underscore. This prevents SQL injection attacks
     * through malicious database/table names.
     */
    private static final Pattern IDENTIFIER_PATTERN = Pattern.compile("^[a-zA-Z_][a-zA-Z0-9_]*$");

    private static final int MAX_IDENTIFIER_LENGTH = 64; // MySQL identifier length limit

    private final DataSource dataSource;
    private final String databaseName;
    private final String tableName;

    /**
     * Create a MysqlSession with default settings.
     *
     * <p>This constructor uses default database name ({@code agentscope}) and table name ({@code
     * agentscope_sessions}), and does NOT auto-create the database or table. If the database or
     * table does not exist, an {@link IllegalStateException} will be thrown.
     *
     * @param dataSource DataSource for database connections
     * @throws IllegalArgumentException if dataSource is null
     * @throws IllegalStateException if database or table does not exist
     */
    public MysqlSession(DataSource dataSource) {
        this(dataSource, DEFAULT_DATABASE_NAME, DEFAULT_TABLE_NAME, false);
    }

    /**
     * Create a MysqlSession with optional auto-creation of database and table.
     *
     * <p>This constructor uses default database name ({@code agentscope}) and table name ({@code
     * agentscope_sessions}). If {@code createIfNotExist} is true, the database and table will be
     * created automatically if they don't exist. If false and the database or table doesn't exist,
     * an {@link IllegalStateException} will be thrown.
     *
     * @param dataSource DataSource for database connections
     * @param createIfNotExist If true, auto-create database and table; if false, require existing
     * @throws IllegalArgumentException if dataSource is null
     * @throws IllegalStateException if createIfNotExist is false and database/table does not exist
     */
    public MysqlSession(DataSource dataSource, boolean createIfNotExist) {
        this(dataSource, DEFAULT_DATABASE_NAME, DEFAULT_TABLE_NAME, createIfNotExist);
    }

    /**
     * Create a MysqlSession with custom database name, table name, and optional auto-creation.
     *
     * <p>If {@code createIfNotExist} is true, the database and table will be created automatically
     * if they don't exist. If false and the database or table doesn't exist, an {@link
     * IllegalStateException} will be thrown.
     *
     * @param dataSource DataSource for database connections
     * @param databaseName Custom database name (uses default if null or empty)
     * @param tableName Custom table name (uses default if null or empty)
     * @param createIfNotExist If true, auto-create database and table; if false, require existing
     * @throws IllegalArgumentException if dataSource is null
     * @throws IllegalStateException if createIfNotExist is false and database/table does not exist
     */
    public MysqlSession(
            DataSource dataSource,
            String databaseName,
            String tableName,
            boolean createIfNotExist) {
        if (dataSource == null) {
            throw new IllegalArgumentException("DataSource cannot be null");
        }

        this.dataSource = dataSource;
        this.databaseName =
                (databaseName == null || databaseName.trim().isEmpty())
                        ? DEFAULT_DATABASE_NAME
                        : databaseName.trim();
        this.tableName =
                (tableName == null || tableName.trim().isEmpty())
                        ? DEFAULT_TABLE_NAME
                        : tableName.trim();

        // Validate database and table names to prevent SQL injection
        validateIdentifier(this.databaseName, "Database name");
        validateIdentifier(this.tableName, "Table name");

        if (createIfNotExist) {
            // Create database and table if they don't exist
            createDatabaseIfNotExist();
            createTableIfNotExist();
        } else {
            // Verify database and table exist
            verifyDatabaseExists();
            verifyTableExists();
        }
    }

    /**
     * Create the database if it doesn't exist.
     *
     * <p>Creates the database with UTF-8 (utf8mb4) character set and unicode collation for proper
     * internationalization support.
     */
    private void createDatabaseIfNotExist() {
        String createDatabaseSql =
                "CREATE DATABASE IF NOT EXISTS "
                        + databaseName
                        + " DEFAULT CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci";

        try (Connection conn = dataSource.getConnection();
                PreparedStatement stmt = conn.prepareStatement(createDatabaseSql)) {
            stmt.execute();
        } catch (SQLException e) {
            throw new RuntimeException("Failed to create database: " + databaseName, e);
        }
    }

    /**
     * Create the sessions table if it doesn't exist.
     */
    private void createTableIfNotExist() {
        String createTableSql =
                "CREATE TABLE IF NOT EXISTS "
                        + databaseName
                        + "."
                        + tableName
                        + " (session_id VARCHAR(255) NOT NULL, state_key VARCHAR(255) NOT NULL,"
                        + " item_index INT NOT NULL DEFAULT 0, state_data LONGTEXT NOT NULL,"
                        + " created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP, updated_at TIMESTAMP"
                        + " DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP, PRIMARY KEY"
                        + " (session_id, state_key, item_index)) DEFAULT CHARACTER SET utf8mb4"
                        + " COLLATE utf8mb4_unicode_ci";

        try (Connection conn = dataSource.getConnection();
                PreparedStatement stmt = conn.prepareStatement(createTableSql)) {
            stmt.execute();
        } catch (SQLException e) {
            throw new RuntimeException("Failed to create session table: " + tableName, e);
        }
    }

    /**
     * Verify that the database exists.
     *
     * @throws IllegalStateException if database does not exist
     */
    private void verifyDatabaseExists() {
        String checkSql =
                "SELECT SCHEMA_NAME FROM INFORMATION_SCHEMA.SCHEMATA WHERE SCHEMA_NAME = ?";

        try (Connection conn = dataSource.getConnection();
                PreparedStatement stmt = conn.prepareStatement(checkSql)) {
            stmt.setString(1, databaseName);
            try (ResultSet rs = stmt.executeQuery()) {
                if (!rs.next()) {
                    throw new IllegalStateException(
                            "Database does not exist: "
                                    + databaseName
                                    + ". Use MysqlSession(dataSource, true) to auto-create.");
                }
            }
        } catch (SQLException e) {
            throw new RuntimeException("Failed to check database existence: " + databaseName, e);
        }
    }

    /**
     * Verify that the sessions table exists.
     *
     * @throws IllegalStateException if table does not exist
     */
    private void verifyTableExists() {
        String checkSql =
                "SELECT TABLE_NAME FROM INFORMATION_SCHEMA.TABLES "
                        + "WHERE TABLE_SCHEMA = ? AND TABLE_NAME = ?";

        try (Connection conn = dataSource.getConnection();
                PreparedStatement stmt = conn.prepareStatement(checkSql)) {
            stmt.setString(1, databaseName);
            stmt.setString(2, tableName);
            try (ResultSet rs = stmt.executeQuery()) {
                if (!rs.next()) {
                    throw new IllegalStateException(
                            "Table does not exist: "
                                    + databaseName
                                    + "."
                                    + tableName
                                    + ". Use MysqlSession(dataSource, true) to auto-create.");
                }
            }
        } catch (SQLException e) {
            throw new RuntimeException("Failed to check table existence: " + tableName, e);
        }
    }

    /**
     * Get the full table name with database prefix.
     *
     * @return The full table name (database.table)
     */
    private String getFullTableName() {
        return databaseName + "." + tableName;
    }

    @Override
    public void save(SessionKey sessionKey, String key, State value) {
        String sessionId = sessionKey.toIdentifier();
        validateSessionId(sessionId);
        validateStateKey(key);

        String upsertSql =
                "INSERT INTO "
                        + getFullTableName()
                        + " (session_id, state_key, item_index, state_data)"
                        + " VALUES (?, ?, ?, ?)"
                        + " ON DUPLICATE KEY UPDATE state_data = VALUES(state_data)";

        try (Connection conn = dataSource.getConnection();
                PreparedStatement stmt = conn.prepareStatement(upsertSql)) {

            String json = JsonUtils.getJsonCodec().toJson(value);

            stmt.setString(1, sessionId);
            stmt.setString(2, key);
            stmt.setInt(3, SINGLE_STATE_INDEX);
            stmt.setString(4, json);

            stmt.executeUpdate();

        } catch (Exception e) {
            throw new RuntimeException("Failed to save state: " + key, e);
        }
    }

    /**
     * Save a list of state values with hash-based change detection.
     *
     * <p>This method uses hash-based change detection to handle both append-only and mutable lists:
     *
     * <ul>
     *   <li>If the hash changes (list was modified), all existing items are deleted and rewritten
     *   <li>If the list shrinks, all existing items are deleted and rewritten
     *   <li>If the list only grows (append-only), only new items are inserted
     *   <li>If nothing changes, the operation is skipped
     * </ul>
     *
     * @param sessionKey the session identifier
     * @param key the state key (e.g., "memory_messages")
     * @param values the list of state values to save
     */
    @Override
    public void save(SessionKey sessionKey, String key, List<? extends State> values) {
        String sessionId = sessionKey.toIdentifier();
        validateSessionId(sessionId);
        validateStateKey(key);

        if (values.isEmpty()) {
            return;
        }

        String hashKey = key + HASH_KEY_SUFFIX;

        try (Connection conn = dataSource.getConnection()) {
            // Compute current hash
            String currentHash = ListHashUtil.computeHash(values);

            // Get stored hash
            String storedHash = getStoredHash(conn, sessionId, hashKey);

            // Get existing count
            int existingCount = getListCount(conn, sessionId, key);

            // Determine if full rewrite is needed
            boolean needsFullRewrite =
                    ListHashUtil.needsFullRewrite(
                            currentHash, storedHash, values.size(), existingCount);

            if (needsFullRewrite) {
                // Transaction: delete all + insert all
                conn.setAutoCommit(false);
                try {
                    deleteListItems(conn, sessionId, key);
                    insertAllItems(conn, sessionId, key, values);
                    saveHash(conn, sessionId, hashKey, currentHash);
                    conn.commit();
                } catch (Exception e) {
                    conn.rollback();
                    throw e;
                } finally {
                    conn.setAutoCommit(true);
                }
            } else if (values.size() > existingCount) {
                // Incremental append
                List<? extends State> newItems = values.subList(existingCount, values.size());
                insertItems(conn, sessionId, key, newItems, existingCount);
                saveHash(conn, sessionId, hashKey, currentHash);
            }
            // else: no change, skip

        } catch (Exception e) {
            throw new RuntimeException("Failed to save list: " + key, e);
        }
    }

    /**
     * Get stored hash value for a list.
     *
     * @param conn database connection
     * @param sessionId session identifier
     * @param hashKey the hash key (e.g., "memory_messages:_hash")
     * @return the stored hash, or null if not found
     */
    private String getStoredHash(Connection conn, String sessionId, String hashKey)
            throws SQLException {
        String selectSql =
                "SELECT state_data FROM "
                        + getFullTableName()
                        + " WHERE session_id = ? AND state_key = ? AND item_index = ?";

        try (PreparedStatement stmt = conn.prepareStatement(selectSql)) {
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

    /**
     * Save hash value for a list.
     *
     * @param conn database connection
     * @param sessionId session identifier
     * @param hashKey the hash key
     * @param hash the hash value to save
     */
    private void saveHash(Connection conn, String sessionId, String hashKey, String hash)
            throws SQLException {
        String upsertSql =
                "INSERT INTO "
                        + getFullTableName()
                        + " (session_id, state_key, item_index, state_data)"
                        + " VALUES (?, ?, ?, ?)"
                        + " ON DUPLICATE KEY UPDATE state_data = VALUES(state_data)";

        try (PreparedStatement stmt = conn.prepareStatement(upsertSql)) {
            stmt.setString(1, sessionId);
            stmt.setString(2, hashKey);
            stmt.setInt(3, SINGLE_STATE_INDEX);
            stmt.setString(4, hash);
            stmt.executeUpdate();
        }
    }

    /**
     * Delete all items for a list state.
     *
     * @param conn database connection
     * @param sessionId session identifier
     * @param key the state key
     */
    private void deleteListItems(Connection conn, String sessionId, String key)
            throws SQLException {
        String deleteSql =
                "DELETE FROM " + getFullTableName() + " WHERE session_id = ? AND state_key = ?";

        try (PreparedStatement stmt = conn.prepareStatement(deleteSql)) {
            stmt.setString(1, sessionId);
            stmt.setString(2, key);
            stmt.executeUpdate();
        }
    }

    /**
     * Insert all items for a list state.
     *
     * @param conn database connection
     * @param sessionId session identifier
     * @param key the state key
     * @param values the values to insert
     */
    private void insertAllItems(
            Connection conn, String sessionId, String key, List<? extends State> values)
            throws Exception {
        insertItems(conn, sessionId, key, values, 0);
    }

    /**
     * Insert items for a list state starting at a given index.
     *
     * @param conn database connection
     * @param sessionId session identifier
     * @param key the state key
     * @param items the items to insert
     * @param startIndex the starting index for item_index
     */
    private void insertItems(
            Connection conn,
            String sessionId,
            String key,
            List<? extends State> items,
            int startIndex)
            throws Exception {
        String insertSql =
                "INSERT INTO "
                        + getFullTableName()
                        + " (session_id, state_key, item_index, state_data)"
                        + " VALUES (?, ?, ?, ?)";

        try (PreparedStatement stmt = conn.prepareStatement(insertSql)) {
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

    /**
     * Get the count of items in a list state (max index + 1).
     */
    private int getListCount(Connection conn, String sessionId, String key) throws SQLException {
        String selectSql =
                "SELECT MAX(item_index) as max_index FROM "
                        + getFullTableName()
                        + " WHERE session_id = ? AND state_key = ?";

        try (PreparedStatement stmt = conn.prepareStatement(selectSql)) {
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
    public <T extends State> Optional<T> get(SessionKey sessionKey, String key, Class<T> type) {
        String sessionId = sessionKey.toIdentifier();
        validateSessionId(sessionId);
        validateStateKey(key);

        String selectSql =
                "SELECT state_data FROM "
                        + getFullTableName()
                        + " WHERE session_id = ? AND state_key = ? AND item_index = ?";

        try (Connection conn = dataSource.getConnection();
                PreparedStatement stmt = conn.prepareStatement(selectSql)) {

            stmt.setString(1, sessionId);
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
    public <T extends State> List<T> getList(SessionKey sessionKey, String key, Class<T> itemType) {
        String sessionId = sessionKey.toIdentifier();
        validateSessionId(sessionId);
        validateStateKey(key);

        String selectSql =
                "SELECT state_data FROM "
                        + getFullTableName()
                        + " WHERE session_id = ? AND state_key = ?"
                        + " ORDER BY item_index";

        try (Connection conn = dataSource.getConnection();
                PreparedStatement stmt = conn.prepareStatement(selectSql)) {

            stmt.setString(1, sessionId);
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
    public boolean exists(SessionKey sessionKey) {
        String sessionId = sessionKey.toIdentifier();
        validateSessionId(sessionId);

        String existsSql = "SELECT 1 FROM " + getFullTableName() + " WHERE session_id = ? LIMIT 1";

        try (Connection conn = dataSource.getConnection();
                PreparedStatement stmt = conn.prepareStatement(existsSql)) {

            stmt.setString(1, sessionId);
            try (ResultSet rs = stmt.executeQuery()) {
                return rs.next();
            }

        } catch (SQLException e) {
            throw new RuntimeException("Failed to check session existence: " + sessionId, e);
        }
    }

    @Override
    public void delete(SessionKey sessionKey) {
        String sessionId = sessionKey.toIdentifier();
        validateSessionId(sessionId);

        String deleteSql = "DELETE FROM " + getFullTableName() + " WHERE session_id = ?";

        try (Connection conn = dataSource.getConnection();
                PreparedStatement stmt = conn.prepareStatement(deleteSql)) {

            stmt.setString(1, sessionId);
            stmt.executeUpdate();

        } catch (SQLException e) {
            throw new RuntimeException("Failed to delete session: " + sessionId, e);
        }
    }

    @Override
    public Set<SessionKey> listSessionKeys() {
        String listSql =
                "SELECT DISTINCT session_id FROM " + getFullTableName() + " ORDER BY session_id";

        try (Connection conn = dataSource.getConnection();
                PreparedStatement stmt = conn.prepareStatement(listSql);
                ResultSet rs = stmt.executeQuery()) {

            Set<SessionKey> sessionKeys = new HashSet<>();
            while (rs.next()) {
                sessionKeys.add(SimpleSessionKey.of(rs.getString("session_id")));
            }
            return sessionKeys;

        } catch (SQLException e) {
            throw new RuntimeException("Failed to list sessions", e);
        }
    }

    /**
     * Close the session and release any resources.
     *
     * <p>Note: This implementation does not close the DataSource as it may be shared across
     * multiple sessions. The caller is responsible for managing the DataSource lifecycle.
     */
    @Override
    public void close() {
        // DataSource is managed externally, so we don't close it here
    }

    /**
     * Get the database name used for storing sessions.
     *
     * @return The database name
     */
    public String getDatabaseName() {
        return databaseName;
    }

    /**
     * Get the table name used for storing sessions.
     *
     * @return The table name
     */
    public String getTableName() {
        return tableName;
    }

    /**
     * Get the DataSource used for database connections.
     *
     * @return The DataSource instance
     */
    public DataSource getDataSource() {
        return dataSource;
    }

    /**
     * Clear all sessions from the database (for testing or cleanup).
     *
     * @return Number of rows deleted
     */
    public int clearAllSessions() {
        String clearSql = "DELETE FROM " + getFullTableName();

        try (Connection conn = dataSource.getConnection();
                PreparedStatement stmt = conn.prepareStatement(clearSql)) {

            return stmt.executeUpdate();

        } catch (SQLException e) {
            throw new RuntimeException("Failed to clear sessions", e);
        }
    }

    /**
     * Validate a session ID format.
     *
     * @param sessionId Session ID to validate
     * @throws IllegalArgumentException if session ID is invalid
     */
    protected void validateSessionId(String sessionId) {
        if (sessionId == null || sessionId.trim().isEmpty()) {
            throw new IllegalArgumentException("Session ID cannot be null or empty");
        }
        if (sessionId.contains("/") || sessionId.contains("\\")) {
            throw new IllegalArgumentException("Session ID cannot contain path separators");
        }
        if (sessionId.length() > 255) {
            throw new IllegalArgumentException("Session ID cannot exceed 255 characters");
        }
    }

    /**
     * Validate a state key format.
     *
     * @param key State key to validate
     * @throws IllegalArgumentException if state key is invalid
     */
    private void validateStateKey(String key) {
        if (key == null || key.trim().isEmpty()) {
            throw new IllegalArgumentException("State key cannot be null or empty");
        }
        if (key.length() > 255) {
            throw new IllegalArgumentException("State key cannot exceed 255 characters");
        }
    }

    /**
     * Validate a database or table identifier to prevent SQL injection.
     *
     * <p>This method ensures that identifiers only contain safe characters (alphanumeric and
     * underscores) and start with a letter or underscore. This is critical for security since
     * database and table names cannot be parameterized in prepared statements.
     *
     * @param identifier The identifier to validate (database name or table name)
     * @param identifierType Description of the identifier type for error messages
     * @throws IllegalArgumentException if the identifier is invalid or contains unsafe characters
     */
    private void validateIdentifier(String identifier, String identifierType) {
        if (identifier == null || identifier.isEmpty()) {
            throw new IllegalArgumentException(identifierType + " cannot be null or empty");
        }
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
}
