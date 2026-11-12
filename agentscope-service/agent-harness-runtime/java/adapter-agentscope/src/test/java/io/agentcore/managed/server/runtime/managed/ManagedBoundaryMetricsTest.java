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

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;

import io.micrometer.core.instrument.Metrics;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;

class ManagedBoundaryMetricsTest {
    @Test
    void measuresSuccessAndFailureWithoutChangingCommitOutcomeOrUsingIdentityTags() {
        var registry = new SimpleMeterRegistry();
        Metrics.addRegistry(registry);
        try {
            assertEquals(
                    "receipt",
                    ManagedBoundaryMetrics.measure(
                            ManagedBoundaryMetrics.Phase.METADATA_TRANSACTION, () -> "receipt"));
            var failure = new ManagedStateException("stale_execution", "owner changed");
            assertSame(
                    failure,
                    assertThrows(
                            ManagedStateException.class,
                            () ->
                                    ManagedBoundaryMetrics.measure(
                                            ManagedBoundaryMetrics.Phase.METADATA_TRANSACTION,
                                            () -> {
                                                throw failure;
                                            })));
            for (var outcome : new String[] {"success", "failure"}) {
                var timer =
                        registry.get("managed.agents.runtime.boundary.metadata_transaction")
                                .tag("outcome", outcome)
                                .timer();
                assertEquals(1, timer.count());
                assertEquals(1, timer.getId().getTags().size());
            }
        } finally {
            Metrics.removeRegistry(registry);
            registry.close();
        }
    }
}
