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
package io.agentscope.harness.agent.gateway;

import io.agentscope.core.agent.Agent;
import io.agentscope.core.agent.RuntimeContext;
import java.util.Optional;

/**
 * Rebuilds a subagent {@link Agent} instance from its type id. Used by {@link HarnessGateway} to
 * recover an exposed subagent on a node that does not hold the live instance (after a restart or
 * when a request is routed to a different replica).
 *
 * <p>Typically backed by
 * {@code DefaultAgentManager::createAgentIfPresent}. The rebuilt agent is invoked with the
 * {@code sessionId} carried by the {@link SubagentRecord}, so a distributed
 * {@link io.agentscope.core.state.AgentStateStore} restores the prior conversation history.
 */
@FunctionalInterface
public interface SubagentMaterializer {

    /**
     * Materializes an agent of the given type.
     *
     * @param agentId the subagent type identifier
     * @param parentRc a runtime context carrying at least the target session id
     * @return the rebuilt agent, or {@link Optional#empty()} if the type is unknown / not spawnable
     */
    Optional<Agent> materialize(String agentId, RuntimeContext parentRc);
}
