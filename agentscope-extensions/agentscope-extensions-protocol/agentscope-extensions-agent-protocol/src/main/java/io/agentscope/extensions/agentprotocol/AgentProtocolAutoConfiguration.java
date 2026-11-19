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
package io.agentscope.extensions.agentprotocol;

import io.agentscope.harness.agent.HarnessAgent;
import java.nio.file.Path;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;

/**
 * Registers task protocol REST endpoints when enabled.
 *
 * <p>{@link ConditionalOnBean} is applied at the {@code @Bean} method level (not class level) to
 * avoid Spring Boot auto-configuration ordering issues: class-level {@code @ConditionalOnBean} on
 * {@code @AutoConfiguration} classes may evaluate before user-defined beans are registered.
 *
 * <p>Agent selection goes through {@link AgentFactory}. Without a user-defined bean, the default
 * factory resolves the single {@link HarnessAgent} bean for every task; define an
 * {@link AgentFactory} bean to route per {@code agent_id} or submission context.
 *
 * <p>Protocol task metadata is persisted through {@link ProtocolTaskRepository},
 * rooted at {@link AgentProtocolProperties#getTaskStorePath()} by default — not through any
 * execution agent's workspace.
 *
 * <p>All {@link RuntimeContextCustomizer} beans are applied, in {@code @Order}, to the runtime
 * context of every task run.
 *
 * <p>{@link ProtocolTaskRunRegistry}, {@link ProtocolTaskContextStore} and {@link
 * ProtocolSessionRegistry} default to process-local implementations. A deployment where {@code
 * /tasks}, {@code /resume}, {@code /cancel} and the {@code /wait} poll can reach different instances
 * declares a {@link ProtocolStores} bean instead; all three then read and write the same {@link
 * io.agentscope.harness.agent.filesystem.remote.store.BaseStore}, and it also needs a shared {@link
 * ProtocolTaskRepository} and {@link AgentProtocolEventBus}. Declaring one collaborator bean
 * explicitly still wins over both.
 *
 * <p>For concurrent task execution, register the {@link HarnessAgent} bean as
 * {@code @Scope("prototype")} so that each task obtains its own instance. Alternatively, configure
 * the singleton with {@code checkRunning(false)} if concurrent access is otherwise safe.
 */
@AutoConfiguration
@EnableConfigurationProperties(AgentProtocolProperties.class)
@ConditionalOnProperty(prefix = "agentscope.agent-protocol", name = "enabled", havingValue = "true")
public class AgentProtocolAutoConfiguration {

    @Bean
    @ConditionalOnMissingBean(AgentProtocolEventBus.class)
    public AgentProtocolEventBus agentProtocolEventBus(AgentProtocolProperties properties) {
        return new AgentProtocolTaskEventBus(properties.getSseReplayBufferSize());
    }

    @Bean
    @ConditionalOnBean(HarnessAgent.class)
    @ConditionalOnMissingBean(AgentFactory.class)
    public AgentFactory agentProtocolAgentFactory(ObjectProvider<HarnessAgent> agentProvider) {
        return request -> agentProvider.getObject();
    }

    @Bean
    @ConditionalOnMissingBean(ProtocolTaskRepository.class)
    public ProtocolTaskRepository agentProtocolTaskRepository(AgentProtocolProperties properties) {
        return new WorkspaceProtocolTaskRepository(Path.of(properties.getTaskStorePath()));
    }

    /**
     * Execution ownership and cancellation. A {@link ProtocolStores} bean switches it to a shared
     * store; without one the registry is process-local, i.e. a single-instance deployment.
     */
    @Bean
    @ConditionalOnMissingBean(ProtocolTaskRunRegistry.class)
    public ProtocolTaskRunRegistry agentProtocolRunRegistry(
            ObjectProvider<ProtocolStores> sharedStores) {
        ProtocolStores shared = sharedStores.getIfAvailable();
        return shared != null ? shared.runRegistry() : BaseStoreProtocolTaskRunRegistry.inMemory();
    }

    /**
     * Submission metadata re-read by {@code /resume}. A {@link ProtocolStores} bean switches it to a
     * shared store; without one the records stay in process.
     */
    @Bean
    @ConditionalOnMissingBean(ProtocolTaskContextStore.class)
    public ProtocolTaskContextStore agentProtocolContextStore(
            ObjectProvider<ProtocolStores> sharedStores) {
        ProtocolStores shared = sharedStores.getIfAvailable();
        return shared != null
                ? shared.contextStore()
                : BaseStoreProtocolTaskContextStore.inMemory();
    }

    /**
     * Ownership record for client-named sessions. A {@link ProtocolStores} bean switches it to a
     * shared store, which is what a deployment where a follow-up can land on another instance
     * needs.
     */
    @Bean
    @ConditionalOnMissingBean(ProtocolSessionRegistry.class)
    public ProtocolSessionRegistry agentProtocolSessionRegistry(
            ObjectProvider<ProtocolStores> sharedStores) {
        ProtocolStores shared = sharedStores.getIfAvailable();
        return shared != null
                ? shared.sessionRegistry()
                : BaseStoreProtocolSessionRegistry.inMemory();
    }

    @Bean
    @ConditionalOnBean({AgentFactory.class, ProtocolTaskRepository.class})
    public AgentProtocolTaskStore agentProtocolTaskStore(
            AgentFactory agentFactory,
            ProtocolTaskRepository taskRepository,
            AgentProtocolEventBus eventBus,
            ProtocolTaskRunRegistry runRegistry,
            ProtocolTaskContextStore contextStore,
            ProtocolSessionRegistry sessionRegistry,
            AgentProtocolProperties properties,
            ObjectProvider<RuntimeContextCustomizer> runtimeContextCustomizers) {
        return new AgentProtocolTaskStore(
                agentFactory,
                taskRepository,
                eventBus,
                properties,
                runtimeContextCustomizers.orderedStream().toList(),
                runRegistry,
                contextStore,
                sessionRegistry);
    }

    @Bean
    @ConditionalOnBean(AgentProtocolTaskStore.class)
    public AgentProtocolController agentProtocolController(
            AgentProtocolTaskStore store, AgentProtocolProperties properties) {
        return new AgentProtocolController(store, properties);
    }
}
