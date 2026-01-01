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

package io.agentscope.spring.boot.a2a;

import io.agentscope.core.ReActAgent;
import io.agentscope.core.a2a.server.AgentScopeA2aServer;
import io.agentscope.core.a2a.server.card.ConfigurableAgentCard;
import io.agentscope.core.a2a.server.executor.AgentExecuteProperties;
import io.agentscope.core.a2a.server.executor.runner.AgentRunner;
import io.agentscope.core.a2a.server.executor.runner.ReActAgentWithBuilderRunner;
import io.agentscope.core.a2a.server.registry.AgentRegistry;
import io.agentscope.core.a2a.server.transport.DeploymentProperties;
import io.agentscope.spring.boot.AgentscopeAutoConfiguration;
import io.agentscope.spring.boot.a2a.controller.A2aJsonRpcController;
import io.agentscope.spring.boot.a2a.controller.AgentCardController;
import io.agentscope.spring.boot.a2a.listener.ServerReadyListener;
import io.agentscope.spring.boot.a2a.properties.A2aAgentCardProperties;
import io.agentscope.spring.boot.a2a.properties.A2aCommonProperties;
import io.agentscope.spring.boot.a2a.properties.Constants;
import io.agentscope.spring.boot.a2a.runner.ReActAgentWithStarterRunner;
import java.util.List;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.core.env.Environment;

/**
 * Spring Boot autoconfiguration that exposes A2A beans for AgentScope.
 *
 * <p>Only support export web type transport for now. If support export not only web type, might remove condition of
 * {@link ConditionalOnWebApplication}.
 */
@AutoConfiguration(after = AgentscopeAutoConfiguration.class)
@EnableConfigurationProperties({A2aCommonProperties.class, A2aAgentCardProperties.class})
@ConditionalOnClass(AgentScopeA2aServer.class)
@ConditionalOnWebApplication
@ConditionalOnProperty(
        prefix = Constants.A2A_SERVER_PREFIX,
        name = "enabled",
        havingValue = "true",
        matchIfMissing = true)
public class AgentscopeA2aAutoConfiguration {

    @Bean
    @ConditionalOnMissingBean
    @ConditionalOnBean(ReActAgent.class)
    public AgentRunner agentRunnerWithStarterRunner(ObjectProvider<ReActAgent> reActAgentProvider) {
        return ReActAgentWithStarterRunner.newInstance(reActAgentProvider);
    }

    @Bean
    @ConditionalOnMissingBean
    @ConditionalOnBean(ReActAgent.Builder.class)
    public AgentRunner agentRunnerWithBuilder(ReActAgent.Builder agentBuilder) {
        return ReActAgentWithBuilderRunner.newInstance(agentBuilder);
    }

    @Bean
    @ConditionalOnMissingBean
    public AgentScopeA2aServer agentScopeA2aServer(
            AgentRunner agentRunner,
            A2aAgentCardProperties agentCardProperties,
            A2aCommonProperties commonProperties,
            Environment environment,
            List<AgentRegistry> agentRegistries) {
        AgentScopeA2aServer.Builder builder = AgentScopeA2aServer.builder(agentRunner);
        builder.agentCard(buildConfigurableAgentCard(agentCardProperties));
        builder.deploymentProperties(buildDeploymentProperties(environment));
        builder.agentExecuteProperties(buildAgentExecuteProperties(commonProperties));
        agentRegistries.forEach(builder::withAgentRegistry);
        return builder.build();
    }

    @Bean
    @ConditionalOnBean(AgentScopeA2aServer.class)
    public AgentCardController agentCardController(AgentScopeA2aServer agentScopeA2aServer) {
        return new AgentCardController(agentScopeA2aServer);
    }

    /**
     * Autoconfiguration A2A JSON-RPC controller.
     *
     * <p>TODO should judge whether user want to export JSON-RPC transport.
     *
     * @param agentScopeA2aServer agentscope A2a server bean
     * @return A2aJsonRpcController bean
     */
    @Bean
    @ConditionalOnBean(AgentScopeA2aServer.class)
    public A2aJsonRpcController a2aJsonRpcController(AgentScopeA2aServer agentScopeA2aServer) {
        return new A2aJsonRpcController(agentScopeA2aServer);
    }

    @Bean
    @ConditionalOnBean(AgentScopeA2aServer.class)
    public ServerReadyListener serverReadyConfiguration(AgentScopeA2aServer agentScopeA2aServer) {
        return new ServerReadyListener(agentScopeA2aServer);
    }

    private ConfigurableAgentCard buildConfigurableAgentCard(
            A2aAgentCardProperties agentCardProperties) {
        return new ConfigurableAgentCard.Builder()
                .name(agentCardProperties.getName())
                .description(agentCardProperties.getDescription())
                .url(agentCardProperties.getUrl())
                .provider(agentCardProperties.getProvider())
                .version(agentCardProperties.getVersion())
                .documentationUrl(agentCardProperties.getDocumentationUrl())
                .defaultInputModes(agentCardProperties.getDefaultInputModes())
                .defaultOutputModes(agentCardProperties.getDefaultOutputModes())
                .skills(agentCardProperties.getSkills())
                .securitySchemes(agentCardProperties.getSecuritySchemes())
                .security(agentCardProperties.getSecurity())
                .iconUrl(agentCardProperties.getIconUrl())
                .additionalInterfaces(agentCardProperties.getAdditionalInterfaces())
                .preferredTransport(agentCardProperties.getPreferredTransport())
                .build();
    }

    private DeploymentProperties buildDeploymentProperties(Environment environment) {
        DeploymentProperties.Builder result = new DeploymentProperties.Builder();
        Integer defaultServerExportPort =
                environment.getProperty(Constants.DEFAULT_SERVER_EXPORT_PORT, Integer.class, 8080);
        String defaultServerExportAddress =
                environment.getProperty(Constants.DEFAULT_SERVER_EXPORT_ADDRESS);
        result.port(defaultServerExportPort);
        if (null != defaultServerExportAddress) {
            result.host(defaultServerExportAddress);
        }
        return result.build();
    }

    private AgentExecuteProperties buildAgentExecuteProperties(
            A2aCommonProperties commonProperties) {
        return AgentExecuteProperties.builder()
                .completeWithMessage(commonProperties.isCompleteWithMessage())
                .requireInnerMessage(commonProperties.isRequireInnerMessage())
                .build();
    }
}
