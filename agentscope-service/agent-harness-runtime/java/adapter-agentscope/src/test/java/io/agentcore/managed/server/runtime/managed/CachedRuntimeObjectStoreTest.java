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
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;

class CachedRuntimeObjectStoreTest {
    private RuntimeObjectStore backend() {
        var backend = mock(RuntimeObjectStore.class);
        when(backend.backendId()).thenReturn("test");
        when(backend.put(anyString(), anyString(), anyString(), any(byte[].class)))
                .thenAnswer(
                        call ->
                                new RuntimeObjectStore.Reference(
                                        "test",
                                        call.getArgument(0),
                                        call.getArgument(1),
                                        call.getArgument(2),
                                        RuntimeObjectStore.sha256(call.getArgument(3)),
                                        ((byte[]) call.getArgument(3)).length));
        return backend;
    }

    @Test
    void immutableContentIsCopiedAndConflictsAreRejectedWithoutNetwork() {
        var backend = backend();
        var cache = new CachedRuntimeObjectStore(backend, 8, 8, new SimpleMeterRegistry());
        byte[] input = {1, 2};
        var ref = cache.put("s", "state", "a", input);
        input[0] = 9;
        var output = cache.read(ref);
        output[0] = 8;
        assertArrayEquals(new byte[] {1, 2}, cache.read("s", "state", "a"));
        assertEquals(ref, cache.put("s", "state", "a", new byte[] {1, 2}));
        assertThrows(
                ManagedStateException.class, () -> cache.put("s", "state", "a", new byte[] {9, 2}));
        assertThrows(
                ManagedStateException.class,
                () ->
                        cache.read(
                                new RuntimeObjectStore.Reference(
                                        "other", "s", "state", "a", ref.sha256(), 2)));
        assertThrows(
                ManagedStateException.class,
                () ->
                        cache.read(
                                new RuntimeObjectStore.Reference(
                                        "test", "s", "state", "a", "0".repeat(64), 2)));
        verify(backend, times(1)).put(anyString(), anyString(), anyString(), any(byte[].class));
    }

    @Test
    void lruEvictionAndItemBudgetBoundMemoryAndRespectScopes() {
        var backend = backend();
        var cache = new CachedRuntimeObjectStore(backend, 4, 2, new SimpleMeterRegistry());
        cache.put("s", "state", "a", new byte[] {1, 2});
        cache.put("s", "state", "b", new byte[] {3, 4});
        cache.read("s", "state", "a");
        cache.put("other", "state", "a", new byte[] {5, 6});
        when(backend.read("s", "state", "b")).thenReturn(new byte[] {3, 4});
        assertArrayEquals(new byte[] {3, 4}, cache.read("s", "state", "b"));
        verify(backend).read("s", "state", "b");
        cache.put("s", "state", "large", new byte[] {1, 2, 3});
        assertEquals(4, cache.bytes());
        assertEquals(2, cache.size());
        cache.close();
        assertEquals(0, cache.bytes());
        verify(backend).close();
    }

    @Test
    void zeroBudgetDisablesContentCaching() {
        var backend = backend();
        var cache = new CachedRuntimeObjectStore(backend, 0, 2, new SimpleMeterRegistry());
        cache.put("s", "state", "a", new byte[] {1});
        assertEquals(0, cache.size());
        when(backend.read("s", "state", "a")).thenReturn(new byte[] {1});
        cache.read("s", "state", "a");
        verify(backend).read("s", "state", "a");
    }
}
