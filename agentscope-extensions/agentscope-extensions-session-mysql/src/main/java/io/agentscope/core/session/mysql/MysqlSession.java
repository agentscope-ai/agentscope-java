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
import javax.sql.DataSource;

/**
 * MySQL database-based session implementation.
 *
 * This implementation stores session state in a MySQL database table.
 * Each session is stored as a single row with JSON-serialized state data.
 *
 * Features:
 * - Multi-module session support
 * - Connection pooling through DataSource
 * - Automatic database and table creation
 * - Transactional operations
 * - UTF-8 encoding for state data
 * - Graceful handling of missing sessions
 *
 * Database Schema (auto-created if not exists):
 * <pre>
 * CREATE DATABASE IF NOT EXISTS agentscope
 *     DEFAULT CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci;
 * </pre>
 *
 * Table Schema (auto-created if not exists):
 * <pre>
 * CREATE TABLE IF NOT EXISTS agentscope_sessions (
 *     session_id VARCHAR(255) PRIMARY KEY,
 *     state_data JSON NOT NULL,
 *     created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
 *     updated_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP
 * );
 * </pre>
 *
 * Usage example:
 * <pre>{@code
 * // Using HikariCP DataSource (connect without specifying database for auto-creation)
 * HikariConfig config = new HikariConfig();
 * config.setJdbcUrl("jdbc:mysql://localhost:3306");
 * config.setUsername("root");
 * config.setPassword("password");
 * DataSource dataSource = new HikariDataSource(config);
 *
 * // Auto-create database 'agentscope' and table
 * MysqlSession session = new MysqlSession(dataSource, "agentscope", "agentscope_sessions");
 *
 * // Or connect directly to existing database
 * config.setJdbcUrl("jdbc:mysql://localhost:3306/agentscope");
 * MysqlSession session = new MysqlSession(dataSource);
 *
 * // Use with SessionManager
 * SessionManager.forSessionId("user123")
 *     .withSession(new MysqlSession(dataSource))
 *     .addComponent(agent)
 *     .addComponent(memory)
 *     .saveSession();
 * }</pre>
 */
public class MysqlSession implements Session {

    private static final String DEFAULT_DATABASE_NAME = "agentscope";
    private static final String DEFAULT_TABLE_NAME = "agentscope_sessions";

    private final DataSource dataSource;
    private final String databaseName;
    private final String tableName;
    private final ObjectMapper objectMapper;

    /**
     * Create a MysqlSession with default database and table names.
     *
     * Note: This constructor does not auto-create the database. The DataSource
     * should be configured to connect to an existing database.
     *
     * @param dataSource DataSource for database connections
     */
    public MysqlSession(DataSource dataSource) {
        this(dataSource, DEFAULT_DATABASE_NAME, DEFAULT_TABLE_NAME);
    }

    /**
     * Create a MysqlSession with a custom table name.
     *
     * Note: This constructor does not auto-create the database. The DataSource
     * should be configured to connect to an existing database.
     *
     * @param dataSource DataSource for database connections
     * @param tableName Name of the table to store sessions
     */
    public MysqlSession(DataSource dataSource, String tableName) {
        this(dataSource, DEFAULT_DATABASE_NAME, tableName);
    }

    /**
     * Create a MysqlSession with custom database and table names.
     *
     * When databaseName is provided, this constructor will:
     * 1. Create the database if it doesn't exist (with utf8mb4 charset)
     * 2. Switch to use the database
     * 3. Create the sessions table if it doesn't exist
     *
     * @param dataSource DataSource for database connections
     * @param databaseName Name of the database (null to skip database creation)
     * @param tableName Name of the table to store sessions
     */
    public MysqlSession(DataSource dataSource, String databaseName, String tableName) {
        if (dataSource == null) {
            throw new IllegalArgumentException("DataSource cannot be null");
        }
        if (databaseName == null || databaseName.trim().isEmpty()) {
            throw new IllegalArgumentException("Database name cannot be null or empty");
        }
        if (tableName == null || tableName.trim().isEmpty()) {
            throw new IllegalArgumentException("Table name cannot be null or empty");
        }

        this.dataSource = dataSource;
        this.databaseName = databaseName;
        this.tableName = tableName;
        this.objectMapper = new ObjectMapper();

        // Initialize database and table
        initializeDatabase();
        initializeTable();
    }

    /**
     * Initialize the database if it doesn't exist.
     *
     * Creates the database with UTF-8 (utf8mb4) character set and unicode collation
     * for proper internationalization support.
     */
    private void initializeDatabase() {
        String createDatabaseSql =
                "CREATE DATABASE IF NOT EXISTS "
                        + databaseName
                        + " DEFAULT CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci";

        try (Connection conn = dataSource.getConnection();
                PreparedStatement stmt = conn.prepareStatement(createDatabaseSql)) {
            stmt.execute();

            // Switch to use the created database
            try (PreparedStatement useStmt = conn.prepareStatement("USE " + databaseName)) {
                useStmt.execute();
            }
        } catch (SQLException e) {
            throw new RuntimeException("Failed to initialize database: " + databaseName, e);
        }
    }

    /**
     * Initialize the sessions table if it doesn't exist.
     */
    private void initializeTable() {
        String createTableSql =
                "CREATE TABLE IF NOT EXISTS "
                        + tableName
                        + " (session_id VARCHAR(255) PRIMARY KEY, state_data JSON NOT NULL,"
                        + " created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP, updated_at TIMESTAMP"
                        + " DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP)"
                        + " DEFAULT CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci";

        try (Connection conn = dataSource.getConnection();
                PreparedStatement stmt = conn.prepareStatement(createTableSql)) {
            // If database name is specified, ensure we're using the right database
            if (databaseName != null && !databaseName.trim().isEmpty()) {
                try (PreparedStatement useStmt = conn.prepareStatement("USE " + databaseName)) {
                    useStmt.execute();
                }
            }
            stmt.execute();
        } catch (SQLException e) {
            throw new RuntimeException("Failed to initialize session table: " + tableName, e);
        }
    }

    /**
     * Save the state of multiple StateModules to the MySQL database.
     *
     * This implementation persists the state of all provided StateModules as a single
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
                            + tableName
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
     * This implementation restores the state of all provided StateModules from the
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

        String selectSql = "SELECT state_data FROM " + tableName + " WHERE session_id = ?";

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

        String existsSql = "SELECT 1 FROM " + tableName + " WHERE session_id = ?";

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

        String deleteSql = "DELETE FROM " + tableName + " WHERE session_id = ?";

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
     * This implementation queries all session IDs from the database table,
     * returning them sorted alphabetically.
     *
     * @return List of session IDs, or empty list if no sessions exist
     * @throws RuntimeException if database operations fail
     */
    @Override
    public List<String> listSessions() {
        String listSql = "SELECT session_id FROM " + tableName + " ORDER BY session_id";

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
     * This implementation queries the session from the database to determine
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
                        + tableName
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
     * Note: This implementation does not close the DataSource as it may be
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
     * @return The database name, or null if not specified
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
        String clearSql = "DELETE FROM " + tableName;

        try (Connection conn = dataSource.getConnection();
                PreparedStatement stmt = conn.prepareStatement(clearSql)) {

            return stmt.executeUpdate();

        } catch (SQLException e) {
            throw new RuntimeException("Failed to clear sessions", e);
        }
    }

    /**
     * Drop the sessions table (for testing or cleanup).
     *
     * Use with caution as this will permanently delete all session data.
     */
    public void dropTable() {
        String dropSql = "DROP TABLE IF EXISTS " + tableName;

        try (Connection conn = dataSource.getConnection();
                PreparedStatement stmt = conn.prepareStatement(dropSql)) {

            stmt.execute();

        } catch (SQLException e) {
            throw new RuntimeException("Failed to drop session table: " + tableName, e);
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
}
