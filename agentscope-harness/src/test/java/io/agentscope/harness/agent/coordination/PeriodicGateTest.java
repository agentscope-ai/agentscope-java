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
package io.agentscope.harness.agent.coordination;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.agentscope.harness.agent.filesystem.remote.store.InMemoryStore;
import java.time.Duration;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class PeriodicGateTest {

    @BeforeEach
    void resetLocalGate() {
        LocalPeriodicGate.clearForTests();
    }

    @Test
    void localGate_firstClaimWinsThenThrottles() {
        LocalPeriodicGate gate = new LocalPeriodicGate();
        Duration gap = Duration.ofHours(1);

        assertTrue(gate.tryClaim("maintenance", gap));
        assertFalse(gate.tryClaim("maintenance", gap));
    }

    @Test
    void localGate_differentNamesAreIndependent() {
        LocalPeriodicGate gate = new LocalPeriodicGate();
        Duration gap = Duration.ofHours(1);

        assertTrue(gate.tryClaim("a", gap));
        assertTrue(gate.tryClaim("b", gap));
        assertFalse(gate.tryClaim("a", gap));
    }

    @Test
    void localGate_releasesAfterGap() throws InterruptedException {
        LocalPeriodicGate gate = new LocalPeriodicGate();
        Duration gap = Duration.ofMillis(30);

        assertTrue(gate.tryClaim("flush", gap));
        assertFalse(gate.tryClaim("flush", gap));

        Thread.sleep(gap.toMillis() * 3);

        assertTrue(gate.tryClaim("flush", gap));
    }

    @Test
    void storeBackedGate_firstClaimWinsThenThrottles() {
        StoreBackedPeriodicGate gate = new StoreBackedPeriodicGate(new InMemoryStore());
        Duration gap = Duration.ofHours(1);

        assertTrue(gate.tryClaim("curator", gap));
        assertFalse(gate.tryClaim("curator", gap));
    }

    @Test
    void storeBackedGate_sharedAcrossInstances() {
        InMemoryStore store = new InMemoryStore();
        StoreBackedPeriodicGate gate1 = new StoreBackedPeriodicGate(store);
        StoreBackedPeriodicGate gate2 = new StoreBackedPeriodicGate(store);
        Duration gap = Duration.ofHours(1);

        assertTrue(gate1.tryClaim("shared", gap));
        assertFalse(gate2.tryClaim("shared", gap));
    }
}
