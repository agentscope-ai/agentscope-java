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
package io.agentscope.core.formatter.openai;

import io.agentscope.core.formatter.AbstractBaseFormatter;
import io.agentscope.core.formatter.openai.dto.OpenAIMessage;
import io.agentscope.core.formatter.openai.dto.OpenAIRequest;
import io.agentscope.core.formatter.openai.dto.OpenAIResponse;
import io.agentscope.core.model.ChatResponse;
import io.agentscope.core.model.GenerateOptions;
import io.agentscope.core.model.ToolChoice;
import io.agentscope.core.model.ToolSchema;
import java.time.Instant;
import java.util.List;

/**
 * Base formatter for OpenAI Chat Completion HTTP API.
 * Provides common functionality for both single-agent and multi-agent formatters.
 */
public abstract class OpenAIBaseFormatter
        extends AbstractBaseFormatter<OpenAIMessage, OpenAIResponse, OpenAIRequest> {

    protected final OpenAIMessageConverter messageConverter;
    protected final OpenAIResponseParser responseParser;
    protected final OpenAIToolsHelper toolsHelper;

    protected OpenAIBaseFormatter() {
        this.messageConverter =
                new OpenAIMessageConverter(
                        this::extractTextContent, this::convertToolResultToString);
        this.responseParser = new OpenAIResponseParser();
        this.toolsHelper = new OpenAIToolsHelper();
    }

    @Override
    public ChatResponse parseResponse(OpenAIResponse response, Instant startTime) {
        return responseParser.parseResponse(response, startTime);
    }

    @Override
    public void applyOptions(
            OpenAIRequest request, GenerateOptions options, GenerateOptions defaultOptions) {
        toolsHelper.applyOptions(request, options, defaultOptions);
    }

    @Override
    public void applyTools(OpenAIRequest request, List<ToolSchema> tools) {
        toolsHelper.applyTools(request, tools);
    }

    @Override
    public void applyTools(
            OpenAIRequest request, List<ToolSchema> tools, String baseUrl, String modelName) {
        ProviderCapability capability = ProviderCapability.fromUrl(baseUrl);
        if (capability == ProviderCapability.UNKNOWN && modelName != null) {
            capability = ProviderCapability.fromModelName(modelName);
        }
        toolsHelper.applyTools(request, tools, capability);
    }

    @Override
    public void applyToolChoice(OpenAIRequest request, ToolChoice toolChoice) {
        toolsHelper.applyToolChoice(request, toolChoice);
    }

    @Override
    public void applyToolChoice(
            OpenAIRequest request, ToolChoice toolChoice, String baseUrl, String modelName) {
        toolsHelper.applyToolChoice(request, toolChoice, baseUrl, modelName);
    }

    /**
     * Build a basic OpenAIRequest.
     *
     * @param model Model name
     * @param messages Formatted OpenAI messages
     * @param stream Whether to enable streaming
     * @return Basic OpenAIRequest
     */
    public OpenAIRequest buildRequest(String model, List<OpenAIMessage> messages, boolean stream) {
        return OpenAIRequest.builder().model(model).messages(messages).stream(stream).build();
    }

    /**
     * Build a complete OpenAIRequest with full configuration.
     * This method is provided for convenience but usage via the standard Formatter interface
     * (instantiating request manually and calling apply methods) is preferred in generic code.
     *
     * @param model Model name
     * @param messages Formatted OpenAI messages
     * @param stream Whether to enable streaming
     * @param options Generation options
     * @param defaultOptions Default generation options
     * @param tools Tool schemas
     * @param toolChoice Tool choice configuration
     * @return Complete OpenAIRequest ready for API call
     */
    public OpenAIRequest buildRequest(
            String model,
            List<OpenAIMessage> messages,
            boolean stream,
            GenerateOptions options,
            GenerateOptions defaultOptions,
            List<ToolSchema> tools,
            ToolChoice toolChoice) {

        OpenAIRequest request =
                OpenAIRequest.builder().model(model).messages(messages).stream(stream).build();

        applyOptions(request, options, defaultOptions);
        applyTools(request, tools);

        if (toolChoice != null) {
            applyToolChoice(request, toolChoice);
        }

        return request;
    }
}
