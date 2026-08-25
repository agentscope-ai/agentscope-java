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
package io.agentscope.extensions.model.dashscope.formatter;

import io.agentscope.core.formatter.AbstractBaseFormatter;
import io.agentscope.core.message.Msg;
import io.agentscope.core.model.ChatResponse;
import io.agentscope.core.model.GenerateOptions;
import io.agentscope.core.model.ToolChoice;
import io.agentscope.core.model.ToolSchema;
import io.agentscope.extensions.model.dashscope.dto.DashScopeContentPart;
import io.agentscope.extensions.model.dashscope.dto.DashScopeInput;
import io.agentscope.extensions.model.dashscope.dto.DashScopeMessage;
import io.agentscope.extensions.model.dashscope.dto.DashScopeParameters;
import io.agentscope.extensions.model.dashscope.dto.DashScopeRequest;
import io.agentscope.extensions.model.dashscope.dto.DashScopeResponse;
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

    /** Apply automatic prompt cache control to DashScope messages. */
    public void applyCacheControl(List<DashScopeMessage> messages) {
        applyCacheControl(messages, true);
    }

    /**
     * Normalize explicit prompt cache markers and optionally apply the automatic strategy.
     *
     * <p>DashScope requires {@code cache_control} inside a content block. Legacy message-level
     * markers are migrated to the last content block and removed from the message before
     * serialization.
     *
     * @param messages formatted DashScope messages
     * @param automatic whether to add automatic breakpoints
     */
    public void applyCacheControl(List<DashScopeMessage> messages, boolean automatic) {
        if (messages == null || messages.isEmpty()) {
            return;
        }

        List<DashScopeMessage> selected =
                selectPromptCacheBreakpoints(
                        messages,
                        automatic,
                        DashScopeChatFormatter::hasExplicitCacheControl,
                        message -> "system".equals(message.getRole()),
                        DashScopeChatFormatter::isCacheable);

        for (DashScopeMessage message : selected) {
            applyCacheControlToContentBlock(message);
        }
        for (DashScopeMessage message : messages) {
            message.setCacheControl(null);
        }

        int markerCount = countCacheControlMarkers(messages);
        if (markerCount > MAX_PROMPT_CACHE_BREAKPOINTS) {
            throw new IllegalArgumentException(
                    "DashScope supports at most "
                            + MAX_PROMPT_CACHE_BREAKPOINTS
                            + " cache_control markers, but got "
                            + markerCount);
        }
    }

    static void applyCacheControlToContentBlock(DashScopeMessage message) {
        if (hasContentBlockCacheControl(message)) {
            message.setCacheControl(null);
            return;
        }

        List<DashScopeContentPart> parts = ensureContentArray(message);
        if (parts.isEmpty()) {
            throw new IllegalArgumentException(
                    "Cannot apply cache_control to a message without cacheable content");
        }

        DashScopeContentPart lastPart = parts.get(parts.size() - 1);
        if (lastPart.getType() == null && lastPart.getText() != null) {
            lastPart.setType("text");
        }
        Map<String, String> cacheControl =
                message.getCacheControl() != null
                        ? message.getCacheControl()
                        : EPHEMERAL_CACHE_CONTROL;
        lastPart.setCacheControl(cacheControl);
        message.setCacheControl(null);
    }

    @SuppressWarnings("unchecked")
    static List<DashScopeContentPart> ensureContentArray(DashScopeMessage message) {
        Object content = message.getContent();
        if (content instanceof List<?>) {
            return (List<DashScopeContentPart>) content;
        }
        if (content instanceof String text) {
            List<DashScopeContentPart> parts =
                    new ArrayList<>(
                            List.of(
                                    DashScopeContentPart.builder()
                                            .type("text")
                                            .text(text)
                                            .build()));
            message.setContent(parts);
            return parts;
        }
        return List.of();
    }

    static boolean isCacheable(DashScopeMessage message) {
        if (!("system".equals(message.getRole())
                || "user".equals(message.getRole())
                || "assistant".equals(message.getRole())
                || "tool".equals(message.getRole()))) {
            return false;
        }
        Object content = message.getContent();
        return (content instanceof String text && !text.isEmpty())
                || (content instanceof List<?> parts && !parts.isEmpty());
    }

    static boolean hasExplicitCacheControl(DashScopeMessage message) {
        return message.getCacheControl() != null || hasContentBlockCacheControl(message);
    }

    static boolean hasContentBlockCacheControl(DashScopeMessage message) {
        List<DashScopeContentPart> parts = message.getContentAsList();
        return parts != null && parts.stream().anyMatch(part -> part.getCacheControl() != null);
    }

    static int countCacheControlMarkers(List<DashScopeMessage> messages) {
        int count = 0;
        for (DashScopeMessage message : messages) {
            List<DashScopeContentPart> parts = message.getContentAsList();
            if (parts != null) {
                count +=
                        (int) parts.stream().filter(part -> part.getCacheControl() != null).count();
            }
        }
        return count;
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
