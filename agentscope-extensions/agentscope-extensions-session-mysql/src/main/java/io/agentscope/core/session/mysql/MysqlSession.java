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

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.agentscope.core.session.Session;
import io.agentscope.core.session.SessionInfo;
import io.agentscope.core.state.StateModule;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;
import javax.sql.DataSource;

/**
 * MySQL database-based session implementation.
 *
 * <p>This implementation stores session state in a MySQL database table.
 * Each session is stored as a single row with JSON-serialized state data.
 *
 * <p>Features:
 * <ul>
 *   <li>Multi-module session support</li>
 *   <li>Connection pooling through DataSource</li>
 *   <li>Optional automatic database and table creation</li>
 *   <li>Transactional operations</li>
 *   <li>UTF-8 encoding for state data</li>
 *   <li>Graceful handling of missing sessions</li>
 * </ul>
 *
 * <p>Database Schema (auto-created if createIfNotExist=true):
 * <pre>
 * CREATE DATABASE IF NOT EXISTS agentscope
 *     DEFAULT CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci;
 * </pre>
 *
 * <p>Table Schema (auto-created if createIfNotExist=true):
 * <pre>
 * CREATE TABLE IF NOT EXISTS agentscope_sessions (
 *     session_id VARCHAR(255) PRIMARY KEY,
 *     state_data JSON NOT NULL,
 *     created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
 *     updated_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP
 * );
 * </pre>
 *
 * <p>Usage example:
 * <pre>{@code
 * // Using HikariCP DataSource
 * HikariConfig config = new HikariConfig();
 * config.setJdbcUrl("jdbc:mysql://localhost:3306");
 * config.setUsername("root");
 * config.setPassword("password");
 * DataSource dataSource = new HikariDataSource(config);
 *
 * // Auto-create database and table
 * MysqlSession session = new MysqlSession(dataSource, true);
 *
 * // Or require existing database and table (throws IllegalStateException if not exist)
 * MysqlSession session = new MysqlSession(dataSource);
 *
 * // Use with SessionManager
 * SessionManager.forSessionId("user123")
 *     .withSession(new MysqlSession(dataSource, true))
 *     .addComponent(agent)
 *     .addComponent(memory)
 *     .saveSession();
 * }</pre>
 */
public class MysqlSession implements Session {

    private static final String DEFAULT_DATABASE_NAME = "agentscope";
    private static final String DEFAULT_TABLE_NAME = "agentscope_sessions";

    /**
     * Pattern for validating database and table names.
     * Only allows alphanumeric characters and underscores, must start with letter or underscore.
     * This prevents SQL injection attacks through malicious database/table names.
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
     * <p>This constructor uses default database name ({@code agentscope}) and table name
     * ({@code agentscope_sessions}), and does NOT auto-create the database or table.
     * If the database or table does not exist, an {@link IllegalStateException} will be thrown.
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
     * <p>This constructor uses default database name ({@code agentscope}) and table name
     * ({@code agentscope_sessions}). If {@code createIfNotExist} is true, the database
     * and table will be created automatically if they don't exist. If false and the
     * database or table doesn't exist, an {@link IllegalStateException} will be thrown.
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
     * <p>If {@code createIfNotExist} is true, the database and table will be created
     * automatically if they don't exist. If false and the database or table doesn't
     * exist, an {@link IllegalStateException} will be thrown.
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
     * <p>Creates the database with UTF-8 (utf8mb4) character set and unicode collation
     * for proper internationalization support.
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
                        + " (session_id VARCHAR(255) PRIMARY KEY, state_data JSON NOT NULL,"
                        + " created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP, updated_at TIMESTAMP"
                        + " DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP)"
                        + " DEFAULT CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci";

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

    /**
     * Save the state of multiple StateModules to the MySQL database.
     *
     * <p>This implementation persists the state of all provided StateModules as a single
     * JSON document in the database. The method uses INSERT ... ON DUPLICATE KEY UPDATE
     * for upsert semantics.
     *
     * @param sessionId Unique identifier for the session
     * @param stateModules Map of component names to StateModule instances
     * @throws RuntimeException if database operations fail
     */
    @Override
    public void saveSessionState(String sessionId, Map<String, StateModule> stateModules) {
        validateSessionId(sessionId);

        try {
            // Collect state from all modules
            Map<String, Object> sessionState = new HashMap<>();
            for (Map.Entry<String, StateModule> entry : stateModules.entrySet()) {
                sessionState.put(entry.getKey(), entry.getValue().stateDict());
            }

            // Serialize to JSON
            String stateJson = objectMapper.writeValueAsString(sessionState);

            // Upsert into database
            String upsertSql =
                    "INSERT INTO "
                            + getFullTableName()
                            + " (session_id, state_data) VALUES (?, ?) "
                            + "ON DUPLICATE KEY UPDATE state_data = VALUES(state_data), "
                            + "updated_at = CURRENT_TIMESTAMP";

            try (Connection conn = dataSource.getConnection();
                    PreparedStatement stmt = conn.prepareStatement(upsertSql)) {
                stmt.setString(1, sessionId);
                stmt.setString(2, stateJson);
                stmt.executeUpdate();
            }

        } catch (JsonProcessingException e) {
            throw new RuntimeException("Failed to serialize session state: " + sessionId, e);
        } catch (SQLException e) {
            throw new RuntimeException("Failed to save session to database: " + sessionId, e);
        }
    }

    /**
     * Load session state from the MySQL database into multiple StateModules.
     *
     * <p>This implementation restores the state of all provided StateModules from the
     * database. The method reads the JSON document, extracts component states, and
     * loads them into the corresponding StateModule instances using non-strict loading.
     *
     * @param sessionId Unique identifier for the session
     * @param allowNotExist Whether to allow loading from non-existent sessions
     * @param stateModules Map of component names to StateModule instances to load into
     * @throws RuntimeException if database operations fail or session doesn't exist when allowNotExist is false
     */
    @Override
    public void loadSessionState(
            String sessionId, boolean allowNotExist, Map<String, StateModule> stateModules) {
        validateSessionId(sessionId);

        String selectSql = "SELECT state_data FROM " + getFullTableName() + " WHERE session_id = ?";

        try (Connection conn = dataSource.getConnection();
                PreparedStatement stmt = conn.prepareStatement(selectSql)) {

            stmt.setString(1, sessionId);
            try (ResultSet rs = stmt.executeQuery()) {
                if (!rs.next()) {
                    if (allowNotExist) {
                        return; // Silently ignore missing session
                    } else {
                        throw new RuntimeException("Session not found: " + sessionId);
                    }
                }

                String stateJson = rs.getString("state_data");

                // Parse JSON and load state into each module
                @SuppressWarnings("unchecked")
                Map<String, Object> sessionState = objectMapper.readValue(stateJson, Map.class);

                for (Map.Entry<String, StateModule> entry : stateModules.entrySet()) {
                    String componentName = entry.getKey();
                    StateModule module = entry.getValue();

                    if (sessionState.containsKey(componentName)) {
                        Object componentState = sessionState.get(componentName);
                        if (componentState instanceof Map) {
                            @SuppressWarnings("unchecked")
                            Map<String, Object> componentStateMap =
                                    (Map<String, Object>) componentState;
                            module.loadStateDict(
                                    componentStateMap, false); // Use non-strict loading
                        }
                    }
                }
            }

        } catch (JsonProcessingException e) {
            throw new RuntimeException("Failed to deserialize session state: " + sessionId, e);
        } catch (SQLException e) {
            throw new RuntimeException("Failed to load session from database: " + sessionId, e);
        }
    }

    /**
     * Check if a session exists in the database.
     *
     * @param sessionId Unique identifier for the session
     * @return true if the session exists in the database
     */
    @Override
    public boolean sessionExists(String sessionId) {
        validateSessionId(sessionId);

        String existsSql = "SELECT 1 FROM " + getFullTableName() + " WHERE session_id = ?";

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

    /**
     * Delete a session from the database.
     *
     * @param sessionId Unique identifier for the session
     * @return true if the session was deleted, false if it didn't exist
     * @throws RuntimeException if database operations fail
     */
    @Override
    public boolean deleteSession(String sessionId) {
        validateSessionId(sessionId);

        String deleteSql = "DELETE FROM " + getFullTableName() + " WHERE session_id = ?";

        try (Connection conn = dataSource.getConnection();
                PreparedStatement stmt = conn.prepareStatement(deleteSql)) {

            stmt.setString(1, sessionId);
            int rowsAffected = stmt.executeUpdate();
            return rowsAffected > 0;

        } catch (SQLException e) {
            throw new RuntimeException("Failed to delete session: " + sessionId, e);
        }
    }

    /**
     * Get a list of all session IDs from the database.
     *
     * <p>This implementation queries all session IDs from the database table,
     * returning them sorted alphabetically.
     *
     * @return List of session IDs, or empty list if no sessions exist
     * @throws RuntimeException if database operations fail
     */
    @Override
    public List<String> listSessions() {
        String listSql = "SELECT session_id FROM " + getFullTableName() + " ORDER BY session_id";

        try (Connection conn = dataSource.getConnection();
                PreparedStatement stmt = conn.prepareStatement(listSql);
                ResultSet rs = stmt.executeQuery()) {

            List<String> sessionIds = new ArrayList<>();
            while (rs.next()) {
                sessionIds.add(rs.getString("session_id"));
            }
            return sessionIds;

        } catch (SQLException e) {
            throw new RuntimeException("Failed to list sessions", e);
        }
    }

    /**
     * Get information about a session from the database.
     *
     * <p>This implementation queries the session from the database to determine
     * the data size, last modification time, and the number of state components.
     *
     * @param sessionId Unique identifier for the session
     * @return Session information including size, last modified time, and component count
     * @throws RuntimeException if database operations fail or session doesn't exist
     */
    @Override
    public SessionInfo getSessionInfo(String sessionId) {
        validateSessionId(sessionId);

        String infoSql =
                "SELECT state_data, LENGTH(state_data) as data_size, updated_at FROM "
                        + getFullTableName()
                        + " WHERE session_id = ?";

        try (Connection conn = dataSource.getConnection();
                PreparedStatement stmt = conn.prepareStatement(infoSql)) {

            stmt.setString(1, sessionId);
            try (ResultSet rs = stmt.executeQuery()) {
                if (!rs.next()) {
                    throw new RuntimeException("Session not found: " + sessionId);
                }

                String stateJson = rs.getString("state_data");
                long size = rs.getLong("data_size");
                Timestamp updatedAt = rs.getTimestamp("updated_at");
                long lastModified = updatedAt != null ? updatedAt.getTime() : 0;

                // Count components by parsing the JSON
                @SuppressWarnings("unchecked")
                Map<String, Object> sessionState = objectMapper.readValue(stateJson, Map.class);
                int componentCount = sessionState.size();

                return new SessionInfo(sessionId, size, lastModified, componentCount);
            }

        } catch (JsonProcessingException e) {
            throw new RuntimeException("Failed to parse session state: " + sessionId, e);
        } catch (SQLException e) {
            throw new RuntimeException("Failed to get session info: " + sessionId, e);
        }
    }

    /**
     * Close the session and release any resources.
     *
     * <p>Note: This implementation does not close the DataSource as it may be
     * shared across multiple sessions. The caller is responsible for managing
     * the DataSource lifecycle.
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
     * @return Number of sessions deleted
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
     * Validate a database or table identifier to prevent SQL injection.
     *
     * <p>This method ensures that identifiers only contain safe characters
     * (alphanumeric and underscores) and start with a letter or underscore.
     * This is critical for security since database and table names cannot
     * be parameterized in prepared statements.
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
