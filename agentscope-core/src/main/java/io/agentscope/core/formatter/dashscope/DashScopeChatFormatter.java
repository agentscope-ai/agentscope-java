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
package io.agentscope.core.formatter.dashscope;

import io.agentscope.core.formatter.AbstractBaseFormatter;
import io.agentscope.core.formatter.dashscope.dto.DashScopeContentPart;
import io.agentscope.core.formatter.dashscope.dto.DashScopeInput;
import io.agentscope.core.formatter.dashscope.dto.DashScopeMessage;
import io.agentscope.core.formatter.dashscope.dto.DashScopeParameters;
import io.agentscope.core.formatter.dashscope.dto.DashScopeRequest;
import io.agentscope.core.formatter.dashscope.dto.DashScopeResponse;
import io.agentscope.core.message.Msg;
import io.agentscope.core.model.ChatResponse;
import io.agentscope.core.model.GenerateOptions;
import io.agentscope.core.model.ToolChoice;
import io.agentscope.core.model.ToolSchema;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * Formatter for DashScope Conversation/Generation APIs.
 * Converts between AgentScope Msg objects and DashScope DTO types.
 *
 * <p>This formatter handles both text and multimodal messages, supporting the DashScope
 * Generation API and MultiModalConversation API.
 */
public class DashScopeChatFormatter
        extends AbstractBaseFormatter<DashScopeMessage, DashScopeResponse, DashScopeRequest> {

    private static final Map<String, String> EPHEMERAL_CACHE_CONTROL = Map.of("type", "ephemeral");

    private final DashScopeMessageConverter messageConverter;
    private final DashScopeResponseParser responseParser;
    private final DashScopeToolsHelper toolsHelper;

    public DashScopeChatFormatter() {
        this.messageConverter = new DashScopeMessageConverter(this::convertToolResultToString);
        this.responseParser = new DashScopeResponseParser();
        this.toolsHelper = new DashScopeToolsHelper();
    }

    @Override
    protected List<DashScopeMessage> doFormat(List<Msg> msgs) {
        List<DashScopeMessage> result = new ArrayList<>();
        for (Msg msg : msgs) {
            boolean hasMedia = hasMediaContent(msg);
            DashScopeMessage dsMsg = messageConverter.convertToMessage(msg, hasMedia);
            if (dsMsg != null) {
                result.add(dsMsg);
            }
        }
        return result;
    }

    @Override
    public ChatResponse parseResponse(DashScopeResponse result, Instant startTime) {
        return responseParser.parseResponse(result, startTime);
    }

    @Override
    public void applyOptions(
            DashScopeRequest request, GenerateOptions options, GenerateOptions defaultOptions) {
        DashScopeParameters params = request.getParameters();
        if (params == null) {
            params = DashScopeParameters.builder().build();
            request.setParameters(params);
        }
        toolsHelper.applyOptions(params, options, defaultOptions);
    }

    @Override
    public void applyTools(DashScopeRequest request, List<ToolSchema> tools) {
        DashScopeParameters params = request.getParameters();
        if (params == null) {
            params = DashScopeParameters.builder().build();
            request.setParameters(params);
        }
        params.setTools(toolsHelper.convertTools(tools));
    }

    /**
     * Apply tool choice configuration to DashScopeRequest.
     *
     * @param request DashScope request
     * @param toolChoice Tool choice configuration
     */
    @Override
    public void applyToolChoice(DashScopeRequest request, ToolChoice toolChoice) {
        DashScopeParameters params = request.getParameters();
        if (params == null) {
            params = DashScopeParameters.builder().build();
            request.setParameters(params);
        }
        toolsHelper.applyToolChoice(params, toolChoice);
    }

    /**
     * Format AgentScope Msg objects to DashScope MultiModal message format.
     * This method is used for vision models that require the MultiModalConversation API.
     *
     * @param messages The AgentScope messages to convert
     * @return List of DashScopeMessage objects with multimodal content
     */
    public List<DashScopeMessage> formatMultiModal(List<Msg> messages) {
        return messages.stream()
                .map(msg -> messageConverter.convertToMessage(msg, true))
                .collect(Collectors.toList());
    }

    /**
     * Build a complete DashScopeRequest for the API call.
     *
     * @param model Model name
     * @param messages Formatted DashScope messages
     * @param stream Whether to enable streaming
     * @return Complete DashScopeRequest ready for API call
     */
    public DashScopeRequest buildRequest(
            String model, List<DashScopeMessage> messages, boolean stream) {
        DashScopeParameters params =
                DashScopeParameters.builder().incrementalOutput(stream).build();

        return DashScopeRequest.builder()
                .model(model)
                .input(DashScopeInput.builder().messages(messages).build())
                .parameters(params)
                .build();
    }

    /**
     * Build a complete DashScopeRequest with full configuration.
     *
     * @param model Model name
     * @param messages Formatted DashScope messages
     * @param stream Whether to enable streaming
     * @param options Generation options
     * @param defaultOptions Default generation options
     * @param tools Tool schemas
     * @param toolChoice Tool choice configuration
     * @return Complete DashScopeRequest ready for API call
     */
    public DashScopeRequest buildRequest(
            String model,
            List<DashScopeMessage> messages,
            boolean stream,
            GenerateOptions options,
            GenerateOptions defaultOptions,
            List<ToolSchema> tools,
            ToolChoice toolChoice) {

        DashScopeRequest request = buildRequest(model, messages, stream);

        applyOptions(request, options, defaultOptions);
        applyTools(request, tools);
        applyToolChoice(request, toolChoice);

        return request;
    }

    /**
     * Apply cache control to DashScope messages at the content block level.
     *
     * <p>Per the DashScope API specification, {@code cache_control} must be placed inside content
     * blocks (within the {@code content} array), not at the message level. This method converts
     * string content to array format when needed and sets {@code cache_control} on the last content
     * block of each target message.
     *
     * <p>Target messages: all system messages and the last message in the list. Messages whose last
     * content block already has {@code cache_control} set will not be overwritten.
     *
     * @param messages the list of formatted DashScope messages
     */
    public void applyCacheControl(List<DashScopeMessage> messages) {
        if (messages == null || messages.isEmpty()) {
            return;
        }
        for (DashScopeMessage msg : messages) {
            if ("system".equals(msg.getRole())) {
                applyCacheControlToContentBlock(msg);
            }
        }
        DashScopeMessage lastMsg = messages.get(messages.size() - 1);
        applyCacheControlToContentBlock(lastMsg);
    }

    /**
     * Apply ephemeral cache_control to the last content block of the given message.
     * If content is a plain string, it is first converted to array format.
     * Skips if the last content block already has cache_control set.
     */
    static void applyCacheControlToContentBlock(DashScopeMessage msg) {
        List<DashScopeContentPart> parts = ensureContentArray(msg);
        if (parts.isEmpty()) {
            return;
        }
        DashScopeContentPart lastPart = parts.get(parts.size() - 1);
        if (lastPart.getCacheControl() == null) {
            lastPart.setCacheControl(EPHEMERAL_CACHE_CONTROL);
        }
    }

    /**
     * Ensure the message content is in array format ({@code List<DashScopeContentPart>}).
     * If content is a plain string, converts it to {@code [{"type":"text","text":"..."}]}.
     *
     * @return the content part list (never null, may be empty)
     */
    @SuppressWarnings("unchecked")
    static List<DashScopeContentPart> ensureContentArray(DashScopeMessage msg) {
        Object content = msg.getContent();
        if (content instanceof List) {
            return (List<DashScopeContentPart>) content;
        }
        List<DashScopeContentPart> parts = new ArrayList<>();
        if (content instanceof String text) {
            parts.add(DashScopeContentPart.text(text));
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
