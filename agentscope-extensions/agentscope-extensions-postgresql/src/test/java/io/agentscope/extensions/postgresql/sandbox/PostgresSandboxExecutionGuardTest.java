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
package io.agentscope.extensions.postgresql.sandbox;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.reset;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import io.agentscope.harness.agent.IsolationScope;
import io.agentscope.harness.agent.sandbox.SandboxIsolationKey;
import io.agentscope.harness.agent.sandbox.SandboxLease;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Duration;
import javax.sql.DataSource;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;

class PostgresSandboxExecutionGuardTest {

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
    }

    @AfterEach
    void tearDown() throws Exception {
        reset(dataSource, connection, preparedStatement, resultSet);
        if (mocks != null) {
            mocks.close();
        }
    }

    private SandboxIsolationKey key() {
        return SandboxIsolationKey.resolve(
                        IsolationScope.SESSION,
                        new io.agentscope.core.agent.RuntimeContext.Builder()
                                .sessionId("session-1")
                                .build(),
                        "agent")
                .orElseThrow();
    }

    @Test
    void builderRejectsNullDataSource() {
        assertThrows(NullPointerException.class, () -> PostgresSandboxExecutionGuard.builder(null));
    }

    @Test
    void builderWithDefaultsCreatesGuard() {
        PostgresSandboxExecutionGuard guard =
                PostgresSandboxExecutionGuard.builder(dataSource).build();
        assertNotNull(guard);
    }

    @Test
    void builderWithCustomKeyPrefix() {
        PostgresSandboxExecutionGuard guard =
                PostgresSandboxExecutionGuard.builder(dataSource)
                        .keyPrefix("custom:prefix")
                        .build();
        assertNotNull(guard);
    }

    @Test
    void builderWithKeyPrefixAlreadyEndingWithColon() {
        PostgresSandboxExecutionGuard guard =
                PostgresSandboxExecutionGuard.builder(dataSource)
                        .keyPrefix("custom:prefix:")
                        .build();
        assertNotNull(guard);
    }

    @Test
    void builderWithCustomTimeout() {
        PostgresSandboxExecutionGuard guard =
                PostgresSandboxExecutionGuard.builder(dataSource)
                        .lockTimeout(Duration.ofSeconds(5))
                        .build();
        assertNotNull(guard);
    }

    @Test
    void builderRejectsBlankKeyPrefix() {
        assertThrows(
                IllegalArgumentException.class,
                () -> PostgresSandboxExecutionGuard.builder(dataSource).keyPrefix("").build());
        assertThrows(
                IllegalArgumentException.class,
                () -> PostgresSandboxExecutionGuard.builder(dataSource).keyPrefix("  ").build());
        assertThrows(
                IllegalArgumentException.class,
                () -> PostgresSandboxExecutionGuard.builder(dataSource).keyPrefix(null).build());
    }

    @Test
    void builderRejectsNonPositiveTimeout() {
        assertThrows(
                IllegalArgumentException.class,
                () ->
                        PostgresSandboxExecutionGuard.builder(dataSource)
                                .lockTimeout(Duration.ZERO)
                                .build());
        assertThrows(
                IllegalArgumentException.class,
                () ->
                        PostgresSandboxExecutionGuard.builder(dataSource)
                                .lockTimeout(Duration.ofSeconds(-1))
                                .build());
    }

    @Test
    void tryEnterAcquiresLockAndReturnsLease() throws Exception {
        PostgresSandboxExecutionGuard guard =
                PostgresSandboxExecutionGuard.builder(dataSource).build();
        when(resultSet.next()).thenReturn(true);
        when(resultSet.getBoolean(1)).thenReturn(true);

        SandboxLease lease = guard.tryEnter(key());

        assertNotNull(lease);
        verify(preparedStatement).setLong(eq(1), anyLong());
    }

    @Test
    void tryEnterPollsUntilLockAcquired() throws Exception {
        PostgresSandboxExecutionGuard guard =
                PostgresSandboxExecutionGuard.builder(dataSource)
                        .lockTimeout(Duration.ofSeconds(10))
                        .build();
        when(resultSet.next()).thenReturn(true, true, true);
        when(resultSet.getBoolean(1)).thenReturn(false, false, true);

        SandboxLease lease = guard.tryEnter(key());

        assertNotNull(lease);
    }

    @Test
    void tryEnterPollsWhenResultSetEmptyThenAcquires() throws Exception {
        PostgresSandboxExecutionGuard guard =
                PostgresSandboxExecutionGuard.builder(dataSource)
                        .lockTimeout(Duration.ofSeconds(10))
                        .build();
        when(resultSet.next()).thenReturn(false, true);
        when(resultSet.getBoolean(1)).thenReturn(true);

        SandboxLease lease = guard.tryEnter(key());

        assertNotNull(lease);
    }

    @Test
    void tryEnterPropagatesResultSetCloseFailure() throws Exception {
        PostgresSandboxExecutionGuard guard =
                PostgresSandboxExecutionGuard.builder(dataSource)
                        .lockTimeout(Duration.ofSeconds(10))
                        .build();
        when(resultSet.next()).thenReturn(false, true);
        when(resultSet.getBoolean(1)).thenReturn(true);
        doThrow(new SQLException("close failed")).when(resultSet).close();

        assertThrows(RuntimeException.class, () -> guard.tryEnter(key()));
    }

    @Test
    void tryEnterTimesOutWhenLockNeverAcquired() throws Exception {
        PostgresSandboxExecutionGuard guard =
                PostgresSandboxExecutionGuard.builder(dataSource)
                        .lockTimeout(Duration.ofMillis(50))
                        .build();
        when(resultSet.next()).thenReturn(true);
        when(resultSet.getBoolean(1)).thenReturn(false);

        assertThrows(InterruptedException.class, () -> guard.tryEnter(key()));
    }

    @Test
    void tryEnterWrapsSqlException() throws Exception {
        PostgresSandboxExecutionGuard guard =
                PostgresSandboxExecutionGuard.builder(dataSource).build();
        when(dataSource.getConnection()).thenThrow(new SQLException("boom"));

        assertThrows(RuntimeException.class, () -> guard.tryEnter(key()));
    }

    @Test
    void leaseCloseReleasesLockAndClosesConnection() throws Exception {
        PostgresSandboxExecutionGuard guard =
                PostgresSandboxExecutionGuard.builder(dataSource).build();
        when(resultSet.next()).thenReturn(true);
        when(resultSet.getBoolean(1)).thenReturn(true);

        SandboxLease lease = guard.tryEnter(key());
        lease.close();

        verify(preparedStatement, org.mockito.Mockito.atLeast(2)).setLong(eq(1), anyLong());
        verify(connection).close();
    }

    @Test
    void leaseCloseHandlesReleaseFailure() throws Exception {
        PostgresSandboxExecutionGuard guard =
                PostgresSandboxExecutionGuard.builder(dataSource).build();
        when(resultSet.next()).thenReturn(true);
        when(resultSet.getBoolean(1)).thenReturn(true);

        SandboxLease lease = guard.tryEnter(key());
        doThrow(new SQLException("release failed")).when(preparedStatement).executeQuery();
        lease.close();

        verify(connection).close();
    }

    @Test
    void leaseCloseHandlesConnectionCloseFailure() throws Exception {
        PostgresSandboxExecutionGuard guard =
                PostgresSandboxExecutionGuard.builder(dataSource).build();
        when(resultSet.next()).thenReturn(true);
        when(resultSet.getBoolean(1)).thenReturn(true);

        SandboxLease lease = guard.tryEnter(key());
        doThrow(new SQLException("close failed")).when(connection).close();

        lease.close();

        verify(connection).close();
    }

    @Test
    void tryEnterHandlesInnerSQLException() throws Exception {
        PostgresSandboxExecutionGuard guard =
                PostgresSandboxExecutionGuard.builder(dataSource).build();
        when(connection.prepareStatement(anyString())).thenThrow(new SQLException("boom"));

        assertThrows(RuntimeException.class, () -> guard.tryEnter(key()));
    }

    @Test
    void tryEnterHandlesInnerInterruptedException() throws Exception {
        PostgresSandboxExecutionGuard guard =
                PostgresSandboxExecutionGuard.builder(dataSource)
                        .lockTimeout(Duration.ofSeconds(10))
                        .build();
        when(resultSet.next()).thenReturn(true);
        when(resultSet.getBoolean(1)).thenReturn(false);

        Thread.currentThread().interrupt();
        try {
            assertThrows(InterruptedException.class, () -> guard.tryEnter(key()));
        } finally {
            Thread.interrupted();
        }
    }

    @Test
    void tryEnterHandlesInnerSQLExceptionAndCloseFailure() throws Exception {
        PostgresSandboxExecutionGuard guard =
                PostgresSandboxExecutionGuard.builder(dataSource).build();
        when(connection.prepareStatement(anyString())).thenThrow(new SQLException("boom"));
        doThrow(new SQLException("close failed")).when(connection).close();

        assertThrows(RuntimeException.class, () -> guard.tryEnter(key()));
    }

    @Test
    void tryEnterHandlesInnerInterruptedExceptionAndCloseFailure() throws Exception {
        PostgresSandboxExecutionGuard guard =
                PostgresSandboxExecutionGuard.builder(dataSource)
                        .lockTimeout(Duration.ofSeconds(10))
                        .build();
        when(resultSet.next()).thenReturn(true);
        when(resultSet.getBoolean(1)).thenReturn(false);
        doThrow(new SQLException("close failed")).when(connection).close();

        Thread.currentThread().interrupt();
        try {
            assertThrows(InterruptedException.class, () -> guard.tryEnter(key()));
        } finally {
            Thread.interrupted();
        }
    }
}
