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

package io.agentscope.claw2.web.config;

import io.agentscope.core.model.ExecutionConfig;
import io.agentscope.core.model.GenerateOptions;
import io.agentscope.core.model.Model;
import io.agentscope.core.model.OpenAIChatModel;
import io.agentscope.core.model.transport.HttpTransport;
import io.agentscope.core.model.transport.HttpTransportConfig;
import io.agentscope.core.model.transport.JdkHttpTransport;
import java.net.http.HttpClient;
import java.time.Duration;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(LocalOpenAiModelConfig.LocalOpenAiProperties.class)
public class LocalOpenAiModelConfig {

    private static final Logger log = LoggerFactory.getLogger(LocalOpenAiModelConfig.class);

    private static final Duration DEFAULT_CONNECT_TIMEOUT = Duration.ofSeconds(10);

    private static final Duration DEFAULT_READ_TIMEOUT = Duration.ofSeconds(120);

    private static final int DEFAULT_MAX_ATTEMPTS = 1;

    @Bean
    @ConditionalOnMissingBean(Model.class)
    @ConditionalOnProperty(prefix = "claw.local-openai", name = "enabled", havingValue = "true")
    public Model localOpenAiModel(LocalOpenAiProperties properties) {
        // The API key is required by the OpenAI-compatible client plumbing.
        // Do not log this value: real deployments may use a private gateway token.
        String apiKey = requireText(properties.apiKey(), "claw.local-openai.api-key");
        String baseUrl = requireText(properties.baseUrl(), "claw.local-openai.base-url");
        String endpointPath = requireText(properties.endpointPath(), "claw.local-openai.endpoint-path");
        String modelName = requireText(properties.modelName(), "claw.local-openai.model-name");

        Duration connectTimeout =
                properties.connectTimeout() != null
                        ? properties.connectTimeout()
                        : DEFAULT_CONNECT_TIMEOUT;
        Duration readTimeout =
                properties.readTimeout() != null ? properties.readTimeout() : DEFAULT_READ_TIMEOUT;
        int maxAttempts =
                properties.maxAttempts() != null && properties.maxAttempts() > 0
                        ? properties.maxAttempts()
                        : DEFAULT_MAX_ATTEMPTS;

        log.info(
                "Building local OpenAI-compatible model: baseUrl={}, endpointPath={}, model={},"
                        + " stream={}, maxAttempts={}",
                baseUrl,
                endpointPath,
                modelName,
                properties.stream(),
                maxAttempts);

        GenerateOptions.Builder options =
                GenerateOptions.builder()
                        .executionConfig(
                                ExecutionConfig.builder()
                                        .timeout(readTimeout)
                                        .maxAttempts(maxAttempts)
                                        .build());

        if (properties.maxTokens() != null && properties.maxTokens() > 0) {
            options.maxTokens(properties.maxTokens());
        }
        if (properties.temperature() != null) {
            options.temperature(properties.temperature());
        }

        HttpTransportConfig transportConfig =
                HttpTransportConfig.builder()
                        .connectTimeout(connectTimeout)
                        .readTimeout(readTimeout)
                        .build();

        HttpClient httpClient =
                HttpClient.newBuilder().connectTimeout(connectTimeout).build();

        HttpTransport transport =
                JdkHttpTransport.builder().client(httpClient).config(transportConfig).build();

        return OpenAIChatModel.builder()
                .apiKey(apiKey)
                .baseUrl(baseUrl)
                .endpointPath(endpointPath)
                .modelName(modelName)
                .stream(properties.stream())
                .generateOptions(options.build())
                .httpTransport(transport)
                .build();
    }

    private static String requireText(String value, String propertyName) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(propertyName + " must not be blank");
        }
        return value;
    }

    @ConfigurationProperties(prefix = "claw.local-openai")
    public record LocalOpenAiProperties(
            boolean enabled,
            String apiKey,
            String baseUrl,
            String endpointPath,
            String modelName,
            boolean stream,
            Integer maxTokens,
            Double temperature,
            Duration readTimeout,
            Duration connectTimeout,
            Integer maxAttempts) {}
}