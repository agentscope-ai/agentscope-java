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

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.atLeast;
import static org.mockito.Mockito.clearInvocations;
import static org.mockito.Mockito.doNothing;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import io.agentscope.core.session.ListHashUtil;
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
 * Unit tests for PostgresSession.
 *
 * <p>These tests use mocked DataSource and Connection to verify the behavior of PostgresSession
 * without requiring an actual PostgreSQL database.
 */
@DisplayName("PostgresSession Tests")
public class PostgresSessionTest {

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
                () -> new PostgresSession(null),
                "DataSource cannot be null");
    }

    @Test
    @DisplayName("Should throw exception when DataSource is null with createIfNotExist flag")
    void testConstructorWithNullDataSourceAndCreateIfNotExist() {
        assertThrows(
                IllegalArgumentException.class,
                () -> new PostgresSession(null, true),
                "DataSource cannot be null");
    }

    @Test
    @DisplayName("Should create session with createIfNotExist=true")
    void testConstructorWithCreateIfNotExistTrue() throws SQLException {
        when(mockStatement.execute()).thenReturn(true);

        PostgresSession session = new PostgresSession(mockDataSource, true);

        assertEquals("public", session.getSchemaName());
        assertEquals("agentscope_sessions", session.getTableName());
        assertEquals(mockDataSource, session.getDataSource());
    }

    @Test
    @DisplayName("Should throw exception when schema does not exist and createIfNotExist=false")
    void testConstructorWithCreateIfNotExistFalseAndSchemaNotExist() throws SQLException {
        when(mockStatement.executeQuery()).thenReturn(mockResultSet);
        when(mockResultSet.next()).thenReturn(false);

        assertThrows(
                IllegalStateException.class,
                () -> new PostgresSession(mockDataSource, false),
                "Schema does not exist");
    }

    @Test
    @DisplayName("Should throw exception when table does not exist and createIfNotExist=false")
    void testConstructorWithCreateIfNotExistFalseAndTableNotExist() throws SQLException {
        when(mockStatement.executeQuery()).thenReturn(mockResultSet);
        when(mockResultSet.next()).thenReturn(true, false);

        assertThrows(
                IllegalStateException.class,
                () -> new PostgresSession(mockDataSource, false),
                "Table does not exist");
    }

    @Test
    @DisplayName("Should create session when both schema and table exist")
    void testConstructorWithCreateIfNotExistFalseAndBothExist() throws SQLException {
        when(mockStatement.executeQuery()).thenReturn(mockResultSet);
        when(mockResultSet.next()).thenReturn(true, true);

        PostgresSession session = new PostgresSession(mockDataSource, false);

        assertEquals("public", session.getSchemaName());
        assertEquals("agentscope_sessions", session.getTableName());
    }

    @Test
    @DisplayName("Default constructor should require existing schema and table")
    void testDefaultConstructorRequiresExistingSchemaAndTable() throws SQLException {
        when(mockStatement.executeQuery()).thenReturn(mockResultSet);
        when(mockResultSet.next()).thenReturn(true, true);

        PostgresSession session = new PostgresSession(mockDataSource);

        assertEquals("public", session.getSchemaName());
        assertEquals("agentscope_sessions", session.getTableName());
    }

    @Test
    @DisplayName("Should wrap schema creation failures")
    void testConstructorWrapsSchemaCreationFailure() throws SQLException {
        when(mockConnection.prepareStatement(anyString()))
                .thenThrow(new SQLException("create schema failed"));

        RuntimeException exception =
                assertThrows(
                        RuntimeException.class, () -> new PostgresSession(mockDataSource, true));

        assertTrue(exception.getMessage().contains("Failed to create schema"));
    }

    @Test
    @DisplayName("Should wrap table creation failures")
    void testConstructorWrapsTableCreationFailure() throws SQLException {
        when(mockConnection.prepareStatement(anyString()))
                .thenReturn(mockStatement)
                .thenThrow(new SQLException("create table failed"));
        when(mockStatement.execute()).thenReturn(true);

        RuntimeException exception =
                assertThrows(
                        RuntimeException.class, () -> new PostgresSession(mockDataSource, true));

        assertTrue(exception.getMessage().contains("Failed to create session table"));
    }

    @Test
    @DisplayName("Should wrap schema verification query failures")
    void testConstructorWrapsSchemaVerificationFailure() throws SQLException {
        when(mockConnection.prepareStatement(anyString()))
                .thenThrow(new SQLException("schema check failed"));

        RuntimeException exception =
                assertThrows(RuntimeException.class, () -> new PostgresSession(mockDataSource));

        assertTrue(exception.getMessage().contains("Failed to check schema existence"));
    }

    @Test
    @DisplayName("Should wrap table verification query failures")
    void testConstructorWrapsTableVerificationFailure() throws SQLException {
        when(mockConnection.prepareStatement(anyString()))
                .thenReturn(mockStatement)
                .thenThrow(new SQLException("table check failed"));
        when(mockStatement.executeQuery()).thenReturn(mockResultSet);
        when(mockResultSet.next()).thenReturn(true);

        RuntimeException exception =
                assertThrows(RuntimeException.class, () -> new PostgresSession(mockDataSource));

        assertTrue(exception.getMessage().contains("Failed to check table existence"));
    }

    @Test
    @DisplayName("Should create session with custom schema and table name")
    void testConstructorWithCustomSchemaAndTableName() throws SQLException {
        when(mockStatement.execute()).thenReturn(true);

        PostgresSession session =
                new PostgresSession(mockDataSource, "custom_db", "custom_table", true);

        assertEquals("custom_db", session.getSchemaName());
        assertEquals("custom_table", session.getTableName());
    }

    @Test
    @DisplayName("Should use default schema name when null is provided")
    void testConstructorWithNullSchemaNameUsesDefault() throws SQLException {
        when(mockStatement.execute()).thenReturn(true);

        PostgresSession session = new PostgresSession(mockDataSource, null, "custom_table", true);

        assertEquals("public", session.getSchemaName());
        assertEquals("custom_table", session.getTableName());
    }

    @Test
    @DisplayName("Should use default schema name when empty string is provided")
    void testConstructorWithEmptySchemaNameUsesDefault() throws SQLException {
        when(mockStatement.execute()).thenReturn(true);

        PostgresSession session = new PostgresSession(mockDataSource, "  ", "custom_table", true);

        assertEquals("public", session.getSchemaName());
        assertEquals("custom_table", session.getTableName());
    }

    @Test
    @DisplayName("Should use default table name when null is provided")
    void testConstructorWithNullTableNameUsesDefault() throws SQLException {
        when(mockStatement.execute()).thenReturn(true);

        PostgresSession session = new PostgresSession(mockDataSource, "custom_db", null, true);

        assertEquals("custom_db", session.getSchemaName());
        assertEquals("agentscope_sessions", session.getTableName());
    }

    @Test
    @DisplayName("Should use default table name when empty string is provided")
    void testConstructorWithEmptyTableNameUsesDefault() throws SQLException {
        when(mockStatement.execute()).thenReturn(true);

        PostgresSession session = new PostgresSession(mockDataSource, "custom_db", "", true);

        assertEquals("custom_db", session.getSchemaName());
        assertEquals("agentscope_sessions", session.getTableName());
    }

    @Test
    @DisplayName("Should get DataSource correctly")
    void testGetDataSource() throws SQLException {
        when(mockStatement.execute()).thenReturn(true);

        PostgresSession session = new PostgresSession(mockDataSource, true);
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

        PostgresSession session = new PostgresSession(mockDataSource, true);
        clearInvocations(mockConnection);
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
    @DisplayName("Should commit single state save when connection auto-commit is disabled")
    void testSaveSingleStateCommitsWhenAutoCommitDisabled() throws SQLException {
        when(mockConnection.getAutoCommit()).thenReturn(false);
        when(mockStatement.execute()).thenReturn(true);
        when(mockStatement.executeUpdate()).thenReturn(1);

        PostgresSession session = new PostgresSession(mockDataSource, true);
        clearInvocations(mockConnection);
        SessionKey sessionKey = SimpleSessionKey.of("session_auto_commit_off");

        session.save(sessionKey, "testModule", new TestState("test_value", 42));

        verify(mockConnection).commit();
        verify(mockConnection, never()).setAutoCommit(true);
    }

    @Test
    @DisplayName("Should save single state when database metadata lookup fails")
    void testSaveSingleStateWhenMetadataLookupFails() throws SQLException {
        when(mockConnection.getMetaData()).thenThrow(new SQLException("metadata failed"));
        when(mockStatement.execute()).thenReturn(true);
        when(mockStatement.executeUpdate()).thenReturn(1);

        PostgresSession session = new PostgresSession(mockDataSource, true);

        session.save(
                SimpleSessionKey.of("session_metadata_lookup_failed"),
                "testModule",
                new TestState("test_value", 42));

        verify(mockStatement, atLeast(1)).executeUpdate();
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

        PostgresSession session = new PostgresSession(mockDataSource, true);
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
    @DisplayName("Should skip saving empty lists")
    void testSaveEmptyListDoesNothing() throws SQLException {
        when(mockStatement.execute()).thenReturn(true);

        PostgresSession session = new PostgresSession(mockDataSource, true);
        clearInvocations(mockStatement);

        session.save(SimpleSessionKey.of("session_empty_list"), "testList", List.of());

        verify(mockStatement, never()).executeUpdate();
        verify(mockStatement, never()).executeBatch();
    }

    @Test
    @DisplayName("Should skip unchanged list state")
    void testSaveUnchangedListDoesNothing() throws SQLException {
        when(mockStatement.execute()).thenReturn(true);
        when(mockStatement.executeQuery()).thenReturn(mockResultSet);
        List<TestState> states = List.of(new TestState("value1", 1), new TestState("value2", 2));
        when(mockResultSet.next()).thenReturn(true, true);
        when(mockResultSet.getString("state_data")).thenReturn(ListHashUtil.computeHash(states));
        when(mockResultSet.getInt("max_index")).thenReturn(states.size() - 1);
        when(mockResultSet.wasNull()).thenReturn(false);

        PostgresSession session = new PostgresSession(mockDataSource, true);
        clearInvocations(mockStatement);

        session.save(SimpleSessionKey.of("session_unchanged_list"), "testList", states);

        verify(mockStatement, never()).executeUpdate();
        verify(mockStatement, never()).executeBatch();
    }

    @Test
    @DisplayName("Should commit incremental list save when connection auto-commit is disabled")
    void testSaveListIncrementalAppendCommitsWhenAutoCommitDisabled() throws SQLException {
        when(mockConnection.getAutoCommit()).thenReturn(false);
        when(mockStatement.execute()).thenReturn(true);
        when(mockStatement.executeQuery()).thenReturn(mockResultSet);
        when(mockResultSet.next()).thenReturn(false, true);
        when(mockResultSet.getInt("max_index")).thenReturn(0);
        when(mockResultSet.wasNull()).thenReturn(true);

        PostgresSession session = new PostgresSession(mockDataSource, true);
        clearInvocations(mockConnection);
        SessionKey sessionKey = SimpleSessionKey.of("session_list_auto_commit_off");
        List<TestState> states = List.of(new TestState("value1", 1), new TestState("value2", 2));

        session.save(sessionKey, "testList", states);

        verify(mockConnection).commit();
        verify(mockConnection, never()).setAutoCommit(true);
    }

    @Test
    @DisplayName("Should save list when count query returns no rows")
    void testSaveListWhenCountQueryReturnsNoRows() throws SQLException {
        when(mockStatement.execute()).thenReturn(true);
        when(mockStatement.executeQuery()).thenReturn(mockResultSet);
        when(mockStatement.executeUpdate()).thenReturn(1);
        when(mockResultSet.next()).thenReturn(false, false);

        PostgresSession session = new PostgresSession(mockDataSource, true);
        List<TestState> states = List.of(new TestState("value1", 1));

        session.save(SimpleSessionKey.of("session_list_no_count_row"), "testList", states);

        verify(mockStatement, atLeast(1)).executeBatch();
    }

    @Test
    @DisplayName(
            "Should not force auto-commit true after full rewrite when connection starts disabled")
    void testSaveListFullRewriteRestoresOriginalAutoCommitState() throws SQLException {
        when(mockConnection.getAutoCommit()).thenReturn(false);
        when(mockStatement.execute()).thenReturn(true);
        when(mockStatement.executeQuery()).thenReturn(mockResultSet);
        when(mockResultSet.next()).thenReturn(true, true);
        when(mockResultSet.getString("state_data")).thenReturn("stale_hash");
        when(mockResultSet.getInt("max_index")).thenReturn(0);
        when(mockResultSet.wasNull()).thenReturn(false);
        when(mockStatement.executeUpdate()).thenReturn(1);

        PostgresSession session = new PostgresSession(mockDataSource, true);
        clearInvocations(mockConnection);
        SessionKey sessionKey = SimpleSessionKey.of("session_full_rewrite_auto_commit_off");
        List<TestState> states = List.of(new TestState("value1", 1));

        session.save(sessionKey, "testList", states);

        verify(mockConnection).commit();
        verify(mockConnection, never()).setAutoCommit(true);
    }

    @Test
    @DisplayName("Should return empty for non-existent state")
    void testGetNonExistentState() throws SQLException {
        when(mockStatement.execute()).thenReturn(true);
        when(mockStatement.executeQuery()).thenReturn(mockResultSet);
        when(mockResultSet.next()).thenReturn(false);

        PostgresSession session = new PostgresSession(mockDataSource, true);
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

        PostgresSession session = new PostgresSession(mockDataSource, true);
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

        PostgresSession session = new PostgresSession(mockDataSource, true);
        clearInvocations(mockConnection);
        SessionKey sessionKey = SimpleSessionKey.of("session1");

        assertTrue(session.exists(sessionKey));
    }

    @Test
    @DisplayName("Should return false when session does not exist")
    void testSessionDoesNotExist() throws SQLException {
        when(mockStatement.execute()).thenReturn(true);
        when(mockStatement.executeQuery()).thenReturn(mockResultSet);
        when(mockResultSet.next()).thenReturn(false);

        PostgresSession session = new PostgresSession(mockDataSource, true);
        SessionKey sessionKey = SimpleSessionKey.of("non_existent");

        assertFalse(session.exists(sessionKey));
    }

    @Test
    @DisplayName("Should delete session correctly")
    void testDeleteSession() throws SQLException {
        when(mockStatement.execute()).thenReturn(true);
        when(mockStatement.executeUpdate()).thenReturn(1);

        PostgresSession session = new PostgresSession(mockDataSource, true);
        clearInvocations(mockConnection);
        SessionKey sessionKey = SimpleSessionKey.of("session1");

        session.delete(sessionKey);

        verify(mockStatement).setString(1, "session1");
        verify(mockStatement).executeUpdate();
    }

    @Test
    @DisplayName("Should commit delete when connection auto-commit is disabled")
    void testDeleteSessionCommitsWhenAutoCommitDisabled() throws SQLException {
        when(mockConnection.getAutoCommit()).thenReturn(false);
        when(mockStatement.execute()).thenReturn(true);
        when(mockStatement.executeUpdate()).thenReturn(1);

        PostgresSession session = new PostgresSession(mockDataSource, true);
        clearInvocations(mockConnection);
        SessionKey sessionKey = SimpleSessionKey.of("session1");

        session.delete(sessionKey);

        verify(mockConnection).commit();
        verify(mockConnection, never()).setAutoCommit(true);
    }

    @Test
    @DisplayName("Should list all session keys when empty")
    void testListSessionKeysEmpty() throws SQLException {
        when(mockStatement.execute()).thenReturn(true);
        when(mockStatement.executeQuery()).thenReturn(mockResultSet);
        when(mockResultSet.next()).thenReturn(false);

        PostgresSession session = new PostgresSession(mockDataSource, true);
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

        PostgresSession session = new PostgresSession(mockDataSource, true);
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

        PostgresSession session = new PostgresSession(mockDataSource, true);
        clearInvocations(mockConnection);
        int deleted = session.clearAllSessions();

        assertEquals(5, deleted);
    }

    @Test
    @DisplayName("Should commit clearAllSessions when connection auto-commit is disabled")
    void testClearAllSessionsCommitsWhenAutoCommitDisabled() throws SQLException {
        when(mockConnection.getAutoCommit()).thenReturn(false);
        when(mockStatement.execute()).thenReturn(true);
        when(mockStatement.executeUpdate()).thenReturn(5);

        PostgresSession session = new PostgresSession(mockDataSource, true);
        clearInvocations(mockConnection);
        int deleted = session.clearAllSessions();

        assertEquals(5, deleted);
        verify(mockConnection).commit();
        verify(mockConnection, never()).setAutoCommit(true);
    }

    @Test
    @DisplayName("Should truncate session table")
    void testTruncateAllSessions() throws SQLException {
        when(mockStatement.execute()).thenReturn(true);
        when(mockStatement.executeUpdate()).thenReturn(0);

        PostgresSession session = new PostgresSession(mockDataSource, true);
        clearInvocations(mockConnection);
        int success = session.truncateAllSessions();

        assertEquals(0, success);
    }

    @Test
    @DisplayName("Should commit truncateAllSessions when connection auto-commit is disabled")
    void testTruncateAllSessionsCommitsWhenAutoCommitDisabled() throws SQLException {
        when(mockConnection.getAutoCommit()).thenReturn(false);
        when(mockStatement.execute()).thenReturn(true);
        when(mockStatement.executeUpdate()).thenReturn(0);

        PostgresSession session = new PostgresSession(mockDataSource, true);
        clearInvocations(mockConnection);
        int success = session.truncateAllSessions();

        assertEquals(0, success);
        verify(mockConnection).commit();
        verify(mockConnection, never()).setAutoCommit(true);
    }

    @Test
    @DisplayName("Should not close DataSource when closing session")
    void testClose() throws SQLException {
        when(mockStatement.execute()).thenReturn(true);

        PostgresSession session = new PostgresSession(mockDataSource, true);
        session.close();
        assertEquals(mockDataSource, session.getDataSource());
    }

    @Test
    @DisplayName("Should reject invalid session identifiers")
    void testRejectsInvalidSessionIdentifiers() throws SQLException {
        when(mockStatement.execute()).thenReturn(true);

        PostgresSession session = new PostgresSession(mockDataSource, true);
        TestState state = new TestState("value", 1);

        assertThrows(
                IllegalArgumentException.class,
                () ->
                        session.save(
                                new SessionKey() {
                                    @Override
                                    public String toIdentifier() {
                                        return null;
                                    }
                                },
                                "module",
                                state));
        assertThrows(
                IllegalArgumentException.class,
                () ->
                        session.save(
                                new SessionKey() {
                                    @Override
                                    public String toIdentifier() {
                                        return "   ";
                                    }
                                },
                                "module",
                                state));
        assertThrows(
                IllegalArgumentException.class,
                () -> session.save(SimpleSessionKey.of("bad/path"), "module", state));
        assertThrows(
                IllegalArgumentException.class,
                () -> session.save(SimpleSessionKey.of("bad\\path"), "module", state));
        assertThrows(
                IllegalArgumentException.class,
                () -> session.save(SimpleSessionKey.of("a".repeat(256)), "module", state));
    }

    @Test
    @DisplayName("Should reject invalid state keys")
    void testRejectsInvalidStateKeys() throws SQLException {
        when(mockStatement.execute()).thenReturn(true);

        PostgresSession session = new PostgresSession(mockDataSource, true);
        SessionKey sessionKey = SimpleSessionKey.of("session_invalid_state_key");
        TestState state = new TestState("value", 1);

        assertThrows(IllegalArgumentException.class, () -> session.save(sessionKey, null, state));
        assertThrows(IllegalArgumentException.class, () -> session.save(sessionKey, "   ", state));
        assertThrows(
                IllegalArgumentException.class,
                () -> session.save(sessionKey, "a".repeat(256), state));
    }

    @Test
    @DisplayName("Should wrap save failures and include rollback failures as suppressed")
    void testSaveWrapsWriteFailureAndSuppressedRollbackFailure() throws SQLException {
        when(mockStatement.execute()).thenReturn(true);
        when(mockStatement.executeUpdate()).thenThrow(new SQLException("write failed"));
        doThrow(new SQLException("rollback failed")).when(mockConnection).rollback();

        PostgresSession session = new PostgresSession(mockDataSource, true);

        RuntimeException exception =
                assertThrows(
                        RuntimeException.class,
                        () ->
                                session.save(
                                        SimpleSessionKey.of("session_save_failure"),
                                        "module",
                                        new TestState("value", 1)));

        assertTrue(exception.getMessage().contains("Failed to save state"));
        assertEquals(1, exception.getCause().getSuppressed().length);
    }

    @Test
    @DisplayName("Should wrap list save failures")
    void testSaveListWrapsQueryFailure() throws SQLException {
        when(mockStatement.execute()).thenReturn(true);
        when(mockStatement.executeQuery()).thenThrow(new SQLException("hash read failed"));

        PostgresSession session = new PostgresSession(mockDataSource, true);

        RuntimeException exception =
                assertThrows(
                        RuntimeException.class,
                        () ->
                                session.save(
                                        SimpleSessionKey.of("session_list_failure"),
                                        "testList",
                                        List.of(new TestState("value", 1))));

        assertTrue(exception.getMessage().contains("Failed to save list"));
    }

    @Test
    @DisplayName("Should wrap list save when hash cursor fails")
    void testSaveListWrapsHashCursorFailure() throws SQLException {
        when(mockStatement.execute()).thenReturn(true);
        when(mockStatement.executeQuery()).thenReturn(mockResultSet);
        when(mockResultSet.next()).thenThrow(new SQLException("hash cursor failed"));

        PostgresSession session = new PostgresSession(mockDataSource, true);

        RuntimeException exception =
                assertThrows(
                        RuntimeException.class,
                        () ->
                                session.save(
                                        SimpleSessionKey.of("session_hash_cursor_failure"),
                                        "testList",
                                        List.of(new TestState("value", 1))));

        assertTrue(exception.getMessage().contains("Failed to save list"));
    }

    @Test
    @DisplayName("Should wrap list save when hash result close fails")
    void testSaveListWrapsHashResultCloseFailure() throws SQLException {
        when(mockStatement.execute()).thenReturn(true);
        when(mockStatement.executeQuery()).thenReturn(mockResultSet);
        when(mockResultSet.next()).thenReturn(false);

        PostgresSession session = new PostgresSession(mockDataSource, true);
        doThrow(new SQLException("hash result close failed")).when(mockResultSet).close();

        RuntimeException exception =
                assertThrows(
                        RuntimeException.class,
                        () ->
                                session.save(
                                        SimpleSessionKey.of("session_hash_result_close_failure"),
                                        "testList",
                                        List.of(new TestState("value", 1))));

        assertTrue(exception.getMessage().contains("Failed to save list"));
    }

    @Test
    @DisplayName("Should wrap list save when hash statement close fails")
    void testSaveListWrapsHashStatementCloseFailure() throws SQLException {
        when(mockStatement.execute()).thenReturn(true);
        when(mockStatement.executeQuery()).thenReturn(mockResultSet);
        when(mockResultSet.next()).thenReturn(false);

        PostgresSession session = new PostgresSession(mockDataSource, true);
        doThrow(new SQLException("hash statement close failed")).when(mockStatement).close();

        RuntimeException exception =
                assertThrows(
                        RuntimeException.class,
                        () ->
                                session.save(
                                        SimpleSessionKey.of("session_hash_statement_close_failure"),
                                        "testList",
                                        List.of(new TestState("value", 1))));

        assertTrue(exception.getMessage().contains("Failed to save list"));
    }

    @Test
    @DisplayName("Should wrap list save when count result close fails")
    void testSaveListWrapsCountResultCloseFailure() throws SQLException {
        when(mockStatement.execute()).thenReturn(true);
        when(mockStatement.executeQuery()).thenReturn(mockResultSet);
        when(mockResultSet.next()).thenReturn(false, true);
        when(mockResultSet.getInt("max_index")).thenReturn(0);
        when(mockResultSet.wasNull()).thenReturn(true);

        PostgresSession session = new PostgresSession(mockDataSource, true);
        doNothing()
                .doThrow(new SQLException("count result close failed"))
                .when(mockResultSet)
                .close();

        RuntimeException exception =
                assertThrows(
                        RuntimeException.class,
                        () ->
                                session.save(
                                        SimpleSessionKey.of("session_count_result_close_failure"),
                                        "testList",
                                        List.of(new TestState("value", 1))));

        assertTrue(exception.getMessage().contains("Failed to save list"));
    }

    @Test
    @DisplayName("Should wrap list save when count cursor fails")
    void testSaveListWrapsCountCursorFailure() throws SQLException {
        when(mockStatement.execute()).thenReturn(true);
        PreparedStatement hashStatement = mock(PreparedStatement.class);
        PreparedStatement countStatement = mock(PreparedStatement.class);
        ResultSet hashResultSet = mock(ResultSet.class);
        ResultSet countResultSet = mock(ResultSet.class);
        when(hashStatement.executeQuery()).thenReturn(hashResultSet);
        when(hashResultSet.next()).thenReturn(false);
        when(countStatement.executeQuery()).thenReturn(countResultSet);
        when(countResultSet.next()).thenThrow(new SQLException("count cursor failed"));

        PostgresSession session = new PostgresSession(mockDataSource, true);
        when(mockConnection.prepareStatement(anyString()))
                .thenReturn(hashStatement, countStatement);

        RuntimeException exception =
                assertThrows(
                        RuntimeException.class,
                        () ->
                                session.save(
                                        SimpleSessionKey.of("session_count_cursor_failure"),
                                        "testList",
                                        List.of(new TestState("value", 1))));

        assertTrue(exception.getMessage().contains("Failed to save list"));
    }

    @Test
    @DisplayName("Should wrap list save when count statement close fails")
    void testSaveListWrapsCountStatementCloseFailure() throws SQLException {
        when(mockStatement.execute()).thenReturn(true);
        when(mockStatement.executeQuery()).thenReturn(mockResultSet);
        when(mockResultSet.next()).thenReturn(false, true);
        when(mockResultSet.getInt("max_index")).thenReturn(0);
        when(mockResultSet.wasNull()).thenReturn(true);

        PostgresSession session = new PostgresSession(mockDataSource, true);
        doNothing()
                .doThrow(new SQLException("count statement close failed"))
                .when(mockStatement)
                .close();

        RuntimeException exception =
                assertThrows(
                        RuntimeException.class,
                        () ->
                                session.save(
                                        SimpleSessionKey.of(
                                                "session_count_statement_close_failure"),
                                        "testList",
                                        List.of(new TestState("value", 1))));

        assertTrue(exception.getMessage().contains("Failed to save list"));
    }

    @Test
    @DisplayName("Should wrap single state read failures")
    void testGetWrapsQueryFailure() throws SQLException {
        when(mockStatement.execute()).thenReturn(true);
        when(mockStatement.executeQuery()).thenThrow(new SQLException("read failed"));

        PostgresSession session = new PostgresSession(mockDataSource, true);

        RuntimeException exception =
                assertThrows(
                        RuntimeException.class,
                        () ->
                                session.get(
                                        SimpleSessionKey.of("session_get_failure"),
                                        "module",
                                        TestState.class));

        assertTrue(exception.getMessage().contains("Failed to get state"));
    }

    @Test
    @DisplayName("Should wrap single state read when cursor fails")
    void testGetWrapsCursorFailure() throws SQLException {
        when(mockStatement.execute()).thenReturn(true);
        when(mockStatement.executeQuery()).thenReturn(mockResultSet);
        when(mockResultSet.next()).thenThrow(new SQLException("read cursor failed"));

        PostgresSession session = new PostgresSession(mockDataSource, true);

        RuntimeException exception =
                assertThrows(
                        RuntimeException.class,
                        () ->
                                session.get(
                                        SimpleSessionKey.of("session_get_cursor_failure"),
                                        "module",
                                        TestState.class));

        assertTrue(exception.getMessage().contains("Failed to get state"));
    }

    @Test
    @DisplayName("Should wrap single state read when result close fails")
    void testGetWrapsResultCloseFailure() throws SQLException {
        when(mockStatement.execute()).thenReturn(true);
        when(mockStatement.executeQuery()).thenReturn(mockResultSet);
        when(mockResultSet.next()).thenReturn(false);

        PostgresSession session = new PostgresSession(mockDataSource, true);
        doThrow(new SQLException("read result close failed")).when(mockResultSet).close();

        RuntimeException exception =
                assertThrows(
                        RuntimeException.class,
                        () ->
                                session.get(
                                        SimpleSessionKey.of("session_get_result_close_failure"),
                                        "module",
                                        TestState.class));

        assertTrue(exception.getMessage().contains("Failed to get state"));
    }

    @Test
    @DisplayName("Should wrap single state read when statement close fails")
    void testGetWrapsStatementCloseFailure() throws SQLException {
        when(mockStatement.execute()).thenReturn(true);
        when(mockStatement.executeQuery()).thenReturn(mockResultSet);
        when(mockResultSet.next()).thenReturn(false);

        PostgresSession session = new PostgresSession(mockDataSource, true);
        doThrow(new SQLException("read statement close failed")).when(mockStatement).close();

        RuntimeException exception =
                assertThrows(
                        RuntimeException.class,
                        () ->
                                session.get(
                                        SimpleSessionKey.of("session_get_statement_close_failure"),
                                        "module",
                                        TestState.class));

        assertTrue(exception.getMessage().contains("Failed to get state"));
    }

    @Test
    @DisplayName("Should wrap list read failures")
    void testGetListWrapsQueryFailure() throws SQLException {
        when(mockStatement.execute()).thenReturn(true);
        when(mockStatement.executeQuery()).thenThrow(new SQLException("read list failed"));

        PostgresSession session = new PostgresSession(mockDataSource, true);

        RuntimeException exception =
                assertThrows(
                        RuntimeException.class,
                        () ->
                                session.getList(
                                        SimpleSessionKey.of("session_get_list_failure"),
                                        "testList",
                                        TestState.class));

        assertTrue(exception.getMessage().contains("Failed to get list"));
    }

    @Test
    @DisplayName("Should wrap exists query failures")
    void testExistsWrapsQueryFailure() throws SQLException {
        when(mockStatement.execute()).thenReturn(true);
        when(mockStatement.executeQuery()).thenThrow(new SQLException("exists failed"));

        PostgresSession session = new PostgresSession(mockDataSource, true);

        RuntimeException exception =
                assertThrows(
                        RuntimeException.class,
                        () -> session.exists(SimpleSessionKey.of("session_exists_failure")));

        assertTrue(exception.getMessage().contains("Failed to check session existence"));
    }

    @Test
    @DisplayName("Should wrap delete failures")
    void testDeleteWrapsWriteFailure() throws SQLException {
        when(mockStatement.execute()).thenReturn(true);
        when(mockStatement.executeUpdate()).thenThrow(new SQLException("delete failed"));

        PostgresSession session = new PostgresSession(mockDataSource, true);

        RuntimeException exception =
                assertThrows(
                        RuntimeException.class,
                        () -> session.delete(SimpleSessionKey.of("session_delete_failure")));

        assertTrue(exception.getMessage().contains("Failed to delete session"));
    }

    @Test
    @DisplayName("Should wrap session listing failures")
    void testListSessionKeysWrapsQueryFailure() throws SQLException {
        when(mockStatement.execute()).thenReturn(true);
        when(mockStatement.executeQuery()).thenThrow(new SQLException("list failed"));

        PostgresSession session = new PostgresSession(mockDataSource, true);

        RuntimeException exception = assertThrows(RuntimeException.class, session::listSessionKeys);

        assertTrue(exception.getMessage().contains("Failed to list sessions"));
    }

    @Test
    @DisplayName("Should wrap clear failures")
    void testClearAllSessionsWrapsWriteFailure() throws SQLException {
        when(mockStatement.execute()).thenReturn(true);
        when(mockStatement.executeUpdate()).thenThrow(new SQLException("clear failed"));

        PostgresSession session = new PostgresSession(mockDataSource, true);

        RuntimeException exception =
                assertThrows(RuntimeException.class, session::clearAllSessions);

        assertTrue(exception.getMessage().contains("Failed to clear sessions"));
    }

    @Test
    @DisplayName("Should wrap truncate failures")
    void testTruncateAllSessionsWrapsWriteFailure() throws SQLException {
        when(mockStatement.execute()).thenReturn(true);
        when(mockStatement.executeUpdate()).thenThrow(new SQLException("truncate failed"));

        PostgresSession session = new PostgresSession(mockDataSource, true);

        RuntimeException exception =
                assertThrows(RuntimeException.class, session::truncateAllSessions);

        assertTrue(exception.getMessage().contains("Failed to truncate sessions"));
    }

    // ==================== SQL Injection Prevention Tests ====================

    @Test
    @DisplayName("Should reject schema name with semicolon (SQL injection)")
    void testConstructorRejectsSchemaNameWithSemicolon() {
        assertThrows(
                IllegalArgumentException.class,
                () ->
                        new PostgresSession(
                                mockDataSource, "db; DROP SCHEMA public; --", "table", true),
                "Schema name contains invalid characters");
    }

    @Test
    @DisplayName("Should reject table name with semicolon (SQL injection)")
    void testConstructorRejectsTableNameWithSemicolon() {
        assertThrows(
                IllegalArgumentException.class,
                () ->
                        new PostgresSession(
                                mockDataSource, "valid_db", "table; DROP TABLE users; --", true),
                "Table name contains invalid characters");
    }

    @Test
    @DisplayName("Should reject schema name with space")
    void testConstructorRejectsSchemaNameWithSpace() {
        assertThrows(
                IllegalArgumentException.class,
                () -> new PostgresSession(mockDataSource, "db name", "table", true),
                "Schema name contains invalid characters");
    }

    @Test
    @DisplayName("Should reject table name with space")
    void testConstructorRejectsTableNameWithSpace() {
        assertThrows(
                IllegalArgumentException.class,
                () -> new PostgresSession(mockDataSource, "valid_db", "table name", true),
                "Table name contains invalid characters");
    }

    @Test
    @DisplayName("Should reject schema name starting with number")
    void testConstructorRejectsSchemaNameStartingWithNumber() {
        assertThrows(
                IllegalArgumentException.class,
                () -> new PostgresSession(mockDataSource, "123db", "table", true),
                "Schema name contains invalid characters");
    }

    @Test
    @DisplayName("Should reject table name starting with number")
    void testConstructorRejectsTableNameStartingWithNumber() {
        assertThrows(
                IllegalArgumentException.class,
                () -> new PostgresSession(mockDataSource, "valid_db", "123table", true),
                "Table name contains invalid characters");
    }

    @Test
    @DisplayName("Should reject schema name exceeding max length")
    void testConstructorRejectsSchemaNameExceedingMaxLength() {
        String longName = "a".repeat(64);
        assertThrows(
                IllegalArgumentException.class,
                () -> new PostgresSession(mockDataSource, longName, "table", true),
                "Schema name cannot exceed 63 characters");
    }

    @Test
    @DisplayName("Should reject table name exceeding max length")
    void testConstructorRejectsTableNameExceedingMaxLength() {
        String longName = "a".repeat(64);
        assertThrows(
                IllegalArgumentException.class,
                () -> new PostgresSession(mockDataSource, "valid_db", longName, true),
                "Table name cannot exceed 63 characters");
    }

    @Test
    @DisplayName("Should accept valid schema and table names")
    void testConstructorAcceptsValidSchemaAndTableNames() throws SQLException {
        when(mockStatement.execute()).thenReturn(true);

        PostgresSession session =
                new PostgresSession(mockDataSource, "my_schema_123", "my_table_456", true);

        assertEquals("my_schema_123", session.getSchemaName());
        assertEquals("my_table_456", session.getTableName());
    }

    @Test
    @DisplayName("Should accept names starting with underscore")
    void testConstructorAcceptsNameStartingWithUnderscore() throws SQLException {
        when(mockStatement.execute()).thenReturn(true);

        PostgresSession session =
                new PostgresSession(mockDataSource, "_private_db", "_private_table", true);

        assertEquals("_private_db", session.getSchemaName());
        assertEquals("_private_table", session.getTableName());
    }

    @Test
    @DisplayName("Should accept max length names")
    void testConstructorAcceptsMaxLengthNames() throws SQLException {
        when(mockStatement.execute()).thenReturn(true);

        String maxLengthName = "a".repeat(63);
        PostgresSession session =
                new PostgresSession(mockDataSource, maxLengthName, maxLengthName, true);

        assertEquals(maxLengthName, session.getSchemaName());
        assertEquals(maxLengthName, session.getTableName());
    }

    @Test
    @DisplayName("Should reject null and empty identifiers")
    void testRejectsNullAndEmptyIdentifiers() throws Exception {
        when(mockStatement.execute()).thenReturn(true);
        PostgresSession session = new PostgresSession(mockDataSource, true);
        java.lang.reflect.Method validateIdentifier =
                PostgresSession.class.getDeclaredMethod(
                        "validateIdentifier", String.class, String.class);
        validateIdentifier.setAccessible(true);

        java.lang.reflect.InvocationTargetException nullException =
                assertThrows(
                        java.lang.reflect.InvocationTargetException.class,
                        () -> validateIdentifier.invoke(session, null, "Identifier"));
        java.lang.reflect.InvocationTargetException emptyException =
                assertThrows(
                        java.lang.reflect.InvocationTargetException.class,
                        () -> validateIdentifier.invoke(session, "", "Identifier"));

        assertTrue(
                nullException
                        .getCause()
                        .getMessage()
                        .contains("Identifier cannot be null or empty"));
        assertTrue(
                emptyException
                        .getCause()
                        .getMessage()
                        .contains("Identifier cannot be null or empty"));
    }

    @Test
    @DisplayName("Should accept schema name with hyphens")
    void testConstructorAcceptsSchemaNameWithHyphens() throws SQLException {
        when(mockStatement.execute()).thenReturn(true);

        PostgresSession session =
                new PostgresSession(mockDataSource, "my-test-schema", "my_table", true);

        assertEquals("my-test-schema", session.getSchemaName());
        assertEquals("my_table", session.getTableName());
    }

    @Test
    @DisplayName("Should accept table name with hyphens")
    void testConstructorAcceptsTableNameWithHyphens() throws SQLException {
        when(mockStatement.execute()).thenReturn(true);

        PostgresSession session =
                new PostgresSession(mockDataSource, "my_db", "my-test-table", true);

        assertEquals("my_db", session.getSchemaName());
        assertEquals("my-test-table", session.getTableName());
    }

    @Test
    @DisplayName("Should accept schema and table names with hyphens")
    void testConstructorAcceptsSchemaAndTableNamesWithHyphens() throws SQLException {
        when(mockStatement.execute()).thenReturn(true);

        PostgresSession session =
                new PostgresSession(mockDataSource, "xxx-xxx-xx", "test-table", true);

        assertEquals("xxx-xxx-xx", session.getSchemaName());
        assertEquals("test-table", session.getTableName());
    }

    @Test
    @DisplayName("Should accept name with underscore and hyphen")
    void testConstructorAcceptsNameWithUnderscoreAndHyphen() throws SQLException {
        when(mockStatement.execute()).thenReturn(true);

        PostgresSession session =
                new PostgresSession(mockDataSource, "my_test-db", "my_table-test", true);

        assertEquals("my_test-db", session.getSchemaName());
        assertEquals("my_table-test", session.getTableName());
    }

    /** Simple test state record for testing. */
    public record TestState(String value, int count) implements State {}
}
