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
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;

/**
 * Unit tests for MysqlSession.
 *
 * These tests use mocked DataSource and Connection to verify the behavior
 * of MysqlSession without requiring an actual MySQL database.
 *
 * For integration tests with a real MySQL database, see MysqlSessionIntegrationTest.
 */
public class MysqlSessionTest {

    @Mock private DataSource mockDataSource;

    @Mock private Connection mockConnection;

    @Mock private PreparedStatement mockStatement;

    @Mock private ResultSet mockResultSet;

    @BeforeEach
    void setUp() throws SQLException {
        MockitoAnnotations.openMocks(this);
        when(mockDataSource.getConnection()).thenReturn(mockConnection);
        when(mockConnection.prepareStatement(anyString())).thenReturn(mockStatement);
    }

    @Test
    void testConstructorWithNullDataSource() {
        assertThrows(
                IllegalArgumentException.class,
                () -> new MysqlSession(null),
                "DataSource cannot be null");
    }

    @Test
    void testConstructorWithNullTableName() {
        assertThrows(
                IllegalArgumentException.class,
                () -> new MysqlSession(mockDataSource, null),
                "Table name cannot be null or empty");
    }

    @Test
    void testConstructorWithEmptyTableName() {
        assertThrows(
                IllegalArgumentException.class,
                () -> new MysqlSession(mockDataSource, ""),
                "Table name cannot be null or empty");
    }

    @Test
    void testDefaultTableName() throws SQLException {
        MysqlSession session = new MysqlSession(mockDataSource);
        assertEquals("agentscope_sessions", session.getTableName());
    }

    @Test
    void testCustomTableName() throws SQLException {
        MysqlSession session = new MysqlSession(mockDataSource, "custom_sessions");
        assertEquals("custom_sessions", session.getTableName());
    }

    @Test
    void testCustomDatabaseAndTableName() throws SQLException {
        MysqlSession session =
                new MysqlSession(mockDataSource, "custom_database", "custom_sessions");
        assertEquals("custom_database", session.getDatabaseName());
        assertEquals("custom_sessions", session.getTableName());
    }

    @Test
    void testGetDatabaseNameReturnsDefaultWhenNotSpecified() throws SQLException {
        MysqlSession session = new MysqlSession(mockDataSource);
        assertEquals("agentscope", session.getDatabaseName());
    }

    @Test
    void testGetDataSource() throws SQLException {
        MysqlSession session = new MysqlSession(mockDataSource);
        assertEquals(mockDataSource, session.getDataSource());
    }

    @Test
    void testSessionExistsReturnsTrue() throws SQLException {
        when(mockStatement.executeQuery()).thenReturn(mockResultSet);
        when(mockResultSet.next()).thenReturn(true);

        MysqlSession session = new MysqlSession(mockDataSource);
        assertTrue(session.sessionExists("test_session"));

        verify(mockStatement).setString(1, "test_session");
    }

    @Test
    void testSessionExistsReturnsFalse() throws SQLException {
        when(mockStatement.executeQuery()).thenReturn(mockResultSet);
        when(mockResultSet.next()).thenReturn(false);

        MysqlSession session = new MysqlSession(mockDataSource);
        assertFalse(session.sessionExists("nonexistent_session"));
    }

    @Test
    void testDeleteSessionReturnsTrue() throws SQLException {
        when(mockStatement.executeUpdate()).thenReturn(1);

        MysqlSession session = new MysqlSession(mockDataSource);
        assertTrue(session.deleteSession("test_session"));

        verify(mockStatement).setString(1, "test_session");
    }

    @Test
    void testDeleteSessionReturnsFalse() throws SQLException {
        when(mockStatement.executeUpdate()).thenReturn(0);

        MysqlSession session = new MysqlSession(mockDataSource);
        assertFalse(session.deleteSession("nonexistent_session"));
    }

    @Test
    void testListSessionsEmpty() throws SQLException {
        when(mockStatement.executeQuery()).thenReturn(mockResultSet);
        when(mockResultSet.next()).thenReturn(false);

        MysqlSession session = new MysqlSession(mockDataSource);
        assertTrue(session.listSessions().isEmpty());
    }

    @Test
    void testListSessionsWithResults() throws SQLException {
        when(mockStatement.executeQuery()).thenReturn(mockResultSet);
        when(mockResultSet.next()).thenReturn(true, true, false);
        when(mockResultSet.getString("session_id")).thenReturn("session1", "session2");

        MysqlSession session = new MysqlSession(mockDataSource);
        var sessions = session.listSessions();

        assertEquals(2, sessions.size());
        assertEquals("session1", sessions.get(0));
        assertEquals("session2", sessions.get(1));
    }

    @Test
    void testSaveSessionState() throws SQLException {
        when(mockStatement.executeUpdate()).thenReturn(1);

        MysqlSession session = new MysqlSession(mockDataSource);

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
        when(mockStatement.executeQuery()).thenReturn(mockResultSet);
        when(mockResultSet.next()).thenReturn(false);

        MysqlSession session = new MysqlSession(mockDataSource);

        TestStateModule module = new TestStateModule();
        Map<String, StateModule> stateModules = Map.of("testModule", module);

        // Should not throw when allowNotExist is true
        session.loadSessionState("nonexistent_session", true, stateModules);
    }

    @Test
    void testLoadSessionStateNotExistNotAllowed() throws SQLException {
        when(mockStatement.executeQuery()).thenReturn(mockResultSet);
        when(mockResultSet.next()).thenReturn(false);

        MysqlSession session = new MysqlSession(mockDataSource);

        TestStateModule module = new TestStateModule();
        Map<String, StateModule> stateModules = Map.of("testModule", module);

        assertThrows(
                RuntimeException.class,
                () -> session.loadSessionState("nonexistent_session", false, stateModules),
                "Session not found: nonexistent_session");
    }

    @Test
    void testLoadSessionStateSuccess() throws SQLException {
        when(mockStatement.executeQuery()).thenReturn(mockResultSet);
        when(mockResultSet.next()).thenReturn(true);
        when(mockResultSet.getString("state_data"))
                .thenReturn("{\"testModule\":{\"value\":\"loaded_value\"}}");

        MysqlSession session = new MysqlSession(mockDataSource);

        TestStateModule module = new TestStateModule();
        Map<String, StateModule> stateModules = Map.of("testModule", module);

        session.loadSessionState("test_session", true, stateModules);

        assertEquals("loaded_value", module.getValue());
    }

    @Test
    void testGetSessionInfoNotFound() throws SQLException {
        when(mockStatement.executeQuery()).thenReturn(mockResultSet);
        when(mockResultSet.next()).thenReturn(false);

        MysqlSession session = new MysqlSession(mockDataSource);

        assertThrows(
                RuntimeException.class,
                () -> session.getSessionInfo("nonexistent_session"),
                "Session not found: nonexistent_session");
    }

    @Test
    void testGetSessionInfoSuccess() throws SQLException {
        String stateJson = "{\"component1\":{},\"component2\":{}}";
        Timestamp timestamp = new Timestamp(System.currentTimeMillis());

        when(mockStatement.executeQuery()).thenReturn(mockResultSet);
        when(mockResultSet.next()).thenReturn(true);
        when(mockResultSet.getString("state_data")).thenReturn(stateJson);
        when(mockResultSet.getLong("data_size")).thenReturn((long) stateJson.length());
        when(mockResultSet.getTimestamp("updated_at")).thenReturn(timestamp);

        MysqlSession session = new MysqlSession(mockDataSource);
        SessionInfo info = session.getSessionInfo("test_session");

        assertNotNull(info);
        assertEquals("test_session", info.getSessionId());
        assertEquals(stateJson.length(), info.getSize());
        assertEquals(timestamp.getTime(), info.getLastModified());
        assertEquals(2, info.getComponentCount());
    }

    @Test
    void testClearAllSessions() throws SQLException {
        when(mockStatement.executeUpdate()).thenReturn(5);

        MysqlSession session = new MysqlSession(mockDataSource);
        int deleted = session.clearAllSessions();

        assertEquals(5, deleted);
    }

    @Test
    void testValidateSessionIdNull() throws SQLException {
        MysqlSession session = new MysqlSession(mockDataSource);

        assertThrows(
                IllegalArgumentException.class,
                () -> session.sessionExists(null),
                "Session ID cannot be null or empty");
    }

    @Test
    void testValidateSessionIdEmpty() throws SQLException {
        MysqlSession session = new MysqlSession(mockDataSource);

        assertThrows(
                IllegalArgumentException.class,
                () -> session.sessionExists(""),
                "Session ID cannot be null or empty");
    }

    @Test
    void testValidateSessionIdWithPathSeparator() throws SQLException {
        MysqlSession session = new MysqlSession(mockDataSource);

        assertThrows(
                IllegalArgumentException.class,
                () -> session.sessionExists("path/with/separator"),
                "Session ID cannot contain path separators");
    }

    @Test
    void testClose() throws SQLException {
        MysqlSession session = new MysqlSession(mockDataSource);
        // close() should not throw and should not close the DataSource
        session.close();
        // DataSource should still be accessible
        assertEquals(mockDataSource, session.getDataSource());
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
