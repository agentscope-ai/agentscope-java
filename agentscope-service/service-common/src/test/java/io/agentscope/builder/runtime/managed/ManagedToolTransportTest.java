/*
 * Copyright 2024-2026 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package io.agentscope.builder.runtime.managed;

import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.util.UUID;
import javax.sql.DataSource;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.jdbc.datasource.DelegatingDataSource;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

class ManagedToolTransportTest {
    @TempDir Path root;

    protected DataSource database() throws Exception {
        return new DriverManagerDataSource(
                "jdbc:h2:mem:direct-" + UUID.randomUUID() + ";MODE=PostgreSQL;DB_CLOSE_DELAY=-1",
                "sa",
                "");
    }

    protected void closeDatabase() throws Exception {}

    @Test
    void sendsFailClosedUntilThePublisherIsConfigured() throws Exception {
        var source = database();
        try (var transport = new ManagedToolTransport(source, new ObjectMapper())) {
            assertThrows(ManagedTransportException.class, () -> transport.send(null));
        } finally {
            closeDatabase();
        }
    }

    @Test
    void cancellationPublishesDirectlyWithoutAnOutboxScan() throws Exception {
        var source = database();
        ManagedAdmissionStore.initialize(source);
        var mapper = new ObjectMapper();
        var bus = mock(ManagedMessageBus.class);
        when(bus.publish(any(), any())).thenReturn(new ManagedMessageBus.Published("broker"));
        var scope = new ManagedScope("t", "n", "s");
        var task =
                new ManagedJournalStore.ToolTask(
                        "task", "turn", "call", "task", "exec", "{}", 1, "ready", null, null, null);
        try (var transport = new ManagedToolTransport(source, mapper)) {
            transport.configure(bus, new ManagedBatchCodec(mapper, root));
            transport.stage(new ManagedToolCommand(scope, "cancel_task", "cancel", task, null));
            verify(bus).publish(eq(ManagedMessageBus.Channel.TOOL_CONTROL), any());
        } finally {
            closeDatabase();
        }
    }

    @FunctionalInterface
    interface SqlGate {
        void accept(String sql, boolean before) throws Exception;
    }

    static DataSource gated(DataSource source, SqlGate hook) {
        return new DelegatingDataSource(source) {
            @Override
            public Connection getConnection() throws SQLException {
                var connection = super.getConnection();
                return (Connection)
                        Proxy.newProxyInstance(
                                Connection.class.getClassLoader(),
                                new Class<?>[] {Connection.class},
                                (proxy, method, args) -> {
                                    Object result = invoke(connection, method, args);
                                    if (!method.getName().equals("prepareStatement")) return result;
                                    String sql = (String) args[0];
                                    var statement = (PreparedStatement) result;
                                    return Proxy.newProxyInstance(
                                            PreparedStatement.class.getClassLoader(),
                                            new Class<?>[] {PreparedStatement.class},
                                            (p, m, a) -> {
                                                boolean execute = m.getName().startsWith("execute");
                                                if (execute) hook.accept(sql, true);
                                                Object value = invoke(statement, m, a);
                                                if (execute) hook.accept(sql, false);
                                                return value;
                                            });
                                });
            }
        };
    }

    static Object invoke(Object target, Method method, Object[] args) throws Throwable {
        try {
            return method.invoke(target, args);
        } catch (InvocationTargetException failure) {
            throw failure.getCause();
        }
    }
}
