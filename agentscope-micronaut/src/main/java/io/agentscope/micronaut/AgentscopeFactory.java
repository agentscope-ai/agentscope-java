/*
 * Copyright 2024-2025 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * You may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      https://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package io.agentscope.micronaut;

import io.agentscope.core.ReActAgent;
import io.agentscope.core.memory.InMemoryMemory;
import io.agentscope.core.memory.Memory;
import io.agentscope.core.model.Model;
import io.agentscope.core.tool.Toolkit;
import io.agentscope.micronaut.model.ModelProviderType;
import io.agentscope.micronaut.properties.AgentscopeProperties;
import io.micronaut.context.annotation.Bean;
import io.micronaut.context.annotation.Factory;
import io.micronaut.context.annotation.Prototype;
import io.micronaut.context.annotation.Requires;
import jakarta.inject.Singleton;

@Factory
public class AgentscopeFactory {

    @Bean
    @Prototype
    @Requires(missingBeans = Memory.class)
    public Memory agentscopeMemory() {
        return new InMemoryMemory();
    }

    @Bean
    @Prototype
    @Requires(missingBeans = Toolkit.class)
    public Toolkit agentscopeToolkit() {
        return new Toolkit();
    }

    @Bean
    @Singleton
    @Requires(missingBeans = Model.class)
    public Model agentscopeModel(AgentscopeProperties properties) {
        return ModelProviderType.fromProperties(properties).createModel(properties);
    }

    @Bean
    @Prototype
    @Requires(property = "agentscope.agent.enabled", value = "true", defaultValue = "true")
    public ReActAgent agentscopeReActAgent(
            Model model, Memory memory, Toolkit toolkit, AgentscopeProperties properties) {
        AgentscopeProperties.AgentProperties config = properties.getAgent();
        return ReActAgent.builder()
                .name(config.getName())
                .sysPrompt(config.getSysPrompt())
                .model(model)
                .memory(memory)
                .toolkit(toolkit)
                .maxIters(config.getMaxIters())
                .build();
    }
}
