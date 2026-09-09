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

/*
 * Copyright 2024-2026 the original author or authors.
 * Licensed under the Apache License, Version 2.0.
 */
package io.agentscope.extensions.aistio.adapter;

import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

/** A business outcome submitted by the model, independently of a physical model turn ending. */
public record AgentTaskOutcome(
        String outcome, String result, String reason, List<String> pendingTaskIds) {
    public AgentTaskOutcome {
        if (outcome == null
                || !List.of("succeeded", "waiting", "blocked", "failed").contains(outcome)) {
            throw new IllegalArgumentException(
                    "outcome must be succeeded, waiting, blocked or failed");
        }
        result = result == null ? "" : result;
        reason = reason == null ? "" : reason;
        pendingTaskIds = pendingTaskIds == null ? List.of() : List.copyOf(pendingTaskIds);
        if (outcome.equals("succeeded") && result.isBlank()) {
            throw new IllegalArgumentException(
                    "Successful work requires the actual deliverable in result");
        }
        if (!outcome.equals("succeeded") && reason.isBlank()) {
            throw new IllegalArgumentException("An unfinished outcome requires a concrete reason");
        }
        if (outcome.equals("waiting") && pendingTaskIds.isEmpty()) {
            throw new IllegalArgumentException("Waiting requires actual pending task IDs");
        }
    }

    /** Per-dispatch storage; never stored on a shared tool or HarnessAgent instance. */
    public static final class State {
        private volatile boolean terminalCommitted;

        public void markTerminalCommitted() {
            terminalCommitted = true;
        }

        public boolean isTerminalCommitted() {
            return terminalCommitted;
        }

        private final AtomicReference<AgentTaskOutcome> value = new AtomicReference<>();

        public void submit(AgentTaskOutcome outcome) {
            value.set(outcome);
        }

        public AgentTaskOutcome take() {
            return value.getAndSet(null);
        }
    }
}
