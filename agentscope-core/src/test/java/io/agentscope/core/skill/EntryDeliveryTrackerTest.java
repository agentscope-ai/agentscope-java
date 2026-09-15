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
package io.agentscope.core.skill;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** Pins the {@link EntryDeliveryTracker} contract: atomic claims, re-marking, bounded scopes. */
class EntryDeliveryTrackerTest {

    @Test
    @DisplayName("First claim wins; overlapping claims and later loads observe delivered")
    void firstClaimWins() {
        EntryDeliveryTracker tracker = new EntryDeliveryTracker();
        assertTrue(tracker.tryClaim("u1::s1", "skillA"), "first claim delivers");
        assertFalse(tracker.tryClaim("u1::s1", "skillA"), "second claim dedups");
        assertTrue(tracker.tryClaim("u1::s2", "skillA"), "other session claims independently");
        assertTrue(tracker.tryClaim("u1::s1", "skillB"), "other skill claims independently");
    }

    @Test
    @DisplayName("mark() re-arms delivery without a claim (explicit reload)")
    void markReArmsAfterClaim() {
        EntryDeliveryTracker tracker = new EntryDeliveryTracker();
        tracker.tryClaim("u1::s1", "skillA");
        tracker.mark("u1::s1", "skillA");
        assertFalse(tracker.tryClaim("u1::s1", "skillA"), "mark keeps the scope delivered");
        assertTrue(tracker.isDelivered("u1::s1", "skillA"));
    }

    @Test
    @DisplayName("Scope count stays bounded under many conversations (LRU eviction)")
    void scopesAreBounded() {
        EntryDeliveryTracker tracker = new EntryDeliveryTracker();
        for (int i = 0; i < 10_000; i++) {
            tracker.tryClaim("user" + i + "::session" + i, "skillA");
        }
        assertTrue(
                tracker.scopeCount() <= EntryDeliveryTracker.MAX_SCOPES,
                "scope count must stay <= cap, was " + tracker.scopeCount());
        // Eviction is safe-directional: the oldest scope was forgotten, so its next
        // load re-delivers instead of being suppressed.
        assertFalse(tracker.isDelivered("user0::session0", "skillA"));
        assertTrue(tracker.isDelivered("user9999::session9999", "skillA"));
    }
}
