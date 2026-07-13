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
package io.agentscope.extensions.postgresql.store;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.reset;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.agentscope.harness.agent.filesystem.remote.store.StoreItem;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.SQLIntegrityConstraintViolationException;
import java.sql.Statement;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import javax.sql.DataSource;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;

class PostgresBaseStoreTest {

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
        when(preparedStatement.getConnection()).thenReturn(connection);
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
        assertThrows(NullPointerException.class, () -> PostgresBaseStore.builder(null));
    }

    @Test
    void builderWithDefaultsCreatesStore() {
        PostgresBaseStore store = PostgresBaseStore.builder(dataSource).build();
        assertNotNull(store);
    }

    @Test
    void builderWithCustomTableName() {
        PostgresBaseStore store =
                PostgresBaseStore.builder(dataSource).tableName("custom_store").build();
        assertNotNull(store);
    }

    @Test
    void builderRejectsInvalidTableName() {
        assertThrows(
                IllegalArgumentException.class,
                () -> PostgresBaseStore.builder(dataSource).tableName("store;drop").build());
        assertThrows(
                IllegalArgumentException.class,
                () -> PostgresBaseStore.builder(dataSource).tableName("").build());
        assertThrows(
                IllegalArgumentException.class,
                () -> PostgresBaseStore.builder(dataSource).tableName(null).build());
    }

    @Test
    void initializeSchemaDefaultDoesNotCreateTable() throws SQLException {
        PostgresBaseStore store = PostgresBaseStore.builder(dataSource).build();
        assertNotNull(store);
        verify(connection, org.mockito.Mockito.never()).createStatement();
    }

    @Test
    void initializeSchemaTrueCreatesTable() throws SQLException {
        PostgresBaseStore store =
                PostgresBaseStore.builder(dataSource).initializeSchema(true).build();
        assertNotNull(store);
        verify(connection).createStatement();
    }

    @Test
    void initializeSchemaFailureThrows() throws SQLException {
        when(connection.createStatement()).thenThrow(new SQLException("boom"));
        assertThrows(
                IllegalStateException.class,
                () -> PostgresBaseStore.builder(dataSource).initializeSchema(true).build());
    }

    @Test
    void getReturnsItem() throws SQLException {
        when(resultSet.next()).thenReturn(true);
        when(resultSet.getString(1)).thenReturn("{\"k\":\"v\"}");
        when(resultSet.getLong(2)).thenReturn(5L);

        PostgresBaseStore store = PostgresBaseStore.builder(dataSource).build();
        StoreItem item = store.get(List.of("a", "b"), "key");

        assertNotNull(item);
        assertEquals("key", item.key());
        assertEquals("v", item.value().get("k"));
        assertEquals(5L, item.version());
    }

    @Test
    void getReturnsNullWhenNotFound() throws SQLException {
        when(resultSet.next()).thenReturn(false);

        PostgresBaseStore store = PostgresBaseStore.builder(dataSource).build();
        assertNull(store.get(List.of("a"), "key"));
    }

    @Test
    void getThrowsWhenSqlFails() throws SQLException {
        PostgresBaseStore store = PostgresBaseStore.builder(dataSource).build();
        when(dataSource.getConnection()).thenThrow(new SQLException("boom"));
        assertThrows(IllegalStateException.class, () -> store.get(List.of("a"), "key"));
    }

    @Test
    void getThrowsWhenQueryFails() throws SQLException {
        PostgresBaseStore store = PostgresBaseStore.builder(dataSource).build();
        when(preparedStatement.executeQuery()).thenThrow(new SQLException("boom"));
        assertThrows(IllegalStateException.class, () -> store.get(List.of("a"), "key"));
    }

    @Test
    void getPropagatesResultSetCloseFailure() throws SQLException {
        when(resultSet.next()).thenReturn(false);
        doThrow(new SQLException("close failed")).when(resultSet).close();

        PostgresBaseStore store = PostgresBaseStore.builder(dataSource).build();
        assertThrows(IllegalStateException.class, () -> store.get(List.of("a"), "key"));
    }

    @Test
    void putWritesItem() throws SQLException {
        when(preparedStatement.executeUpdate()).thenReturn(1);

        PostgresBaseStore store = PostgresBaseStore.builder(dataSource).build();
        store.put(List.of("a"), "key", Map.of("k", "v"));

        verify(preparedStatement).setString(1, "a");
        verify(preparedStatement).setString(2, "key");
        verify(preparedStatement).setString(3, "{\"k\":\"v\"}");
        verify(preparedStatement).setLong(eq(4), anyLong());
        verify(preparedStatement).executeUpdate();
    }

    @Test
    void putThrowsWhenSqlFails() throws SQLException {
        PostgresBaseStore store = PostgresBaseStore.builder(dataSource).build();
        when(dataSource.getConnection()).thenThrow(new SQLException("boom"));
        assertThrows(IllegalStateException.class, () -> store.put(List.of("a"), "key", Map.of()));
    }

    @Test
    void putIfVersionRejectsNegativeExpectedVersion() {
        PostgresBaseStore store = PostgresBaseStore.builder(dataSource).build();
        assertThrows(
                IllegalArgumentException.class,
                () -> store.putIfVersion(List.of("a"), "key", Map.of(), -1L));
    }

    @Test
    void putIfVersionInsertSucceeds() throws SQLException {
        when(preparedStatement.executeUpdate()).thenReturn(1);

        PostgresBaseStore store = PostgresBaseStore.builder(dataSource).build();
        assertTrue(store.putIfVersion(List.of("a"), "key", Map.of("k", "v"), 0L));
    }

    @Test
    void putIfVersionInsertDuplicateReturnsFalse() throws SQLException {
        when(preparedStatement.executeUpdate())
                .thenThrow(new SQLIntegrityConstraintViolationException("dup"));

        PostgresBaseStore store = PostgresBaseStore.builder(dataSource).build();
        assertFalse(store.putIfVersion(List.of("a"), "key", Map.of(), 0L));
    }

    @Test
    void putIfVersionInsertDuplicateBySqlStateReturnsFalse() throws SQLException {
        when(preparedStatement.executeUpdate()).thenThrow(new SQLException("dup", "23505"));

        PostgresBaseStore store = PostgresBaseStore.builder(dataSource).build();
        assertFalse(store.putIfVersion(List.of("a"), "key", Map.of(), 0L));
    }

    @Test
    void putIfVersionInsertDuplicateByNullSqlStateThrows() throws SQLException {
        when(preparedStatement.executeUpdate()).thenThrow(new SQLException("dup"));

        PostgresBaseStore store = PostgresBaseStore.builder(dataSource).build();
        assertThrows(
                IllegalStateException.class,
                () -> store.putIfVersion(List.of("a"), "key", Map.of(), 0L));
    }

    @Test
    void putIfVersionInsertOtherSqlExceptionThrows() throws SQLException {
        when(preparedStatement.executeUpdate()).thenThrow(new SQLException("boom", "42000"));

        PostgresBaseStore store = PostgresBaseStore.builder(dataSource).build();
        assertThrows(
                IllegalStateException.class,
                () -> store.putIfVersion(List.of("a"), "key", Map.of(), 0L));
    }

    @Test
    void putIfVersionUpdateSucceeds() throws SQLException {
        when(preparedStatement.executeUpdate()).thenReturn(1);

        PostgresBaseStore store = PostgresBaseStore.builder(dataSource).build();
        assertTrue(store.putIfVersion(List.of("a"), "key", Map.of("k", "v"), 5L));
    }

    @Test
    void putIfVersionUpdateMismatchReturnsFalse() throws SQLException {
        when(preparedStatement.executeUpdate()).thenReturn(0);

        PostgresBaseStore store = PostgresBaseStore.builder(dataSource).build();
        assertFalse(store.putIfVersion(List.of("a"), "key", Map.of(), 5L));
    }

    @Test
    void putIfVersionUpdateThrowsWhenSqlFails() throws SQLException {
        PostgresBaseStore store = PostgresBaseStore.builder(dataSource).build();
        when(dataSource.getConnection()).thenThrow(new SQLException("boom"));
        assertThrows(
                IllegalStateException.class,
                () -> store.putIfVersion(List.of("a"), "key", Map.of(), 5L));
    }

    @Test
    void searchReturnsItems() throws SQLException {
        when(resultSet.next()).thenReturn(true, true, false);
        when(resultSet.getString(1)).thenReturn("key1", "key2");
        when(resultSet.getString(2)).thenReturn("{\"a\":1}", "{\"b\":2}");
        when(resultSet.getLong(3)).thenReturn(1L, 2L);

        PostgresBaseStore store = PostgresBaseStore.builder(dataSource).build();
        List<StoreItem> items = store.search(List.of("ns"), 10, 0);

        assertEquals(2, items.size());
        assertEquals("key1", items.get(0).key());
        assertEquals("key2", items.get(1).key());
    }

    @Test
    void searchEscapesLikeSpecialCharacters() throws SQLException {
        when(resultSet.next()).thenReturn(false);

        PostgresBaseStore store = PostgresBaseStore.builder(dataSource).build();
        store.search(List.of("n%s", "_under", "!bang"), 10, 0);

        verify(preparedStatement).setString(eq(1), org.mockito.ArgumentMatchers.contains("n!%s"));
        verify(preparedStatement)
                .setString(eq(1), org.mockito.ArgumentMatchers.contains("!_under"));
        verify(preparedStatement).setString(eq(1), org.mockito.ArgumentMatchers.contains("!!bang"));
    }

    @Test
    void searchReturnsEmptyForNonPositiveLimit() {
        PostgresBaseStore store = PostgresBaseStore.builder(dataSource).build();
        assertTrue(store.search(List.of("ns"), 0, 0).isEmpty());
        assertTrue(store.search(List.of("ns"), -1, 0).isEmpty());
    }

    @Test
    void searchThrowsWhenSqlFails() throws SQLException {
        PostgresBaseStore store = PostgresBaseStore.builder(dataSource).build();
        when(dataSource.getConnection()).thenThrow(new SQLException("boom"));
        assertThrows(IllegalStateException.class, () -> store.search(List.of("ns"), 10, 0));
    }

    @Test
    void deleteRemovesItem() throws SQLException {
        when(preparedStatement.executeUpdate()).thenReturn(1);

        PostgresBaseStore store = PostgresBaseStore.builder(dataSource).build();
        store.delete(List.of("a"), "key");

        verify(preparedStatement).setString(1, "a");
        verify(preparedStatement).setString(2, "key");
        verify(preparedStatement).executeUpdate();
    }

    @Test
    void deleteThrowsWhenSqlFails() throws SQLException {
        PostgresBaseStore store = PostgresBaseStore.builder(dataSource).build();
        when(dataSource.getConnection()).thenThrow(new SQLException("boom"));
        assertThrows(IllegalStateException.class, () -> store.delete(List.of("a"), "key"));
    }

    @Test
    void deserializeHandlesNullAndEmpty() throws SQLException {
        when(resultSet.next()).thenReturn(true);
        when(resultSet.getString(1)).thenReturn(null);
        when(resultSet.getLong(2)).thenReturn(1L);

        PostgresBaseStore store = PostgresBaseStore.builder(dataSource).build();
        StoreItem item = store.get(List.of("a"), "key");

        assertNotNull(item);
        assertTrue(item.value().isEmpty());

        when(resultSet.getString(1)).thenReturn("");
        item = store.get(List.of("a"), "key");
        assertTrue(item.value().isEmpty());
    }

    @Test
    void namespaceSegmentCannotBeNull() {
        PostgresBaseStore store = PostgresBaseStore.builder(dataSource).build();
        assertThrows(
                IllegalArgumentException.class,
                () -> store.put(Arrays.asList("a", null), "key", Map.of()));
    }

    @Test
    void namespaceSegmentCannotContainSeparator() {
        PostgresBaseStore store = PostgresBaseStore.builder(dataSource).build();
        assertThrows(
                IllegalArgumentException.class, () -> store.put(List.of("a"), "key", Map.of()));
    }

    @Test
    void keyCannotBeNullOrEmpty() {
        PostgresBaseStore store = PostgresBaseStore.builder(dataSource).build();
        assertThrows(IllegalArgumentException.class, () -> store.put(List.of("a"), null, Map.of()));
        assertThrows(IllegalArgumentException.class, () -> store.put(List.of("a"), "", Map.of()));
    }

    @Test
    void serializeNullValueAsEmptyMap() throws SQLException {
        when(preparedStatement.executeUpdate()).thenReturn(1);

        PostgresBaseStore store = PostgresBaseStore.builder(dataSource).build();
        store.put(List.of("a"), "key", null);

        verify(preparedStatement).setString(3, "{}");
    }

    @Test
    void customObjectMapperRoundTrip() throws SQLException {
        ObjectMapper mapper = new ObjectMapper();
        when(preparedStatement.executeUpdate()).thenReturn(1);
        when(resultSet.next()).thenReturn(true);
        when(resultSet.getString(1)).thenReturn("{\"custom\":true}");
        when(resultSet.getLong(2)).thenReturn(7L);

        PostgresBaseStore store =
                PostgresBaseStore.builder(dataSource).objectMapper(mapper).build();
        store.put(List.of("a"), "key", Map.of("custom", true));
        StoreItem item = store.get(List.of("a"), "key");

        assertEquals(Boolean.TRUE, item.value().get("custom"));
    }

    @Test
    void deserializeInvalidJsonThrows() throws SQLException {
        when(resultSet.next()).thenReturn(true);
        when(resultSet.getString(1)).thenReturn("not-json");
        when(resultSet.getLong(2)).thenReturn(1L);

        PostgresBaseStore store = PostgresBaseStore.builder(dataSource).build();
        assertThrows(IllegalStateException.class, () -> store.get(List.of("a"), "key"));
    }

    @Test
    void serializeFailureThrows() {
        ObjectMapper failingMapper =
                new ObjectMapper() {
                    @Override
                    public String writeValueAsString(Object value)
                            throws com.fasterxml.jackson.core.JsonProcessingException {
                        throw new com.fasterxml.jackson.core.JsonProcessingException("fail") {};
                    }
                };
        PostgresBaseStore store =
                PostgresBaseStore.builder(dataSource).objectMapper(failingMapper).build();
        assertThrows(
                IllegalArgumentException.class,
                () -> store.put(List.of("a"), "key", Map.of("x", "y")));
    }
}
