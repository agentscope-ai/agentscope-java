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

import java.time.Instant;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/** Unit tests for {@link MemoryMaintenanceMiddleware#cleanupStaleEntries()}. */
class MemoryMaintenanceMiddlewareStaleEntryTest {

    @BeforeEach
    void resetSharedMap() {
        MemoryMaintenanceMiddleware.SHARED_LAST_RUN_AT.clear();
    }

    @Test
    void cleanupStaleEntries_removesOldEntries() {
        MemoryMaintenanceMiddleware.SHARED_LAST_RUN_AT.put(
                "USER:staleUser", new AtomicReference<>(Instant.EPOCH));

        MemoryMaintenanceMiddleware.cleanupStaleEntries();

        assertTrue(
                MemoryMaintenanceMiddleware.SHARED_LAST_RUN_AT.isEmpty(),
                "stale entry (EPOCH timestamp) should be removed");
    }

    @Test
    void cleanupStaleEntries_preservesRecentEntries() {
        MemoryMaintenanceMiddleware.SHARED_LAST_RUN_AT.put(
                "USER:recentUser", new AtomicReference<>(Instant.now()));

        MemoryMaintenanceMiddleware.cleanupStaleEntries();

        assertTrue(
                MemoryMaintenanceMiddleware.SHARED_LAST_RUN_AT.containsKey("USER:recentUser"),
                "recent entry should survive cleanup");
    }

    @Test
    void cleanupStaleEntries_onlyRemovesStaleEntries() {
        MemoryMaintenanceMiddleware.SHARED_LAST_RUN_AT.put(
                "USER:staleUser", new AtomicReference<>(Instant.EPOCH));
        MemoryMaintenanceMiddleware.SHARED_LAST_RUN_AT.put(
                "USER:recentUser", new AtomicReference<>(Instant.now()));

        MemoryMaintenanceMiddleware.cleanupStaleEntries();

        assertFalse(
                MemoryMaintenanceMiddleware.SHARED_LAST_RUN_AT.containsKey("USER:staleUser"),
                "stale entry should be removed");
        assertTrue(
                MemoryMaintenanceMiddleware.SHARED_LAST_RUN_AT.containsKey("USER:recentUser"),
                "recent entry should survive cleanup");
    }
}
