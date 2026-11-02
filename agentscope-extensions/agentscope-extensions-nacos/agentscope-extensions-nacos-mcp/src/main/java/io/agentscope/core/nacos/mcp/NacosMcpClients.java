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
package io.agentscope.core.nacos.mcp;

import io.agentscope.core.nacos.mcp.loadbalance.NacosLoadBalancedMcpClientWrapper;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Iterator;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * A group of {@link NacosLoadBalancedMcpClientWrapper}s created from the Nacos MCP registry
 * configuration.
 *
 * <p>This holder exists so that the wrappers can be injected as a single unambiguous bean. Pick the
 * connections an agent needs with {@link #get(String)} and register them into its Toolkit:
 * <pre>{@code
 * HarnessAgent agent = HarnessAgent.builder().toolkit(new Toolkit())...build();
 * NacosLoadBalancedMcpClientWrapper weather = nacosMcpClients.get("weather");
 * weather.initialize().block();
 * agent.getToolkit().registerMcpClient(weather).block();
 * }</pre>
 *
 * <p>A Toolkit registration publishes the tools that are available at that moment: endpoint scale
 * in/out is applied by each wrapper in the background from Nacos push, but a server that only
 * appears after registration, or a server whose tool set changes later, has to be registered again.
 *
 * <p>Closing this holder closes all the wrappers it contains.
 */
public class NacosMcpClients implements Iterable<NacosLoadBalancedMcpClientWrapper>, AutoCloseable {

    private static final Logger logger = LoggerFactory.getLogger(NacosMcpClients.class);

    private final List<NacosLoadBalancedMcpClientWrapper> clients;

    /**
     * Constructs a holder for the given wrappers.
     *
     * @param clients the load-balanced MCP client wrappers, may be empty
     */
    public NacosMcpClients(List<NacosLoadBalancedMcpClientWrapper> clients) {
        this.clients = clients == null ? Collections.emptyList() : List.copyOf(clients);
    }

    /**
     * Returns all wrappers in this group.
     *
     * @return an unmodifiable list of load-balanced MCP client wrappers
     */
    public List<NacosLoadBalancedMcpClientWrapper> list() {
        return clients;
    }

    /**
     * Returns the wrapper with the given client name (the connection key configured under
     * {@code agentscope.nacos.mcp.connections}).
     *
     * @param clientName the logical MCP client name
     * @return the wrapper, never null
     * @throws IllegalArgumentException if no connection with that name is configured
     */
    public NacosLoadBalancedMcpClientWrapper get(String clientName) {
        for (NacosLoadBalancedMcpClientWrapper client : clients) {
            if (client.getName().equals(clientName)) {
                return client;
            }
        }
        throw new IllegalArgumentException(
                "No Nacos MCP connection named '"
                        + clientName
                        + "'; configured connections: "
                        + configuredNames());
    }

    private List<String> configuredNames() {
        List<String> names = new ArrayList<>(clients.size());
        for (NacosLoadBalancedMcpClientWrapper client : clients) {
            names.add(client.getName());
        }
        return names;
    }

    /**
     * Checks whether this group contains no wrapper, which is the case when no connection is
     * configured.
     *
     * @return true if empty
     */
    public boolean isEmpty() {
        return clients.isEmpty();
    }

    /**
     * Closes all wrappers in this group.
     */
    @Override
    public void close() {
        List<NacosLoadBalancedMcpClientWrapper> toClose = new ArrayList<>(clients);
        for (NacosLoadBalancedMcpClientWrapper client : toClose) {
            try {
                client.close();
            } catch (Exception e) {
                logger.error("Error closing Nacos MCP client '{}'", client.getName(), e);
            }
        }
    }

    @Override
    public Iterator<NacosLoadBalancedMcpClientWrapper> iterator() {
        return clients.iterator();
    }
}
