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

package io.agentscope.spring.boot.nacos;

import com.alibaba.nacos.api.ai.AiFactory;
import com.alibaba.nacos.api.ai.AiService;
import com.alibaba.nacos.api.exception.NacosException;
import io.agentscope.core.nacos.mcp.NacosMcpClients;
import io.agentscope.core.nacos.mcp.discovery.NacosMcpDiscoveryClient;
import io.agentscope.core.nacos.mcp.loadbalance.EndpointSelector;
import io.agentscope.core.nacos.mcp.loadbalance.NacosLoadBalancedMcpClientWrapper;
import io.agentscope.core.nacos.mcp.loadbalance.RoundRobinEndpointSelector;
import io.agentscope.core.nacos.mcp.loadbalance.StickyEndpointSelector;
import io.agentscope.spring.boot.nacos.constants.NacosConstants;
import io.agentscope.spring.boot.nacos.properties.AgentScopeNacosProperties;
import io.agentscope.spring.boot.nacos.properties.mcp.AgentScopeMcpNacosProperties;
import io.agentscope.spring.boot.nacos.properties.mcp.NacosMcpConnectionProperties;
import jakarta.annotation.PreDestroy;
import java.io.Closeable;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;

/**
 * Spring Boot autoconfiguration that subscribes MCP servers from the Nacos MCP registry and
 * exposes load-balanced MCP clients as a {@link NacosMcpClients} bean.
 *
 * <p>This AutoConfiguration creates a dedicated Nacos client {@link AiService} for MCP discovery,
 * avoiding conflicts with the Nacos clients used by A2A or prompt integrations, so that different
 * Nacos clusters can be configured for each capability.
 *
 * <p>Each entry under {@code agentscope.nacos.mcp.connections} becomes one load-balanced MCP
 * client. Applications pick the connections they need and register them into the Toolkit their
 * agent actually holds:
 * <pre>{@code
 * @Bean
 * public HarnessAgent harnessAgent(NacosMcpClients nacosMcpClients) {
 *     HarnessAgent agent = HarnessAgent.builder().toolkit(new Toolkit())...build();
 *     NacosLoadBalancedMcpClientWrapper weather = nacosMcpClients.get("weather");
 *     weather.initialize().block();
 *     weather.awaitConnectedEndpoint(Duration.ofSeconds(30));  // optional: wait for a first instance
 *     agent.getToolkit().registerMcpClient(weather).block();
 *     return agent;
 * }
 * }</pre>
 *
 * <p>Requires a Nacos 3.x server with the MCP registry capability enabled.
 */
@AutoConfiguration
@EnableConfigurationProperties({
    AgentScopeNacosProperties.class,
    AgentScopeMcpNacosProperties.class
})
@ConditionalOnClass(NacosLoadBalancedMcpClientWrapper.class)
@ConditionalOnProperty(
        prefix = NacosConstants.NACOS_MCP_PREFIX,
        name = "enabled",
        havingValue = "true",
        matchIfMissing = false)
public class AgentscopeMcpNacosAutoConfiguration implements Closeable {

    private static final Logger log =
            LoggerFactory.getLogger(AgentscopeMcpNacosAutoConfiguration.class);

    private final AiService mcpAiService;

    /**
     * Constructs a new instance of {@link AgentscopeMcpNacosAutoConfiguration}.
     *
     * @param nacosProperties the basic Nacos properties
     * @param mcpNacosProperties the MCP Nacos properties
     * @throws NacosException if there is an error creating the Nacos MCP service
     */
    public AgentscopeMcpNacosAutoConfiguration(
            AgentScopeNacosProperties nacosProperties,
            AgentScopeMcpNacosProperties mcpNacosProperties)
            throws NacosException {
        Properties nacosClientProperties = nacosProperties.getNacosProperties();
        nacosClientProperties.putAll(mcpNacosProperties.getExplicitNacosProperties());
        this.mcpAiService = AiFactory.createAiService(nacosClientProperties);
    }

    /**
     * Creates the discovery client used to subscribe MCP servers from the Nacos MCP registry.
     *
     * @return an instance of {@link NacosMcpDiscoveryClient}
     */
    @Bean
    @ConditionalOnMissingBean
    public NacosMcpDiscoveryClient nacosMcpDiscoveryClient() {
        return new NacosMcpDiscoveryClient(mcpAiService);
    }

    /**
     * Creates one load-balanced MCP client wrapper per configured connection and groups them into
     * a {@link NacosMcpClients} holder.
     *
     * <p>The wrappers are not initialized here; initialization happens when the application
     * registers them into a Toolkit.
     *
     * <p>The returned holder is an {@link AutoCloseable} bean, so Spring closes all wrappers (and
     * their Nacos subscriptions) on shutdown, including when the application supplies its own
     * {@link NacosMcpClients} bean and this method backs off.
     *
     * @param discoveryClient the Nacos MCP discovery client
     * @param mcpNacosProperties the MCP Nacos properties
     * @return the holder of load-balanced MCP client wrappers
     */
    @Bean(destroyMethod = "close")
    @ConditionalOnMissingBean
    public NacosMcpClients nacosMcpClients(
            NacosMcpDiscoveryClient discoveryClient,
            AgentScopeMcpNacosProperties mcpNacosProperties) {
        List<NacosLoadBalancedMcpClientWrapper> clients = new ArrayList<>();
        for (Map.Entry<String, NacosMcpConnectionProperties> entry :
                mcpNacosProperties.getConnections().entrySet()) {
            String clientName = entry.getKey();
            NacosMcpConnectionProperties connection = entry.getValue();
            String serviceName = connection == null ? null : connection.getServiceName();
            if (serviceName == null || serviceName.trim().isEmpty()) {
                throw new IllegalStateException(
                        "Missing MCP server service-name for Nacos MCP connection '"
                                + clientName
                                + "'; set agentscope.nacos.mcp.connections."
                                + clientName
                                + ".service-name to the name of the MCP server registered in"
                                + " Nacos");
            }
            NacosMcpConnectionProperties.LoadBalanceStrategy strategy =
                    connection.getLoadBalance() != null
                            ? connection.getLoadBalance()
                            : mcpNacosProperties.getLoadBalance();
            EndpointSelector selector =
                    strategy == NacosMcpConnectionProperties.LoadBalanceStrategy.STICKY
                            ? new StickyEndpointSelector()
                            : new RoundRobinEndpointSelector();
            clients.add(
                    NacosLoadBalancedMcpClientWrapper.builder(clientName)
                            .serverName(serviceName)
                            .version(connection.getVersion())
                            .discoveryClient(discoveryClient)
                            .endpointSelector(selector)
                            .build());
        }
        return new NacosMcpClients(clients);
    }

    /**
     * Shuts down the dedicated Nacos MCP service. The MCP client wrappers are closed by Spring
     * through the {@code destroyMethod} of the {@link NacosMcpClients} bean.
     *
     * @throws IOException if there is an error during the shutdown process
     */
    @Override
    @PreDestroy
    public void close() throws IOException {
        if (null != mcpAiService) {
            try {
                mcpAiService.shutdown();
            } catch (NacosException e) {
                log.error("Error shutting down Nacos MCP service", e);
            }
        }
    }
}
