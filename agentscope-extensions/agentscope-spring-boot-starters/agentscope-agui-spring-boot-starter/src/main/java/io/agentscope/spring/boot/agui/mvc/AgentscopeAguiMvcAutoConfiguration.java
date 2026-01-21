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
package io.agentscope.spring.boot.agui.mvc;

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
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

/**
 * Spring Boot auto-configuration for AG-UI MVC integration.
 *
 * <p>This auto-configuration provides:
 * <ul>
 *   <li>AguiAgentRegistry bean for managing agents</li>
 *   <li>AguiMvcController bean for handling requests</li>
 *   <li>AguiRestController bean for exposing endpoints</li>
 * </ul>
 *
 * <p>To use this auto-configuration, simply add the dependency to your project
 * and register your agents with the AguiAgentRegistry.
 */
@AutoConfiguration
@ConditionalOnClass({SseEmitter.class, Agent.class})
@ConditionalOnWebApplication(type = ConditionalOnWebApplication.Type.SERVLET)
@EnableConfigurationProperties(AguiProperties.class)
public class AgentscopeAguiMvcAutoConfiguration {

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
     * Creates the AG-UI MVC controller bean.
     *
     * @param registry The agent registry
     * @param sessionManager The thread session manager
     * @param props The configuration properties
     * @return A new AguiMvcController
     */
    @Bean
    @ConditionalOnMissingBean
    public AguiMvcController aguiMvcController(
            AguiAgentRegistry registry, ThreadSessionManager sessionManager, AguiProperties props) {
        AguiAdapterConfig config =
                AguiAdapterConfig.builder()
                        .toolMergeMode(props.getDefaultToolMergeMode())
                        .runTimeout(props.getRunTimeout())
                        .emitStateEvents(props.isEmitStateEvents())
                        .emitToolCallArgs(props.isEmitToolCallArgs())
                        .enableReasoning(props.isEnableReasoning())
                        .defaultAgentId(props.getDefaultAgentId())
                        .build();

        return AguiMvcController.builder()
                .agentRegistry(registry)
                .sessionManager(sessionManager)
                .serverSideMemory(props.isServerSideMemory())
                .agentIdHeader(props.getAgentIdHeader())
                .sseTimeout(props.getSseTimeout())
                .config(config)
                .build();
    }

    /**
     * Creates the REST controller for AG-UI endpoints.
     *
     * @param aguiMvcController The AG-UI MVC controller
     * @param props The configuration properties
     * @return A new AguiRestController
     */
    @Bean
    @ConditionalOnMissingBean
    public AguiRestController aguiRestController(
            AguiMvcController aguiMvcController, AguiProperties props) {
        return new AguiRestController(
                aguiMvcController, props.getPathPrefix(), props.isEnablePathRouting());
    }
}
