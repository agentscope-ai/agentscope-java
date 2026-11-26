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
import io.agentscope.core.agui.adapter.AguiAgentAdapterFactory;
import io.agentscope.core.agui.adapter.strategy.AgentEventConverter;
import io.agentscope.core.agui.adapter.strategy.AguiEventEnricher;
import io.agentscope.core.agui.processor.AguiRequestProcessor;
import io.agentscope.core.agui.queue.MessageQueueRegistry;
import io.agentscope.core.agui.registry.AguiAgentRegistry;
import io.agentscope.core.agui.runtime.AguiRequestBodyParser;
import io.agentscope.core.agui.runtime.AguiRuntimeContextResolver;
import io.agentscope.spring.boot.agui.common.AguiProperties;
import io.agentscope.spring.boot.agui.common.DefaultAgentResolver;
import io.agentscope.spring.boot.agui.common.ThreadSessionManager;
import io.agentscope.spring.boot.agui.queue.AguiQueueController;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
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
@ConditionalOnBean(AguiAgentRegistry.class)
@ConditionalOnWebApplication(type = ConditionalOnWebApplication.Type.SERVLET)
@EnableConfigurationProperties(AguiProperties.class)
public class AgentscopeAguiMvcAutoConfiguration {

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
     * Creates the default AG-UI request body parser bean.
     *
     * <p>The parser decodes request bodies with AgentScope's Jackson 2 codec so that Spring Boot
     * 4's Jackson 3 converters do not touch AG-UI's Jackson 2-annotated models. Applications can
     * override this bean to customize request body parsing.
     *
     * @return A new AguiRequestBodyParser
     */
    @Bean
    @ConditionalOnMissingBean
    public AguiRequestBodyParser aguiRequestBodyParser() {
        return new AguiRequestBodyParser();
    }

    /**
     * Creates the AG-UI request processor bean.
     *
     * <p>The run controller and the message queue must observe the same run state, so the processor
     * is exposed as a bean: the run controller is built from it, and the queue endpoint takes the
     * processor from that controller. Applications can provide their own
     * {@code AguiRequestProcessor} bean to replace the default one.
     *
     * @param registry The agent registry
     * @param sessionManager The thread session manager
     * @param props The configuration properties
     * @return A new AguiRequestProcessor
     */
    @Bean
    @ConditionalOnMissingBean
    public AguiRequestProcessor aguiRequestProcessor(
            AguiAgentRegistry registry,
            ThreadSessionManager sessionManager,
            AguiProperties props,
            ObjectProvider<AgentEventConverter> eventConvertersProvider,
            ObjectProvider<AguiEventEnricher> eventEnrichersProvider,
            ObjectProvider<AguiRuntimeContextResolver> runtimeContextResolverProvider,
            ObjectProvider<AguiAgentAdapterFactory> adapterFactoryProvider) {
        return AguiRequestProcessor.builder()
                .agentResolver(
                        DefaultAgentResolver.builder()
                                .registry(registry)
                                .sessionManager(sessionManager)
                                .serverSideMemory(props.isServerSideMemory())
                                .build())
                .config(adapterConfig(props, eventConvertersProvider, eventEnrichersProvider))
                .adapterFactory(adapterFactoryProvider.getIfAvailable())
                .runtimeContextResolver(runtimeContextResolverProvider.getIfAvailable())
                .recordLastRunId(props.getQueue().isEnabled())
                .build();
    }

    /**
     * Creates the AG-UI MVC controller bean.
     *
     * @param requestProcessor The AG-UI request processor
     * @param props The configuration properties
     * @return A new AguiMvcController
     */
    @Bean
    @ConditionalOnMissingBean
    public AguiMvcController aguiMvcController(
            AguiRequestProcessor requestProcessor, AguiProperties props) {
        return AguiMvcController.builder()
                .requestProcessor(requestProcessor)
                .agentIdHeader(props.getAgentIdHeader())
                .sseTimeout(props.getSseTimeout())
                .interruptOnDisconnect(props.isInterruptOnDisconnect())
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
            AguiMvcController aguiMvcController,
            AguiProperties props,
            AguiRequestBodyParser requestBodyParser) {
        return new AguiRestController(
                aguiMvcController,
                props.getPathPrefix(),
                props.isEnablePathRouting(),
                requestBodyParser);
    }

    /**
     * Creates the message queue registry when the queue is enabled.
     *
     * @param props The configuration properties
     * @return A new MessageQueueRegistry
     */
    @Bean
    @ConditionalOnMissingBean
    @ConditionalOnProperty(prefix = "agentscope.agui.queue", name = "enabled", havingValue = "true")
    public MessageQueueRegistry messageQueueRegistry(AguiProperties props) {
        return new MessageQueueRegistry(
                props.getQueue().getCapacity(), props.getQueue().getIdempotencyRetention());
    }

    /**
     * Creates the queue endpoints when the queue is enabled.
     *
     * <p>The controller is taken from the transport bean rather than from a fresh processor bean:
     * an application that replaces {@code AguiMvcController} with its own builder still gets a
     * queue that observes the runs of that controller.
     *
     * @param registry The message queue registry
     * @param aguiMvcController The AG-UI MVC controller that executes the runs
     * @param requestBodyParser The AG-UI request body parser
     * @return A new AguiQueueController
     */
    @Bean
    @ConditionalOnMissingBean
    @ConditionalOnProperty(prefix = "agentscope.agui.queue", name = "enabled", havingValue = "true")
    public AguiQueueController aguiQueueController(
            MessageQueueRegistry registry,
            AguiMvcController aguiMvcController,
            AguiRequestBodyParser requestBodyParser) {
        return new AguiQueueController(
                registry, aguiMvcController.getProcessor(), requestBodyParser);
    }

    static AguiAdapterConfig adapterConfig(
            AguiProperties props,
            ObjectProvider<AgentEventConverter> eventConvertersProvider,
            ObjectProvider<AguiEventEnricher> eventEnrichersProvider) {
        return AguiAdapterConfig.builder()
                .toolMergeMode(props.getDefaultToolMergeMode())
                .runTimeout(props.getRunTimeout())
                .emitStateEvents(props.isEmitStateEvents())
                .emitToolCallArgs(props.isEmitToolCallArgs())
                .emitTokenUsage(props.isEmitTokenUsage())
                .enableReasoning(props.isEnableReasoning())
                .emitRunFinishedAfterError(props.isEmitRunFinishedAfterError())
                .defaultAgentId(props.getDefaultAgentId())
                .eventConverters(eventConvertersProvider.orderedStream().toList())
                .eventEnrichers(eventEnrichersProvider.orderedStream().toList())
                .build();
    }
}
