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
package io.agentscope.core.session.dameng;

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
 * DaMeng database-based session implementation.
 *
 * <p>This implementation stores session state in DaMeng database tables with the following
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
 * CREATE TABLE agentscope_sessions (
 *     session_id VARCHAR(255) NOT NULL,
 *     state_key VARCHAR(255) NOT NULL,
 *     item_index INT NOT NULL DEFAULT 0,
 *     state_data CLOB NOT NULL,
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
public class DaMengSession implements Session {

    private static final String DEFAULT_SCHEMA_NAME = "AGENTSCOPE";
    private static final String DEFAULT_TABLE_NAME = "AGENTSCOPE_SESSIONS";

    /** Suffix for hash storage keys. */
    private static final String HASH_KEY_SUFFIX = ":_hash";

    /** item_index value for single state values. */
    private static final int SINGLE_STATE_INDEX = 0;

    /**
     * Pattern for validating schema and table names. Only allows alphanumeric characters and
     * underscores, must start with letter or underscore. This prevents SQL injection attacks
     * through malicious schema/table names.
     */
    private static final Pattern IDENTIFIER_PATTERN = Pattern.compile("^[a-zA-Z_][a-zA-Z0-9_]*$");

    private static final int MAX_IDENTIFIER_LENGTH = 128; // DaMeng identifier length limit

    private final DataSource dataSource;
    private final String schemaName;
    private final String tableName;

    /**
     * Create a DaMengSession with default settings.
     *
     * <p>This constructor uses default schema name ({@code AGENTSCOPE}) and table name ({@code
     * AGENTSCOPE_SESSIONS}), and does NOT auto-create the schema or table. If the schema or table
     * does not exist, an {@link IllegalStateException} will be thrown.
     *
     * @param dataSource DataSource for database connections
     * @throws IllegalArgumentException if dataSource is null
     * @throws IllegalStateException if schema or table does not exist
     */
    public DaMengSession(DataSource dataSource) {
        this(dataSource, DEFAULT_SCHEMA_NAME, DEFAULT_TABLE_NAME, false);
    }

    /**
     * Create a DaMengSession with optional auto-creation of schema and table.
     *
     * <p>This constructor uses default schema name ({@code AGENTSCOPE}) and table name ({@code
     * AGENTSCOPE_SESSIONS}). If {@code createIfNotExist} is true, the schema and table will be
     * created automatically if they don't exist. If false and the schema or table doesn't exist,
     * an {@link IllegalStateException} will be thrown.
     *
     * @param dataSource DataSource for database connections
     * @param createIfNotExist If true, auto-create schema and table; if false, require existing
     * @throws IllegalArgumentException if dataSource is null
     * @throws IllegalStateException if createIfNotExist is false and schema/table does not exist
     */
    public DaMengSession(DataSource dataSource, boolean createIfNotExist) {
        this(dataSource, DEFAULT_SCHEMA_NAME, DEFAULT_TABLE_NAME, createIfNotExist);
    }

    /**
     * Create a DaMengSession with custom schema name, table name, and optional auto-creation.
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
    public DaMengSession(
            DataSource dataSource, String schemaName, String tableName, boolean createIfNotExist) {
        if (dataSource == null) {
            throw new IllegalArgumentException("DataSource cannot be null");
        }

        this.dataSource = dataSource;
        this.schemaName =
                (schemaName == null || schemaName.trim().isEmpty())
                        ? DEFAULT_SCHEMA_NAME
                        : schemaName.trim().toUpperCase();
        this.tableName =
                (tableName == null || tableName.trim().isEmpty())
                        ? DEFAULT_TABLE_NAME
                        : tableName.trim().toUpperCase();

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

    /** Create the schema if it doesn't exist. */
    private void createSchemaIfNotExist() {
        // DaMeng doesn't support IF NOT EXISTS for CREATE SCHEMA
        // Check if schema exists first
        String checkSql = "SELECT 1 FROM DBA_USERS WHERE USERNAME = ?";
        String createSchemaSql = "CREATE SCHEMA " + schemaName;

        try (Connection conn = dataSource.getConnection()) {
            // Check if schema exists
            boolean schemaExists = false;
            try (PreparedStatement checkStmt = conn.prepareStatement(checkSql)) {
                checkStmt.setString(1, schemaName);
                try (ResultSet rs = checkStmt.executeQuery()) {
                    schemaExists = rs.next();
                }
            }

            // Create schema if it doesn't exist
            if (!schemaExists) {
                try (PreparedStatement createStmt = conn.prepareStatement(createSchemaSql)) {
                    createStmt.execute();
                }
            }
        } catch (SQLException e) {
            throw new RuntimeException("Failed to create schema: " + schemaName, e);
        }
    }

    /** Create the sessions table if it doesn't exist. */
    private void createTableIfNotExist() {
        // DaMeng doesn't support IF NOT EXISTS for CREATE TABLE
        // Check if table exists first
        String checkSql = "SELECT 1 FROM DBA_TABLES WHERE OWNER = ? AND TABLE_NAME = ?";
        String createTableSql =
                "CREATE TABLE "
                        + getFullTableName()
                        + " ("
                        + "session_id VARCHAR(255) NOT NULL, "
                        + "state_key VARCHAR(255) NOT NULL, "
                        + "item_index INT NOT NULL DEFAULT 0, "
                        + "state_data CLOB NOT NULL, "
                        + "created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP, "
                        + "updated_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP, "
                        + "PRIMARY KEY (session_id, state_key, item_index)"
                        + ")";

        try (Connection conn = dataSource.getConnection()) {
            // Check if table exists
            boolean tableExists = false;
            try (PreparedStatement checkStmt = conn.prepareStatement(checkSql)) {
                checkStmt.setString(1, schemaName);
                checkStmt.setString(2, tableName);
                try (ResultSet rs = checkStmt.executeQuery()) {
                    tableExists = rs.next();
                }
            }

            // Create table if it doesn't exist
            if (!tableExists) {
                try (PreparedStatement createStmt = conn.prepareStatement(createTableSql)) {
                    createStmt.execute();
                }
            }
        } catch (SQLException e) {
            throw new RuntimeException("Failed to create session table: " + tableName, e);
        }
    }

    /**
     * Verify that the schema exists.
     *
     * @throws IllegalStateException if schema does not exist
     */
    private void verifySchemaExists() {
        String checkSql = "SELECT 1 FROM DBA_USERS WHERE USERNAME = ?";

        try (Connection conn = dataSource.getConnection();
                PreparedStatement stmt = conn.prepareStatement(checkSql)) {
            stmt.setString(1, schemaName);
            try (ResultSet rs = stmt.executeQuery()) {
                if (!rs.next()) {
                    throw new IllegalStateException(
                            "Schema does not exist: "
                                    + schemaName
                                    + ". Use DaMengSession(dataSource, true) to auto-create.");
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
        String checkSql = "SELECT 1 FROM DBA_TABLES WHERE OWNER = ? AND TABLE_NAME = ?";

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
                                    + ". Use DaMengSession(dataSource, true) to auto-create.");
                }
            }
        } catch (SQLException e) {
            throw new RuntimeException("Failed to check table existence: " + tableName, e);
        }
    }

    /**
     * Get the full table name with schema prefix.
     *
     * @return The full table name (SCHEMA.TABLE)
     */
    private String getFullTableName() {
        return schemaName + "." + tableName;
    }

    @Override
    public void save(SessionKey sessionKey, String key, State value) {
        String sessionId = sessionKey.toIdentifier();
        validateSessionId(sessionId);
        validateStateKey(key);

        // DaMeng uses MERGE INTO for upsert
        String mergeSql =
                "MERGE INTO "
                        + getFullTableName()
                        + " T1 "
                        + "USING (SELECT ? AS session_id, ? AS state_key, ? AS item_index, ? AS"
                        + " state_data FROM DUAL) T2 "
                        + "ON (T1.session_id = T2.session_id AND T1.state_key = T2.state_key AND"
                        + " T1.item_index = T2.item_index) "
                        + "WHEN MATCHED THEN UPDATE SET T1.state_data = T2.state_data,"
                        + " T1.updated_at = CURRENT_TIMESTAMP "
                        + "WHEN NOT MATCHED THEN INSERT (session_id, state_key, item_index,"
                        + " state_data) VALUES (T2.session_id, T2.state_key, T2.item_index,"
                        + " T2.state_data)";

        try (Connection conn = dataSource.getConnection();
                PreparedStatement stmt = conn.prepareStatement(mergeSql)) {

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

    private void saveHash(Connection conn, String sessionId, String hashKey, String hash)
            throws SQLException {
        String mergeSql =
                "MERGE INTO "
                        + getFullTableName()
                        + " T1 "
                        + "USING (SELECT ? AS session_id, ? AS state_key, ? AS item_index, ? AS"
                        + " state_data FROM DUAL) T2 "
                        + "ON (T1.session_id = T2.session_id AND T1.state_key = T2.state_key AND"
                        + " T1.item_index = T2.item_index) "
                        + "WHEN MATCHED THEN UPDATE SET T1.state_data = T2.state_data,"
                        + " T1.updated_at = CURRENT_TIMESTAMP "
                        + "WHEN NOT MATCHED THEN INSERT (session_id, state_key, item_index,"
                        + " state_data) VALUES (T2.session_id, T2.state_key, T2.item_index,"
                        + " T2.state_data)";

        try (PreparedStatement stmt = conn.prepareStatement(mergeSql)) {
            stmt.setString(1, sessionId);
            stmt.setString(2, hashKey);
            stmt.setInt(3, SINGLE_STATE_INDEX);
            stmt.setString(4, hash);
            stmt.executeUpdate();
        }
    }

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

        String existsSql =
                "SELECT 1 FROM " + getFullTableName() + " WHERE session_id = ? AND ROWNUM = 1";

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

    @Override
    public void close() {
        // DataSource is managed externally, so we don't close it here
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

    public int clearAllSessions() {
        String clearSql = "DELETE FROM " + getFullTableName();

        try (Connection conn = dataSource.getConnection();
                PreparedStatement stmt = conn.prepareStatement(clearSql)) {

            return stmt.executeUpdate();

        } catch (SQLException e) {
            throw new RuntimeException("Failed to clear sessions", e);
        }
    }

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

    private void validateStateKey(String key) {
        if (key == null || key.trim().isEmpty()) {
            throw new IllegalArgumentException("State key cannot be null or empty");
        }
        if (key.length() > 255) {
            throw new IllegalArgumentException("State key cannot exceed 255 characters");
        }
    }

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
