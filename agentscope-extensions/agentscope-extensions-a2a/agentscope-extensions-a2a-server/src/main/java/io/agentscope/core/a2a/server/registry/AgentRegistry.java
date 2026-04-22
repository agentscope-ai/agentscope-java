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

package io.agentscope.core.a2a.server.registry;

import io.a2a.spec.AgentCard;
import io.agentscope.core.a2a.server.transport.TransportProperties;
import java.util.List;

/**
 * Agent Register Interface to Agent Registry.
 */
public interface AgentRegistry {

    /**
     * Registry Name.
     *
     * @return registry name
     */
    String registryName();

    /**
     * Do registry operation by AgentCard and deployProperties.
     *
     * @param agentCard           agent card of this agent
     * @param transportProperties transports properties
     */
    void register(AgentCard agentCard, List<TransportProperties> transportProperties);
}
