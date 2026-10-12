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
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

/** Tests for the default in-process {@link IdempotencyStore} implementation. */
class IdempotencyStoreTest {

    @Test
    void firstObservationIsAllowedAndRedeliveryIsDropped() {
        IdempotencyStore store = new IdempotencyStore();
        assertTrue(store.firstSeen("ch|1"));
        assertFalse(store.firstSeen("ch|1"));
        assertTrue(store.firstSeen("ch|2"));
    }

    @Test
    void nullKeyIsAlwaysFirstSeen() {
        IdempotencyStore store = new IdempotencyStore();
        assertTrue(store.firstSeen(null));
        assertTrue(store.firstSeen(null));
    }

    @Test
    void entriesExpireAfterTtl() throws InterruptedException {
        IdempotencyStore store = new IdempotencyStore(80, 100);
        assertTrue(store.firstSeen("ch|1"));
        assertFalse(store.firstSeen("ch|1"));
        Thread.sleep(160);
        assertTrue(store.firstSeen("ch|1"));
        // The accepted observation restarts the retention window: an immediate redelivery drops.
        assertFalse(store.firstSeen("ch|1"));
    }

    @Test
    void sizeIsBoundedByMaxEntries() {
        IdempotencyStore store = new IdempotencyStore(60_000, 3);
        assertTrue(store.firstSeen("a"));
        assertTrue(store.firstSeen("b"));
        assertTrue(store.firstSeen("c"));
        assertEquals(3, store.size());
        assertTrue(store.firstSeen("d"));
        assertEquals(3, store.size());
    }
}
