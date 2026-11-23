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

package io.agentscope.extensions.durable.mcp;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

import io.agentscope.core.tool.mcp.McpClientWrapper;
import io.agentscope.durable.DurableException;
import io.agentscope.durable.DurableJson;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

class McpConnectionsTest {
    @Test
    void rotationAndShutdownWaitForEveryBorrowerAndCapacityIncludesRetiringClients() {
        var clients = new ArrayList<McpClientWrapper>();
        var pool =
                new McpConnections(
                        2,
                        Duration.ofMinutes(1),
                        config -> {
                            var client = mock(McpClientWrapper.class);
                            clients.add(client);
                            return client;
                        });
        var oldConfig = DurableJson.value(Map.of("credentialRevision", 1));
        var newConfig = DurableJson.value(Map.of("credentialRevision", 2));
        try (pool;
                var old = pool.borrow("tenant/server", oldConfig);
                var same = pool.borrow("tenant/server", oldConfig);
                var replacement = pool.borrow("tenant/server", newConfig)) {
            assertSame(old.client(), same.client());
            assertEquals(2, clients.size());
            assertEquals(1, pool.status().retiring());
            verify(old.client(), never()).close();
            assertEquals(
                    DurableException.Code.UNAVAILABLE,
                    assertThrows(DurableException.class, () -> pool.borrow("another", oldConfig))
                            .code());
            old.close();
            verify(clients.get(0), never()).close();
            same.close();
            verify(clients.get(0), times(1)).close();
            try (var another = pool.borrow("another", oldConfig)) {
                assertEquals(2, pool.status().connections());
                pool.close();
                assertTrue(pool.status().closed());
                verify(replacement.client(), never()).close();
                verify(another.client(), never()).close();
            }
            assertThrows(DurableException.class, () -> pool.borrow("tenant/server", newConfig));
        }
        clients.forEach(client -> verify(client, times(1)).close());
        assertEquals(0, pool.status().connections());
    }

    @Test
    void concurrentBorrowersShareInitializationAndDoNotHoldTheGlobalPoolLock() throws Exception {
        var opening = new CountDownLatch(1);
        var release = new CountDownLatch(1);
        var clients = new CopyOnWriteArrayList<McpClientWrapper>();
        var threads = Executors.newFixedThreadPool(3);
        try (var pool =
                new McpConnections(
                        2,
                        Duration.ofMinutes(1),
                        config -> {
                            var client = mock(McpClientWrapper.class);
                            clients.add(client);
                            if (config.path("slow").asBoolean()) {
                                opening.countDown();
                                try {
                                    if (!release.await(5, TimeUnit.SECONDS))
                                        throw new IllegalStateException("fixture timeout");
                                } catch (InterruptedException interrupted) {
                                    Thread.currentThread().interrupt();
                                    throw new IllegalStateException(interrupted);
                                }
                            }
                            return client;
                        })) {
            var slow = DurableJson.value(Map.of("slow", true));
            var first = threads.submit(() -> pool.borrow("slow", slow));
            assertTrue(opening.await(2, TimeUnit.SECONDS));
            var second = threads.submit(() -> pool.borrow("slow", slow));
            try (var fast = pool.borrow("fast", DurableJson.value(Map.of()))) {
                assertEquals(2, clients.size());
                assertEquals(1, pool.status().initializing());
                release.countDown();
                try (var a = first.get(2, TimeUnit.SECONDS);
                        var b = second.get(2, TimeUnit.SECONDS)) {
                    assertSame(a.client(), b.client());
                    assertEquals(2, clients.size());
                }
            }
        } finally {
            release.countDown();
            threads.shutdownNow();
        }
        clients.forEach(client -> verify(client, times(1)).close());
    }

    @Test
    void failedInitializationReleasesCapacityAndUnusedConnectionsCanBeEvicted() {
        var attempts = new AtomicInteger();
        var clients = new ArrayList<McpClientWrapper>();
        try (var pool =
                new McpConnections(
                        1,
                        Duration.ofMinutes(1),
                        config -> {
                            if (attempts.incrementAndGet() == 1)
                                throw new IllegalStateException("connect unavailable");
                            var client = mock(McpClientWrapper.class);
                            clients.add(client);
                            return client;
                        })) {
            var config = DurableJson.value(Map.of());
            assertThrows(DurableException.class, () -> pool.borrow("first", config));
            assertEquals(0, pool.status().connections());
            try (var first = pool.borrow("first", config)) {
                assertSame(clients.get(0), first.client());
            }
            try (var second = pool.borrow("second", config)) {
                assertEquals(3, attempts.get());
                verify(clients.get(0), times(1)).close();
                verify(second.client(), never()).close();
            }
        }
    }

    @Test
    void closeDuringInitializationClosesTheConnectionWhenItArrives() throws Exception {
        var started = new CountDownLatch(1);
        var release = new CountDownLatch(1);
        var client = mock(McpClientWrapper.class);
        var thread = Executors.newSingleThreadExecutor();
        try (var pool =
                new McpConnections(
                        1,
                        Duration.ofMinutes(1),
                        config -> {
                            started.countDown();
                            try {
                                release.await(3, TimeUnit.SECONDS);
                            } catch (InterruptedException failure) {
                                throw new IllegalStateException(failure);
                            }
                            return client;
                        })) {
            var result =
                    thread.submit(
                            () ->
                                    assertThrows(
                                            DurableException.class,
                                            () ->
                                                    pool.borrow(
                                                            "server",
                                                            DurableJson.value(Map.of()))));
            assertTrue(started.await(2, TimeUnit.SECONDS));
            pool.close();
            release.countDown();
            result.get(2, TimeUnit.SECONDS);
            verify(client, times(1)).close();
            assertEquals(0, pool.status().connections());
        } finally {
            release.countDown();
            thread.shutdownNow();
        }
    }
}
