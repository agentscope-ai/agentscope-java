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

package io.agentscope.examples.bobatea.supervisor.config;

import io.agentscope.core.formatter.dashscope.DashScopeChatFormatter;
import io.agentscope.core.formatter.openai.OpenAIChatFormatter;
import io.agentscope.core.model.DashScopeChatModel;
import io.agentscope.core.model.Model;
import io.agentscope.core.model.OpenAIChatModel;
import io.agentscope.extensions.scheduler.config.DashScopeModelConfig;
import io.agentscope.extensions.scheduler.config.ModelConfig;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * AgentScope Model and Formatter Configuration
 * Supports both DashScope and OpenAI model providers
 */
@Configuration
public class AgentScopeModelConfig {
    private static final Logger logger = LoggerFactory.getLogger(AgentScopeModelConfig.class);

    private static final String PROVIDER_DASHSCOPE = "dashscope";
    private static final String PROVIDER_OPENAI = "openai";

    @Value("${agentscope.model.provider}")
    private String modelProvider;

    @Value("${agentscope.dashscope.api-key}")
    private String dashscopeApiKey;

    @Value("${agentscope.dashscope.model-name:qwen-max}")
    private String dashscopeModelName;

    @Value("${agentscope.dashscope.base-url}")
    private String dashscopeBaseUrl;

    @Value("${agentscope.openai.api-key}")
    private String openaiApiKey;

    @Value("${agentscope.openai.model-name:gpt-5}")
    private String openaiModelName;

    @Value("${agentscope.openai.base-url}")
    private String openaiBaseUrl;

    @Bean
    public Model model() {
        if (PROVIDER_OPENAI.equalsIgnoreCase(modelProvider)) {
            logger.info(
                    "Creating OpenAI Model with model: {}, baseUrl: {}",
                    openaiModelName,
                    openaiBaseUrl);
            OpenAIChatModel.Builder builder =
                    OpenAIChatModel.builder()
                            .apiKey(openaiApiKey)
                            .modelName(openaiModelName)
                            .formatter(new OpenAIChatFormatter());
            if (openaiBaseUrl != null && !openaiBaseUrl.isEmpty() && !openaiBaseUrl.equals("-")) {
                builder.baseUrl(openaiBaseUrl);
            }
            return builder.build();
        } else {
            logger.info(
                    "Creating DashScope Model with model: {}, baseUrl: {}",
                    dashscopeModelName,
                    dashscopeBaseUrl);
            DashScopeChatModel.Builder builder =
                    DashScopeChatModel.builder()
                            .apiKey(dashscopeApiKey)
                            .modelName(dashscopeModelName)
                            .formatter(new DashScopeChatFormatter());
            if (dashscopeBaseUrl != null
                    && !dashscopeBaseUrl.isEmpty()
                    && !dashscopeBaseUrl.equals("-")) {
                builder.baseUrl(dashscopeBaseUrl);
            }
            return builder.build();
        }
    }

    @Bean
    public ModelConfig modelConfig() {
        logger.info(
                "Creating DashScope ModelConfig with model: {}, baseUrl: {}",
                dashscopeModelName,
                dashscopeBaseUrl);
        DashScopeModelConfig.Builder builder =
                DashScopeModelConfig.builder()
                        .apiKey(dashscopeApiKey)
                        .modelName(dashscopeModelName);
        if (dashscopeBaseUrl != null
                && !dashscopeBaseUrl.isEmpty()
                && !dashscopeBaseUrl.equals("-")) {
            builder.baseUrl(dashscopeBaseUrl);
        }
        return builder.build();
    }
}
