/*
 * Copyright 2024-2025 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      https://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package io.agentscope.core.formatter.openai;

import com.openai.models.chat.completions.ChatCompletionCreateParams;
import com.openai.models.chat.completions.ChatCompletionMessageParam;
import io.agentscope.core.formatter.AbstractBaseFormatter;
import io.agentscope.core.message.Msg;
import io.agentscope.core.model.ChatResponse;
import io.agentscope.core.model.GenerateOptions;
import io.agentscope.core.model.ToolChoice;
import io.agentscope.core.model.ToolSchema;
import java.time.Instant;
import java.util.List;
import java.util.stream.Collectors;

/**
 * Formatter for OpenAI Chat Completion API.
 * Converts between AgentScope Msg objects and OpenAI SDK types.
 *
 * <p>Note: OpenAI has two response types (ChatCompletion for non-streaming and ChatCompletionChunk
 * for streaming), so this formatter handles both via the OpenAIResponseParser.
 */
public class OpenAIChatFormatter
        extends AbstractBaseFormatter<
                ChatCompletionMessageParam, Object, ChatCompletionCreateParams.Builder> {

    private final OpenAIMessageConverter messageConverter;
    private final OpenAIResponseParser responseParser;
    private final OpenAIToolsHelper toolsHelper;

    public OpenAIChatFormatter() {
        this.messageConverter =
                new OpenAIMessageConverter(
                        this::extractTextContent, this::convertToolResultToString);
        this.responseParser = new OpenAIResponseParser();
        this.toolsHelper = new OpenAIToolsHelper();
    }

    @Override
    public List<ChatCompletionMessageParam> format(List<Msg> msgs) {
        return msgs.stream()
                .map(msg -> messageConverter.convertToParam(msg, hasMediaContent(msg)))
                .collect(Collectors.toList());
    }

    @Override
    public ChatResponse parseResponse(Object response, Instant startTime) {
        return responseParser.parseResponse(response, startTime);
    }

    @Override
    public void applyOptions(
            ChatCompletionCreateParams.Builder paramsBuilder,
            GenerateOptions options,
            GenerateOptions defaultOptions) {
        toolsHelper.applyOptions(
                paramsBuilder,
                options,
                defaultOptions,
                opt -> getOptionOrDefault(options, defaultOptions, opt));
    }

    @Override
    public void applyTools(
            ChatCompletionCreateParams.Builder paramsBuilder, List<ToolSchema> tools) {
        toolsHelper.applyTools(paramsBuilder, tools);
    }

    /**
     * Apply tool choice configuration to OpenAI request parameters.
     *
     * @param paramsBuilder OpenAI request parameters builder
     * @param toolChoice Tool choice configuration
     */
    public void applyToolChoice(
            ChatCompletionCreateParams.Builder paramsBuilder, ToolChoice toolChoice) {
        toolsHelper.applyToolChoice(paramsBuilder, toolChoice);
    }
}
