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
import io.agentscope.core.chat.completions.session.InMemorySessionManager;
import io.agentscope.core.session.InMemorySession;
import io.agentscope.core.session.Session;
import io.agentscope.spring.boot.chat.session.SpringChatCompletionsSessionManager;
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
     * Create the session manager bean using core {@link InMemorySessionManager}.
     *
     * <p>This bean bridges Spring's {@link ObjectProvider} for prototype-scoped ReActAgent with the
     * core session manager. Uses the injected {@link Session} bean for agent state management.
     *
     * @param agentProvider Provider for creating new ReActAgent instances (prototype-scoped)
     * @param session The Session bean for state storage (injected via Spring)
     * @return A {@link SpringChatCompletionsSessionManager} that delegates to
     *     {@link InMemorySessionManager}
     */
    @Bean
    @ConditionalOnMissingBean(SpringChatCompletionsSessionManager.class)
    public SpringChatCompletionsSessionManager sessionManager(
            ObjectProvider<ReActAgent> agentProvider, Session session) {
        InMemorySessionManager delegate = new InMemorySessionManager(session);
        return sessionId -> delegate.getOrCreateAgent(sessionId, agentProvider::getObject);
    }

    /**
     * Create the chat completions controller bean.
     *
     * @param sessionManager Manager for session-scoped agents
     * @param messageConverter Converter for HTTP DTOs to framework messages
     * @param responseBuilder Builder for response objects
     * @param streamingService Service for streaming responses
     * @return The configured ChatCompletionsController bean
     */
    @Bean
    @ConditionalOnMissingBean
    public ChatCompletionsController chatCompletionsController(
            SpringChatCompletionsSessionManager sessionManager,
            ChatMessageConverter messageConverter,
            ChatCompletionsResponseBuilder responseBuilder,
            ChatCompletionsStreamingService streamingService) {
        return new ChatCompletionsController(
                sessionManager, messageConverter, responseBuilder, streamingService);
    }
}
