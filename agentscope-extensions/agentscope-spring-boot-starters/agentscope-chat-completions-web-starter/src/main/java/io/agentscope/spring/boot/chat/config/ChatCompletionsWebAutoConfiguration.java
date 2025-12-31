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
import io.agentscope.core.chat.completions.builder.ChatCompletionsResponseBuilder;
import io.agentscope.core.chat.completions.converter.ChatMessageConverter;
import io.agentscope.spring.boot.chat.session.SpringChatCompletionsSessionManager;
import io.agentscope.spring.boot.chat.session.SpringInMemorySessionManager;
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
     * Create the message converter bean.
     *
     * <p>Users can provide their own implementation by creating a bean of type
     * {@link ChatMessageConverter}.
     *
     * @return A new {@link ChatMessageConverter} instance for converting HTTP DTOs to framework
     *     messages
     */
    @Bean
    @ConditionalOnMissingBean
    public ChatMessageConverter chatMessageConverter() {
        return new ChatMessageConverter();
    }

    /**
     * Create the response builder bean.
     *
     * <p>Users can provide their own implementation by creating a bean of type
     * {@link ChatCompletionsResponseBuilder}.
     *
     * @return A new {@link ChatCompletionsResponseBuilder} instance for building chat completion
     *     responses
     */
    @Bean
    @ConditionalOnMissingBean
    public ChatCompletionsResponseBuilder chatCompletionsResponseBuilder() {
        return new ChatCompletionsResponseBuilder();
    }

    /**
     * Create the default in-memory session manager bean.
     *
     * <p>This bean is created when:
     * <ul>
     *   <li>No existing {@link SpringChatCompletionsSessionManager} bean exists</li>
     *   <li>The session manager type is "in-memory" (default) or not specified</li>
     * </ul>
     *
     * <p>Users can provide their own implementation by creating a bean of type
     * {@link SpringChatCompletionsSessionManager}, or configure a different type via
     * {@code agentscope.chat-completions.session-manager.type}.
     *
     * @return A new {@link SpringInMemorySessionManager} instance for managing session-scoped
     *     agents in memory
     */
    @Bean
    @ConditionalOnMissingBean(SpringChatCompletionsSessionManager.class)
    @ConditionalOnProperty(
            prefix = "agentscope.chat-completions.session-manager",
            name = "type",
            havingValue = "in-memory",
            matchIfMissing = true)
    public SpringChatCompletionsSessionManager inMemorySessionManager() {
        return new SpringInMemorySessionManager();
    }

    // Future: Add RedisSessionManagerAdapter bean with @ConditionalOnProperty(type = "redis")
    // Future: Add MySQLSessionManagerAdapter bean with @ConditionalOnProperty(type = "mysql")

    /**
     * Create the chat completions controller bean.
     *
     * <p>This bean is only created if:
     * <ul>
     *   <li>No existing {@link ChatCompletionsController} bean exists</li>
     *   <li>The property {@code agentscope.chat-completions.enabled} is {@code true} (default)</li>
     * </ul>
     *
     * @param agentProvider Provider for creating ReActAgent instances
     * @param sessionManager Manager for session-scoped agents
     * @param messageConverter Converter for HTTP DTOs to framework messages
     * @param responseBuilder Builder for response objects
     * @param streamingService Service for streaming responses
     * @return The configured ChatCompletionsController bean
     */
    @Bean
    @ConditionalOnMissingBean
    public ChatCompletionsController chatCompletionsController(
            ObjectProvider<ReActAgent> agentProvider,
            SpringChatCompletionsSessionManager sessionManager,
            ChatMessageConverter messageConverter,
            ChatCompletionsResponseBuilder responseBuilder,
            ChatCompletionsStreamingService streamingService) {
        return new ChatCompletionsController(
                agentProvider, sessionManager, messageConverter, responseBuilder, streamingService);
    }
}
