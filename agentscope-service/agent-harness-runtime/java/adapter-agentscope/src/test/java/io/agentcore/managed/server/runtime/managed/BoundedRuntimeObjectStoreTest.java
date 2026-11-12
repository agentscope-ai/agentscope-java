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

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.time.Duration;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;

class BoundedRuntimeObjectStoreTest {
    @Test
    void unknownReadsCannotExceedByteBudgetAndActualBytesAreMeasured() throws Exception {
        var delegate = mock(RuntimeObjectStore.class);
        var metrics = new SimpleMeterRegistry();
        var entered = new CountDownLatch(1);
        var release = new CountDownLatch(1);
        when(delegate.read("s", "native", "a"))
                .thenAnswer(
                        call -> {
                            entered.countDown();
                            assertTrue(release.await(3, TimeUnit.SECONDS));
                            return new byte[] {1};
                        });
        var store =
                new BoundedRuntimeObjectStore(
                        delegate,
                        4,
                        RuntimeObjectStore.MAX_BYTES,
                        Duration.ofMillis(40),
                        metrics,
                        "shared",
                        true);
        var executor = Executors.newSingleThreadExecutor();
        try {
            var first = executor.submit(() -> store.read("s", "native", "a"));
            assertTrue(entered.await(1, TimeUnit.SECONDS));
            assertEquals(RuntimeObjectStore.MAX_BYTES, store.inflightBytes());
            assertThrows(
                    ManagedTransportException.class,
                    () -> store.put("s", "native", "b", new byte[] {2}));
            assertEquals(0, store.waiting());
            assertEquals(1, store.active());
            release.countDown();
            assertArrayEquals(new byte[] {1}, first.get(2, TimeUnit.SECONDS));
            assertEquals(0, store.active());
            assertEquals(0, store.inflightBytes());
            assertEquals(
                    1,
                    metrics.get("managed.agents.object.store.bytes")
                            .tag("operation", "get")
                            .counter()
                            .count());
            assertEquals(
                    1,
                    metrics.get("managed.agents.object.store.capacity.timeouts").counter().count());
        } finally {
            release.countDown();
            executor.shutdownNow();
            store.close();
        }
        verify(delegate).close();
    }

    @Test
    void providerFailuresReleaseCapacityAndProjectionCannotCloseTheSharedClient() {
        var delegate = mock(RuntimeObjectStore.class);
        var metrics = new SimpleMeterRegistry();
        when(delegate.read("s", "native", "a"))
                .thenThrow(new ManagedTransportException("unavailable"));
        var store =
                new BoundedRuntimeObjectStore(
                        delegate,
                        1,
                        RuntimeObjectStore.MAX_BYTES,
                        Duration.ofSeconds(1),
                        metrics,
                        "projection",
                        false);
        assertThrows(ManagedTransportException.class, () -> store.read("s", "native", "a"));
        assertEquals(0, store.active());
        assertEquals(0, store.inflightBytes());
        assertEquals(
                1,
                metrics.get("managed.agents.object.store.requests")
                        .tag("outcome", "error")
                        .timer()
                        .count());
        store.exists("s", "native", "b");
        store.close();
        verify(delegate, never()).close();
    }

    @Test
    void invalidBudgetsFailBeforeAnyRequest() {
        var delegate = mock(RuntimeObjectStore.class);
        var metrics = new SimpleMeterRegistry();
        assertThrows(
                IllegalArgumentException.class,
                () ->
                        new BoundedRuntimeObjectStore(
                                delegate,
                                0,
                                RuntimeObjectStore.MAX_BYTES,
                                Duration.ofSeconds(1),
                                metrics,
                                "shared",
                                true));
        assertThrows(
                IllegalArgumentException.class,
                () ->
                        new BoundedRuntimeObjectStore(
                                delegate, 1, 1, Duration.ofSeconds(1), metrics, "shared", true));
        assertThrows(
                IllegalArgumentException.class,
                () ->
                        new BoundedRuntimeObjectStore(
                                delegate,
                                1,
                                RuntimeObjectStore.MAX_BYTES,
                                Duration.ZERO,
                                metrics,
                                "shared",
                                true));
        verifyNoInteractions(delegate);
    }
}
