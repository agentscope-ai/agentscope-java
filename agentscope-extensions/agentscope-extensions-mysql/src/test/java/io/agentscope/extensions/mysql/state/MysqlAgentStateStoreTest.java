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
package io.agentscope.extensions.mysql.state;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import io.agentscope.core.state.AgentStateStore;
import io.agentscope.core.state.State;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import javax.sql.DataSource;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;

/**
 * Unit tests for {@link MysqlAgentStateStore} optimistic-concurrency versioning.
 *
 * <p>Uses Mockito to mock JDBC resources so no real MySQL server is required. These tests cover
 * the {@code saveIfVersion} UNVERSIONED path and the {@code readVersion} method, which were
 * not covered by any existing tests.
 */
@DisplayName("MysqlAgentStateStore versioning")
class MysqlAgentStateStoreTest {

    record TestState(String value) implements State {}

    @Mock private DataSource dataSource;
    @Mock private Connection connection;
    @Mock private PreparedStatement preparedStatement;
    @Mock private ResultSet resultSet;

    private AutoCloseable mocks;

    @BeforeEach
    void setUp() throws SQLException {
        mocks = MockitoAnnotations.openMocks(this);
        when(dataSource.getConnection()).thenReturn(connection);
        when(connection.prepareStatement(anyString())).thenReturn(preparedStatement);
        when(preparedStatement.executeQuery()).thenReturn(resultSet);
        when(preparedStatement.executeUpdate()).thenReturn(1);
        // Constructor verification calls: verifyDatabaseExists, verifyTableExists,
        // ensureVersionColumn. All three need resultSet.next() → true.
        when(resultSet.next()).thenReturn(true, true, true);
        // ensureVersionColumn: COUNT(*) returns 1 (column exists, no ALTER needed)
        when(resultSet.getInt(1)).thenReturn(1);
    }

    @AfterEach
    void tearDown() throws Exception {
        if (mocks != null) {
            mocks.close();
        }
    }

    private MysqlAgentStateStore newStore() {
        return new MysqlAgentStateStore(dataSource, "agentscope", "agentscope_sessions", false);
    }

    // ------------------------------------------------------------------
    //  saveIfVersion UNVERSIONED — must not deserialize State.class
    // ------------------------------------------------------------------

    @Test
    @DisplayName("saveIfVersion with UNVERSIONED returns readVersion without deserializing State")
    void saveIfVersionUnconditionalUsesReadVersion() throws SQLException {
        // The UNVERSIONED path calls save() then readVersion(). We verify that readVersion
        // only reads the 'version' column and never touches 'state_data'.
        MysqlAgentStateStore store = newStore();

        // save() issues an INSERT ... ON DUPLICATE KEY UPDATE
        when(preparedStatement.executeUpdate()).thenReturn(1);
        // readVersion() issues SELECT version FROM ...
        when(resultSet.next()).thenReturn(true, true, true, true, true);
        when(resultSet.getLong("version")).thenReturn(42L);

        long version =
                store.saveIfVersion(
                        "user",
                        "session",
                        "agent_state",
                        new TestState("v"),
                        AgentStateStore.UNVERSIONED);

        assertEquals(42L, version);
        // Regression: readVersion must never deserialize state_data as State.class
        verify(resultSet, never()).getString("state_data");
    }

    @Test
    @DisplayName("saveIfVersion with UNVERSIONED on absent key returns version 0")
    void saveIfVersionUnconditionalAbsentKeyReturnsZero() throws SQLException {
        // When the key doesn't exist, readVersion returns 0L.
        MysqlAgentStateStore store = newStore();

        // save() succeeds
        when(preparedStatement.executeUpdate()).thenReturn(1);
        // readVersion(): rs.next() returns false (no row)
        when(resultSet.next()).thenReturn(true, true, true, true, false);

        long version =
                store.saveIfVersion(
                        "user",
                        "session",
                        "absent_key",
                        new TestState("v"),
                        AgentStateStore.UNVERSIONED);

        assertEquals(0L, version);
        verify(resultSet, never()).getString("state_data");
    }

    // ------------------------------------------------------------------
    //  readVersion edge cases
    // ------------------------------------------------------------------

    @Test
    @DisplayName("readVersion returns version when row exists")
    void readVersionReturnsVersionWhenRowExists() throws SQLException {
        // The UNVERSIONED path of saveIfVersion exercises readVersion internally.
        MysqlAgentStateStore store = newStore();

        when(preparedStatement.executeUpdate()).thenReturn(1);
        when(resultSet.next()).thenReturn(true, true, true, true, true);
        when(resultSet.getLong("version")).thenReturn(7L);

        long version =
                store.saveIfVersion(
                        "user",
                        "session",
                        "agent_state",
                        new TestState("v"),
                        AgentStateStore.UNVERSIONED);

        assertEquals(7L, version);
    }

    @Test
    @DisplayName("saveIfVersion with UNVERSIONED propagates SQLException from readVersion")
    void saveIfVersionUnconditionalPropagatesReadVersionException() throws SQLException {
        MysqlAgentStateStore store = newStore();

        // save() succeeds
        when(preparedStatement.executeUpdate()).thenReturn(1);
        // readVersion() throws SQLException
        when(resultSet.next()).thenReturn(true, true, true, true, true);
        when(resultSet.getLong("version")).thenThrow(new SQLException("read failed"));

        assertThrows(
                RuntimeException.class,
                () ->
                        store.saveIfVersion(
                                "user",
                                "session",
                                "agent_state",
                                new TestState("v"),
                                AgentStateStore.UNVERSIONED));
    }
}
