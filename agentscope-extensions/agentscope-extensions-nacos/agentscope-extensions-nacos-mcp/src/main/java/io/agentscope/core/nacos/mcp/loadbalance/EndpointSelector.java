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
package io.agentscope.core.nacos.mcp.loadbalance;

import io.agentscope.core.nacos.mcp.discovery.NacosMcpEndpoint;
import java.util.List;

/**
 * Strategy for selecting one endpoint from a set of healthy MCP server endpoints.
 *
 * <p>Implementations decide how tool calls are distributed across the backend instances
 * discovered from the Nacos MCP registry. The default implementation is
 * {@link RoundRobinEndpointSelector}; {@link StickyEndpointSelector} can be used when
 * consecutive calls of one session should stick to the same instance.
 */
public interface EndpointSelector {

    /**
     * Selects one endpoint from the given candidates.
     *
     * @param endpoints the current candidate endpoints, never null or empty
     * @return the selected endpoint, must be one of the candidates
     */
    NacosMcpEndpoint select(List<NacosMcpEndpoint> endpoints);
}
