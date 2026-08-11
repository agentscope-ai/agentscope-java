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
package io.agentscope.extensions.agentprotocol;

import io.agentscope.harness.agent.HarnessAgent;
import java.util.Objects;
import java.util.function.Supplier;

/**
 * Resolves the {@link HarnessAgent} that executes an Agent Protocol task.
 *
 * <p>Called once per run — on the initial {@code POST /tasks} submission and again for every
 * {@code POST /tasks/{id}/resume} — with the full {@link AgentRequest}, including the submission
 * {@code context} map. Implementations may therefore route by {@code agentId}, tenant, or any
 * custom context attribute, and may build a fresh agent per task.
 *
 * <p>Register a bean of this type to override the default factory, which always returns the single
 * {@link HarnessAgent} bean:
 *
 * <pre>{@code
 * @Bean
 * AgentFactory agentFactory(Map<String, HarnessAgent> agentsByName) {
 *     return request -> agentsByName.getOrDefault(request.agentId(), agentsByName.get("default"));
 * }
 * }</pre>
 *
 * <p>For concurrent tasks, return a distinct instance per call (e.g. a prototype-scoped bean),
 * or configure the shared agent with {@code checkRunning(false)}.
 */
@FunctionalInterface
public interface AgentFactory {

    /**
     * Returns the agent for this run. Must not return {@code null}; throw to fail the task with an
     * error status instead.
     */
    HarnessAgent create(AgentRequest request);

    /** Factory that always returns the same agent, ignoring the request. */
    static AgentFactory fixed(HarnessAgent agent) {
        Objects.requireNonNull(agent, "agent");
        return request -> agent;
    }

    /** Factory backed by a supplier, ignoring the request. */
    static AgentFactory of(Supplier<HarnessAgent> supplier) {
        Objects.requireNonNull(supplier, "supplier");
        return request -> supplier.get();
    }
}
