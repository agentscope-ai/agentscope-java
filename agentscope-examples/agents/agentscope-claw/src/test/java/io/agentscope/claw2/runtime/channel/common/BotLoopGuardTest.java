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

class BotLoopGuardTest {

    @Test
    void allowsUpToCapAndThenTripsCooldown() {
        BotLoopGuard guard = new BotLoopGuard(3, 60_000L, 60_000L);
        assertTrue(guard.allow("p1"));
        assertTrue(guard.allow("p1"));
        assertTrue(guard.allow("p1"));
        // Fourth event in the window trips cooldown.
        assertFalse(guard.allow("p1"));
        assertTrue(guard.isCoolingDown("p1"));
        // Subsequent events stay blocked while cooling down.
        assertFalse(guard.allow("p1"));
    }

    @Test
    void perPeerIndependent() {
        BotLoopGuard guard = new BotLoopGuard(2, 60_000L, 60_000L);
        assertTrue(guard.allow("p1"));
        assertTrue(guard.allow("p1"));
        assertFalse(guard.allow("p1"));
        // p2 has a fresh window.
        assertTrue(guard.allow("p2"));
        assertTrue(guard.allow("p2"));
        assertFalse(guard.allow("p2"));
    }

    @Test
    void cooldownExpires() throws InterruptedException {
        BotLoopGuard guard = new BotLoopGuard(1, 60_000L, 50L);
        assertTrue(guard.allow("p1"));
        assertFalse(guard.allow("p1"));
        Thread.sleep(80);
        assertTrue(guard.allow("p1"));
    }

    @Test
    void nullPeerAllowed() {
        BotLoopGuard guard = new BotLoopGuard(1, 60_000L, 60_000L);
        assertTrue(guard.allow(null));
        assertTrue(guard.allow("  "));
    }
}
