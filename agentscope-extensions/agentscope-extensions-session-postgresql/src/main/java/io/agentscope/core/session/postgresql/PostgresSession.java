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
package io.agentscope.core.session.postgresql;

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
 * PostgreSQL database-based session implementation.
 *
 * <p>This implementation stores session state in PostgreSQL tables with the following
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
 * CREATE TABLE IF NOT EXISTS "public"."agentscope_sessions" (
 *     session_id VARCHAR(255) NOT NULL,
 *     state_key VARCHAR(255) NOT NULL,
 *     item_index INTEGER NOT NULL DEFAULT 0,
 *     state_data TEXT NOT NULL,
 *     created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
 *     updated_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
 *     PRIMARY KEY (session_id, state_key, item_index)
 * );
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
public class PostgresSession implements Session {

    private static final String DEFAULT_SCHEMA_NAME = "public";
    private static final String DEFAULT_TABLE_NAME = "agentscope_sessions";

    /** Suffix for hash storage keys. */
    private static final String HASH_KEY_SUFFIX = ":_hash";

    /** item_index value for single state values. */
    private static final int SINGLE_STATE_INDEX = 0;

    /**
     * Pattern for validating schema and table names. Only allows alphanumeric characters,
     * underscores, and hyphens, must start with letter or underscore. This prevents SQL injection
     * attacks through malicious schema/table names.
     *
     * <p>Note: Identifiers containing hyphens require double-quote escaping in SQL queries.
     */
    private static final Pattern IDENTIFIER_PATTERN = Pattern.compile("^[a-zA-Z_][a-zA-Z0-9_-]*$");

    private static final int MAX_IDENTIFIER_LENGTH = 63; // PostgreSQL identifier length limit

    private final DataSource dataSource;
    private final String schemaName;
    private final String tableName;

    @FunctionalInterface
    private interface SqlOperation {
        void execute() throws Exception;
    }

    /**
     * Create a PostgresSession with default settings.
     *
     * <p>This constructor uses default schema name ({@code public}) and table name ({@code
     * agentscope_sessions}), and does NOT auto-create the schema or table. If the schema or
     * table does not exist, an {@link IllegalStateException} will be thrown.
     *
     * @param dataSource DataSource for database connections
     * @throws IllegalArgumentException if dataSource is null
     * @throws IllegalStateException if schema or table does not exist
     */
    public PostgresSession(DataSource dataSource) {
        this(dataSource, DEFAULT_SCHEMA_NAME, DEFAULT_TABLE_NAME, false);
    }

    /**
     * Create a PostgresSession with optional auto-creation of schema and table.
     *
     * <p>This constructor uses default schema name ({@code public}) and table name ({@code
     * agentscope_sessions}). If {@code createIfNotExist} is true, the schema and table will be
     * created automatically if they don't exist. If false and the schema or table doesn't exist,
     * an {@link IllegalStateException} will be thrown.
     *
     * @param dataSource DataSource for database connections
     * @param createIfNotExist If true, auto-create schema and table; if false, require existing
     * @throws IllegalArgumentException if dataSource is null
     * @throws IllegalStateException if createIfNotExist is false and schema/table does not exist
     */
    public PostgresSession(DataSource dataSource, boolean createIfNotExist) {
        this(dataSource, DEFAULT_SCHEMA_NAME, DEFAULT_TABLE_NAME, createIfNotExist);
    }

    /**
     * Create a PostgresSession with custom schema name, table name, and optional auto-creation.
     *
     * <p>If {@code createIfNotExist} is true, the schema and table will be created automatically
     * if they don't exist. If false and the schema or table doesn't exist, an {@link
     * IllegalStateException} will be thrown.
     *
     * @param dataSource DataSource for database connections
     * @param schemaName Custom schema name (uses default if null or empty)
     * @param tableName Custom table name (uses default if null or empty)
     * @param createIfNotExist If true, auto-create schema and table; if false, require existing
     * @throws IllegalArgumentException if dataSource is null
     * @throws IllegalStateException if createIfNotExist is false and schema/table does not exist
     */
    public PostgresSession(
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

        // Validate schema and table names to prevent SQL injection
        validateIdentifier(this.schemaName, "Schema name");
        validateIdentifier(this.tableName, "Table name");

        if (createIfNotExist) {
            // Create schema and table if they don't exist
            createSchemaIfNotExist();
            createTableIfNotExist();
        } else {
            // Verify schema and table exist
            verifySchemaExists();
            verifyTableExists();
        }
    }

    /**
     * Create the schema if it doesn't exist.
     *
     * <p>PostgreSQL databases are selected by the JDBC URL, so this implementation creates a
     * schema inside that database instead of trying to create a database.
     */
    private void createSchemaIfNotExist() {
        String createSchemaSql = "CREATE SCHEMA IF NOT EXISTS " + quoteIdentifier(schemaName);

        try (Connection conn = dataSource.getConnection()) {
            executeInWriteTransaction(
                    conn,
                    () -> {
                        try (PreparedStatement stmt = conn.prepareStatement(createSchemaSql)) {
                            stmt.execute();
                        }
                    });
        } catch (Exception e) {
            throw new RuntimeException("Failed to create schema: " + schemaName, e);
        }
    }

    /**
     * Create the sessions table if it doesn't exist.
     *
     * <p>Uses double-quote escaping for the table name to safely handle identifiers with special
     * characters like hyphens.
     */
    private void createTableIfNotExist() {
        String createTableSql =
                "CREATE TABLE IF NOT EXISTS "
                        + getFullTableName()
                        + " (session_id VARCHAR(255) NOT NULL, state_key VARCHAR(255) NOT NULL,"
                        + " item_index INTEGER NOT NULL DEFAULT 0, state_data TEXT NOT NULL,"
                        + " created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP, updated_at TIMESTAMP"
                        + " DEFAULT CURRENT_TIMESTAMP, PRIMARY KEY"
                        + " (session_id, state_key, item_index))";

        try (Connection conn = dataSource.getConnection()) {
            executeInWriteTransaction(
                    conn,
                    () -> {
                        try (PreparedStatement stmt = conn.prepareStatement(createTableSql)) {
                            stmt.execute();
                        }
                    });
        } catch (Exception e) {
            throw new RuntimeException("Failed to create session table: " + tableName, e);
        }
    }

    /**
     * Verify that the schema exists.
     *
     * @throws IllegalStateException if schema does not exist
     */
    private void verifySchemaExists() {
        String checkSql =
                "SELECT SCHEMA_NAME FROM INFORMATION_SCHEMA.SCHEMATA WHERE SCHEMA_NAME = ?";

        try (Connection conn = dataSource.getConnection();
                PreparedStatement stmt = conn.prepareStatement(checkSql)) {
            stmt.setString(1, schemaName);
            try (ResultSet rs = stmt.executeQuery()) {
                if (!rs.next()) {
                    throw new IllegalStateException(
                            "Schema does not exist: "
                                    + schemaName
                                    + ". Use PostgresSession(dataSource, true) to auto-create.");
                }
            }
        } catch (SQLException e) {
            throw new RuntimeException("Failed to check schema existence: " + schemaName, e);
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
            stmt.setString(1, schemaName);
            stmt.setString(2, tableName);
            try (ResultSet rs = stmt.executeQuery()) {
                if (!rs.next()) {
                    throw new IllegalStateException(
                            "Table does not exist: "
                                    + schemaName
                                    + "."
                                    + tableName
                                    + ". Use PostgresSession(dataSource, true) to auto-create.");
                }
            }
        } catch (SQLException e) {
            throw new RuntimeException("Failed to check table existence: " + tableName, e);
        }
    }

    /**
     * Get the full table name with schema prefix, properly escaped with double quotes.
     *
     * <p>Uses double quotes to escape identifiers that may contain special characters like hyphens.
     *
     * @return The full table name with double-quote escaping ("schema"."table")
     */
    private String getFullTableName() {
        return quoteIdentifier(schemaName) + "." + quoteIdentifier(tableName);
    }

    private String quoteIdentifier(String identifier) {
        return "\"" + identifier + "\"";
    }

    private String getUpsertSql(Connection conn) {
        if (isH2(conn)) {
            return "MERGE INTO "
                    + getFullTableName()
                    + " (session_id, state_key, item_index, state_data, updated_at)"
                    + " KEY(session_id, state_key, item_index)"
                    + " VALUES (?, ?, ?, ?, CURRENT_TIMESTAMP)";
        }
        return "INSERT INTO "
                + getFullTableName()
                + " (session_id, state_key, item_index, state_data)"
                + " VALUES (?, ?, ?, ?)"
                + " ON CONFLICT (session_id, state_key, item_index) DO UPDATE SET"
                + " state_data = EXCLUDED.state_data,"
                + " updated_at = CURRENT_TIMESTAMP";
    }

    private boolean isH2(Connection conn) {
        try {
            var metadata = conn.getMetaData();
            if (metadata == null) {
                return false;
            }
            String productName = metadata.getDatabaseProductName();
            return "H2".equalsIgnoreCase(productName);
        } catch (SQLException | RuntimeException e) {
            return false;
        }
    }

    /**
     * Execute a write operation in an explicit transaction.
     *
     * <p>PostgresSession obtains and owns a fresh JDBC connection for each write method call. This
     * helper makes write semantics consistent even when the underlying DataSource defaults to
     * {@code autoCommit=false}, and restores the connection's original auto-commit mode before
     * returning it to the pool.
     */
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
    public void save(SessionKey sessionKey, String key, State value) {
        String sessionId = sessionKey.toIdentifier();
        validateSessionId(sessionId);
        validateStateKey(key);

        try (Connection conn = dataSource.getConnection()) {
            executeInWriteTransaction(
                    conn,
                    () -> {
                        String upsertSql = getUpsertSql(conn);
                        try (PreparedStatement stmt = conn.prepareStatement(upsertSql)) {
                            String json = JsonUtils.getJsonCodec().toJson(value);

                            stmt.setString(1, sessionId);
                            stmt.setString(2, key);
                            stmt.setInt(3, SINGLE_STATE_INDEX);
                            stmt.setString(4, json);

                            stmt.executeUpdate();
                        }
                    });
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
            executeInWriteTransaction(
                    conn,
                    () -> {
                        // Compute current hash
                        String currentHash = ListHashUtil.computeHash(values);

                        // Get stored hash
                        String storedHash = getStoredHash(conn, sessionId, hashKey);

                        // Get existing count
                        int existingCount = getListCount(conn, sessionId, key);

                        // Determine if full rewrite is needed
                        boolean needsFullRewrite =
                                ListHashUtil.needsFullRewrite(values, storedHash, existingCount);

                        if (needsFullRewrite) {
                            deleteListItems(conn, sessionId, key);
                            insertAllItems(conn, sessionId, key, values);
                            saveHash(conn, sessionId, hashKey, currentHash);
                        } else if (values.size() > existingCount) {
                            List<? extends State> newItems =
                                    values.subList(existingCount, values.size());
                            insertItems(conn, sessionId, key, newItems, existingCount);
                            saveHash(conn, sessionId, hashKey, currentHash);
                        }
                        // else: no change, skip
                    });
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

        PreparedStatement stmt = conn.prepareStatement(selectSql);
        try {
            stmt.setString(1, sessionId);
            stmt.setString(2, hashKey);
            stmt.setInt(3, SINGLE_STATE_INDEX);

            ResultSet rs = stmt.executeQuery();
            try {
                if (rs.next()) {
                    return rs.getString("state_data");
                }
                return null;
            } finally {
                rs.close();
            }
        } finally {
            stmt.close();
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
        String upsertSql = getUpsertSql(conn);

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

        PreparedStatement stmt = conn.prepareStatement(selectSql);
        try {
            stmt.setString(1, sessionId);
            stmt.setString(2, key);

            ResultSet rs = stmt.executeQuery();
            try {
                if (rs.next()) {
                    int maxIndex = rs.getInt("max_index");
                    if (rs.wasNull()) {
                        return 0;
                    }
                    return maxIndex + 1;
                }
                return 0;
            } finally {
                rs.close();
            }
        } finally {
            stmt.close();
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

        try {
            Connection conn = dataSource.getConnection();
            try {
                PreparedStatement stmt = conn.prepareStatement(selectSql);
                try {

                    stmt.setString(1, sessionId);
                    stmt.setString(2, key);
                    stmt.setInt(3, SINGLE_STATE_INDEX);

                    ResultSet rs = stmt.executeQuery();
                    try {
                        if (rs.next()) {
                            String json = rs.getString("state_data");
                            return Optional.of(JsonUtils.getJsonCodec().fromJson(json, type));
                        }
                        return Optional.empty();
                    } finally {
                        rs.close();
                    }
                } finally {
                    stmt.close();
                }
            } finally {
                conn.close();
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

        try (Connection conn = dataSource.getConnection()) {
            executeInWriteTransaction(
                    conn,
                    () -> {
                        try (PreparedStatement stmt = conn.prepareStatement(deleteSql)) {
                            stmt.setString(1, sessionId);
                            stmt.executeUpdate();
                        }
                    });
        } catch (Exception e) {
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
     * Get the schema name used for storing sessions.
     *
     * @return The schema name
     */
    public String getSchemaName() {
        return schemaName;
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
     * @deprecated Use {@link #truncateAllSessions()} instead
     */
    @Deprecated
    public int clearAllSessions() {
        String clearSql = "DELETE FROM " + getFullTableName();

        try (Connection conn = dataSource.getConnection()) {
            int[] deletedRows = new int[1];
            executeInWriteTransaction(
                    conn,
                    () -> {
                        try (PreparedStatement stmt = conn.prepareStatement(clearSql)) {
                            deletedRows[0] = stmt.executeUpdate();
                        }
                    });
            return deletedRows[0];
        } catch (Exception e) {
            throw new RuntimeException("Failed to clear sessions", e);
        }
    }

    /**
     * Truncate session table from the database (for testing or cleanup).
     *
     * <p>This method clears all session records by executing a TRUNCATE TABLE statement on the
     * sessions table. TRUNCATE is faster than DELETE as it resets the table without logging
     * individual row deletions and reclaims storage space immediately.
     *
     * <p><strong>Note:</strong> In PostgreSQL, {@code TRUNCATE TABLE} is transactional. This method
     * routes the statement through {@link #executeInWriteTransaction(Connection, SqlOperation)} so
     * it persists even when the DataSource returns connections with {@code autoCommit=false}.
     *
     * <p><strong>Note:</strong> The TRUNCATE operation requires TRUNCATE privileges in PostgreSQL.
     *
     * @return typically 0 if successful
     */
    public int truncateAllSessions() {
        String clearSql = "TRUNCATE TABLE " + getFullTableName();

        try (Connection conn = dataSource.getConnection()) {
            int[] deletedRows = new int[1];
            executeInWriteTransaction(
                    conn,
                    () -> {
                        try (PreparedStatement stmt = conn.prepareStatement(clearSql)) {
                            deletedRows[0] = stmt.executeUpdate();
                        }
                    });
            return deletedRows[0];
        } catch (Exception e) {
            throw new RuntimeException("Failed to truncate sessions", e);
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
     * Validate a schema or table identifier to prevent SQL injection.
     *
     * <p>This method ensures that identifiers only contain safe characters (alphanumeric,
     * underscores, and hyphens) and start with a letter or underscore. This is critical for
     * security since schema and table names cannot be parameterized in prepared statements.
     *
     * @param identifier The identifier to validate (schema name or table name)
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
                            + " contains invalid characters. Only alphanumeric characters,"
                            + " underscores, and hyphens are allowed, and it must start with a"
                            + " letter or underscore. Invalid value: "
                            + identifier);
        }
    }
}
