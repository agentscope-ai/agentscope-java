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
package io.agentscope.spring.boot.responses.config;

import io.agentscope.core.ReActAgent;
import io.agentscope.core.responses.builder.ResponsesResponseBuilder;
import io.agentscope.core.responses.converter.ResponsesGenerationOptionsConverter;
import io.agentscope.core.responses.converter.ResponsesInputConverter;
import io.agentscope.core.responses.converter.ResponsesToolConverter;
import io.agentscope.core.responses.streaming.ResponsesStreamingAdapter;
import io.agentscope.spring.boot.responses.service.ResponsesStateService;
import io.agentscope.spring.boot.responses.service.ResponsesStreamingService;
import io.agentscope.spring.boot.responses.web.ConversationsController;
import io.agentscope.spring.boot.responses.web.ResponsesController;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;

/**
 * Auto-configuration for exposing Responses and Conversations API endpoints.
 *
 * <p>The starter contributes only the web-facing Responses API beans. It relies on
 * {@code agentscope-spring-boot-starter} to provide a {@link ReActAgent}. Request hooks, external
 * tool schemas, and transient execution state are isolated per invocation, so both singleton and
 * prototype agent beans are supported.
 */
@AutoConfiguration
@EnableConfigurationProperties(ResponsesProperties.class)
@ConditionalOnProperty(
        prefix = "agentscope.responses",
        name = "enabled",
        havingValue = "true",
        matchIfMissing = true)
@ConditionalOnClass(ReActAgent.class)
public class ResponsesWebAutoConfiguration {

    /** Create the request converter that maps Responses DTOs to AgentScope messages. */
    @Bean
    @ConditionalOnMissingBean
    public ResponsesInputConverter responsesInputConverter() {
        return new ResponsesInputConverter();
    }

    /** Create the converter that maps Responses function tools to AgentScope tool schemas. */
    @Bean
    @ConditionalOnMissingBean
    public ResponsesToolConverter responsesToolConverter() {
        return new ResponsesToolConverter();
    }

    /** Create the converter for request-level generation options. */
    @Bean
    @ConditionalOnMissingBean
    public ResponsesGenerationOptionsConverter responsesGenerationOptionsConverter(
            ResponsesToolConverter toolConverter) {
        return new ResponsesGenerationOptionsConverter(toolConverter);
    }

    /** Create the builder for non-streaming and terminal streaming Responses objects. */
    @Bean
    @ConditionalOnMissingBean
    public ResponsesResponseBuilder responsesResponseBuilder() {
        return new ResponsesResponseBuilder();
    }

    /** Create the framework-agnostic adapter from AgentScope stream events to Responses events. */
    @Bean
    @ConditionalOnMissingBean
    public ResponsesStreamingAdapter responsesStreamingAdapter(
            ResponsesResponseBuilder responseBuilder) {
        return new ResponsesStreamingAdapter(responseBuilder);
    }

    /** Create the Spring SSE service for Responses streaming. */
    @Bean
    @ConditionalOnMissingBean
    public ResponsesStreamingService responsesStreamingService(
            ResponsesStreamingAdapter streamingAdapter) {
        return new ResponsesStreamingService(streamingAdapter);
    }

    /** Create the state service for stored responses, background requests, and conversations. */
    @Bean
    @ConditionalOnMissingBean
    public ResponsesStateService responsesStateService() {
        return new ResponsesStateService();
    }

    /** Create the HTTP controller that exposes the configured Responses endpoint. */
    @Bean
    @ConditionalOnMissingBean
    public ResponsesController responsesController(
            ObjectProvider<ReActAgent> agentProvider,
            ResponsesInputConverter inputConverter,
            ResponsesToolConverter toolConverter,
            ResponsesGenerationOptionsConverter generationOptionsConverter,
            ResponsesResponseBuilder responseBuilder,
            ResponsesStreamingService streamingService,
            ResponsesStateService stateService) {
        return new ResponsesController(
                agentProvider,
                inputConverter,
                toolConverter,
                generationOptionsConverter,
                responseBuilder,
                streamingService,
                stateService);
    }

    /** Create the HTTP controller that exposes the configured Conversations endpoint. */
    @Bean
    @ConditionalOnMissingBean
    public ConversationsController conversationsController(
            ResponsesStateService stateService, ResponsesResponseBuilder responseBuilder) {
        return new ConversationsController(stateService, responseBuilder);
    }
}
