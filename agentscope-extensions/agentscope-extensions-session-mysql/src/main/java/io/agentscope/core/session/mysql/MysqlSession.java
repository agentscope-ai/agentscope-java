/*
 * Copyright 2024-2025 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * You may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      https://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package io.agentscope.core.session.mysql;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JavaType;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.agentscope.core.session.Session;
import io.agentscope.core.state.SessionKey;
import io.agentscope.core.state.SimpleSessionKey;
import io.agentscope.core.state.State;
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
 *   <li>Single state: stored as JSON in state_data column
 *   <li>List state: stored as JSON array in state_data column with incremental updates
 * </ul>
 *
 * <p>Table Schema (auto-created if createIfNotExist=true):
 *
 * <pre>
 * CREATE TABLE IF NOT EXISTS agentscope_sessions (
 *     session_id VARCHAR(255) NOT NULL,
 *     state_key VARCHAR(255) NOT NULL,
 *     state_type VARCHAR(20) NOT NULL,
 *     state_data LONGTEXT NOT NULL,
 *     created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
 *     updated_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
 *     PRIMARY KEY (session_id, state_key)
 * ) DEFAULT CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci;
 * </pre>
 *
 * <p>Features:
 *
 * <ul>
 *   <li>Incremental list storage (only appends new items)
 *   <li>Type-safe state serialization using Jackson
 *   <li>Automatic table creation
 *   <li>SQL injection prevention through parameterized queries
 * </ul>
 */
public class MysqlSession implements Session {

    private static final String DEFAULT_DATABASE_NAME = "agentscope";
    private static final String DEFAULT_TABLE_NAME = "agentscope_sessions";
    private static final String STATE_TYPE_SINGLE = "single";
    private static final String STATE_TYPE_LIST = "list";

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
    private final ObjectMapper objectMapper;

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
        this.objectMapper = new ObjectMapper();

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
                        + " state_type VARCHAR(20) NOT NULL, state_data LONGTEXT NOT NULL,"
                        + " created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP, updated_at TIMESTAMP"
                        + " DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP, PRIMARY KEY"
                        + " (session_id, state_key)) DEFAULT CHARACTER SET utf8mb4 COLLATE"
                        + " utf8mb4_unicode_ci";

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
        String sessionId = extractSessionId(sessionKey);
        validateSessionId(sessionId);
        validateStateKey(key);

        String upsertSql =
                "INSERT INTO "
                        + getFullTableName()
                        + " (session_id, state_key, state_type, state_data)"
                        + " VALUES (?, ?, ?, ?)"
                        + " ON DUPLICATE KEY UPDATE state_data = VALUES(state_data),"
                        + " state_type = VALUES(state_type)";

        try (Connection conn = dataSource.getConnection();
                PreparedStatement stmt = conn.prepareStatement(upsertSql)) {

            String json = objectMapper.writeValueAsString(value);

            stmt.setString(1, sessionId);
            stmt.setString(2, key);
            stmt.setString(3, STATE_TYPE_SINGLE);
            stmt.setString(4, json);

            stmt.executeUpdate();

        } catch (Exception e) {
            throw new RuntimeException("Failed to save state: " + key, e);
        }
    }

    @Override
    public void save(SessionKey sessionKey, String key, List<? extends State> values) {
        String sessionId = extractSessionId(sessionKey);
        validateSessionId(sessionId);
        validateStateKey(key);

        try (Connection conn = dataSource.getConnection()) {
            // Get existing count
            int existingCount = getListCount(conn, sessionId, key);

            // Only process new items
            if (values.size() > existingCount) {
                List<? extends State> newItems = values.subList(existingCount, values.size());

                // Read existing data, append new items, and write back
                List<Object> allItems = new ArrayList<>();

                // Read existing items if any
                if (existingCount > 0) {
                    String selectSql =
                            "SELECT state_data FROM "
                                    + getFullTableName()
                                    + " WHERE session_id = ? AND state_key = ?";

                    try (PreparedStatement selectStmt = conn.prepareStatement(selectSql)) {
                        selectStmt.setString(1, sessionId);
                        selectStmt.setString(2, key);

                        try (ResultSet rs = selectStmt.executeQuery()) {
                            if (rs.next()) {
                                String existingJson = rs.getString("state_data");
                                List<Object> existing =
                                        objectMapper.readValue(
                                                existingJson, new TypeReference<List<Object>>() {});
                                allItems.addAll(existing);
                            }
                        }
                    }
                }

                // Add new items
                for (State item : newItems) {
                    // Convert State to generic Object for storage
                    Object itemAsMap = objectMapper.convertValue(item, Object.class);
                    allItems.add(itemAsMap);
                }

                // Write all items
                String upsertSql =
                        "INSERT INTO "
                                + getFullTableName()
                                + " (session_id, state_key, state_type, state_data)"
                                + " VALUES (?, ?, ?, ?)"
                                + " ON DUPLICATE KEY UPDATE state_data = VALUES(state_data)";

                try (PreparedStatement upsertStmt = conn.prepareStatement(upsertSql)) {
                    String json = objectMapper.writeValueAsString(allItems);

                    upsertStmt.setString(1, sessionId);
                    upsertStmt.setString(2, key);
                    upsertStmt.setString(3, STATE_TYPE_LIST);
                    upsertStmt.setString(4, json);

                    upsertStmt.executeUpdate();
                }
            }

        } catch (Exception e) {
            throw new RuntimeException("Failed to save list: " + key, e);
        }
    }

    /**
     * Get the count of items in a list state.
     */
    private int getListCount(Connection conn, String sessionId, String key) throws SQLException {
        String selectSql =
                "SELECT state_data, state_type FROM "
                        + getFullTableName()
                        + " WHERE session_id = ? AND state_key = ?";

        try (PreparedStatement stmt = conn.prepareStatement(selectSql)) {
            stmt.setString(1, sessionId);
            stmt.setString(2, key);

            try (ResultSet rs = stmt.executeQuery()) {
                if (rs.next()) {
                    String stateType = rs.getString("state_type");
                    if (STATE_TYPE_LIST.equals(stateType)) {
                        String json = rs.getString("state_data");
                        try {
                            List<?> items = objectMapper.readValue(json, List.class);
                            return items.size();
                        } catch (Exception e) {
                            return 0;
                        }
                    }
                }
                return 0;
            }
        }
    }

    @Override
    public <T extends State> Optional<T> get(SessionKey sessionKey, String key, Class<T> type) {
        String sessionId = extractSessionId(sessionKey);
        validateSessionId(sessionId);
        validateStateKey(key);

        String selectSql =
                "SELECT state_data, state_type FROM "
                        + getFullTableName()
                        + " WHERE session_id = ? AND state_key = ?";

        try (Connection conn = dataSource.getConnection();
                PreparedStatement stmt = conn.prepareStatement(selectSql)) {

            stmt.setString(1, sessionId);
            stmt.setString(2, key);

            try (ResultSet rs = stmt.executeQuery()) {
                if (rs.next()) {
                    String stateType = rs.getString("state_type");
                    if (!STATE_TYPE_SINGLE.equals(stateType)) {
                        return Optional.empty();
                    }

                    String json = rs.getString("state_data");
                    return Optional.of(objectMapper.readValue(json, type));
                }
                return Optional.empty();
            }

        } catch (Exception e) {
            throw new RuntimeException("Failed to get state: " + key, e);
        }
    }

    @Override
    public <T extends State> List<T> getList(SessionKey sessionKey, String key, Class<T> itemType) {
        String sessionId = extractSessionId(sessionKey);
        validateSessionId(sessionId);
        validateStateKey(key);

        String selectSql =
                "SELECT state_data, state_type FROM "
                        + getFullTableName()
                        + " WHERE session_id = ? AND state_key = ?";

        try (Connection conn = dataSource.getConnection();
                PreparedStatement stmt = conn.prepareStatement(selectSql)) {

            stmt.setString(1, sessionId);
            stmt.setString(2, key);

            try (ResultSet rs = stmt.executeQuery()) {
                if (rs.next()) {
                    String stateType = rs.getString("state_type");
                    if (!STATE_TYPE_LIST.equals(stateType)) {
                        return List.of();
                    }

                    String json = rs.getString("state_data");
                    JavaType listType =
                            objectMapper
                                    .getTypeFactory()
                                    .constructCollectionType(List.class, itemType);
                    return objectMapper.readValue(json, listType);
                }
                return List.of();
            }

        } catch (Exception e) {
            throw new RuntimeException("Failed to get list: " + key, e);
        }
    }

    @Override
    public boolean exists(SessionKey sessionKey) {
        String sessionId = extractSessionId(sessionKey);
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
        String sessionId = extractSessionId(sessionKey);
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

    private String extractSessionId(SessionKey sessionKey) {
        if (sessionKey instanceof SimpleSessionKey simple) {
            return simple.sessionId();
        }
        return sessionKey.toString();
    }
}
