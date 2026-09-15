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
 * A sticky {@link EndpointSelector} that keeps selecting the same endpoint as long as it stays
 * available, providing session affinity for stateful MCP servers.
 *
 * <p>On the first selection the first candidate is chosen and remembered. Subsequent selections
 * return the remembered endpoint while it still exists among the candidates; once it disappears
 * (e.g. the instance is scaled in), the selector fails over to the first remaining candidate.
 *
 * <p>Note that stickiness is maintained per selector instance, i.e. per
 * {@link NacosLoadBalancedMcpClientWrapper}. All tool calls issued through one wrapper stick to
 * one backend instance.
 */
public class StickyEndpointSelector implements EndpointSelector {

    private volatile String stickyKey;

    @Override
    public NacosMcpEndpoint select(List<NacosMcpEndpoint> endpoints) {
        String currentKey = stickyKey;
        if (currentKey != null) {
            for (NacosMcpEndpoint endpoint : endpoints) {
                if (currentKey.equals(endpoint.key())) {
                    return endpoint;
                }
            }
        }
        NacosMcpEndpoint fallback = endpoints.get(0);
        stickyKey = fallback.key();
        return fallback;
    }
}
