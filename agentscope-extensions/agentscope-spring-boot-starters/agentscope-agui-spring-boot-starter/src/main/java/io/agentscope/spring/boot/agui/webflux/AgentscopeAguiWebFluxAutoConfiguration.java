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
package io.agentscope.spring.boot.agui.webflux;

import io.agentscope.core.agent.Agent;
import io.agentscope.core.agui.adapter.AguiAdapterConfig;
import io.agentscope.core.agui.registry.AguiAgentRegistry;
import io.agentscope.spring.boot.agui.common.AguiProperties;
import io.agentscope.spring.boot.agui.common.ThreadSessionManager;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.web.reactive.function.server.RouterFunction;
import org.springframework.web.reactive.function.server.RouterFunctions;
import org.springframework.web.reactive.function.server.ServerResponse;

/**
 * Spring Boot auto-configuration for AG-UI WebFlux integration.
 *
 * <p>This auto-configuration provides:
 *
 * <ul>
 *   <li>AguiAgentRegistry bean for managing agents
 *   <li>AguiWebFluxHandler bean for handling requests
 *   <li>RouterFunction bean for routing to AG-UI endpoints
 * </ul>
 *
 * <p>To use this auto-configuration, simply add the dependency to your project and register your
 * agents with the AguiAgentRegistry.
 */
@AutoConfiguration
@ConditionalOnClass({RouterFunction.class, Agent.class})
@ConditionalOnWebApplication(type = ConditionalOnWebApplication.Type.REACTIVE)
@EnableConfigurationProperties(AguiProperties.class)
public class AgentscopeAguiWebFluxAutoConfiguration {

    /**
     * Creates the agent registry bean.
     *
     * @return A new AguiAgentRegistry
     */
    @Bean
    @ConditionalOnMissingBean
    public AguiAgentRegistry aguiAgentRegistry() {
        return new AguiAgentRegistry();
    }

    /**
     * Creates the thread session manager bean.
     *
     * @param props The configuration properties
     * @return A new ThreadSessionManager
     */
    @Bean
    @ConditionalOnMissingBean
    public ThreadSessionManager threadSessionManager(AguiProperties props) {
        return new ThreadSessionManager(
                props.getMaxThreadSessions(), props.getSessionTimeoutMinutes());
    }

    /**
     * Creates the AG-UI WebFlux handler bean.
     *
     * @param registry The agent registry
     * @param sessionManager The thread session manager
     * @param props The configuration properties
     * @return A new AguiWebFluxHandler
     */
    @Bean
    @ConditionalOnMissingBean
    public AguiWebFluxHandler aguiWebFluxHandler(
            AguiAgentRegistry registry, ThreadSessionManager sessionManager, AguiProperties props) {
        AguiAdapterConfig config =
                AguiAdapterConfig.builder()
                        .toolMergeMode(props.getDefaultToolMergeMode())
                        .runTimeout(props.getRunTimeout())
                        .emitStateEvents(props.isEmitStateEvents())
                        .emitToolCallArgs(props.isEmitToolCallArgs())
                        .defaultAgentId(props.getDefaultAgentId())
                        .build();

        return AguiWebFluxHandler.builder()
                .agentRegistry(registry)
                .sessionManager(sessionManager)
                .serverSideMemory(props.isServerSideMemory())
                .agentIdHeader(props.getAgentIdHeader())
                .config(config)
                .build();
    }

    /**
     * Creates the router function for AG-UI endpoints.
     *
     * <p>Provides two routes:
     *
     * <ul>
     *   <li>{@code POST /agui/run} - Agent ID from header or forwardedProps
     *   <li>{@code POST /agui/run/{agentId}} - Agent ID from path variable (if enabled)
     * </ul>
     *
     * @param handler The AG-UI handler
     * @param props The configuration properties
     * @return The router function
     */
    @Bean
    public RouterFunction<ServerResponse> aguiRoutes(
            AguiWebFluxHandler handler, AguiProperties props) {
        RouterFunctions.Builder routerBuilder =
                RouterFunctions.route().POST(props.getPathPrefix() + "/run", handler::handle);

        // Add path variable route if enabled
        if (props.isEnablePathRouting()) {
            routerBuilder.POST(
                    props.getPathPrefix() + "/run/{agentId}", handler::handleWithAgentId);
        }

        return routerBuilder.build();
    }
}
