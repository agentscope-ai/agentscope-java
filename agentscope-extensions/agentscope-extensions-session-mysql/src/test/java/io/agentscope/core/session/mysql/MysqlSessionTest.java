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

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.atLeast;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import io.agentscope.core.state.SessionKey;
import io.agentscope.core.state.SimpleSessionKey;
import io.agentscope.core.state.State;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import javax.sql.DataSource;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;

/**
 * Unit tests for MysqlSession.
 *
 * <p>These tests use mocked DataSource and Connection to verify the behavior of MysqlSession
 * without requiring an actual MySQL database.
 */
@DisplayName("MysqlSession Tests")
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
    @DisplayName("Should throw exception when DataSource is null")
    void testConstructorWithNullDataSource() {
        assertThrows(
                IllegalArgumentException.class,
                () -> new MysqlSession(null),
                "DataSource cannot be null");
    }

    @Test
    @DisplayName("Should throw exception when DataSource is null with createIfNotExist flag")
    void testConstructorWithNullDataSourceAndCreateIfNotExist() {
        assertThrows(
                IllegalArgumentException.class,
                () -> new MysqlSession(null, true),
                "DataSource cannot be null");
    }

    @Test
    @DisplayName("Should create session with createIfNotExist=true")
    void testConstructorWithCreateIfNotExistTrue() throws SQLException {
        when(mockStatement.execute()).thenReturn(true);

        MysqlSession session = new MysqlSession(mockDataSource, true);

        assertEquals("agentscope", session.getDatabaseName());
        assertEquals("agentscope_sessions", session.getTableName());
        assertEquals(mockDataSource, session.getDataSource());
    }

    @Test
    @DisplayName("Should throw exception when database does not exist and createIfNotExist=false")
    void testConstructorWithCreateIfNotExistFalseAndDatabaseNotExist() throws SQLException {
        when(mockStatement.executeQuery()).thenReturn(mockResultSet);
        when(mockResultSet.next()).thenReturn(false);

        assertThrows(
                IllegalStateException.class,
                () -> new MysqlSession(mockDataSource, false),
                "Database does not exist");
    }

    @Test
    @DisplayName("Should throw exception when table does not exist and createIfNotExist=false")
    void testConstructorWithCreateIfNotExistFalseAndTableNotExist() throws SQLException {
        when(mockStatement.executeQuery()).thenReturn(mockResultSet);
        when(mockResultSet.next()).thenReturn(true, false);

        assertThrows(
                IllegalStateException.class,
                () -> new MysqlSession(mockDataSource, false),
                "Table does not exist");
    }

    @Test
    @DisplayName("Should create session when both database and table exist")
    void testConstructorWithCreateIfNotExistFalseAndBothExist() throws SQLException {
        when(mockStatement.executeQuery()).thenReturn(mockResultSet);
        when(mockResultSet.next()).thenReturn(true, true);

        MysqlSession session = new MysqlSession(mockDataSource, false);

        assertEquals("agentscope", session.getDatabaseName());
        assertEquals("agentscope_sessions", session.getTableName());
    }

    @Test
    @DisplayName("Should create session with custom database and table name")
    void testConstructorWithCustomDatabaseAndTableName() throws SQLException {
        when(mockStatement.execute()).thenReturn(true);

        MysqlSession session = new MysqlSession(mockDataSource, "custom_db", "custom_table", true);

        assertEquals("custom_db", session.getDatabaseName());
        assertEquals("custom_table", session.getTableName());
    }

    @Test
    @DisplayName("Should use default database name when null is provided")
    void testConstructorWithNullDatabaseNameUsesDefault() throws SQLException {
        when(mockStatement.execute()).thenReturn(true);

        MysqlSession session = new MysqlSession(mockDataSource, null, "custom_table", true);

        assertEquals("agentscope", session.getDatabaseName());
        assertEquals("custom_table", session.getTableName());
    }

    @Test
    @DisplayName("Should use default database name when empty string is provided")
    void testConstructorWithEmptyDatabaseNameUsesDefault() throws SQLException {
        when(mockStatement.execute()).thenReturn(true);

        MysqlSession session = new MysqlSession(mockDataSource, "  ", "custom_table", true);

        assertEquals("agentscope", session.getDatabaseName());
        assertEquals("custom_table", session.getTableName());
    }

    @Test
    @DisplayName("Should use default table name when null is provided")
    void testConstructorWithNullTableNameUsesDefault() throws SQLException {
        when(mockStatement.execute()).thenReturn(true);

        MysqlSession session = new MysqlSession(mockDataSource, "custom_db", null, true);

        assertEquals("custom_db", session.getDatabaseName());
        assertEquals("agentscope_sessions", session.getTableName());
    }

    @Test
    @DisplayName("Should use default table name when empty string is provided")
    void testConstructorWithEmptyTableNameUsesDefault() throws SQLException {
        when(mockStatement.execute()).thenReturn(true);

        MysqlSession session = new MysqlSession(mockDataSource, "custom_db", "", true);

        assertEquals("custom_db", session.getDatabaseName());
        assertEquals("agentscope_sessions", session.getTableName());
    }

    @Test
    @DisplayName("Should get DataSource correctly")
    void testGetDataSource() throws SQLException {
        when(mockStatement.execute()).thenReturn(true);

        MysqlSession session = new MysqlSession(mockDataSource, true);
        assertEquals(mockDataSource, session.getDataSource());
    }

    @Test
    @DisplayName("Should save and get single state correctly")
    void testSaveAndGetSingleState() throws SQLException {
        when(mockStatement.execute()).thenReturn(true);
        when(mockStatement.executeUpdate()).thenReturn(1);
        when(mockStatement.executeQuery()).thenReturn(mockResultSet);
        when(mockResultSet.next()).thenReturn(true);
        when(mockResultSet.getString("state_data"))
                .thenReturn("{\"value\":\"test_value\",\"count\":42}");

        MysqlSession session = new MysqlSession(mockDataSource, true);
        SessionKey sessionKey = SimpleSessionKey.of("session1");
        TestState state = new TestState("test_value", 42);

        // Save state
        session.save(sessionKey, "testModule", state);

        // Verify save operations
        verify(mockStatement, atLeast(1)).executeUpdate();

        // Get state
        Optional<TestState> loaded = session.get(sessionKey, "testModule", TestState.class);
        assertTrue(loaded.isPresent());
        assertEquals("test_value", loaded.get().value());
        assertEquals(42, loaded.get().count());
    }

    @Test
    @DisplayName("Should save and get list state correctly")
    void testSaveAndGetListState() throws SQLException {
        when(mockStatement.execute()).thenReturn(true);
        when(mockStatement.executeUpdate()).thenReturn(1);
        when(mockStatement.executeQuery()).thenReturn(mockResultSet);

        // Mock sequence for save():
        // 1. getStoredHash() query - no hash found (next=false)
        // 2. getListCount() query - no items (next=true, wasNull=true)
        // Then for getList():
        // 3. getList() query - 2 rows (next=true, true, false)
        when(mockResultSet.next()).thenReturn(false, true, true, true, false);
        when(mockResultSet.getInt("max_index")).thenReturn(0);
        when(mockResultSet.wasNull()).thenReturn(true);
        when(mockResultSet.getString("state_data"))
                .thenReturn("{\"value\":\"value1\",\"count\":1}")
                .thenReturn("{\"value\":\"value2\",\"count\":2}");

        MysqlSession session = new MysqlSession(mockDataSource, true);
        SessionKey sessionKey = SimpleSessionKey.of("session1");
        List<TestState> states = List.of(new TestState("value1", 1), new TestState("value2", 2));

        // Save list state
        session.save(sessionKey, "testList", states);

        // Get list state
        List<TestState> loaded = session.getList(sessionKey, "testList", TestState.class);
        assertEquals(2, loaded.size());
        assertEquals("value1", loaded.get(0).value());
        assertEquals("value2", loaded.get(1).value());
    }

    @Test
    @DisplayName("Should return empty for non-existent state")
    void testGetNonExistentState() throws SQLException {
        when(mockStatement.execute()).thenReturn(true);
        when(mockStatement.executeQuery()).thenReturn(mockResultSet);
        when(mockResultSet.next()).thenReturn(false);

        MysqlSession session = new MysqlSession(mockDataSource, true);
        SessionKey sessionKey = SimpleSessionKey.of("non_existent");

        Optional<TestState> state = session.get(sessionKey, "testModule", TestState.class);
        assertFalse(state.isPresent());
    }

    @Test
    @DisplayName("Should return empty list for non-existent list state")
    void testGetNonExistentListState() throws SQLException {
        when(mockStatement.execute()).thenReturn(true);
        when(mockStatement.executeQuery()).thenReturn(mockResultSet);
        when(mockResultSet.next()).thenReturn(false);

        MysqlSession session = new MysqlSession(mockDataSource, true);
        SessionKey sessionKey = SimpleSessionKey.of("non_existent");

        List<TestState> states = session.getList(sessionKey, "testList", TestState.class);
        assertTrue(states.isEmpty());
    }

    @Test
    @DisplayName("Should return true when session exists")
    void testSessionExists() throws SQLException {
        when(mockStatement.execute()).thenReturn(true);
        when(mockStatement.executeQuery()).thenReturn(mockResultSet);
        when(mockResultSet.next()).thenReturn(true);

        MysqlSession session = new MysqlSession(mockDataSource, true);
        SessionKey sessionKey = SimpleSessionKey.of("session1");

        assertTrue(session.exists(sessionKey));
    }

    @Test
    @DisplayName("Should return false when session does not exist")
    void testSessionDoesNotExist() throws SQLException {
        when(mockStatement.execute()).thenReturn(true);
        when(mockStatement.executeQuery()).thenReturn(mockResultSet);
        when(mockResultSet.next()).thenReturn(false);

        MysqlSession session = new MysqlSession(mockDataSource, true);
        SessionKey sessionKey = SimpleSessionKey.of("non_existent");

        assertFalse(session.exists(sessionKey));
    }

    @Test
    @DisplayName("Should delete session correctly")
    void testDeleteSession() throws SQLException {
        when(mockStatement.execute()).thenReturn(true);
        when(mockStatement.executeUpdate()).thenReturn(1);

        MysqlSession session = new MysqlSession(mockDataSource, true);
        SessionKey sessionKey = SimpleSessionKey.of("session1");

        session.delete(sessionKey);

        verify(mockStatement).setString(1, "session1");
        verify(mockStatement).executeUpdate();
    }

    @Test
    @DisplayName("Should list all session keys when empty")
    void testListSessionKeysEmpty() throws SQLException {
        when(mockStatement.execute()).thenReturn(true);
        when(mockStatement.executeQuery()).thenReturn(mockResultSet);
        when(mockResultSet.next()).thenReturn(false);

        MysqlSession session = new MysqlSession(mockDataSource, true);
        Set<SessionKey> sessionKeys = session.listSessionKeys();

        assertTrue(sessionKeys.isEmpty());
    }

    @Test
    @DisplayName("Should list all session keys")
    void testListSessionKeysWithResults() throws SQLException {
        when(mockStatement.execute()).thenReturn(true);
        when(mockStatement.executeQuery()).thenReturn(mockResultSet);
        when(mockResultSet.next()).thenReturn(true, true, false);
        when(mockResultSet.getString("session_id")).thenReturn("session1", "session2");

        MysqlSession session = new MysqlSession(mockDataSource, true);
        Set<SessionKey> sessionKeys = session.listSessionKeys();

        assertEquals(2, sessionKeys.size());
        assertTrue(sessionKeys.contains(SimpleSessionKey.of("session1")));
        assertTrue(sessionKeys.contains(SimpleSessionKey.of("session2")));
    }

    @Test
    @DisplayName("Should clear all sessions")
    void testClearAllSessions() throws SQLException {
        when(mockStatement.execute()).thenReturn(true);
        when(mockStatement.executeUpdate()).thenReturn(5);

        MysqlSession session = new MysqlSession(mockDataSource, true);
        int deleted = session.clearAllSessions();

        assertEquals(5, deleted);
    }

    @Test
    @DisplayName("Should not close DataSource when closing session")
    void testClose() throws SQLException {
        when(mockStatement.execute()).thenReturn(true);

        MysqlSession session = new MysqlSession(mockDataSource, true);
        session.close();
        assertEquals(mockDataSource, session.getDataSource());
    }

    // ==================== SQL Injection Prevention Tests ====================

    @Test
    @DisplayName("Should reject database name with semicolon (SQL injection)")
    void testConstructorRejectsDatabaseNameWithSemicolon() {
        assertThrows(
                IllegalArgumentException.class,
                () ->
                        new MysqlSession(
                                mockDataSource, "db; DROP DATABASE mysql; --", "table", true),
                "Database name contains invalid characters");
    }

    @Test
    @DisplayName("Should reject table name with semicolon (SQL injection)")
    void testConstructorRejectsTableNameWithSemicolon() {
        assertThrows(
                IllegalArgumentException.class,
                () ->
                        new MysqlSession(
                                mockDataSource, "valid_db", "table; DROP TABLE users; --", true),
                "Table name contains invalid characters");
    }

    @Test
    @DisplayName("Should reject database name with space")
    void testConstructorRejectsDatabaseNameWithSpace() {
        assertThrows(
                IllegalArgumentException.class,
                () -> new MysqlSession(mockDataSource, "db name", "table", true),
                "Database name contains invalid characters");
    }

    @Test
    @DisplayName("Should reject table name with space")
    void testConstructorRejectsTableNameWithSpace() {
        assertThrows(
                IllegalArgumentException.class,
                () -> new MysqlSession(mockDataSource, "valid_db", "table name", true),
                "Table name contains invalid characters");
    }

    @Test
    @DisplayName("Should reject database name starting with number")
    void testConstructorRejectsDatabaseNameStartingWithNumber() {
        assertThrows(
                IllegalArgumentException.class,
                () -> new MysqlSession(mockDataSource, "123db", "table", true),
                "Database name contains invalid characters");
    }

    @Test
    @DisplayName("Should reject table name starting with number")
    void testConstructorRejectsTableNameStartingWithNumber() {
        assertThrows(
                IllegalArgumentException.class,
                () -> new MysqlSession(mockDataSource, "valid_db", "123table", true),
                "Table name contains invalid characters");
    }

    @Test
    @DisplayName("Should reject database name exceeding max length")
    void testConstructorRejectsDatabaseNameExceedingMaxLength() {
        String longName = "a".repeat(65);
        assertThrows(
                IllegalArgumentException.class,
                () -> new MysqlSession(mockDataSource, longName, "table", true),
                "Database name cannot exceed 64 characters");
    }

    @Test
    @DisplayName("Should reject table name exceeding max length")
    void testConstructorRejectsTableNameExceedingMaxLength() {
        String longName = "a".repeat(65);
        assertThrows(
                IllegalArgumentException.class,
                () -> new MysqlSession(mockDataSource, "valid_db", longName, true),
                "Table name cannot exceed 64 characters");
    }

    @Test
    @DisplayName("Should accept valid database and table names")
    void testConstructorAcceptsValidDatabaseAndTableNames() throws SQLException {
        when(mockStatement.execute()).thenReturn(true);

        MysqlSession session =
                new MysqlSession(mockDataSource, "my_database_123", "my_table_456", true);

        assertEquals("my_database_123", session.getDatabaseName());
        assertEquals("my_table_456", session.getTableName());
    }

    @Test
    @DisplayName("Should accept names starting with underscore")
    void testConstructorAcceptsNameStartingWithUnderscore() throws SQLException {
        when(mockStatement.execute()).thenReturn(true);

        MysqlSession session =
                new MysqlSession(mockDataSource, "_private_db", "_private_table", true);

        assertEquals("_private_db", session.getDatabaseName());
        assertEquals("_private_table", session.getTableName());
    }

    @Test
    @DisplayName("Should accept max length names")
    void testConstructorAcceptsMaxLengthNames() throws SQLException {
        when(mockStatement.execute()).thenReturn(true);

        String maxLengthName = "a".repeat(64);
        MysqlSession session = new MysqlSession(mockDataSource, maxLengthName, maxLengthName, true);

        assertEquals(maxLengthName, session.getDatabaseName());
        assertEquals(maxLengthName, session.getTableName());
    }

    @Test
    @DisplayName("Should accept database name with hyphens")
    void testConstructorAcceptsDatabaseNameWithHyphens() throws SQLException {
        when(mockStatement.execute()).thenReturn(true);

        MysqlSession session = new MysqlSession(mockDataSource, "my-test-db", "my_table", true);

        assertEquals("my-test-db", session.getDatabaseName());
        assertEquals("my_table", session.getTableName());
    }

    @Test
    @DisplayName("Should accept table name with hyphens")
    void testConstructorAcceptsTableNameWithHyphens() throws SQLException {
        when(mockStatement.execute()).thenReturn(true);

        MysqlSession session = new MysqlSession(mockDataSource, "my_db", "my-test-table", true);

        assertEquals("my_db", session.getDatabaseName());
        assertEquals("my-test-table", session.getTableName());
    }

    @Test
    @DisplayName("Should accept database and table names with hyphens")
    void testConstructorAcceptsDatabaseAndTableNamesWithHyphens() throws SQLException {
        when(mockStatement.execute()).thenReturn(true);

        MysqlSession session = new MysqlSession(mockDataSource, "xxx-xxx-xx", "test-table", true);

        assertEquals("xxx-xxx-xx", session.getDatabaseName());
        assertEquals("test-table", session.getTableName());
    }

    @Test
    @DisplayName("Should accept name with underscore and hyphen")
    void testConstructorAcceptsNameWithUnderscoreAndHyphen() throws SQLException {
        when(mockStatement.execute()).thenReturn(true);

        MysqlSession session =
                new MysqlSession(mockDataSource, "my_test-db", "my_table-test", true);

        assertEquals("my_test-db", session.getDatabaseName());
        assertEquals("my_table-test", session.getTableName());
    }

    /** Simple test state record for testing. */
    public record TestState(String value, int count) implements State {}
}
