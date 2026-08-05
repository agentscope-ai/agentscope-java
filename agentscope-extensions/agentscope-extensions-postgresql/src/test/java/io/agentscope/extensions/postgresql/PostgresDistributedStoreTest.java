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
package io.agentscope.extensions.postgresql;

import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.when;

import io.agentscope.extensions.postgresql.sandbox.PostgresSandboxExecutionGuard;
import io.agentscope.extensions.postgresql.snapshot.PostgresSnapshotSpec;
import io.agentscope.extensions.postgresql.state.PostgresAgentStateStore;
import io.agentscope.extensions.postgresql.store.PostgresBaseStore;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import javax.sql.DataSource;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;

class PostgresDistributedStoreTest {

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
        when(connection.prepareStatement(org.mockito.ArgumentMatchers.anyString()))
                .thenReturn(preparedStatement);
        when(preparedStatement.executeQuery()).thenReturn(resultSet);
        when(preparedStatement.execute()).thenReturn(true);
        when(resultSet.next()).thenReturn(true, true);
    }

    @AfterEach
    void tearDown() throws Exception {
        if (mocks != null) {
            mocks.close();
        }
    }

    @Test
    void createRejectsNullDataSource() {
        assertThrows(NullPointerException.class, () -> PostgresDistributedStore.create(null));
    }

    @Test
    void createReturnsStore() {
        assertNotNull(PostgresDistributedStore.create(dataSource));
    }

    @Test
    void agentStateStoreReturnsPostgresAgentStateStore() {
        PostgresDistributedStore store = PostgresDistributedStore.create(dataSource);
        assertInstanceOf(PostgresAgentStateStore.class, store.agentStateStore());
    }

    @Test
    void baseStoreReturnsPostgresBaseStore() {
        PostgresDistributedStore store = PostgresDistributedStore.create(dataSource);
        assertInstanceOf(PostgresBaseStore.class, store.baseStore());
    }

    @Test
    void sandboxSnapshotSpecReturnsPostgresSnapshotSpec() {
        PostgresDistributedStore store = PostgresDistributedStore.create(dataSource);
        assertInstanceOf(PostgresSnapshotSpec.class, store.sandboxSnapshotSpec());
    }

    @Test
    void sandboxExecutionGuardReturnsPostgresSandboxExecutionGuard() {
        PostgresDistributedStore store = PostgresDistributedStore.create(dataSource);
        assertInstanceOf(PostgresSandboxExecutionGuard.class, store.sandboxExecutionGuard());
    }
}
