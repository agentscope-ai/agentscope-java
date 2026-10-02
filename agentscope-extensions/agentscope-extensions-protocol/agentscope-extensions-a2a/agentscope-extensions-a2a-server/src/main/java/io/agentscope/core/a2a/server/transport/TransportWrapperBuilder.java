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

package io.agentscope.core.a2a.server.transport;

import io.a2a.server.requesthandlers.RequestHandler;
import io.a2a.spec.AgentCard;
import io.agentscope.core.a2a.server.AgentScopeA2aServer;
import java.util.concurrent.Executor;

/**
 * Builder interface to build {@link TransportWrapper} for different transports.
 */
@SuppressWarnings("rawtypes")
public interface TransportWrapperBuilder<T extends TransportWrapper> {

    /**
     * Get wrapper transport type name to build.
     *
     * @return name of transport type
     */
    String getTransportType();

    /**
     * Build wrapper transport without extendedAgentCard.
     *
     * @param agentCard         agent card build by {@link AgentScopeA2aServer}
     * @param requestHandler    request handler build by {@link AgentScopeA2aServer}
     * @param executor          request handle executor
     * @return wrapper transport
     */
    default T build(AgentCard agentCard, RequestHandler requestHandler, Executor executor) {
        return build(agentCard, requestHandler, executor, null);
    }

    /**
     * Build wrapper transport with extendedAgentCard.
     *
     * @param agentCard         agent card build by {@link AgentScopeA2aServer}
     * @param requestHandler    request handler build by {@link AgentScopeA2aServer}
     * @param executor          request handle executor
     * @param extendedAgentCard extended agent card build by {@link AgentScopeA2aServer}
     * @return wrapper transport
     */
    T build(
            AgentCard agentCard,
            RequestHandler requestHandler,
            Executor executor,
            AgentCard extendedAgentCard);
}
