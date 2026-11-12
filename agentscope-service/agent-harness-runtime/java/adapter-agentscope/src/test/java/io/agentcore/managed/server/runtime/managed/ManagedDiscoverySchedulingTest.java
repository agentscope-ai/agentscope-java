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
package io.agentcore.managed.server.runtime.managed;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.timeout;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Duration;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.IntStream;
import org.junit.jupiter.api.Test;

class ManagedDiscoverySchedulingTest {
    @Test
    void oneQueueWorkerStartsManyDiscoveriesAndResumesOnAWorkerAfterNotification()
            throws Exception {
        var queue = mock(ManagedSessionQueue.class);
        var handler = mock(ManagedHarnessRuntimeTurns.Handler.class);
        var scopes =
                IntStream.range(0, 32).mapToObj(i -> new ManagedScope("t", "n", "s" + i)).toList();
        var acquired = ConcurrentHashMap.<ManagedScope>newKeySet();
        var pending = new ConcurrentHashMap<ManagedScope, CompletableFuture<Void>>();
        when(queue.due(anyInt()))
                .thenAnswer(ignored -> scopes.stream().filter(s -> !acquired.contains(s)).toList());
        when(queue.acquire(any()))
                .thenAnswer(
                        invocation -> {
                            ManagedScope scope = invocation.getArgument(0);
                            acquired.add(scope);
                            return new ManagedSessionQueue.Dispatch(scope, "token", 1);
                        });
        when(queue.candidate(any())).thenReturn("turn");
        when(handler.prepare(any(), eq("turn")))
                .thenAnswer(
                        invocation -> {
                            ManagedScope scope = invocation.getArgument(0);
                            var future = new CompletableFuture<Void>();
                            pending.put(scope, future);
                            return future;
                        });
        var advanced = ConcurrentHashMap.<ManagedScope>newKeySet();
        doAnswer(
                        invocation -> {
                            assertNotEquals(
                                    "test-discovery-transport", Thread.currentThread().getName());
                            advanced.add(invocation.getArgument(0));
                            return null;
                        })
                .when(handler)
                .advance(any(), eq("turn"));
        try (var loop = new ManagedSessionQueueLoop(queue, handler, 1)) {
            long deadline = System.nanoTime() + Duration.ofSeconds(5).toNanos();
            while (pending.size() < 32 && System.nanoTime() < deadline) {
                loop.scan();
                Thread.sleep(10);
            }
            assertEquals(32, pending.size());
            assertTrue(advanced.isEmpty());
            var ready = scopes.get(0);
            var transport =
                    new Thread(() -> pending.get(ready).complete(null), "test-discovery-transport");
            transport.start();
            transport.join();
            verify(handler, timeout(2000)).advance(ready, "turn");
            verify(queue, timeout(2000))
                    .release(new ManagedSessionQueue.Dispatch(ready, "token", 1));
        }
        assertTrue(pending.values().stream().allMatch(CompletableFuture::isDone));
        assertEquals(Set.of(scopes.get(0)), advanced);
    }

    @Test
    void mqPreparationSuspendsWithoutBlockingOrRepeatingDiscoveryAndCancelsOnClose() {
        var futures = new ConcurrentHashMap<String, CompletableFuture<Void>>();
        try (var preparation = new ManagedTurnPreparation()) {
            for (int i = 0; i < 24; i++) {
                String key = "resume-" + i;
                var future = new CompletableFuture<Void>();
                futures.put(key, future);
                assertThrows(
                        ManagedDeliverySuspended.class,
                        () -> preparation.require(key, () -> future));
                assertThrows(
                        ManagedDeliverySuspended.class,
                        () ->
                                preparation.require(
                                        key,
                                        () -> {
                                            throw new AssertionError("Repeated discovery");
                                        }));
            }
            futures.get("resume-0").complete(null);
            preparation.require(
                    "resume-0",
                    () -> {
                        throw new AssertionError("Repeated discovery");
                    });
            preparation.forget("resume-0");
        }
        assertTrue(futures.values().stream().allMatch(CompletableFuture::isDone));
    }
}
