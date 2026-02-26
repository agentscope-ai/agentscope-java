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
 * Unit tests for DaMengSession.
 *
 * <p>These tests use mocked DataSource and Connection to verify the behavior of DaMengSession
 * without requiring an actual DaMeng database.
 */
@DisplayName("DaMengSession Tests")
public class DaMengSessionTest {

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
                () -> new DaMengSession(null),
                "DataSource cannot be null");
    }

    @Test
    @DisplayName("Should throw exception when DataSource is null with createIfNotExist flag")
    void testConstructorWithNullDataSourceAndCreateIfNotExist() {
        assertThrows(
                IllegalArgumentException.class,
                () -> new DaMengSession(null, true),
                "DataSource cannot be null");
    }

    @Test
    @DisplayName("Should create session with createIfNotExist=true")
    void testConstructorWithCreateIfNotExistTrue() throws SQLException {
        // Mock schema check (schema doesn't exist)
        when(mockStatement.executeQuery()).thenReturn(mockResultSet);
        when(mockResultSet.next()).thenReturn(false, false);
        when(mockStatement.execute()).thenReturn(true);

        DaMengSession session = new DaMengSession(mockDataSource, true);

        assertEquals("AGENTSCOPE", session.getSchemaName());
        assertEquals("AGENTSCOPE_SESSIONS", session.getTableName());
        assertEquals(mockDataSource, session.getDataSource());
    }

    @Test
    @DisplayName("Should throw exception when schema does not exist and createIfNotExist=false")
    void testConstructorWithCreateIfNotExistFalseAndSchemaNotExist() throws SQLException {
        when(mockStatement.executeQuery()).thenReturn(mockResultSet);
        when(mockResultSet.next()).thenReturn(false);

        assertThrows(
                IllegalStateException.class,
                () -> new DaMengSession(mockDataSource, false),
                "Schema does not exist");
    }

    @Test
    @DisplayName("Should throw exception when table does not exist and createIfNotExist=false")
    void testConstructorWithCreateIfNotExistFalseAndTableNotExist() throws SQLException {
        when(mockStatement.executeQuery()).thenReturn(mockResultSet);
        when(mockResultSet.next()).thenReturn(true, false);

        assertThrows(
                IllegalStateException.class,
                () -> new DaMengSession(mockDataSource, false),
                "Table does not exist");
    }

    @Test
    @DisplayName("Should create session when both schema and table exist")
    void testConstructorWithCreateIfNotExistFalseAndBothExist() throws SQLException {
        when(mockStatement.executeQuery()).thenReturn(mockResultSet);
        when(mockResultSet.next()).thenReturn(true, true);

        DaMengSession session = new DaMengSession(mockDataSource, false);

        assertEquals("AGENTSCOPE", session.getSchemaName());
        assertEquals("AGENTSCOPE_SESSIONS", session.getTableName());
    }

    @Test
    @DisplayName("Should create session with custom schema and table name")
    void testConstructorWithCustomSchemaAndTableName() throws SQLException {
        when(mockStatement.executeQuery()).thenReturn(mockResultSet);
        when(mockResultSet.next()).thenReturn(false, false);
        when(mockStatement.execute()).thenReturn(true);

        DaMengSession session =
                new DaMengSession(mockDataSource, "CUSTOM_SCHEMA", "CUSTOM_TABLE", true);

        assertEquals("CUSTOM_SCHEMA", session.getSchemaName());
        assertEquals("CUSTOM_TABLE", session.getTableName());
    }

    @Test
    @DisplayName("Should use default schema name when null is provided")
    void testConstructorWithNullSchemaNameUsesDefault() throws SQLException {
        when(mockStatement.executeQuery()).thenReturn(mockResultSet);
        when(mockResultSet.next()).thenReturn(false, false);
        when(mockStatement.execute()).thenReturn(true);

        DaMengSession session = new DaMengSession(mockDataSource, null, "CUSTOM_TABLE", true);

        assertEquals("AGENTSCOPE", session.getSchemaName());
        assertEquals("CUSTOM_TABLE", session.getTableName());
    }

    @Test
    @DisplayName("Should use default schema name when empty string is provided")
    void testConstructorWithEmptySchemaNameUsesDefault() throws SQLException {
        when(mockStatement.executeQuery()).thenReturn(mockResultSet);
        when(mockResultSet.next()).thenReturn(false, false);
        when(mockStatement.execute()).thenReturn(true);

        DaMengSession session = new DaMengSession(mockDataSource, "  ", "CUSTOM_TABLE", true);

        assertEquals("AGENTSCOPE", session.getSchemaName());
        assertEquals("CUSTOM_TABLE", session.getTableName());
    }

    @Test
    @DisplayName("Should use default table name when null is provided")
    void testConstructorWithNullTableNameUsesDefault() throws SQLException {
        when(mockStatement.executeQuery()).thenReturn(mockResultSet);
        when(mockResultSet.next()).thenReturn(false, false);
        when(mockStatement.execute()).thenReturn(true);

        DaMengSession session = new DaMengSession(mockDataSource, "CUSTOM_SCHEMA", null, true);

        assertEquals("CUSTOM_SCHEMA", session.getSchemaName());
        assertEquals("AGENTSCOPE_SESSIONS", session.getTableName());
    }

    @Test
    @DisplayName("Should use default table name when empty string is provided")
    void testConstructorWithEmptyTableNameUsesDefault() throws SQLException {
        when(mockStatement.executeQuery()).thenReturn(mockResultSet);
        when(mockResultSet.next()).thenReturn(false, false);
        when(mockStatement.execute()).thenReturn(true);

        DaMengSession session = new DaMengSession(mockDataSource, "CUSTOM_SCHEMA", "", true);

        assertEquals("CUSTOM_SCHEMA", session.getSchemaName());
        assertEquals("AGENTSCOPE_SESSIONS", session.getTableName());
    }

    @Test
    @DisplayName("Should convert schema and table names to uppercase")
    void testConstructorConvertsNamesToUppercase() throws SQLException {
        when(mockStatement.executeQuery()).thenReturn(mockResultSet);
        when(mockResultSet.next()).thenReturn(false, false);
        when(mockStatement.execute()).thenReturn(true);

        DaMengSession session =
                new DaMengSession(mockDataSource, "lowercase_schema", "lowercase_table", true);

        assertEquals("LOWERCASE_SCHEMA", session.getSchemaName());
        assertEquals("LOWERCASE_TABLE", session.getTableName());
    }

    @Test
    @DisplayName("Should get DataSource correctly")
    void testGetDataSource() throws SQLException {
        when(mockStatement.executeQuery()).thenReturn(mockResultSet);
        when(mockResultSet.next()).thenReturn(false, false);
        when(mockStatement.execute()).thenReturn(true);

        DaMengSession session = new DaMengSession(mockDataSource, true);
        assertEquals(mockDataSource, session.getDataSource());
    }

    @Test
    @DisplayName("Should save and get single state correctly")
    void testSaveAndGetSingleState() throws SQLException {
        when(mockStatement.executeQuery()).thenReturn(mockResultSet);
        when(mockResultSet.next()).thenReturn(false, false, true);
        when(mockStatement.execute()).thenReturn(true);
        when(mockStatement.executeUpdate()).thenReturn(1);
        when(mockResultSet.getString("state_data"))
                .thenReturn("{\"value\":\"test_value\",\"count\":42}");

        DaMengSession session = new DaMengSession(mockDataSource, true);
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
        when(mockStatement.executeQuery()).thenReturn(mockResultSet);
        when(mockResultSet.next()).thenReturn(false, false, false, true, true, true, false);
        when(mockStatement.execute()).thenReturn(true);
        when(mockStatement.executeUpdate()).thenReturn(1);
        when(mockResultSet.getInt("max_index")).thenReturn(0);
        when(mockResultSet.wasNull()).thenReturn(true);
        when(mockResultSet.getString("state_data"))
                .thenReturn("{\"value\":\"value1\",\"count\":1}")
                .thenReturn("{\"value\":\"value2\",\"count\":2}");

        DaMengSession session = new DaMengSession(mockDataSource, true);
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
        when(mockStatement.executeQuery()).thenReturn(mockResultSet);
        when(mockResultSet.next()).thenReturn(false, false, false);
        when(mockStatement.execute()).thenReturn(true);

        DaMengSession session = new DaMengSession(mockDataSource, true);
        SessionKey sessionKey = SimpleSessionKey.of("non_existent");

        Optional<TestState> state = session.get(sessionKey, "testModule", TestState.class);
        assertFalse(state.isPresent());
    }

    @Test
    @DisplayName("Should return empty list for non-existent list state")
    void testGetNonExistentListState() throws SQLException {
        when(mockStatement.executeQuery()).thenReturn(mockResultSet);
        when(mockResultSet.next()).thenReturn(false, false, false);
        when(mockStatement.execute()).thenReturn(true);

        DaMengSession session = new DaMengSession(mockDataSource, true);
        SessionKey sessionKey = SimpleSessionKey.of("non_existent");

        List<TestState> states = session.getList(sessionKey, "testList", TestState.class);
        assertTrue(states.isEmpty());
    }

    @Test
    @DisplayName("Should return true when session exists")
    void testSessionExists() throws SQLException {
        when(mockStatement.executeQuery()).thenReturn(mockResultSet);
        when(mockResultSet.next()).thenReturn(false, false, true);
        when(mockStatement.execute()).thenReturn(true);

        DaMengSession session = new DaMengSession(mockDataSource, true);
        SessionKey sessionKey = SimpleSessionKey.of("session1");

        assertTrue(session.exists(sessionKey));
    }

    @Test
    @DisplayName("Should return false when session does not exist")
    void testSessionDoesNotExist() throws SQLException {
        when(mockStatement.executeQuery()).thenReturn(mockResultSet);
        when(mockResultSet.next()).thenReturn(false, false, false);
        when(mockStatement.execute()).thenReturn(true);

        DaMengSession session = new DaMengSession(mockDataSource, true);
        SessionKey sessionKey = SimpleSessionKey.of("non_existent");

        assertFalse(session.exists(sessionKey));
    }

    @Test
    @DisplayName("Should delete session correctly")
    void testDeleteSession() throws SQLException {
        when(mockStatement.executeQuery()).thenReturn(mockResultSet);
        when(mockResultSet.next()).thenReturn(false, false);
        when(mockStatement.execute()).thenReturn(true);
        when(mockStatement.executeUpdate()).thenReturn(1);

        DaMengSession session = new DaMengSession(mockDataSource, true);
        SessionKey sessionKey = SimpleSessionKey.of("session1");

        session.delete(sessionKey);

        verify(mockStatement).setString(1, "session1");
        verify(mockStatement).executeUpdate();
    }

    @Test
    @DisplayName("Should list all session keys when empty")
    void testListSessionKeysEmpty() throws SQLException {
        when(mockStatement.executeQuery()).thenReturn(mockResultSet);
        when(mockResultSet.next()).thenReturn(false, false, false);
        when(mockStatement.execute()).thenReturn(true);

        DaMengSession session = new DaMengSession(mockDataSource, true);
        Set<SessionKey> sessionKeys = session.listSessionKeys();

        assertTrue(sessionKeys.isEmpty());
    }

    @Test
    @DisplayName("Should list all session keys")
    void testListSessionKeysWithResults() throws SQLException {
        when(mockStatement.executeQuery()).thenReturn(mockResultSet);
        when(mockResultSet.next()).thenReturn(false, false, true, true, false);
        when(mockStatement.execute()).thenReturn(true);
        when(mockResultSet.getString("session_id")).thenReturn("session1", "session2");

        DaMengSession session = new DaMengSession(mockDataSource, true);
        Set<SessionKey> sessionKeys = session.listSessionKeys();

        assertEquals(2, sessionKeys.size());
        assertTrue(sessionKeys.contains(SimpleSessionKey.of("session1")));
        assertTrue(sessionKeys.contains(SimpleSessionKey.of("session2")));
    }

    @Test
    @DisplayName("Should clear all sessions")
    void testClearAllSessions() throws SQLException {
        when(mockStatement.executeQuery()).thenReturn(mockResultSet);
        when(mockResultSet.next()).thenReturn(false, false);
        when(mockStatement.execute()).thenReturn(true);
        when(mockStatement.executeUpdate()).thenReturn(5);

        DaMengSession session = new DaMengSession(mockDataSource, true);
        int deleted = session.clearAllSessions();

        assertEquals(5, deleted);
    }

    @Test
    @DisplayName("Should not close DataSource when closing session")
    void testClose() throws SQLException {
        when(mockStatement.executeQuery()).thenReturn(mockResultSet);
        when(mockResultSet.next()).thenReturn(false, false);
        when(mockStatement.execute()).thenReturn(true);

        DaMengSession session = new DaMengSession(mockDataSource, true);
        session.close();
        assertEquals(mockDataSource, session.getDataSource());
    }

    // ==================== SQL Injection Prevention Tests ====================

    @Test
    @DisplayName("Should reject schema name with semicolon (SQL injection)")
    void testConstructorRejectsSchemaNameWithSemicolon() {
        assertThrows(
                IllegalArgumentException.class,
                () ->
                        new DaMengSession(
                                mockDataSource,
                                "SCHEMA; DROP SCHEMA AGENTSCOPE; --",
                                "TABLE",
                                true),
                "Schema name contains invalid characters");
    }

    @Test
    @DisplayName("Should reject table name with semicolon (SQL injection)")
    void testConstructorRejectsTableNameWithSemicolon() {
        assertThrows(
                IllegalArgumentException.class,
                () ->
                        new DaMengSession(
                                mockDataSource,
                                "VALID_SCHEMA",
                                "TABLE; DROP TABLE USERS; --",
                                true),
                "Table name contains invalid characters");
    }

    @Test
    @DisplayName("Should reject schema name with space")
    void testConstructorRejectsSchemaNameWithSpace() {
        assertThrows(
                IllegalArgumentException.class,
                () -> new DaMengSession(mockDataSource, "SCHEMA NAME", "TABLE", true),
                "Schema name contains invalid characters");
    }

    @Test
    @DisplayName("Should reject table name with space")
    void testConstructorRejectsTableNameWithSpace() {
        assertThrows(
                IllegalArgumentException.class,
                () -> new DaMengSession(mockDataSource, "VALID_SCHEMA", "TABLE NAME", true),
                "Table name contains invalid characters");
    }

    @Test
    @DisplayName("Should reject schema name starting with number")
    void testConstructorRejectsSchemaNameStartingWithNumber() {
        assertThrows(
                IllegalArgumentException.class,
                () -> new DaMengSession(mockDataSource, "123SCHEMA", "TABLE", true),
                "Schema name contains invalid characters");
    }

    @Test
    @DisplayName("Should reject table name starting with number")
    void testConstructorRejectsTableNameStartingWithNumber() {
        assertThrows(
                IllegalArgumentException.class,
                () -> new DaMengSession(mockDataSource, "VALID_SCHEMA", "123TABLE", true),
                "Table name contains invalid characters");
    }

    @Test
    @DisplayName("Should reject schema name exceeding max length")
    void testConstructorRejectsSchemaNameExceedingMaxLength() {
        String longName = "A".repeat(129);
        assertThrows(
                IllegalArgumentException.class,
                () -> new DaMengSession(mockDataSource, longName, "TABLE", true),
                "Schema name cannot exceed 128 characters");
    }

    @Test
    @DisplayName("Should reject table name exceeding max length")
    void testConstructorRejectsTableNameExceedingMaxLength() {
        String longName = "A".repeat(129);
        assertThrows(
                IllegalArgumentException.class,
                () -> new DaMengSession(mockDataSource, "VALID_SCHEMA", longName, true),
                "Table name cannot exceed 128 characters");
    }

    @Test
    @DisplayName("Should accept valid schema and table names")
    void testConstructorAcceptsValidSchemaAndTableNames() throws SQLException {
        when(mockStatement.executeQuery()).thenReturn(mockResultSet);
        when(mockResultSet.next()).thenReturn(false, false);
        when(mockStatement.execute()).thenReturn(true);

        DaMengSession session =
                new DaMengSession(mockDataSource, "MY_SCHEMA_123", "MY_TABLE_456", true);

        assertEquals("MY_SCHEMA_123", session.getSchemaName());
        assertEquals("MY_TABLE_456", session.getTableName());
    }

    @Test
    @DisplayName("Should accept names starting with underscore")
    void testConstructorAcceptsNameStartingWithUnderscore() throws SQLException {
        when(mockStatement.executeQuery()).thenReturn(mockResultSet);
        when(mockResultSet.next()).thenReturn(false, false);
        when(mockStatement.execute()).thenReturn(true);

        DaMengSession session =
                new DaMengSession(mockDataSource, "_PRIVATE_SCHEMA", "_PRIVATE_TABLE", true);

        assertEquals("_PRIVATE_SCHEMA", session.getSchemaName());
        assertEquals("_PRIVATE_TABLE", session.getTableName());
    }

    @Test
    @DisplayName("Should accept max length names")
    void testConstructorAcceptsMaxLengthNames() throws SQLException {
        when(mockStatement.executeQuery()).thenReturn(mockResultSet);
        when(mockResultSet.next()).thenReturn(false, false);
        when(mockStatement.execute()).thenReturn(true);

        String maxLengthName = "A".repeat(128);
        DaMengSession session =
                new DaMengSession(mockDataSource, maxLengthName, maxLengthName, true);

        assertEquals(maxLengthName, session.getSchemaName());
        assertEquals(maxLengthName, session.getTableName());
    }

    /** Simple test state record for testing. */
    public record TestState(String value, int count) implements State {}
}
