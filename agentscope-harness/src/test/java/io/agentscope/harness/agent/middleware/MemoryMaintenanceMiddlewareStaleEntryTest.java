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
package io.agentscope.harness.agent.middleware;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.agentscope.harness.agent.coordination.LocalPeriodicGate;
import java.time.Duration;
import java.time.Instant;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/** Unit tests for {@link LocalPeriodicGate#cleanupStaleEntries(Duration)}. */
class MemoryMaintenanceMiddlewareStaleEntryTest {

    private final LocalPeriodicGate gate = new LocalPeriodicGate();

    @BeforeEach
    void resetSharedMap() {
        LocalPeriodicGate.clearForTests();
    }

    @Test
    void cleanupStaleEntries_removesOldEntries() {
        LocalPeriodicGate.seedLastClaimAtForTests("USER:staleUser", Instant.EPOCH);

        gate.cleanupStaleEntries(Duration.ofMinutes(60));

        assertFalse(
                LocalPeriodicGate.hasClaimForTests("USER:staleUser"),
                "stale entry (EPOCH timestamp) should be removed");
    }

    @Test
    void cleanupStaleEntries_preservesRecentEntries() {
        gate.tryClaim("USER:recentUser", Duration.ZERO);

        gate.cleanupStaleEntries(Duration.ofMinutes(60));

        assertTrue(
                LocalPeriodicGate.hasClaimForTests("USER:recentUser"),
                "recent entry should survive cleanup");
    }

    @Test
    void cleanupStaleEntries_onlyRemovesStaleEntries() {
        LocalPeriodicGate.seedLastClaimAtForTests("USER:staleUser", Instant.EPOCH);
        gate.tryClaim("USER:recentUser", Duration.ZERO);

        gate.cleanupStaleEntries(Duration.ofMinutes(60));

        assertFalse(
                LocalPeriodicGate.hasClaimForTests("USER:staleUser"),
                "stale entry should be removed");
        assertTrue(
                LocalPeriodicGate.hasClaimForTests("USER:recentUser"),
                "recent entry should survive cleanup");
    }
}
