/*
 * Copyright 2024-2025 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
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

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import io.agentscope.core.session.SessionInfo;
import io.agentscope.core.state.StateModule;
import io.agentscope.core.state.StateModuleBase;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.util.Map;
import javax.sql.DataSource;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;

/**
 * Unit tests for MysqlSession.
 *
 * <p>These tests use mocked DataSource and Connection to verify the behavior
 * of MysqlSession without requiring an actual MySQL database.
 */
public class MysqlSessionTest {

    @Mock private DataSource mockDataSource;

    @Mock private Connection mockConnection;

    @Mock private PreparedStatement mockStatement;

    @Mock private ResultSet mockResultSet;

    private AutoCloseable mockitoCloseable;

    @BeforeEach
    void setUp() throws SQLException {
        mockitoCloseable = MockitoAnnotations.openMocks(this);
        when(mockDataSource.getConnection()).thenReturn(mockConnection);
        when(mockConnection.prepareStatement(anyString())).thenReturn(mockStatement);
    }

    @AfterEach
    void tearDown() throws Exception {
        if (mockitoCloseable != null) {
            mockitoCloseable.close();
        }
    }

    @Test
    void testConstructorWithNullDataSource() {
        assertThrows(
                IllegalArgumentException.class,
                () -> new MysqlSession(null),
                "DataSource cannot be null");
    }

    @Test
    void testConstructorWithNullDataSourceAndCreateIfNotExist() {
        assertThrows(
                IllegalArgumentException.class,
                () -> new MysqlSession(null, true),
                "DataSource cannot be null");
    }

    @Test
    void testConstructorWithCreateIfNotExistTrue() throws SQLException {
        // Mock successful execution for CREATE DATABASE and CREATE TABLE
        when(mockStatement.execute()).thenReturn(true);

        MysqlSession session = new MysqlSession(mockDataSource, true);

        assertEquals("agentscope", session.getDatabaseName());
        assertEquals("agentscope_sessions", session.getTableName());
        assertEquals(mockDataSource, session.getDataSource());
    }

    @Test
    void testConstructorWithCreateIfNotExistFalseAndDatabaseNotExist() throws SQLException {
        // Mock database check returns empty result (database doesn't exist)
        when(mockStatement.executeQuery()).thenReturn(mockResultSet);
        when(mockResultSet.next()).thenReturn(false);

        assertThrows(
                IllegalStateException.class,
                () -> new MysqlSession(mockDataSource, false),
                "Database does not exist");
    }

    @Test
    void testConstructorWithCreateIfNotExistFalseAndTableNotExist() throws SQLException {
        // First query (database check) returns true
        // Second query (table check) returns false
        when(mockStatement.executeQuery()).thenReturn(mockResultSet);
        when(mockResultSet.next()).thenReturn(true, false);

        assertThrows(
                IllegalStateException.class,
                () -> new MysqlSession(mockDataSource, false),
                "Table does not exist");
    }

    @Test
    void testConstructorWithCreateIfNotExistFalseAndBothExist() throws SQLException {
        // Both database and table exist
        when(mockStatement.executeQuery()).thenReturn(mockResultSet);
        when(mockResultSet.next()).thenReturn(true, true);

        MysqlSession session = new MysqlSession(mockDataSource, false);

        assertEquals("agentscope", session.getDatabaseName());
        assertEquals("agentscope_sessions", session.getTableName());
    }

    @Test
    void testConstructorWithCustomDatabaseAndTableName() throws SQLException {
        // Mock successful execution for CREATE DATABASE and CREATE TABLE
        when(mockStatement.execute()).thenReturn(true);

        MysqlSession session = new MysqlSession(mockDataSource, "custom_db", "custom_table", true);

        assertEquals("custom_db", session.getDatabaseName());
        assertEquals("custom_table", session.getTableName());
    }

    @Test
    void testConstructorWithNullDatabaseNameUsesDefault() throws SQLException {
        when(mockStatement.execute()).thenReturn(true);

        MysqlSession session = new MysqlSession(mockDataSource, null, "custom_table", true);

        assertEquals("agentscope", session.getDatabaseName());
        assertEquals("custom_table", session.getTableName());
    }

    @Test
    void testConstructorWithEmptyDatabaseNameUsesDefault() throws SQLException {
        when(mockStatement.execute()).thenReturn(true);

        MysqlSession session = new MysqlSession(mockDataSource, "  ", "custom_table", true);

        assertEquals("agentscope", session.getDatabaseName());
        assertEquals("custom_table", session.getTableName());
    }

    @Test
    void testConstructorWithNullTableNameUsesDefault() throws SQLException {
        when(mockStatement.execute()).thenReturn(true);

        MysqlSession session = new MysqlSession(mockDataSource, "custom_db", null, true);

        assertEquals("custom_db", session.getDatabaseName());
        assertEquals("agentscope_sessions", session.getTableName());
    }

    @Test
    void testConstructorWithEmptyTableNameUsesDefault() throws SQLException {
        when(mockStatement.execute()).thenReturn(true);

        MysqlSession session = new MysqlSession(mockDataSource, "custom_db", "", true);

        assertEquals("custom_db", session.getDatabaseName());
        assertEquals("agentscope_sessions", session.getTableName());
    }

    @Test
    void testConstructorWithCustomNamesAndCreateIfNotExistFalse() throws SQLException {
        // Both database and table exist
        when(mockStatement.executeQuery()).thenReturn(mockResultSet);
        when(mockResultSet.next()).thenReturn(true, true);

        MysqlSession session =
                new MysqlSession(mockDataSource, "my_database", "my_sessions", false);

        assertEquals("my_database", session.getDatabaseName());
        assertEquals("my_sessions", session.getTableName());
    }

    @Test
    void testDefaultConstructorRequiresExistingDatabaseAndTable() throws SQLException {
        // Default constructor uses createIfNotExist=false
        // Mock database check returns empty result (database doesn't exist)
        when(mockStatement.executeQuery()).thenReturn(mockResultSet);
        when(mockResultSet.next()).thenReturn(false);

        assertThrows(
                IllegalStateException.class,
                () -> new MysqlSession(mockDataSource),
                "Database does not exist");
    }

    @Test
    void testGetDataSource() throws SQLException {
        when(mockStatement.execute()).thenReturn(true);

        MysqlSession session = new MysqlSession(mockDataSource, true);
        assertEquals(mockDataSource, session.getDataSource());
    }

    @Test
    void testSessionExistsReturnsTrue() throws SQLException {
        // Setup for constructor (createIfNotExist=true)
        when(mockStatement.execute()).thenReturn(true);

        MysqlSession session = new MysqlSession(mockDataSource, true);

        // Setup for sessionExists call
        when(mockStatement.executeQuery()).thenReturn(mockResultSet);
        when(mockResultSet.next()).thenReturn(true);

        assertTrue(session.sessionExists("test_session"));

        verify(mockStatement).setString(1, "test_session");
    }

    @Test
    void testSessionExistsReturnsFalse() throws SQLException {
        when(mockStatement.execute()).thenReturn(true);

        MysqlSession session = new MysqlSession(mockDataSource, true);

        when(mockStatement.executeQuery()).thenReturn(mockResultSet);
        when(mockResultSet.next()).thenReturn(false);

        assertFalse(session.sessionExists("nonexistent_session"));
    }

    @Test
    void testDeleteSessionReturnsTrue() throws SQLException {
        when(mockStatement.execute()).thenReturn(true);
        when(mockStatement.executeUpdate()).thenReturn(1);

        MysqlSession session = new MysqlSession(mockDataSource, true);
        assertTrue(session.deleteSession("test_session"));

        verify(mockStatement).setString(1, "test_session");
    }

    @Test
    void testDeleteSessionReturnsFalse() throws SQLException {
        when(mockStatement.execute()).thenReturn(true);
        when(mockStatement.executeUpdate()).thenReturn(0);

        MysqlSession session = new MysqlSession(mockDataSource, true);
        assertFalse(session.deleteSession("nonexistent_session"));
    }

    @Test
    void testListSessionsEmpty() throws SQLException {
        when(mockStatement.execute()).thenReturn(true);

        MysqlSession session = new MysqlSession(mockDataSource, true);

        when(mockStatement.executeQuery()).thenReturn(mockResultSet);
        when(mockResultSet.next()).thenReturn(false);

        assertTrue(session.listSessions().isEmpty());
    }

    @Test
    void testListSessionsWithResults() throws SQLException {
        when(mockStatement.execute()).thenReturn(true);

        MysqlSession session = new MysqlSession(mockDataSource, true);

        when(mockStatement.executeQuery()).thenReturn(mockResultSet);
        when(mockResultSet.next()).thenReturn(true, true, false);
        when(mockResultSet.getString("session_id")).thenReturn("session1", "session2");

        var sessions = session.listSessions();

        assertEquals(2, sessions.size());
        assertEquals("session1", sessions.get(0));
        assertEquals("session2", sessions.get(1));
    }

    @Test
    void testSaveSessionState() throws SQLException {
        when(mockStatement.execute()).thenReturn(true);
        when(mockStatement.executeUpdate()).thenReturn(1);

        MysqlSession session = new MysqlSession(mockDataSource, true);

        TestStateModule module = new TestStateModule();
        module.setValue("test_value");

        Map<String, StateModule> stateModules = Map.of("testModule", module);
        session.saveSessionState("test_session", stateModules);

        // Verify both session_id and state_data parameters are set
        verify(mockStatement).setString(1, "test_session");
        verify(mockStatement, org.mockito.Mockito.atLeast(2))
                .setString(any(Integer.class), any(String.class));
    }

    @Test
    void testLoadSessionStateNotExistAllowed() throws SQLException {
        when(mockStatement.execute()).thenReturn(true);

        MysqlSession session = new MysqlSession(mockDataSource, true);

        when(mockStatement.executeQuery()).thenReturn(mockResultSet);
        when(mockResultSet.next()).thenReturn(false);

        TestStateModule module = new TestStateModule();
        Map<String, StateModule> stateModules = Map.of("testModule", module);

        // Should not throw when allowNotExist is true
        session.loadSessionState("nonexistent_session", true, stateModules);
    }

    @Test
    void testLoadSessionStateNotExistNotAllowed() throws SQLException {
        when(mockStatement.execute()).thenReturn(true);

        MysqlSession session = new MysqlSession(mockDataSource, true);

        when(mockStatement.executeQuery()).thenReturn(mockResultSet);
        when(mockResultSet.next()).thenReturn(false);

        TestStateModule module = new TestStateModule();
        Map<String, StateModule> stateModules = Map.of("testModule", module);

        assertThrows(
                RuntimeException.class,
                () -> session.loadSessionState("nonexistent_session", false, stateModules),
                "Session not found: nonexistent_session");
    }

    @Test
    void testLoadSessionStateSuccess() throws SQLException {
        when(mockStatement.execute()).thenReturn(true);

        MysqlSession session = new MysqlSession(mockDataSource, true);

        when(mockStatement.executeQuery()).thenReturn(mockResultSet);
        when(mockResultSet.next()).thenReturn(true);
        when(mockResultSet.getString("state_data"))
                .thenReturn("{\"testModule\":{\"value\":\"loaded_value\"}}");

        TestStateModule module = new TestStateModule();
        Map<String, StateModule> stateModules = Map.of("testModule", module);

        session.loadSessionState("test_session", true, stateModules);

        assertEquals("loaded_value", module.getValue());
    }

    @Test
    void testGetSessionInfoNotFound() throws SQLException {
        when(mockStatement.execute()).thenReturn(true);

        MysqlSession session = new MysqlSession(mockDataSource, true);

        when(mockStatement.executeQuery()).thenReturn(mockResultSet);
        when(mockResultSet.next()).thenReturn(false);

        assertThrows(
                RuntimeException.class,
                () -> session.getSessionInfo("nonexistent_session"),
                "Session not found: nonexistent_session");
    }

    @Test
    void testGetSessionInfoSuccess() throws SQLException {
        when(mockStatement.execute()).thenReturn(true);

        String stateJson = "{\"component1\":{},\"component2\":{}}";
        Timestamp timestamp = new Timestamp(System.currentTimeMillis());

        MysqlSession session = new MysqlSession(mockDataSource, true);

        when(mockStatement.executeQuery()).thenReturn(mockResultSet);
        when(mockResultSet.next()).thenReturn(true);
        when(mockResultSet.getString("state_data")).thenReturn(stateJson);
        when(mockResultSet.getLong("data_size")).thenReturn((long) stateJson.length());
        when(mockResultSet.getTimestamp("updated_at")).thenReturn(timestamp);

        SessionInfo info = session.getSessionInfo("test_session");

        assertNotNull(info);
        assertEquals("test_session", info.getSessionId());
        assertEquals(stateJson.length(), info.getSize());
        assertEquals(timestamp.getTime(), info.getLastModified());
        assertEquals(2, info.getComponentCount());
    }

    @Test
    void testClearAllSessions() throws SQLException {
        when(mockStatement.execute()).thenReturn(true);
        when(mockStatement.executeUpdate()).thenReturn(5);

        MysqlSession session = new MysqlSession(mockDataSource, true);
        int deleted = session.clearAllSessions();

        assertEquals(5, deleted);
    }

    @Test
    void testValidateSessionIdNull() throws SQLException {
        when(mockStatement.execute()).thenReturn(true);

        MysqlSession session = new MysqlSession(mockDataSource, true);

        assertThrows(
                IllegalArgumentException.class,
                () -> session.sessionExists(null),
                "Session ID cannot be null or empty");
    }

    @Test
    void testValidateSessionIdEmpty() throws SQLException {
        when(mockStatement.execute()).thenReturn(true);

        MysqlSession session = new MysqlSession(mockDataSource, true);

        assertThrows(
                IllegalArgumentException.class,
                () -> session.sessionExists(""),
                "Session ID cannot be null or empty");
    }

    @Test
    void testValidateSessionIdWithPathSeparator() throws SQLException {
        when(mockStatement.execute()).thenReturn(true);

        MysqlSession session = new MysqlSession(mockDataSource, true);

        assertThrows(
                IllegalArgumentException.class,
                () -> session.sessionExists("path/with/separator"),
                "Session ID cannot contain path separators");
    }

    @Test
    void testClose() throws SQLException {
        when(mockStatement.execute()).thenReturn(true);

        MysqlSession session = new MysqlSession(mockDataSource, true);
        // close() should not throw and should not close the DataSource
        session.close();
        // DataSource should still be accessible
        assertEquals(mockDataSource, session.getDataSource());
    }

    // ==================== SQL Injection Prevention Tests ====================

    @Test
    void testConstructorRejectsDatabaseNameWithSemicolon() {
        assertThrows(
                IllegalArgumentException.class,
                () ->
                        new MysqlSession(
                                mockDataSource, "db; DROP DATABASE mysql; --", "table", true),
                "Database name contains invalid characters");
    }

    @Test
    void testConstructorRejectsTableNameWithSemicolon() {
        assertThrows(
                IllegalArgumentException.class,
                () ->
                        new MysqlSession(
                                mockDataSource, "valid_db", "table; DROP TABLE users; --", true),
                "Table name contains invalid characters");
    }

    @Test
    void testConstructorRejectsDatabaseNameWithSpace() {
        assertThrows(
                IllegalArgumentException.class,
                () -> new MysqlSession(mockDataSource, "db name", "table", true),
                "Database name contains invalid characters");
    }

    @Test
    void testConstructorRejectsTableNameWithSpace() {
        assertThrows(
                IllegalArgumentException.class,
                () -> new MysqlSession(mockDataSource, "valid_db", "table name", true),
                "Table name contains invalid characters");
    }

    @Test
    void testConstructorRejectsDatabaseNameWithQuotes() {
        assertThrows(
                IllegalArgumentException.class,
                () -> new MysqlSession(mockDataSource, "db'--", "table", true),
                "Database name contains invalid characters");
    }

    @Test
    void testConstructorRejectsTableNameWithQuotes() {
        assertThrows(
                IllegalArgumentException.class,
                () -> new MysqlSession(mockDataSource, "valid_db", "table\"--", true),
                "Table name contains invalid characters");
    }

    @Test
    void testConstructorRejectsDatabaseNameStartingWithNumber() {
        assertThrows(
                IllegalArgumentException.class,
                () -> new MysqlSession(mockDataSource, "123db", "table", true),
                "Database name contains invalid characters");
    }

    @Test
    void testConstructorRejectsTableNameStartingWithNumber() {
        assertThrows(
                IllegalArgumentException.class,
                () -> new MysqlSession(mockDataSource, "valid_db", "123table", true),
                "Table name contains invalid characters");
    }

    @Test
    void testConstructorRejectsDatabaseNameWithHyphen() {
        assertThrows(
                IllegalArgumentException.class,
                () -> new MysqlSession(mockDataSource, "my-database", "table", true),
                "Database name contains invalid characters");
    }

    @Test
    void testConstructorRejectsTableNameWithHyphen() {
        assertThrows(
                IllegalArgumentException.class,
                () -> new MysqlSession(mockDataSource, "valid_db", "my-table", true),
                "Table name contains invalid characters");
    }

    @Test
    void testConstructorRejectsDatabaseNameExceedingMaxLength() {
        String longName = "a".repeat(65); // 65 characters, exceeds 64 limit
        assertThrows(
                IllegalArgumentException.class,
                () -> new MysqlSession(mockDataSource, longName, "table", true),
                "Database name cannot exceed 64 characters");
    }

    @Test
    void testConstructorRejectsTableNameExceedingMaxLength() {
        String longName = "a".repeat(65); // 65 characters, exceeds 64 limit
        assertThrows(
                IllegalArgumentException.class,
                () -> new MysqlSession(mockDataSource, "valid_db", longName, true),
                "Table name cannot exceed 64 characters");
    }

    @Test
    void testConstructorAcceptsValidDatabaseAndTableNames() throws SQLException {
        when(mockStatement.execute()).thenReturn(true);

        // Valid names with letters, numbers, and underscores
        MysqlSession session =
                new MysqlSession(mockDataSource, "my_database_123", "my_table_456", true);

        assertEquals("my_database_123", session.getDatabaseName());
        assertEquals("my_table_456", session.getTableName());
    }

    @Test
    void testConstructorAcceptsNameStartingWithUnderscore() throws SQLException {
        when(mockStatement.execute()).thenReturn(true);

        MysqlSession session =
                new MysqlSession(mockDataSource, "_private_db", "_private_table", true);

        assertEquals("_private_db", session.getDatabaseName());
        assertEquals("_private_table", session.getTableName());
    }

    @Test
    void testConstructorAcceptsMaxLengthNames() throws SQLException {
        when(mockStatement.execute()).thenReturn(true);

        String maxLengthName = "a".repeat(64); // Exactly 64 characters
        MysqlSession session = new MysqlSession(mockDataSource, maxLengthName, maxLengthName, true);

        assertEquals(maxLengthName, session.getDatabaseName());
        assertEquals(maxLengthName, session.getTableName());
    }

    @Test
    void testConstructorRejectsSqlInjectionInDatabaseName() {
        // Common SQL injection patterns
        String[] injectionAttempts = {
            "db; DROP TABLE users;",
            "db' OR '1'='1",
            "db\" OR \"1\"=\"1",
            "db`; DELETE FROM sessions;",
            "db\nDROP DATABASE",
            "db/*comment*/",
            "db--comment"
        };

        for (String injection : injectionAttempts) {
            assertThrows(
                    IllegalArgumentException.class,
                    () -> new MysqlSession(mockDataSource, injection, "table", true),
                    "Should reject SQL injection attempt: " + injection);
        }
    }

    @Test
    void testConstructorRejectsSqlInjectionInTableName() {
        // Common SQL injection patterns
        String[] injectionAttempts = {
            "table; DROP TABLE users;",
            "table' OR '1'='1",
            "table\" OR \"1\"=\"1",
            "table`; DELETE FROM sessions;",
            "table\nDROP DATABASE",
            "table/*comment*/",
            "table--comment"
        };

        for (String injection : injectionAttempts) {
            assertThrows(
                    IllegalArgumentException.class,
                    () -> new MysqlSession(mockDataSource, "valid_db", injection, true),
                    "Should reject SQL injection attempt: " + injection);
        }
    }

    /**
     * Simple test state module implementation.
     */
    private static class TestStateModule extends StateModuleBase {
        private String value;

        public TestStateModule() {
            registerState("value");
        }

        public String getValue() {
            return value;
        }

        public void setValue(String value) {
            this.value = value;
        }

        @Override
        public String getComponentName() {
            return "testModule";
        }
    }
}
