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
package io.agentscope.claw2.runtime.channel.common;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class IdempotencyStoreTest {

    @Test
    void firstSeenReturnsTrueOnce() {
        IdempotencyStore store = new IdempotencyStore();
        assertTrue(store.firstSeen("m1"));
        assertFalse(store.firstSeen("m1"));
        assertTrue(store.firstSeen("m2"));
    }

    @Test
    void nullKeyIsAlwaysFirstSeen() {
        IdempotencyStore store = new IdempotencyStore();
        assertTrue(store.firstSeen(null));
        assertTrue(store.firstSeen(null));
    }

    @Test
    void boundedSizeEvictsWhenFull() throws InterruptedException {
        IdempotencyStore store = new IdempotencyStore(50L, 5);
        for (int i = 0; i < 5; i++) {
            assertTrue(store.firstSeen("k" + i));
        }
        // Sleep past the TTL so the sweep can evict.
        Thread.sleep(80);
        // Adding a sixth entry should trigger a sweep that drops expired entries.
        assertTrue(store.firstSeen("k5"));
        // Original entries are no longer remembered after expiry.
        assertTrue(store.firstSeen("k0"));
    }
}
