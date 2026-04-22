/*
 * Copyright 2024-2026 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * You may not use this file except in compliance with the License.
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
import io.agentscope.core.a2a.agent.card.AgentCardResolver;
import io.agentscope.core.a2a.server.registry.AgentRegistry;
import io.agentscope.core.nacos.a2a.discovery.NacosAgentCardResolver;
import io.agentscope.core.nacos.a2a.registry.NacosA2aRegistryProperties;
import io.agentscope.core.nacos.a2a.registry.NacosAgentRegistry;
import io.agentscope.spring.boot.nacos.constants.NacosConstants;
import io.agentscope.spring.boot.nacos.properties.AgentScopeNacosProperties;
import io.agentscope.spring.boot.nacos.properties.a2a.AgentScopeA2aNacosProperties;
import jakarta.annotation.PreDestroy;
import java.io.Closeable;
import java.io.IOException;
import java.util.Properties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;

/**
 * Spring Boot autoconfiguration that exposes A2A Nacos beans for AgentScope.
 *
 * <p>This AutoConfiguration will create a nacos client {@link AiService} to do A2A discovery and registry. This is to
 * avoid conflicts with the Nacos client in the MCP registry center, which would otherwise prevent the configuration of different
 * Nacos clusters to handle A2A and MCP separately.
 */
@AutoConfiguration
@EnableConfigurationProperties({
    AgentScopeNacosProperties.class,
    AgentScopeA2aNacosProperties.class
})
@ConditionalOnProperty(
        prefix = NacosConstants.A2A_NACOS_PREFIX,
        name = "enabled",
        havingValue = "true",
        matchIfMissing = true)
public class AgentscopeA2aNacosAutoConfiguration implements Closeable {

    private static final Logger log =
            LoggerFactory.getLogger(AgentscopeA2aNacosAutoConfiguration.class);

    private final AiService a2aService;

    /**
     * Constructs a new instance of {@link AgentscopeA2aNacosAutoConfiguration}.
     *
     * @param nacosProperties the basic Nacos properties
     * @param a2aNacosProperties the A2A Nacos properties
     * @throws NacosException if there is an error creating the Nacos A2A service
     */
    public AgentscopeA2aNacosAutoConfiguration(
            AgentScopeNacosProperties nacosProperties,
            AgentScopeA2aNacosProperties a2aNacosProperties)
            throws NacosException {
        this.a2aService = a2aService(nacosProperties, a2aNacosProperties);
    }

    private AiService a2aService(
            AgentScopeNacosProperties nacosProperties,
            AgentScopeA2aNacosProperties a2aNacosProperties)
            throws NacosException {
        Properties nacosClientProperties = nacosProperties.getNacosProperties();
        nacosClientProperties.putAll(a2aNacosProperties.getNacosProperties());
        return AiFactory.createAiService(nacosClientProperties);
    }

    /**
     * Closes the Nacos A2A service by shutting it down.
     *
     * @throws IOException if there is an error during the shutdown process
     */
    @Override
    @PreDestroy
    public void close() throws IOException {
        if (null != a2aService) {
            try {
                a2aService.shutdown();
            } catch (NacosException e) {
                log.error("Error shutting down Nacos A2A service", e);
            }
        }
    }

    /**
     * Creates a bean for resolving agent cards from Nacos.
     *
     * @return an instance of {@link NacosAgentCardResolver}
     */
    @Bean
    @ConditionalOnProperty(
            prefix = NacosConstants.A2A_NACOS_DISCOVERY_PREFIX,
            name = "enabled",
            havingValue = "true",
            matchIfMissing = true)
    public AgentCardResolver nacosAgentCardResolver() {
        return new NacosAgentCardResolver(a2aService);
    }

    /**
     * Creates a bean for registering agents to Nacos.
     *
     * @param a2aNacosProperties the A2A Nacos properties
     * @return an instance of {@link NacosAgentRegistry}
     */
    @Bean
    @ConditionalOnProperty(
            prefix = NacosConstants.A2A_NACOS_REGISTRY_PREFIX,
            name = "enabled",
            havingValue = "true",
            matchIfMissing = true)
    public AgentRegistry nacosAgentRegistry(AgentScopeA2aNacosProperties a2aNacosProperties) {
        return NacosAgentRegistry.builder(a2aService)
                .nacosA2aProperties(buildNacosA2aProperties(a2aNacosProperties))
                .build();
    }

    private NacosA2aRegistryProperties buildNacosA2aProperties(
            AgentScopeA2aNacosProperties a2aProperties) {
        return NacosA2aRegistryProperties.builder()
                .setAsLatest(a2aProperties.getRegistry().isRegisterAsLatest())
                .enabledRegisterEndpoint(a2aProperties.getRegistry().isEnabledRegisterEndpoint())
                .overwritePreferredTransport(
                        a2aProperties.getRegistry().getOverwritePreferredTransport())
                .build();
    }
}
