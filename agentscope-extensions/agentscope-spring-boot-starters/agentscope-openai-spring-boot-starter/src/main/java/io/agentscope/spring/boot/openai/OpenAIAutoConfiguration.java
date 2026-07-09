/*
 * Copyright 2024-2026 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package io.agentscope.spring.boot.openai;

import io.agentscope.core.model.Model;
import io.agentscope.extensions.model.openai.OpenAIChatModel;
import io.agentscope.spring.boot.AgentscopeAutoConfiguration;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;

/**
 * Spring Boot auto-configuration for the OpenAI model extension.
 */
@AutoConfiguration(before = AgentscopeAutoConfiguration.class)
@EnableConfigurationProperties(OpenAIProperties.class)
@ConditionalOnClass(OpenAIChatModel.class)
public class OpenAIAutoConfiguration {

    @Bean
    @ConditionalOnProperty(prefix = "agentscope.model", name = "provider", havingValue = "openai")
    @ConditionalOnProperty(
            prefix = "agentscope.openai",
            name = "enabled",
            havingValue = "true",
            matchIfMissing = true)
    @ConditionalOnMissingBean(Model.class)
    public OpenAIChatModel openAIChatModel(
            OpenAIProperties properties,
            ObjectProvider<OpenAIChatModelBuilderCustomizer> customizerObjectProvider) {
        String apiKey = trimToNull(properties.getApiKey());
        if (apiKey == null) {
            throw new IllegalStateException(
                    "agentscope.openai.api-key must be configured when OpenAI provider is"
                            + " selected");
        }

        String modelName = trimToNull(properties.getModelName());
        if (modelName == null) {
            throw new IllegalStateException(
                    "agentscope.openai.model-name must be configured when OpenAI provider is"
                            + " selected");
        }

        OpenAIChatModel.Builder builder =
                OpenAIChatModel.builder().apiKey(apiKey).modelName(modelName).stream(
                        properties.isStream());

        String baseUrl = trimToNull(properties.getBaseUrl());
        if (baseUrl != null) {
            builder.baseUrl(baseUrl);
        }

        String endpointPath = trimToNull(properties.getEndpointPath());
        if (endpointPath != null) {
            builder.endpointPath(endpointPath);
        }

        customizerObjectProvider
                .orderedStream()
                .forEach(customizer -> customizer.customize(builder));

        return builder.build();
    }

    private static String trimToNull(String value) {
        if (value == null) {
            return null;
        }
        String trimmed = value.trim();
        return trimmed.isEmpty() ? null : trimmed;
    }
}
