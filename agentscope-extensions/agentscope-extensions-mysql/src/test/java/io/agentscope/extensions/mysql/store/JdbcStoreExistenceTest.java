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
package io.agentscope.extensions.mysql.store;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.agentscope.core.agent.RuntimeContext;
import io.agentscope.harness.agent.filesystem.remote.RemoteFilesystem;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.List;
import java.util.Map;
import javax.sql.DataSource;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.sqlite.SQLiteDataSource;

class JdbcStoreExistenceTest {

    private static final String TABLE = "exists_store";
    private static final List<String> NS = List.of("workspace");

    @TempDir Path directory;
    private DataSource dataSource;
    private final CountingMapper mapper = new CountingMapper();

    @Test
    void remoteExistenceChecksDoNotDecodeValues() {
        JdbcStore store = createStore();
        store.put(NS, "/target/child.txt", Map.of("content", "child"));
        for (int i = 0; i < 249; i++) {
            store.put(NS, "/other/" + i, Map.of("content", "unrelated"));
        }
        RemoteFilesystem filesystem = new RemoteFilesystem(store, NS);

        assertTrue(filesystem.exists(RuntimeContext.empty(), "/target/child.txt"));
        assertTrue(filesystem.exists(RuntimeContext.empty(), "/target"));
        assertFalse(filesystem.exists(RuntimeContext.empty(), "/missing"));
        assertEquals(0, mapper.reads);
    }

    @Test
    void exactKeysAndPrefixesKeepTheirNamespaceSemantics() {
        JdbcStore store = createStore();
        store.put(NS, "/parent.txt", Map.of());
        store.put(List.of("workspace", "child"), "/nested/file.txt", Map.of());
        store.put(List.of("workspace-other"), "/sibling/file.txt", Map.of());

        assertTrue(store.containsKey(NS, "/parent.txt"));
        assertFalse(store.containsKey(NS, "/nested/file.txt"));
        assertFalse(store.containsKey(NS, "/missing"));
        assertTrue(store.hasKeyPrefix(NS, "/nested/"));
        assertFalse(store.hasKeyPrefix(NS, "/sibling/"));
        assertFalse(store.hasKeyPrefix(List.of("work"), "/parent"));
        assertFalse(store.hasKeyPrefix(NS, "/nest/"));
        assertEquals(0, mapper.reads);
    }

    @Test
    void namespaceAndKeyWildcardsAreLiteral() {
        JdbcStore store = createStore();
        List<String> special = List.of("team%_!");
        store.put(List.of("teamXX"), "/foreign/file", Map.of());
        store.put(special, "/a%_!/file", Map.of());
        store.put(special, "/ordinary/file", Map.of());

        assertTrue(store.containsKey(special, "/a%_!/file"));
        assertTrue(store.hasKeyPrefix(special, "/a%_!/"));
        assertFalse(store.hasKeyPrefix(special, "/foreign/"));
        assertFalse(store.hasKeyPrefix(special, "/ord_nary/"));
        assertFalse(store.hasKeyPrefix(special, "/a%_!missing/"));
        assertEquals(0, mapper.reads);
    }

    @Test
    void prefixMatchingIsCaseSensitiveEvenWhenLikeIsNot() {
        JdbcStore store = createStore();
        store.put(NS, "/FILES/first.txt", Map.of());
        assertFalse(store.hasKeyPrefix(NS, "/files/"));
        store.put(NS, "/files/second.txt", Map.of());
        assertTrue(store.hasKeyPrefix(NS, "/files/"));
        assertFalse(store.containsKey(NS, "/files/first.txt"));
    }

    @Test
    void existenceDoesNotDependOnTheStoredJson() throws Exception {
        JdbcStore store = createStore();
        store.put(NS, "/directory/file.txt", Map.of());
        try (Connection connection = dataSource.getConnection();
                Statement statement = connection.createStatement()) {
            statement.executeUpdate("UPDATE " + TABLE + " SET value_json = 'not-json'");
        }

        assertTrue(store.containsKey(NS, "/directory/file.txt"));
        assertTrue(store.hasKeyPrefix(NS, "/directory/"));
        assertEquals(0, mapper.reads);
    }

    @Test
    void sqlFailuresPropagate() throws Exception {
        DataSource source = mock(DataSource.class);
        SQLException failure = new SQLException("database unavailable");
        when(source.getConnection()).thenThrow(failure);
        JdbcStore store = JdbcStore.builder(source).dialect(new SqliteJdbcStoreDialect()).build();

        assertSame(
                failure,
                assertThrows(IllegalStateException.class, () -> store.containsKey(NS, "/file"))
                        .getCause());
        assertSame(
                failure,
                assertThrows(
                                IllegalStateException.class,
                                () -> store.hasKeyPrefix(NS, "/directory/"))
                        .getCause());
    }

    @Test
    void prefixSearchClosesResourcesAfterACaseSensitiveMatch() throws Exception {
        DataSource source = mock(DataSource.class);
        Connection connection = mock(Connection.class);
        PreparedStatement statement = mock(PreparedStatement.class);
        ResultSet results = mock(ResultSet.class);
        when(source.getConnection()).thenReturn(connection);
        when(connection.prepareStatement(anyString())).thenReturn(statement);
        when(statement.executeQuery()).thenReturn(results);
        when(results.next()).thenReturn(true, true, false);
        when(results.getString(1)).thenReturn("/FILES/first.txt", "/files/second.txt");
        JdbcStore store = JdbcStore.builder(source).dialect(new SqliteJdbcStoreDialect()).build();

        assertTrue(store.hasKeyPrefix(NS, "/files/"));
        verify(results).close();
        verify(statement).close();
        verify(connection).close();
    }

    private JdbcStore createStore() {
        SQLiteDataSource source = new SQLiteDataSource();
        source.setUrl("jdbc:sqlite:" + directory.resolve("store.db"));
        dataSource = source;
        return JdbcStore.builder(dataSource)
                .dialect(new SqliteJdbcStoreDialect())
                .tableName(TABLE)
                .initializeSchema(true)
                .objectMapper(mapper)
                .build();
    }

    @Test
    void configuredLikeEscapeCharacterIsRespected() {
        createStore();
        JdbcStore store =
                JdbcStore.builder(dataSource)
                        .dialect(
                                new SqliteJdbcStoreDialect() {
                                    @Override
                                    public char getLikeEscapeChar() {
                                        return '#';
                                    }
                                })
                        .tableName(TABLE)
                        .build();
        List<String> ns = List.of("team#%_");
        store.put(ns, "/key#%_/file", Map.of());
        store.put(List.of("teamXX"), "/foreign/file", Map.of());

        assertTrue(store.hasKeyPrefix(ns, "/key#%_/"));
        assertFalse(store.hasKeyPrefix(ns, "/foreign/"));
        assertFalse(store.hasKeyPrefix(ns, "/key#XX/"));
    }

    private static class CountingMapper extends ObjectMapper {
        int reads;

        @Override
        public <T> T readValue(String content, TypeReference<T> type)
                throws JsonProcessingException {
            reads++;
            return super.readValue(content, type);
        }
    }
}
