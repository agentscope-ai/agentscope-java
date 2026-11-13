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
package io.agentscope.core.tool.circuitbreaker;

/**
 * Lifecycle state of a single tool's circuit.
 *
 * <p>The state is always <em>derived</em> from the persisted snapshot plus the current time
 * rather than stored directly, so a cooldown that elapsed while no traffic flowed is observed
 * correctly on the next read without any background scheduler.
 *
 * <pre>
 *   CLOSED --failure x threshold--&gt; OPEN --cooldown elapsed--&gt; HALF_OPEN --success--&gt; CLOSED
 *                                    ^                              |
 *                                    +---------- failure -----------+
 * </pre>
 */
public enum ToolCircuitState {

    /**
     * Normal operation: the tool is exposed to the model and consecutive failures are counted.
     *
     * <p>Named after a closed electrical circuit — current flows, so {@code CLOSED} means
     * "healthy", not "unavailable".
     */
    CLOSED,

    /**
     * Tripped and still cooling down: the tool is withheld from the model.
     */
    OPEN,

    /**
     * Cooldown elapsed: the tool is exposed again as a single probe.
     *
     * <p>A successful probe closes the circuit; a failing probe re-opens it with the next
     * (longer) backoff generation.
     */
    HALF_OPEN
}
