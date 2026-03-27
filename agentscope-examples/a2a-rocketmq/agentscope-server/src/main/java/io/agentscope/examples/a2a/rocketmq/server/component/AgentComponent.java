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
package io.agentscope.examples.a2a.rocketmq.server.component;

import io.agentscope.core.ReActAgent;
import io.agentscope.core.model.DashScopeChatModel;
import org.apache.rocketmq.shaded.commons.lang3.StringUtils;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.stereotype.Component;

/**
 * Component for configuring and creating ReActAgent instances.
 */
@Component
public class AgentComponent {
    /**
     * Creates a ReActAgent builder with default configuration.
     *
     * @return configured ReActAgent builder.
     */
    @Bean
    public ReActAgent.Builder agentBuilder(
            @Value("${agentscope.dashscope.api-key:}") String dashScopeApiKey,
            @Value("${agentscope.agent.name:}") String agentName,
            @Value("${agentscope.agent.modelName:qwen-plus}") String modelName) {
        return ReActAgent.builder()
                .name(agentName)
                .model(dashScopeChatModel(dashScopeApiKey, modelName));
    }

    /**
     * Builds a DashScope chat model with the specified API key.
     *
     * @param dashScopeApiKey the API key for DashScope authentication.
     * @return configured DashScopeChatModel instance.
     * @throws IllegalArgumentException if API key is null or empty.
     */
    public static DashScopeChatModel dashScopeChatModel(String dashScopeApiKey, String modelName) {
        if (StringUtils.isEmpty(dashScopeApiKey) || StringUtils.isEmpty(modelName)) {
            throw new IllegalArgumentException(
                    "DashScopeApiKey Or modelName is empty, please check your configuration");
        }
        return DashScopeChatModel.builder().apiKey(dashScopeApiKey).modelName(modelName).stream(
                        true)
                .enableThinking(false)
                .build();
    }
}
