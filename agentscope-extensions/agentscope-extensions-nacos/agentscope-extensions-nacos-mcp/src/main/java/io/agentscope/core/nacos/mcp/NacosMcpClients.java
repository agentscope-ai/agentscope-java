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
import io.agentscope.core.tool.Toolkit;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Iterator;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * A group of {@link NacosLoadBalancedMcpClientWrapper}s created from the Nacos MCP registry
 * configuration, with a convenience method to register them all into a {@link Toolkit}.
 *
 * <p>This holder exists so that the wrappers can be injected as a single unambiguous bean, and so
 * that wiring Nacos-discovered MCP tools into an agent takes one line:
 * <pre>{@code
 * HarnessAgent agent = HarnessAgent.builder().toolkit(new Toolkit())...build();
 * nacosMcpClients.registerTo(agent.getToolkit());
 * }</pre>
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
     * @return the wrapper, or null if no connection with that name is configured
     */
    public NacosLoadBalancedMcpClientWrapper get(String clientName) {
        for (NacosLoadBalancedMcpClientWrapper client : clients) {
            if (client.getName().equals(clientName)) {
                return client;
            }
        }
        return null;
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
     * Registers all wrappers into the given Toolkit, turning the remote MCP tools into AgentScope
     * tools.
     *
     * <p>A wrapper that fails to register (for example because the MCP server is not available yet)
     * is logged and skipped, so that one broken connection does not prevent the others from being
     * registered.
     *
     * @param toolkit the Toolkit the agent actually holds
     */
    public void registerTo(Toolkit toolkit) {
        if (toolkit == null) {
            throw new IllegalArgumentException("Toolkit must not be null");
        }
        for (NacosLoadBalancedMcpClientWrapper client : clients) {
            try {
                toolkit.registerMcpClient(client).block();
                logger.info(
                        "Registered Nacos MCP client '{}' into Toolkit with {} endpoint(s)",
                        client.getName(),
                        client.getCurrentEndpoints().size());
            } catch (Exception e) {
                logger.error(
                        "Failed to register Nacos MCP client '{}' into Toolkit,"
                                + " its tools are unavailable",
                        client.getName(),
                        e);
            }
        }
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
