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
import io.agentscope.core.chat.completions.streaming.ChatCompletionsStreamingAdapter;
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
 * <p><b>Stateless API Design:</b>
 *
 * <p>This API is <b>100% stateless</b>, fully compatible with OpenAI's Chat Completions API:
 *
 * <ul>
 *   <li>Each request is independent - no server-side state
 *   <li>Client sends complete conversation history in {@code messages}
 *   <li>Server creates a fresh agent, processes messages, and returns response
 *   <li>Client appends response to their history for next request
 * </ul>
 *
 * <p>For clients that need server-side session management, consider using a different API design
 * pattern such as the OpenAI Assistants API or implementing session management in a higher layer.
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
     * Create the streaming adapter bean.
     *
     * <p>This is the framework-agnostic adapter that handles core streaming logic.
     *
     * @return A new {@link ChatCompletionsStreamingAdapter} instance
     */
    @Bean
    @ConditionalOnMissingBean
    public ChatCompletionsStreamingAdapter chatCompletionsStreamingAdapter() {
        return new ChatCompletionsStreamingAdapter();
    }

    /**
     * Create the streaming service bean.
     *
     * <p>This is the Spring-specific adapter that converts chunks to SSE events.
     *
     * @param streamingAdapter The framework-agnostic streaming adapter
     * @return A new {@link ChatCompletionsStreamingService} instance
     */
    @Bean
    @ConditionalOnMissingBean
    public ChatCompletionsStreamingService chatCompletionsStreamingService(
            ChatCompletionsStreamingAdapter streamingAdapter) {
        return new ChatCompletionsStreamingService(streamingAdapter);
    }

    /**
     * Create the chat completions controller bean.
     *
     * <p>The controller uses {@link ObjectProvider} to create a fresh prototype-scoped agent for
     * each request, ensuring stateless operation.
     *
     * @param agentProvider Provider for creating prototype-scoped ReActAgent instances
     * @param messageConverter Converter for HTTP DTOs to framework messages
     * @param responseBuilder Builder for response objects
     * @param streamingService Service for streaming responses
     * @return The configured ChatCompletionsController bean
     */
    @Bean
    @ConditionalOnMissingBean
    public ChatCompletionsController chatCompletionsController(
            ObjectProvider<ReActAgent> agentProvider,
            ChatMessageConverter messageConverter,
            ChatCompletionsResponseBuilder responseBuilder,
            ChatCompletionsStreamingService streamingService) {
        return new ChatCompletionsController(
                agentProvider, messageConverter, responseBuilder, streamingService);
    }
}
