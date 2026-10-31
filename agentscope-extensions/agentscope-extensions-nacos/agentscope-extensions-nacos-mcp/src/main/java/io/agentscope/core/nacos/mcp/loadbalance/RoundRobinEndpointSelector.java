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
import java.util.concurrent.atomic.AtomicInteger;

/**
 * A round-robin {@link EndpointSelector} that distributes calls evenly across endpoints.
 *
 * <p>This is the default selector. It is stateless with respect to sessions: consecutive tool
 * calls may land on different backend instances, so the MCP server instances are expected to be
 * stateless (or at least tolerant to calls arriving on any instance).
 */
public class RoundRobinEndpointSelector implements EndpointSelector {

    private final AtomicInteger index = new AtomicInteger(0);

    @Override
    public NacosMcpEndpoint select(List<NacosMcpEndpoint> endpoints) {
        int currentIndex = index.getAndUpdate(i -> (i + 1) % endpoints.size());
        return endpoints.get(Math.floorMod(currentIndex, endpoints.size()));
    }
}
