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
import io.agentscope.harness.agent.workspace.WorkspaceManager;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
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
 * <p>For concurrent task execution, register the {@link HarnessAgent} bean as
 * {@code @Scope("prototype")} so that each task obtains its own instance. Alternatively, configure
 * the singleton with {@code checkRunning(false)} if concurrent access is otherwise safe.
 */
@AutoConfiguration
@EnableConfigurationProperties(AgentProtocolProperties.class)
@ConditionalOnProperty(prefix = "agentscope.agent-protocol", name = "enabled", havingValue = "true")
public class AgentProtocolAutoConfiguration {

    @Bean
    @ConditionalOnBean({HarnessAgent.class, WorkspaceManager.class})
    public AgentProtocolTaskStore agentProtocolTaskStore(
            ObjectProvider<HarnessAgent> agentProvider, WorkspaceManager workspaceManager) {
        return new AgentProtocolTaskStore(agentProvider::getObject, workspaceManager);
    }

    @Bean
    @ConditionalOnBean(AgentProtocolTaskStore.class)
    public AgentProtocolController agentProtocolController(AgentProtocolTaskStore store) {
        return new AgentProtocolController(store);
    }
}
