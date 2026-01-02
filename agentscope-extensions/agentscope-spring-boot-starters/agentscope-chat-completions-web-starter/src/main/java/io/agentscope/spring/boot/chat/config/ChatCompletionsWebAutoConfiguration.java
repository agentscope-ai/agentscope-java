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
package io.agentscope.spring.boot.chat.config;

import io.agentscope.core.ReActAgent;
import io.agentscope.core.chat.completions.builder.ChatCompletionsResponseBuilder;
import io.agentscope.core.chat.completions.converter.ChatMessageConverter;
import io.agentscope.core.session.InMemorySession;
import io.agentscope.core.session.Session;
import io.agentscope.spring.boot.chat.service.ChatCompletionsAgentService;
import io.agentscope.spring.boot.chat.service.ChatCompletionsStreamingService;
import io.agentscope.spring.boot.chat.web.ChatCompletionsController;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;

/**
 * Auto-configuration for exposing a Chat Completions style HTTP API.
 *
 * <p>This configuration assumes that the core {@code agentscope-spring-boot-starter} is already on
 * the classpath and has configured a prototype-scoped {@link ReActAgent} bean.
 *
 * <p><b>Simplified Design:</b> This configuration uses {@link ChatCompletionsAgentService} in the
 * service layer to manage agent lifecycle and state persistence, creating a new prototype-scoped
 * agent for each request and loading/saving state from/to the configured {@link Session}.
 */
@AutoConfiguration
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
     * @return A new {@link ChatCompletionsResponseBuilder} instance for building chat completion
     *     responses
     */
    @Bean
    @ConditionalOnMissingBean
    public ChatCompletionsResponseBuilder chatCompletionsResponseBuilder() {
        return new ChatCompletionsResponseBuilder();
    }

    /**
     * Create the default Session bean for agent state management.
     *
     * <p>This bean provides in-memory session storage. Users can override by providing their own
     * {@link Session} bean (e.g., JsonSession, MysqlSession, RedisSession).
     *
     * @return A new {@link InMemorySession} instance
     */
    @Bean
    @ConditionalOnMissingBean(Session.class)
    public Session chatCompletionsSession() {
        return new InMemorySession();
    }

    /**
     * Create the agent service bean for managing agent lifecycle and state.
     *
     * <p>This service:
     *
     * <ul>
     *   <li>Creates a new prototype-scoped agent for each request via {@link ObjectProvider}
     *   <li>Loads agent state from {@link Session} if the session exists
     *   <li>Saves agent state to {@link Session} after request completes
     * </ul>
     *
     * @param agentProvider Provider for creating new ReActAgent instances (prototype-scoped)
     * @param session The Session bean for state storage
     * @return A new {@link ChatCompletionsAgentService} instance
     */
    @Bean
    @ConditionalOnMissingBean
    public ChatCompletionsAgentService chatCompletionsAgentService(
            ObjectProvider<ReActAgent> agentProvider, Session session) {
        return new ChatCompletionsAgentService(agentProvider, session);
    }

    /**
     * Create the streaming service bean.
     *
     * @param responseBuilder Builder for extracting text content from agent messages
     * @return A new {@link ChatCompletionsStreamingService} instance
     */
    @Bean
    @ConditionalOnMissingBean
    public ChatCompletionsStreamingService chatCompletionsStreamingService(
            ChatCompletionsResponseBuilder responseBuilder) {
        return new ChatCompletionsStreamingService(responseBuilder);
    }

    /**
     * Create the chat completions controller bean.
     *
     * @param agentService Service for managing agent lifecycle and state persistence
     * @param messageConverter Converter for HTTP DTOs to framework messages
     * @param responseBuilder Builder for response objects
     * @param streamingService Service for streaming responses
     * @return The configured ChatCompletionsController bean
     */
    @Bean
    @ConditionalOnMissingBean
    public ChatCompletionsController chatCompletionsController(
            ChatCompletionsAgentService agentService,
            ChatMessageConverter messageConverter,
            ChatCompletionsResponseBuilder responseBuilder,
            ChatCompletionsStreamingService streamingService) {
        return new ChatCompletionsController(
                agentService, messageConverter, responseBuilder, streamingService);
    }
}
