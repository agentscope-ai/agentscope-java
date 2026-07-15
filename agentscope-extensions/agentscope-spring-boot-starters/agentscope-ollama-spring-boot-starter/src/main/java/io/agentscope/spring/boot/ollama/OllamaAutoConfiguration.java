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
package io.agentscope.spring.boot.ollama;

import io.agentscope.core.model.Model;
import io.agentscope.extensions.model.ollama.OllamaChatModel;
import io.agentscope.spring.boot.AgentscopeAutoConfiguration;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;

/**
 * Spring Boot auto-configuration for the Ollama model extension.
 */
@AutoConfiguration(before = AgentscopeAutoConfiguration.class)
@EnableConfigurationProperties(OllamaProperties.class)
@ConditionalOnClass(OllamaChatModel.class)
public class OllamaAutoConfiguration {

    @Bean
    @ConditionalOnProperty(prefix = "agentscope.model", name = "provider", havingValue = "ollama")
    @ConditionalOnProperty(
            prefix = "agentscope.ollama",
            name = "enabled",
            havingValue = "true",
            matchIfMissing = true)
    @ConditionalOnMissingBean(Model.class)
    public OllamaChatModel ollamaChatModel(
            OllamaProperties properties,
            ObjectProvider<OllamaChatModelBuilderCustomizer> customizerObjectProvider) {
        String modelName = trimToNull(properties.getModelName());
        if (modelName == null) {
            throw new IllegalStateException(
                    "agentscope.ollama.model-name must be configured when Ollama provider is"
                            + " selected");
        }
        OllamaChatModel.Builder builder = OllamaChatModel.builder().modelName(modelName);
        String baseUrl = trimToNull(properties.getBaseUrl());
        if (baseUrl != null) {
            builder.baseUrl(baseUrl);
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
