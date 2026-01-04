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
 *
 * <p>Subclasses must implement:
 * <ul>
 *   <li>{@link #doFormat(List)} - Convert messages to OpenAI format
 *   <li>{@link #applyOptions(OpenAIRequest, GenerateOptions, GenerateOptions)} - Apply generation options
 *   <li>{@link #applyTools(OpenAIRequest, List)} - Apply tool schemas
 *   <li>{@link #applyToolChoice(OpenAIRequest, ToolChoice)} - Apply tool choice configuration
 * </ul>
 */
public abstract class OpenAIBaseFormatter
        extends AbstractBaseFormatter<OpenAIMessage, OpenAIResponse, OpenAIRequest> {

    protected final OpenAIMessageConverter messageConverter;
    protected final OpenAIResponseParser responseParser;

    protected OpenAIBaseFormatter() {
        this.messageConverter =
                new OpenAIMessageConverter(
                        this::extractTextContent, this::convertToolResultToString);
        this.responseParser = new OpenAIResponseParser();
    }

    @Override
    public ChatResponse parseResponse(OpenAIResponse response, Instant startTime) {
        return responseParser.parseResponse(response, startTime);
    }

    /**
     * Apply generation options to the request.
     * Subclasses implement provider-specific option handling.
     *
     * @param request OpenAI request DTO
     * @param options Generation options to apply
     * @param defaultOptions Default options to use if options parameter is null
     */
    @Override
    public abstract void applyOptions(
            OpenAIRequest request, GenerateOptions options, GenerateOptions defaultOptions);

    /**
     * Apply tool schemas to the request.
     * Subclasses implement provider-specific tool handling.
     *
     * @param request OpenAI request DTO
     * @param tools List of tool schemas to apply (may be null or empty)
     */
    @Override
    public abstract void applyTools(OpenAIRequest request, List<ToolSchema> tools);

    /**
     * Apply tool choice configuration to the request.
     * Subclasses implement provider-specific tool choice handling.
     *
     * @param request OpenAI request DTO
     * @param toolChoice Tool choice configuration (null means auto)
     */
    @Override
    public abstract void applyToolChoice(OpenAIRequest request, ToolChoice toolChoice);

    /**
     * Apply tool schemas with provider context.
     * Default implementation delegates to {@link #applyTools(OpenAIRequest, List)}.
     *
     * @param request OpenAI request DTO
     * @param tools Tool schemas to apply
     * @param baseUrl API base URL (ignored by default)
     * @param modelName Model name (ignored by default)
     */
    @Override
    public void applyTools(
            OpenAIRequest request, List<ToolSchema> tools, String baseUrl, String modelName) {
        applyTools(request, tools);
    }

    /**
     * Apply tool choice with provider context.
     * Default implementation delegates to {@link #applyToolChoice(OpenAIRequest, ToolChoice)}.
     *
     * @param request OpenAI request DTO
     * @param toolChoice Tool choice configuration
     * @param baseUrl API base URL (ignored by default)
     * @param modelName Model name (ignored by default)
     */
    @Override
    public void applyToolChoice(
            OpenAIRequest request, ToolChoice toolChoice, String baseUrl, String modelName) {
        applyToolChoice(request, toolChoice);
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
