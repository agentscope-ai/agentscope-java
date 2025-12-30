/*
 * Copyright 2024-2025 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * You may not use this file except in compliance with the License.
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
package io.agentscope.spring.boot.chat.config;

import io.agentscope.core.ReActAgent;
import io.agentscope.spring.boot.chat.builder.ChatCompletionsResponseBuilder;
import io.agentscope.spring.boot.chat.converter.ChatMessageConverter;
import io.agentscope.spring.boot.chat.session.ChatCompletionsSessionManager;
import io.agentscope.spring.boot.chat.session.InMemorySessionManager;
import io.agentscope.spring.boot.chat.streaming.ChatCompletionsStreamingService;
import io.agentscope.spring.boot.chat.web.ChatCompletionsController;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.ComponentScan;

/**
 * Auto-configuration for exposing a Chat Completions style HTTP API.
 *
 * <p>This configuration assumes that the core {@code agentscope-spring-boot-starter} is already
 * on the classpath and has configured a prototype-scoped {@link ReActAgent} bean.
 *
 * <p>Service components ({@code ChatMessageConverter}, {@code ChatCompletionsResponseBuilder},
 * {@code ChatCompletionsStreamingService}) are automatically discovered via component scanning
 * and do not need to be explicitly created here.
 */
@AutoConfiguration
@ComponentScan(basePackages = "io.agentscope.spring.boot.chat")
@EnableConfigurationProperties(ChatCompletionsProperties.class)
@ConditionalOnProperty(
        prefix = "agentscope.chat-completions",
        name = "enabled",
        havingValue = "true",
        matchIfMissing = true)
@ConditionalOnClass(ReActAgent.class)
public class ChatCompletionsWebAutoConfiguration {

    /**
     * Create the default session manager bean.
     *
     * <p>Users can provide their own implementation by creating a bean of type
     * {@link ChatCompletionsSessionManager}.
     */
    @Bean
    @ConditionalOnMissingBean
    public ChatCompletionsSessionManager chatCompletionsSessionManager() {
        return new InMemorySessionManager();
    }

    /**
     * Create the chat completions controller bean.
     *
     * <p>Service dependencies ({@code ChatMessageConverter}, {@code ChatCompletionsResponseBuilder},
     * {@code ChatCompletionsStreamingService}) are automatically injected by Spring from component
     * scanning. Spring will automatically provide these @Component beans as method parameters.
     *
     * <p>This bean is only created if:
     * <ul>
     *   <li>No existing {@link ChatCompletionsController} bean exists</li>
     *   <li>The property {@code agentscope.chat-completions.enabled} is {@code true} (default)</li>
     * </ul>
     */
    @Bean
    @ConditionalOnMissingBean
    public ChatCompletionsController chatCompletionsController(
            ObjectProvider<ReActAgent> agentProvider,
            ChatCompletionsSessionManager sessionManager,
            ChatMessageConverter messageConverter,
            ChatCompletionsResponseBuilder responseBuilder,
            ChatCompletionsStreamingService streamingService) {
        // Spring automatically injects @Component beans (messageConverter, responseBuilder,
        // streamingService)
        // from component scanning - we just need to declare them as parameters
        return new ChatCompletionsController(
                agentProvider, sessionManager, messageConverter, responseBuilder, streamingService);
    }
}
