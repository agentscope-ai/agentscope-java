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

import io.micrometer.core.instrument.Metrics;
import java.util.Locale;
import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;

/** Separate boundary IO and metadata costs without per-boundary logs or identity labels. */
final class ManagedBoundaryMetrics {
    enum Phase {
        CHECKPOINT_PREPARE,
        RECEIPT_LOOKUP,
        CHECKPOINT_VERIFY,
        MODEL_PREPARE,
        RECOVERY_STAGE,
        PROJECTION_STAGE,
        METADATA_TRANSACTION
    }

    private ManagedBoundaryMetrics() {}

    static void committed(String kind, int eventCount) {
        Metrics.counter("managed.agents.runtime.commits", "boundary", kind).increment();
        Metrics.summary("managed.agents.runtime.commit.events", "boundary", kind)
                .record(eventCount);
    }

    static <T> T measure(Phase phase, Supplier<T> work) {
        long started = System.nanoTime();
        String outcome = "failure";
        try (var observation =
                ManagedExecutionTrace.span("boundary_" + phase.name().toLowerCase(Locale.ROOT))) {
            T value = work.get();
            outcome = "success";
            return value;
        } finally {
            Metrics.timer(
                            "managed.agents.runtime.boundary."
                                    + phase.name().toLowerCase(Locale.ROOT),
                            "outcome",
                            outcome)
                    .record(System.nanoTime() - started, TimeUnit.NANOSECONDS);
        }
    }
}
