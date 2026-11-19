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
package io.agentscope.extensions.channel.common;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

/** Tests for the default in-process {@link InMemoryAccessTokenStore}. */
class InMemoryAccessTokenStoreTest {

    @Test
    void putThenGetReturnsLatestEntry() {
        InMemoryAccessTokenStore store = new InMemoryAccessTokenStore();
        assertNull(store.get());
        CachedAccessToken first = new CachedAccessToken("t1", 100L);
        store.put(first);
        assertEquals(first, store.get());
        CachedAccessToken second = new CachedAccessToken("t2", 200L);
        store.put(second);
        assertEquals(second, store.get());
    }

    @Test
    void clearDropsTheEntry() {
        InMemoryAccessTokenStore store = new InMemoryAccessTokenStore();
        store.put(new CachedAccessToken("t1", 100L));
        store.clear();
        assertNull(store.get());
    }

    @Test
    void nullTokenIsRejected() {
        InMemoryAccessTokenStore store = new InMemoryAccessTokenStore();
        assertThrows(NullPointerException.class, () -> store.put(null));
        assertThrows(NullPointerException.class, () -> store.putIfNewer(null));
        assertNull(store.get());
    }

    @Test
    void putIfNewerStoresOnlyFresherEntries() {
        InMemoryAccessTokenStore store = new InMemoryAccessTokenStore();
        assertTrue(store.putIfNewer(new CachedAccessToken("t1", 100L)));
        assertFalse(store.putIfNewer(new CachedAccessToken("t0", 50L)));
        assertEquals("t1", store.get().value());
        assertTrue(store.putIfNewer(new CachedAccessToken("t2", 200L)));
        assertEquals("t2", store.get().value());
    }
}
