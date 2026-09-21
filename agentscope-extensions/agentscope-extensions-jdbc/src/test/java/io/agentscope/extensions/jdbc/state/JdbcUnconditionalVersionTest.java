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
package io.agentscope.extensions.jdbc.state;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import io.agentscope.core.state.AgentStateStore;
import io.agentscope.core.state.State;
import io.agentscope.extensions.jdbc.H2TestSupport;
import io.agentscope.extensions.jdbc.dialect.vendor.H2Dialect;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.sql.Connection;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import javax.sql.DataSource;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

class JdbcUnconditionalVersionTest {
    record Value(String text) implements State {}

    @Test
    @Timeout(30)
    void concurrentStoreInstancesReturnUniqueContiguousVersions() throws Exception {
        DataSource ds = H2TestSupport.createDataSource("unconditional_contention");
        JdbcAgentStateStore reader = new JdbcAgentStateStore(ds, new H2Dialect(), true);
        reader.save("u", "s", "k", new Value("initial"));
        int workers = 4;
        int rounds = 25;
        CyclicBarrier start = new CyclicBarrier(workers);
        var executor = Executors.newFixedThreadPool(workers);
        List<Future<List<Map.Entry<Long, Value>>>> futures = new ArrayList<>();
        try {
            for (int worker = 0; worker < workers; worker++) {
                int id = worker;
                JdbcAgentStateStore writer = new JdbcAgentStateStore(ds, new H2Dialect(), true);
                futures.add(
                        executor.submit(
                                () -> {
                                    List<Map.Entry<Long, Value>> writes = new ArrayList<>();
                                    for (int round = 0; round < rounds; round++) {
                                        start.await(10, TimeUnit.SECONDS);
                                        Value value = new Value(id + ":" + round);
                                        long version =
                                                writer.saveIfVersion(
                                                        "u",
                                                        "s",
                                                        "k",
                                                        value,
                                                        AgentStateStore.UNVERSIONED);
                                        writes.add(Map.entry(version, value));
                                    }
                                    return writes;
                                }));
            }
            Map<Long, Value> writesByVersion = new HashMap<>();
            for (var future : futures) {
                for (var write : future.get(15, TimeUnit.SECONDS)) {
                    assertNull(
                            writesByVersion.put(write.getKey(), write.getValue()),
                            "duplicate version");
                }
            }
            assertEquals(workers * rounds, writesByVersion.size());
            for (long version = 2; version <= workers * rounds + 1; version++) {
                org.junit.jupiter.api.Assertions.assertTrue(writesByVersion.containsKey(version));
            }
            var latest = reader.getVersioned("u", "s", "k", Value.class);
            assertEquals(workers * rounds + 1, latest.version());
            assertEquals(writesByVersion.get(latest.version()), latest.value());
            assertEquals(
                    AgentStateStore.UNVERSIONED,
                    reader.saveIfVersion("u", "s", "k", new Value("stale"), latest.version() - 1));
            assertEquals(latest, reader.getVersioned("u", "s", "k", Value.class));
        } finally {
            executor.shutdownNow();
        }
    }

    @Test
    @Timeout(30)
    void returnsOwnVersionEvenWhenAnotherWriterCommitsBeforeConnectionCloses() throws Exception {
        DataSource delegate = H2TestSupport.createDataSource("unconditional_versions");
        new JdbcAgentStateStore(delegate, new H2Dialect(), true)
                .save("u", "s", "k", new Value("initial"));
        CyclicBarrier committed = new CyclicBarrier(2);
        DataSource coordinated =
                (DataSource)
                        Proxy.newProxyInstance(
                                DataSource.class.getClassLoader(),
                                new Class<?>[] {DataSource.class},
                                (proxy, method, args) -> {
                                    Object result = invoke(delegate, method, args);
                                    if (!(result instanceof Connection connection)) {
                                        return result;
                                    }
                                    AtomicBoolean wrote = new AtomicBoolean();
                                    return Proxy.newProxyInstance(
                                            Connection.class.getClassLoader(),
                                            new Class<?>[] {Connection.class},
                                            (p, m, a) -> {
                                                if (m.getName().equals("prepareStatement")
                                                        && ((String) a[0])
                                                                .startsWith("MERGE INTO")) {
                                                    wrote.set(true);
                                                }
                                                Object value = invoke(connection, m, a);
                                                // The database connection is closed and locks are
                                                // released first.
                                                // Both real writes finish before either
                                                // saveIfVersion can return.
                                                if (m.getName().equals("close") && wrote.get()) {
                                                    committed.await(10, TimeUnit.SECONDS);
                                                }
                                                return value;
                                            });
                                });
        JdbcAgentStateStore first = new JdbcAgentStateStore(coordinated, new H2Dialect(), true);
        JdbcAgentStateStore second = new JdbcAgentStateStore(coordinated, new H2Dialect(), true);
        var executor = Executors.newFixedThreadPool(2);
        try {
            var a =
                    executor.submit(
                            () ->
                                    first.saveIfVersion(
                                            "u",
                                            "s",
                                            "k",
                                            new Value("A"),
                                            AgentStateStore.UNVERSIONED));
            var b =
                    executor.submit(
                            () ->
                                    second.saveIfVersion(
                                            "u",
                                            "s",
                                            "k",
                                            new Value("B"),
                                            AgentStateStore.UNVERSIONED));
            long av = a.get(15, TimeUnit.SECONDS);
            long bv = b.get(15, TimeUnit.SECONDS);
            assertNotEquals(av, bv, "each successful write must return its own version");
            assertEquals(Set.of(2L, 3L), Set.of(av, bv));
            var latest = first.getVersioned("u", "s", "k", Value.class);
            assertEquals(3L, latest.version());
            assertEquals(av == 3 ? "A" : "B", latest.value().text());
            assertEquals(
                    AgentStateStore.UNVERSIONED,
                    first.saveIfVersion("u", "s", "k", new Value("stale"), 2));
            assertEquals(latest, first.getVersioned("u", "s", "k", Value.class));
        } finally {
            executor.shutdownNow();
        }
    }

    private static Object invoke(Object target, Method method, Object[] args) throws Throwable {
        try {
            return method.invoke(target, args);
        } catch (InvocationTargetException e) {
            throw e.getCause();
        }
    }
}
