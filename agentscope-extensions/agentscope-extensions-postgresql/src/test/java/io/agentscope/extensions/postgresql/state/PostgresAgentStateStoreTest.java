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
package io.agentscope.extensions.postgresql.state;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.reset;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import io.agentscope.core.state.State;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import javax.sql.DataSource;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;

class PostgresAgentStateStoreTest {

    record TestState(String value) implements State {}

    @Mock private DataSource dataSource;
    @Mock private Connection connection;
    @Mock private Statement statement;
    @Mock private PreparedStatement preparedStatement;
    @Mock private ResultSet resultSet;

    private AutoCloseable mocks;

    @BeforeEach
    void setUp() throws SQLException {
        mocks = MockitoAnnotations.openMocks(this);
        when(dataSource.getConnection()).thenReturn(connection);
        when(connection.createStatement()).thenReturn(statement);
        when(connection.prepareStatement(anyString())).thenReturn(preparedStatement);
        when(connection.prepareStatement(anyString(), anyInt())).thenReturn(preparedStatement);
        when(preparedStatement.executeQuery()).thenReturn(resultSet);
        when(preparedStatement.execute()).thenReturn(true);
        when(preparedStatement.executeUpdate()).thenReturn(1);
        when(resultSet.next()).thenReturn(true, true);
    }

    @AfterEach
    void tearDown() throws Exception {
        reset(dataSource, connection, statement, preparedStatement, resultSet);
        if (mocks != null) {
            mocks.close();
        }
    }

    @Test
    void builderRejectsNullDataSource() {
        assertThrows(IllegalArgumentException.class, () -> PostgresAgentStateStore.builder(null));
    }

    @Test
    void builderWithDefaultsCreatesStore() {
        PostgresAgentStateStore store = PostgresAgentStateStore.builder(dataSource).build();
        assertNotNull(store);
        assertEquals("agentscope", store.getSchemaName());
        assertEquals("agentscope_sessions", store.getTableName());
        assertEquals(dataSource, store.getDataSource());
    }

    @Test
    void builderWithCustomNames() {
        PostgresAgentStateStore store =
                PostgresAgentStateStore.builder(dataSource)
                        .schemaName("custom_schema")
                        .tableName("custom_sessions")
                        .createIfNotExist(false)
                        .build();
        assertEquals("custom_schema", store.getSchemaName());
        assertEquals("custom_sessions", store.getTableName());
    }

    @Test
    void builderNullNamesFallbackToDefaults() {
        PostgresAgentStateStore store =
                PostgresAgentStateStore.builder(dataSource)
                        .schemaName(null)
                        .tableName(null)
                        .build();
        assertEquals("agentscope", store.getSchemaName());
        assertEquals("agentscope_sessions", store.getTableName());
    }

    @Test
    void builderBlankNamesFallbackToDefaults() {
        PostgresAgentStateStore store =
                PostgresAgentStateStore.builder(dataSource).schemaName("  ").tableName("").build();
        assertEquals("agentscope", store.getSchemaName());
        assertEquals("agentscope_sessions", store.getTableName());
    }

    @Test
    void constructorDefaultsDoNotAutoCreate() throws SQLException {
        PostgresAgentStateStore store = new PostgresAgentStateStore(dataSource);
        assertNotNull(store);
        verify(connection, never()).createStatement();
    }

    @Test
    void constructorWithAutoCreate() throws SQLException {
        PostgresAgentStateStore store = new PostgresAgentStateStore(dataSource, true);
        assertNotNull(store);
        verify(preparedStatement, atLeastOnce()).execute();
    }

    @Test
    void rejectsNullDataSourceInConstructor() {
        assertThrows(IllegalArgumentException.class, () -> new PostgresAgentStateStore(null, true));
    }

    @Test
    void rejectsInvalidSchemaName() {
        assertThrows(
                IllegalArgumentException.class,
                () -> PostgresAgentStateStore.builder(dataSource).schemaName("bad-schema").build());
    }

    @Test
    void rejectsTooLongIdentifier() {
        String longName = "a".repeat(64);
        assertThrows(
                IllegalArgumentException.class,
                () -> PostgresAgentStateStore.builder(dataSource).schemaName(longName).build());
    }

    @Test
    void rejectsInvalidTableName() {
        assertThrows(
                IllegalArgumentException.class,
                () -> PostgresAgentStateStore.builder(dataSource).tableName("bad;table").build());
    }

    @Test
    void createSchemaFailureThrows() throws SQLException {
        when(connection.prepareStatement(anyString())).thenReturn(preparedStatement);
        doThrow(new SQLException("boom")).when(preparedStatement).execute();
        assertThrows(
                RuntimeException.class,
                () -> PostgresAgentStateStore.builder(dataSource).createIfNotExist(true).build());
    }

    @Test
    void createTableFailureThrows() throws SQLException {
        when(connection.prepareStatement(anyString()))
                .thenReturn(preparedStatement)
                .thenThrow(new SQLException("boom"));
        assertThrows(
                RuntimeException.class,
                () -> PostgresAgentStateStore.builder(dataSource).createIfNotExist(true).build());
    }

    @Test
    void verifySchemaMissingThrows() throws SQLException {
        when(resultSet.next()).thenReturn(false);
        assertThrows(
                IllegalStateException.class,
                () -> PostgresAgentStateStore.builder(dataSource).createIfNotExist(false).build());
    }

    @Test
    void verifyTableMissingThrows() throws SQLException {
        when(resultSet.next()).thenReturn(true, false);
        assertThrows(
                IllegalStateException.class,
                () -> PostgresAgentStateStore.builder(dataSource).createIfNotExist(false).build());
    }

    @Test
    void verifySchemaSqlExceptionThrows() throws SQLException {
        when(preparedStatement.executeQuery()).thenThrow(new SQLException("boom"));
        assertThrows(
                RuntimeException.class,
                () -> PostgresAgentStateStore.builder(dataSource).createIfNotExist(false).build());
    }

    @Test
    void saveSingleState() throws SQLException {
        PostgresAgentStateStore store =
                PostgresAgentStateStore.builder(dataSource).createIfNotExist(false).build();
        when(preparedStatement.executeUpdate()).thenReturn(1);

        store.save("user", "session", "key", new TestState("value"));

        verify(preparedStatement).setString(1, "user:session");
        verify(preparedStatement).setString(2, "key");
        verify(preparedStatement).setInt(3, 0);
        verify(preparedStatement).setString(4, "{\"value\":\"value\"}");
    }

    @Test
    void saveSingleStateThrowsWhenSqlFails() throws SQLException {
        PostgresAgentStateStore store =
                PostgresAgentStateStore.builder(dataSource).createIfNotExist(false).build();
        when(dataSource.getConnection()).thenThrow(new SQLException("boom"));
        assertThrows(
                RuntimeException.class,
                () -> store.save("user", "session", "key", new TestState("value")));
    }

    @Test
    void saveListAppendsNewItems() throws SQLException {
        PostgresAgentStateStore store =
                PostgresAgentStateStore.builder(dataSource).createIfNotExist(false).build();
        when(preparedStatement.executeUpdate()).thenReturn(1);
        when(preparedStatement.executeBatch()).thenReturn(new int[] {1});
        when(resultSet.next())
                .thenReturn(true, false, true, false); // hash exists, count, then hash save
        when(resultSet.getString("state_data")).thenReturn(null);
        when(resultSet.getInt("max_index")).thenReturn(-1);

        store.save("user", "session", "list", List.of(new TestState("a"), new TestState("b")));

        verify(preparedStatement, atLeastOnce()).executeBatch();
    }

    @Test
    void saveListEmptyDoesNothing() throws SQLException {
        PostgresAgentStateStore store =
                PostgresAgentStateStore.builder(dataSource).createIfNotExist(false).build();
        store.save("user", "session", "list", List.of());
        verify(preparedStatement, never()).executeBatch();
    }

    @Test
    void getSingleState() throws Exception {
        PostgresAgentStateStore store =
                PostgresAgentStateStore.builder(dataSource).createIfNotExist(false).build();
        when(resultSet.next()).thenReturn(true);
        when(resultSet.getString("state_data")).thenReturn("{\"value\":\"found\"}");

        Optional<TestState> result = store.get("user", "session", "key", TestState.class);

        assertTrue(result.isPresent());
        assertEquals("found", result.get().value());
    }

    @Test
    void getSingleStateReturnsEmptyWhenMissing() throws Exception {
        PostgresAgentStateStore store =
                PostgresAgentStateStore.builder(dataSource).createIfNotExist(false).build();
        when(resultSet.next()).thenReturn(false);

        Optional<TestState> result = store.get("user", "session", "key", TestState.class);

        assertTrue(result.isEmpty());
    }

    @Test
    void getList() throws Exception {
        PostgresAgentStateStore store =
                PostgresAgentStateStore.builder(dataSource).createIfNotExist(false).build();
        when(resultSet.next()).thenReturn(true, true, false);
        when(resultSet.getString("state_data"))
                .thenReturn("{\"value\":\"a\"}", "{\"value\":\"b\"}");

        List<TestState> result = store.getList("user", "session", "list", TestState.class);

        assertEquals(2, result.size());
        assertEquals("a", result.get(0).value());
        assertEquals("b", result.get(1).value());
    }

    @Test
    void existsTrue() throws SQLException {
        PostgresAgentStateStore store =
                PostgresAgentStateStore.builder(dataSource).createIfNotExist(false).build();
        when(resultSet.next()).thenReturn(true);
        assertTrue(store.exists("user", "session"));
    }

    @Test
    void existsFalse() throws SQLException {
        PostgresAgentStateStore store =
                PostgresAgentStateStore.builder(dataSource).createIfNotExist(false).build();
        when(resultSet.next()).thenReturn(false);
        assertFalse(store.exists("user", "session"));
    }

    @Test
    void deleteSession() throws SQLException {
        PostgresAgentStateStore store =
                PostgresAgentStateStore.builder(dataSource).createIfNotExist(false).build();
        when(preparedStatement.executeUpdate()).thenReturn(1);

        store.delete("user", "session");

        verify(preparedStatement).setString(1, "user:session");
    }

    @Test
    void listSessionIds() throws SQLException {
        PostgresAgentStateStore store =
                PostgresAgentStateStore.builder(dataSource).createIfNotExist(false).build();
        when(resultSet.next()).thenReturn(true, true, false);
        when(resultSet.getString("session_id")).thenReturn("user:s1", "user:s2");

        Set<String> ids = store.listSessionIds("user");

        assertEquals(Set.of("s1", "s2"), ids);
    }

    @Test
    void listSessionIdsForAnonymousUser() throws SQLException {
        PostgresAgentStateStore store =
                PostgresAgentStateStore.builder(dataSource).createIfNotExist(false).build();
        when(resultSet.next()).thenReturn(true, false);
        when(resultSet.getString("session_id")).thenReturn("__anon__:s1");

        Set<String> ids = store.listSessionIds(null);

        assertEquals(Set.of("s1"), ids);
    }

    @Test
    void listSessionIdsForBlankUser() throws SQLException {
        PostgresAgentStateStore store =
                PostgresAgentStateStore.builder(dataSource).createIfNotExist(false).build();
        when(resultSet.next()).thenReturn(true, false);
        when(resultSet.getString("session_id")).thenReturn("__anon__:s1");

        Set<String> ids = store.listSessionIds("  ");

        assertEquals(Set.of("s1"), ids);
    }

    @Test
    void rejectsNullSessionId() {
        PostgresAgentStateStore store =
                PostgresAgentStateStore.builder(dataSource).createIfNotExist(false).build();
        assertThrows(
                IllegalArgumentException.class,
                () -> store.save("user", null, "key", new TestState("v")));
    }

    @Test
    void rejectsBlankSessionId() {
        PostgresAgentStateStore store =
                PostgresAgentStateStore.builder(dataSource).createIfNotExist(false).build();
        assertThrows(
                IllegalArgumentException.class,
                () -> store.save("user", "  ", "key", new TestState("v")));
    }

    @Test
    void validateSessionIdRejectsNullOrEmpty() {
        PostgresAgentStateStore store =
                PostgresAgentStateStore.builder(dataSource).createIfNotExist(false).build();
        assertThrows(IllegalArgumentException.class, () -> store.validateSessionId(null));
        assertThrows(IllegalArgumentException.class, () -> store.validateSessionId("  "));
    }

    @Test
    void rejectsSessionIdWithPathSeparators() {
        PostgresAgentStateStore store =
                PostgresAgentStateStore.builder(dataSource).createIfNotExist(false).build();
        assertThrows(
                IllegalArgumentException.class,
                () -> store.save("user", "a/b", "key", new TestState("v")));
        assertThrows(
                IllegalArgumentException.class,
                () -> store.save("user", "a\\b", "key", new TestState("v")));
    }

    @Test
    void rejectsTooLongSessionId() {
        PostgresAgentStateStore store =
                PostgresAgentStateStore.builder(dataSource).createIfNotExist(false).build();
        String longId = "a".repeat(256);
        assertThrows(
                IllegalArgumentException.class,
                () -> store.save("user", longId, "key", new TestState("v")));
    }

    @Test
    void rejectsNullStateKey() {
        PostgresAgentStateStore store =
                PostgresAgentStateStore.builder(dataSource).createIfNotExist(false).build();
        assertThrows(
                IllegalArgumentException.class,
                () -> store.save("user", "session", null, new TestState("v")));
    }

    @Test
    void closeIsNoOp() {
        PostgresAgentStateStore store =
                PostgresAgentStateStore.builder(dataSource).createIfNotExist(false).build();
        store.close();
    }

    @Test
    void executeInWriteTransactionRollsBackOnException() throws SQLException {
        PostgresAgentStateStore store =
                PostgresAgentStateStore.builder(dataSource).createIfNotExist(false).build();
        when(connection.getAutoCommit()).thenReturn(true, false);
        when(preparedStatement.executeUpdate()).thenThrow(new SQLException("boom"));

        assertThrows(RuntimeException.class, () -> store.delete("user", "session"));
        verify(connection).rollback();
        verify(connection).setAutoCommit(true);
    }

    @Test
    void verifyTableExistenceSqlExceptionThrows() throws SQLException {
        when(preparedStatement.executeQuery())
                .thenReturn(resultSet)
                .thenThrow(new SQLException("boom"));
        assertThrows(
                RuntimeException.class,
                () -> PostgresAgentStateStore.builder(dataSource).createIfNotExist(false).build());
    }

    @Test
    void rollbackFailureIsAddedAsSuppressed() throws SQLException {
        PostgresAgentStateStore store =
                PostgresAgentStateStore.builder(dataSource).createIfNotExist(false).build();
        when(connection.getAutoCommit()).thenReturn(true, false);
        SQLException rollbackException = new SQLException("rollback failed");
        when(preparedStatement.executeUpdate()).thenThrow(new SQLException("boom"));
        doThrow(rollbackException).when(connection).rollback();

        RuntimeException exception =
                assertThrows(RuntimeException.class, () -> store.delete("user", "session"));
        assertTrue(
                java.util.Arrays.stream(exception.getCause().getSuppressed())
                        .anyMatch(t -> t == rollbackException));
    }

    @Test
    void saveListThrowsWhenSqlFails() throws SQLException {
        PostgresAgentStateStore store =
                PostgresAgentStateStore.builder(dataSource).createIfNotExist(false).build();
        when(dataSource.getConnection()).thenThrow(new SQLException("boom"));
        assertThrows(
                RuntimeException.class,
                () -> store.save("user", "session", "list", List.of(new TestState("a"))));
    }

    @Test
    void saveListFullRewriteWhenHashChanged() throws SQLException {
        PostgresAgentStateStore store =
                PostgresAgentStateStore.builder(dataSource).createIfNotExist(false).build();
        when(preparedStatement.executeUpdate()).thenReturn(1);
        when(preparedStatement.executeBatch()).thenReturn(new int[] {1, 1});
        when(resultSet.next()).thenReturn(true, true, false);
        when(resultSet.getString("state_data")).thenReturn("different_hash");
        when(resultSet.getInt("max_index")).thenReturn(1);

        store.save("user", "session", "list", List.of(new TestState("a"), new TestState("b")));

        verify(preparedStatement, atLeastOnce()).executeBatch();
    }

    @Test
    void saveListHashNotFound() throws SQLException {
        PostgresAgentStateStore store =
                PostgresAgentStateStore.builder(dataSource).createIfNotExist(false).build();
        when(preparedStatement.executeUpdate()).thenReturn(1);
        when(preparedStatement.executeBatch()).thenReturn(new int[] {1});
        when(resultSet.next()).thenReturn(false, true);
        when(resultSet.getInt("max_index")).thenReturn(2);

        store.save(
                "user",
                "session",
                "list",
                List.of(new TestState("a"), new TestState("b"), new TestState("c")));

        verify(preparedStatement, atLeastOnce()).executeBatch();
    }

    @Test
    void saveListSkipsWhenHashMatchesAndSizeUnchanged() throws SQLException {
        PostgresAgentStateStore store =
                PostgresAgentStateStore.builder(dataSource).createIfNotExist(false).build();
        when(preparedStatement.executeUpdate()).thenReturn(1);
        when(resultSet.next()).thenReturn(true, true);
        when(resultSet.getString("state_data"))
                .thenReturn(
                        io.agentscope.core.state.ListHashUtil.computeHash(
                                List.of(new TestState("a"), new TestState("b"))));
        when(resultSet.getInt("max_index")).thenReturn(1);

        store.save("user", "session", "list", List.of(new TestState("a"), new TestState("b")));

        verify(preparedStatement, never()).executeBatch();
    }

    @Test
    void getListCountReturnsZeroWhenNoRows() throws SQLException {
        PostgresAgentStateStore store =
                PostgresAgentStateStore.builder(dataSource).createIfNotExist(false).build();
        when(preparedStatement.executeUpdate()).thenReturn(1);
        when(preparedStatement.executeBatch()).thenReturn(new int[] {1, 1});
        when(resultSet.next()).thenReturn(false, false);

        store.save("user", "session", "list", List.of(new TestState("a"), new TestState("b")));

        verify(preparedStatement, atLeastOnce()).executeBatch();
    }

    @Test
    void executeInWriteTransactionKeepsAutoCommitWhenAlreadyDisabled() throws SQLException {
        PostgresAgentStateStore store =
                PostgresAgentStateStore.builder(dataSource).createIfNotExist(false).build();
        when(connection.getAutoCommit()).thenReturn(false);
        when(preparedStatement.executeUpdate()).thenReturn(1);

        store.delete("user", "session");

        verify(connection, never()).setAutoCommit(false);
        verify(connection).commit();
    }

    @Test
    void getListCountReturnsZeroWhenMaxIndexNull() throws SQLException {
        PostgresAgentStateStore store =
                PostgresAgentStateStore.builder(dataSource).createIfNotExist(false).build();
        when(preparedStatement.executeUpdate()).thenReturn(1);
        when(preparedStatement.executeBatch()).thenReturn(new int[] {1});
        when(resultSet.next()).thenReturn(false, true);
        when(resultSet.getInt("max_index")).thenReturn(0);
        when(resultSet.wasNull()).thenReturn(true);

        store.save("user", "session", "list", List.of(new TestState("a")));

        verify(preparedStatement, atLeastOnce()).executeBatch();
    }

    @Test
    void getThrowsWhenSqlFails() throws SQLException {
        PostgresAgentStateStore store =
                PostgresAgentStateStore.builder(dataSource).createIfNotExist(false).build();
        when(dataSource.getConnection()).thenThrow(new SQLException("boom"));
        assertThrows(
                RuntimeException.class, () -> store.get("user", "session", "key", TestState.class));
    }

    @Test
    void getThrowsWhenJsonInvalid() throws SQLException {
        PostgresAgentStateStore store =
                PostgresAgentStateStore.builder(dataSource).createIfNotExist(false).build();
        when(resultSet.next()).thenReturn(true);
        when(resultSet.getString("state_data")).thenReturn("not-json");

        assertThrows(
                RuntimeException.class, () -> store.get("user", "session", "key", TestState.class));
    }

    @Test
    void getThrowsWhenQueryFails() throws SQLException {
        PostgresAgentStateStore store =
                PostgresAgentStateStore.builder(dataSource).createIfNotExist(false).build();
        when(preparedStatement.executeQuery()).thenThrow(new SQLException("boom"));

        assertThrows(
                RuntimeException.class, () -> store.get("user", "session", "key", TestState.class));
    }

    @Test
    void getThrowsWhenResultSetReadFails() throws SQLException {
        PostgresAgentStateStore store =
                PostgresAgentStateStore.builder(dataSource).createIfNotExist(false).build();
        when(resultSet.next()).thenReturn(true);
        when(resultSet.getString("state_data")).thenThrow(new SQLException("read failed"));

        assertThrows(
                RuntimeException.class, () -> store.get("user", "session", "key", TestState.class));
    }

    @Test
    void getPropagatesResultSetCloseFailure() throws SQLException {
        PostgresAgentStateStore store =
                PostgresAgentStateStore.builder(dataSource).createIfNotExist(false).build();
        when(resultSet.next()).thenReturn(false);
        doThrow(new SQLException("close failed")).when(resultSet).close();

        assertThrows(
                RuntimeException.class, () -> store.get("user", "session", "key", TestState.class));
    }

    @Test
    void saveListPropagatesResultSetCloseFailure() throws SQLException {
        PostgresAgentStateStore store =
                PostgresAgentStateStore.builder(dataSource).createIfNotExist(false).build();
        when(resultSet.next()).thenReturn(false);
        doThrow(new SQLException("close failed")).when(resultSet).close();

        assertThrows(
                RuntimeException.class,
                () -> store.save("user", "session", "list", List.of(new TestState("a"))));
    }

    @Test
    void getListCountUsesMaxIndexWhenPresent() throws SQLException {
        PostgresAgentStateStore store =
                PostgresAgentStateStore.builder(dataSource).createIfNotExist(false).build();
        when(preparedStatement.executeUpdate()).thenReturn(1);
        when(preparedStatement.executeBatch()).thenReturn(new int[] {1});
        when(resultSet.next()).thenReturn(true, true);
        when(resultSet.getString("state_data")).thenReturn("stale_hash");
        when(resultSet.getInt("max_index")).thenReturn(0);
        when(resultSet.wasNull()).thenReturn(false);

        store.save("user", "session", "list", List.of(new TestState("only")));

        verify(preparedStatement, atLeastOnce()).executeBatch();
    }

    @Test
    void getListThrowsWhenSqlFails() throws SQLException {
        PostgresAgentStateStore store =
                PostgresAgentStateStore.builder(dataSource).createIfNotExist(false).build();
        when(dataSource.getConnection()).thenThrow(new SQLException("boom"));
        assertThrows(
                RuntimeException.class,
                () -> store.getList("user", "session", "list", TestState.class));
    }

    @Test
    void existsThrowsWhenSqlFails() throws SQLException {
        PostgresAgentStateStore store =
                PostgresAgentStateStore.builder(dataSource).createIfNotExist(false).build();
        when(dataSource.getConnection()).thenThrow(new SQLException("boom"));
        assertThrows(RuntimeException.class, () -> store.exists("user", "session"));
    }

    @Test
    void listSessionIdsThrowsWhenSqlFails() throws SQLException {
        PostgresAgentStateStore store =
                PostgresAgentStateStore.builder(dataSource).createIfNotExist(false).build();
        when(dataSource.getConnection()).thenThrow(new SQLException("boom"));
        assertThrows(RuntimeException.class, () -> store.listSessionIds("user"));
    }

    @Test
    void rejectsEmptyStateKey() {
        PostgresAgentStateStore store =
                PostgresAgentStateStore.builder(dataSource).createIfNotExist(false).build();
        assertThrows(
                IllegalArgumentException.class,
                () -> store.save("user", "session", "  ", new TestState("v")));
    }

    @Test
    void rejectsTooLongStateKey() {
        PostgresAgentStateStore store =
                PostgresAgentStateStore.builder(dataSource).createIfNotExist(false).build();
        String longKey = "a".repeat(256);
        assertThrows(
                IllegalArgumentException.class,
                () -> store.save("user", "session", longKey, new TestState("v")));
    }

    @Test
    void truncateAllSessionsTruncatesTable() throws SQLException {
        PostgresAgentStateStore store =
                PostgresAgentStateStore.builder(dataSource).createIfNotExist(false).build();
        when(preparedStatement.executeUpdate()).thenReturn(0);

        int result = store.truncateAllSessions();

        assertEquals(0, result);
        verify(preparedStatement).executeUpdate();
    }

    @Test
    void truncateAllSessionsThrowsWhenSqlFails() throws SQLException {
        PostgresAgentStateStore store =
                PostgresAgentStateStore.builder(dataSource).createIfNotExist(false).build();
        when(dataSource.getConnection()).thenThrow(new SQLException("boom"));
        assertThrows(RuntimeException.class, () -> store.truncateAllSessions());
    }
}
