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
import io.agentscope.core.formatter.openai.dto.OpenAIContentPart;
import io.agentscope.core.formatter.openai.dto.OpenAIMessage;
import io.agentscope.core.formatter.openai.dto.OpenAIRequest;
import io.agentscope.core.formatter.openai.dto.OpenAIResponse;
import io.agentscope.core.model.ChatResponse;
import io.agentscope.core.model.GenerateOptions;
import io.agentscope.core.model.ToolChoice;
import io.agentscope.core.model.ToolSchema;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

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

    private static final Map<String, String> EPHEMERAL_CACHE_CONTROL = Map.of("type", "ephemeral");

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

    /**
     * Apply cache control to OpenAI messages at the content block level.
     *
     * <p>Per the DashScope API specification (which also applies to the OpenAI-compatible protocol),
     * {@code cache_control} must be placed inside content blocks (within the {@code content} array),
     * not at the message level. This method converts string content to array format when needed and
     * sets {@code cache_control} on the last content block of each target message.
     *
     * <p>Target messages: all system messages and the last message in the list. Messages whose last
     * content block already has {@code cache_control} set will not be overwritten.
     *
     * @param messages the list of formatted OpenAI messages
     */
    public void applyCacheControl(List<OpenAIMessage> messages) {
        if (messages == null || messages.isEmpty()) {
            return;
        }
        for (OpenAIMessage msg : messages) {
            if ("system".equals(msg.getRole())) {
                applyCacheControlToContentBlock(msg);
            }
        }
        OpenAIMessage lastMsg = messages.get(messages.size() - 1);
        applyCacheControlToContentBlock(lastMsg);
    }

    /**
     * Apply ephemeral cache_control to the last content block of the given message.
     * If content is a plain string, it is first converted to array format.
     * Skips if the last content block already has cache_control set.
     */
    static void applyCacheControlToContentBlock(OpenAIMessage msg) {
        List<OpenAIContentPart> parts = ensureContentArray(msg);
        if (parts.isEmpty()) {
            return;
        }
        OpenAIContentPart lastPart = parts.get(parts.size() - 1);
        if (lastPart.getCacheControl() == null) {
            lastPart.setCacheControl(EPHEMERAL_CACHE_CONTROL);
        }
    }

    /**
     * Ensure the message content is in array format ({@code List<OpenAIContentPart>}).
     * If content is a plain string, converts it to {@code [{"type":"text","text":"..."}]}.
     *
     * @return the content part list (never null, may be empty)
     */
    @SuppressWarnings("unchecked")
    static List<OpenAIContentPart> ensureContentArray(OpenAIMessage msg) {
        Object content = msg.getContent();
        if (content instanceof List) {
            return (List<OpenAIContentPart>) content;
        }
        List<OpenAIContentPart> parts = new ArrayList<>();
        if (content instanceof String text) {
            parts.add(OpenAIContentPart.text(text));
        }
        msg.setContent(parts);
        return parts;
    }

    /**
     * Get the ephemeral cache control constant.
     *
     * @return unmodifiable map representing ephemeral cache control
     */
    static Map<String, String> getEphemeralCacheControl() {
        return EPHEMERAL_CACHE_CONTROL;
    }
}
