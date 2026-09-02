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
package io.agentscope.extensions.model.openai.formatter;

import io.agentscope.core.formatter.AbstractBaseFormatter;
import io.agentscope.core.model.ChatResponse;
import io.agentscope.core.model.GenerateOptions;
import io.agentscope.core.model.ToolChoice;
import io.agentscope.core.model.ToolSchema;
import io.agentscope.extensions.model.openai.dto.OpenAIContentPart;
import io.agentscope.extensions.model.openai.dto.OpenAIMessage;
import io.agentscope.extensions.model.openai.dto.OpenAIRequest;
import io.agentscope.extensions.model.openai.dto.OpenAIResponse;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
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
    private static final Map<String, String> EXPLICIT_PROMPT_CACHE_BREAKPOINT =
            Map.of("mode", "explicit");

    /**
     * Sentinel for an explicit "no cache" intent. An empty map is serialized away (via
     * {@code @JsonInclude(NON_EMPTY)} on {@link OpenAIMessage#cacheControl}), so the upstream API
     * receives no {@code cache_control} field and therefore performs no caching.
     */
    private static final Map<String, String> NO_CACHE_CONTROL = Collections.emptyMap();

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
     * Apply legacy message-level cache control.
     *
     * <p>This method emits the provider-specific {@code cache_control} field used by older
     * OpenAI-compatible integrations. The official OpenAI request path does not call it: OpenAI
     * automatic caching is provider-managed, while explicit AgentScope markers are normalized to
     * {@code prompt_cache_breakpoint} by {@link #applyOpenAIPromptCache(OpenAIRequest)}.
     *
     * @param messages the list of formatted OpenAI messages
     * @deprecated use the model request path so the selected endpoint can apply its native cache
     *     protocol
     */
    @Deprecated(since = "2.0.3", forRemoval = false)
    public void applyCacheControl(List<OpenAIMessage> messages) {
        if (messages == null || messages.isEmpty()) {
            return;
        }
        for (OpenAIMessage msg : messages) {
            if ("system".equals(msg.getRole()) && shouldAutoCache(msg)) {
                msg.setCacheControl(EPHEMERAL_CACHE_CONTROL);
            }
        }
        OpenAIMessage lastMsg = messages.get(messages.size() - 1);
        if (shouldAutoCache(lastMsg)) {
            lastMsg.setCacheControl(EPHEMERAL_CACHE_CONTROL);
        }
    }

    /**
     * Apply DashScope-compatible content-block cache control.
     *
     * <p>Legacy message-level markers are migrated to the last content block. Automatic
     * breakpoints use the shared first-system and last-conversation strategy.
     *
     * @param messages formatted OpenAI-compatible messages
     * @param automatic whether to add automatic breakpoints
     */
    public void applyDashScopeCacheControl(List<OpenAIMessage> messages, boolean automatic) {
        if (messages == null || messages.isEmpty()) {
            return;
        }

        List<OpenAIMessage> selected =
                selectPromptCacheBreakpoints(
                        messages,
                        automatic,
                        OpenAIBaseFormatter::hasExplicitCacheControl,
                        message -> "system".equals(message.getRole()),
                        OpenAIBaseFormatter::isCacheable);
        for (OpenAIMessage message : selected) {
            applyDashScopeCacheControlToContentBlock(message);
        }
        for (OpenAIMessage message : messages) {
            message.setCacheControl(null);
        }

        int markerCount = countContentBlockCacheMarkers(messages);
        if (markerCount > MAX_PROMPT_CACHE_BREAKPOINTS) {
            throw new IllegalArgumentException(
                    "DashScope supports at most "
                            + MAX_PROMPT_CACHE_BREAKPOINTS
                            + " cache_control markers, but got "
                            + markerCount);
        }
    }

    /**
     * Normalize manually marked messages to the official OpenAI explicit prompt-cache protocol.
     *
     * <p>OpenAI enables automatic prompt caching by default, so this method never invents
     * breakpoints for {@link GenerateOptions#getCacheControl() cacheControl=true}. It only
     * migrates explicit AgentScope metadata (temporarily represented by legacy
     * {@code cache_control}) to a content-part {@code prompt_cache_breakpoint}, and enables
     * explicit mode at request level.
     *
     * @param request request to normalize
     */
    public void applyOpenAIPromptCache(OpenAIRequest request) {
        if (request == null || request.getMessages() == null) {
            return;
        }

        for (OpenAIMessage message : request.getMessages()) {
            boolean legacyExplicit = hasSerializableCacheControl(message.getCacheControl());
            List<OpenAIContentPart> parts = message.getContentAsList();
            if (parts != null) {
                for (OpenAIContentPart part : parts) {
                    if (hasSerializableCacheControl(part.getCacheControl())) {
                        legacyExplicit = true;
                    }
                    if (part.getCacheControl() != null) {
                        part.setCacheControl(null);
                    }
                }
            }

            if (legacyExplicit && !hasPromptCacheBreakpoint(message)) {
                parts = ensureContentArray(message);
                if (parts.isEmpty()) {
                    throw new IllegalArgumentException(
                            "Cannot apply prompt_cache_breakpoint to a message without cacheable"
                                    + " content");
                }
                parts.get(parts.size() - 1)
                        .setPromptCacheBreakpoint(EXPLICIT_PROMPT_CACHE_BREAKPOINT);
            }
            message.setCacheControl(null);
        }

        int markerCount = countPromptCacheBreakpoints(request.getMessages());
        if (markerCount > MAX_PROMPT_CACHE_BREAKPOINTS) {
            throw new IllegalArgumentException(
                    "OpenAI supports at most "
                            + MAX_PROMPT_CACHE_BREAKPOINTS
                            + " prompt_cache_breakpoint markers, but got "
                            + markerCount);
        }
        if (markerCount > 0) {
            Map<String, Object> promptCacheOptions = new java.util.LinkedHashMap<>();
            Object configured =
                    request.getExtraParams() != null
                            ? request.getExtraParams().get("prompt_cache_options")
                            : null;
            if (configured instanceof Map<?, ?> configuredMap) {
                configuredMap.forEach(
                        (key, value) -> {
                            if (key instanceof String stringKey) {
                                promptCacheOptions.put(stringKey, value);
                            }
                        });
            }
            promptCacheOptions.put("mode", "explicit");
            request.addExtraParam("prompt_cache_options", promptCacheOptions);
        }
    }

    /** Remove AgentScope's internal legacy markers before calling an unknown compatible API. */
    public void clearLegacyCacheControl(List<OpenAIMessage> messages) {
        if (messages == null) {
            return;
        }
        for (OpenAIMessage message : messages) {
            message.setCacheControl(null);
            List<OpenAIContentPart> parts = message.getContentAsList();
            if (parts != null) {
                parts.forEach(part -> part.setCacheControl(null));
            }
        }
    }

    static void applyDashScopeCacheControlToContentBlock(OpenAIMessage message) {
        if (isExplicitNoCache(message)) {
            message.setCacheControl(null);
            return;
        }
        if (hasContentBlockCacheControl(message)) {
            message.setCacheControl(null);
            return;
        }

        List<OpenAIContentPart> parts = ensureContentArray(message);
        if (parts.isEmpty()) {
            throw new IllegalArgumentException(
                    "Cannot apply cache_control to a message without cacheable content");
        }
        Map<String, String> cacheControl =
                message.getCacheControl() != null
                        ? message.getCacheControl()
                        : EPHEMERAL_CACHE_CONTROL;
        parts.get(parts.size() - 1).setCacheControl(cacheControl);
        message.setCacheControl(null);
    }

    @SuppressWarnings("unchecked")
    static List<OpenAIContentPart> ensureContentArray(OpenAIMessage message) {
        Object content = message.getContent();
        if (content instanceof List<?>) {
            return (List<OpenAIContentPart>) content;
        }
        if (content instanceof String text) {
            List<OpenAIContentPart> parts = new ArrayList<>(List.of(OpenAIContentPart.text(text)));
            message.setContent(parts);
            return parts;
        }
        return List.of();
    }

    private static boolean isCacheable(OpenAIMessage message) {
        if (isExplicitNoCache(message)) {
            return false;
        }
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

    private static boolean hasExplicitCacheControl(OpenAIMessage message) {
        return hasSerializableCacheControl(message.getCacheControl())
                || hasContentBlockCacheControl(message);
    }

    private static boolean isExplicitNoCache(OpenAIMessage message) {
        return message.getCacheControl() != null && message.getCacheControl().isEmpty();
    }

    private static boolean hasSerializableCacheControl(Map<String, String> cacheControl) {
        return cacheControl != null && !cacheControl.isEmpty();
    }

    private static boolean hasContentBlockCacheControl(OpenAIMessage message) {
        List<OpenAIContentPart> parts = message.getContentAsList();
        return parts != null
                && parts.stream()
                        .anyMatch(part -> hasSerializableCacheControl(part.getCacheControl()));
    }

    private static boolean hasPromptCacheBreakpoint(OpenAIMessage message) {
        List<OpenAIContentPart> parts = message.getContentAsList();
        return parts != null
                && parts.stream().anyMatch(part -> part.getPromptCacheBreakpoint() != null);
    }

    private static int countPromptCacheBreakpoints(List<OpenAIMessage> messages) {
        int count = 0;
        for (OpenAIMessage message : messages) {
            List<OpenAIContentPart> parts = message.getContentAsList();
            if (parts != null) {
                count +=
                        (int)
                                parts.stream()
                                        .filter(part -> part.getPromptCacheBreakpoint() != null)
                                        .count();
            }
        }
        return count;
    }

    private static int countContentBlockCacheMarkers(List<OpenAIMessage> messages) {
        int count = 0;
        for (OpenAIMessage message : messages) {
            List<OpenAIContentPart> parts = message.getContentAsList();
            if (parts != null) {
                count +=
                        (int)
                                parts.stream()
                                        .filter(
                                                part ->
                                                        hasSerializableCacheControl(
                                                                part.getCacheControl()))
                                        .count();
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

    /**
     * Get the "no cache" sentinel constant.
     *
     * @return unmodifiable empty map representing an explicit "no cache" intent
     */
    static Map<String, String> getNoCacheControl() {
        return NO_CACHE_CONTROL;
    }

    /**
     * Whether the automatic cache-control strategy should mark a message as ephemeral.
     *
     * <p>Returns {@code true} only when the message has no cache_control value at all. Any non-null
     * value — an explicit {@code {"type": "ephemeral"}}, a custom value, or the empty "no cache"
     * sentinel — is left unchanged.
     *
     * @param message the message to inspect
     * @return {@code true} if the message should be auto-cached, {@code false} otherwise
     */
    private static boolean shouldAutoCache(OpenAIMessage message) {
        return message.getCacheControl() == null;
    }
}
