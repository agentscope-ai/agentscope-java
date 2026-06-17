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
package io.agentscope.core.shutdown;

import io.agentscope.core.state.AgentState;

/**
 * Strategy for persisting {@link AgentState} during graceful shutdown.
 *
 * <p>Implementations are registered via
 * {@link GracefulShutdownManager#bindStateSaver(io.agentscope.core.agent.Agent, ShutdownStateSaver)}
 * so the shutdown manager can checkpoint agent state without depending on the legacy
 * {@link io.agentscope.core.state.AgentStateStore} API.
 */
@FunctionalInterface
public interface ShutdownStateSaver {

    /**
     * Persist the given agent state snapshot.
     *
     * @param state the current agent state (with {@code shutdownInterrupted} already set)
     */
    void save(AgentState state);
}
