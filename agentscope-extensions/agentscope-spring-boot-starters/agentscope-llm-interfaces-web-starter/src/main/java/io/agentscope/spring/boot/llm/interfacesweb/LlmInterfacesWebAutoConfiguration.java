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
package io.agentscope.spring.boot.llm.interfacesweb;

import io.agentscope.core.ReActAgent;
import io.agentscope.core.chat.completions.builder.ChatCompletionsResponseBuilder;
import io.agentscope.core.chat.completions.converter.ChatMessageConverter;
import io.agentscope.core.chat.completions.converter.OpenAIToolConverter;
import io.agentscope.core.chat.completions.streaming.ChatCompletionsStreamingAdapter;
import io.agentscope.core.llm.interfacesweb.anthropic.AnthropicMessageConverter;
import io.agentscope.core.llm.interfacesweb.anthropic.AnthropicResponseBuilder;
import io.agentscope.core.llm.interfacesweb.anthropic.AnthropicStreamingAdapter;
import io.agentscope.core.llm.interfacesweb.anthropic.AnthropicToolConverter;
import io.agentscope.core.llm.interfacesweb.responses.ResponsesMessageConverter;
import io.agentscope.core.llm.interfacesweb.responses.ResponsesResponseBuilder;
import io.agentscope.core.llm.interfacesweb.responses.ResponsesStreamingAdapter;
import io.agentscope.core.llm.interfacesweb.responses.ResponsesToolConverter;
import io.agentscope.core.model.Model;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;

/** Auto-configuration for aggregated OpenAI/Anthropic-compatible LLM HTTP endpoints. */
@AutoConfiguration
@EnableConfigurationProperties(LlmInterfacesProperties.class)
@ConditionalOnProperty(
        prefix = "agentscope.llm-interfaces",
        name = "enabled",
        havingValue = "true",
        matchIfMissing = true)
@ConditionalOnClass(ReActAgent.class)
public class LlmInterfacesWebAutoConfiguration {

    @Bean
    @ConditionalOnMissingBean
    public ChatMessageConverter llmInterfacesChatMessageConverter() {
        return new ChatMessageConverter();
    }

    @Bean
    @ConditionalOnMissingBean
    public OpenAIToolConverter llmInterfacesOpenAIToolConverter() {
        return new OpenAIToolConverter();
    }

    @Bean
    @ConditionalOnMissingBean
    public ChatCompletionsResponseBuilder llmInterfacesChatResponseBuilder() {
        return new ChatCompletionsResponseBuilder();
    }

    @Bean
    @ConditionalOnMissingBean
    public ChatCompletionsStreamingAdapter llmInterfacesChatStreamingAdapter() {
        return new ChatCompletionsStreamingAdapter();
    }

    @Bean
    @ConditionalOnMissingBean
    public ResponsesMessageConverter responsesMessageConverter() {
        return new ResponsesMessageConverter();
    }

    @Bean
    @ConditionalOnMissingBean
    public ResponsesToolConverter responsesToolConverter() {
        return new ResponsesToolConverter();
    }

    @Bean
    @ConditionalOnMissingBean
    public ResponsesResponseBuilder responsesResponseBuilder() {
        return new ResponsesResponseBuilder();
    }

    @Bean
    @ConditionalOnMissingBean
    public ResponsesStreamingAdapter responsesStreamingAdapter(
            ResponsesResponseBuilder responseBuilder) {
        return new ResponsesStreamingAdapter(responseBuilder);
    }

    @Bean
    @ConditionalOnMissingBean
    public AnthropicMessageConverter anthropicMessageConverter() {
        return new AnthropicMessageConverter();
    }

    @Bean
    @ConditionalOnMissingBean
    public AnthropicToolConverter anthropicToolConverter() {
        return new AnthropicToolConverter();
    }

    @Bean
    @ConditionalOnMissingBean
    public AnthropicResponseBuilder anthropicResponseBuilder() {
        return new AnthropicResponseBuilder();
    }

    @Bean
    @ConditionalOnMissingBean
    public AnthropicStreamingAdapter anthropicStreamingAdapter(
            AnthropicResponseBuilder responseBuilder) {
        return new AnthropicStreamingAdapter(responseBuilder);
    }

    @Bean
    @ConditionalOnMissingBean
    public LlmInterfacesController llmInterfacesController(
            ObjectProvider<ReActAgent> agentProvider,
            ObjectProvider<Model> modelProvider,
            LlmInterfacesProperties properties,
            ChatMessageConverter chatMessageConverter,
            OpenAIToolConverter openAIToolConverter,
            ChatCompletionsResponseBuilder chatResponseBuilder,
            ChatCompletionsStreamingAdapter chatStreamingAdapter,
            ResponsesMessageConverter responsesMessageConverter,
            ResponsesToolConverter responsesToolConverter,
            ResponsesResponseBuilder responsesResponseBuilder,
            ResponsesStreamingAdapter responsesStreamingAdapter,
            AnthropicMessageConverter anthropicMessageConverter,
            AnthropicToolConverter anthropicToolConverter,
            AnthropicResponseBuilder anthropicResponseBuilder,
            AnthropicStreamingAdapter anthropicStreamingAdapter) {
        return new LlmInterfacesController(
                agentProvider,
                modelProvider,
                properties,
                chatMessageConverter,
                openAIToolConverter,
                chatResponseBuilder,
                chatStreamingAdapter,
                responsesMessageConverter,
                responsesToolConverter,
                responsesResponseBuilder,
                responsesStreamingAdapter,
                anthropicMessageConverter,
                anthropicToolConverter,
                anthropicResponseBuilder,
                anthropicStreamingAdapter);
    }
}
