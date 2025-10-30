/*
 * Copyright 2024-2025 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * You may not use this file except in compliance with the License.
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
package io.agentscope.core.model;

import com.alibaba.dashscope.aigc.generation.Generation;
import com.alibaba.dashscope.aigc.generation.GenerationParam;
import com.alibaba.dashscope.aigc.generation.GenerationParam.GenerationParamBuilder;
import com.alibaba.dashscope.aigc.generation.GenerationResult;
import com.alibaba.dashscope.aigc.multimodalconversation.MultiModalConversation;
import com.alibaba.dashscope.aigc.multimodalconversation.MultiModalConversationParam;
import com.alibaba.dashscope.aigc.multimodalconversation.MultiModalConversationResult;
import com.alibaba.dashscope.common.Message;
import com.alibaba.dashscope.common.MultiModalMessage;
import com.alibaba.dashscope.common.ResultCallback;
import com.alibaba.dashscope.protocol.Protocol;
import com.alibaba.dashscope.tools.ToolCallBase;
import com.alibaba.dashscope.tools.ToolCallFunction;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.agentscope.core.formatter.AbstractDashScopeFormatter;
import io.agentscope.core.formatter.DashScopeChatFormatter;
import io.agentscope.core.formatter.Formatter;
import io.agentscope.core.message.ContentBlock;
import io.agentscope.core.message.Msg;
import io.agentscope.core.message.TextBlock;
import io.agentscope.core.message.ToolUseBlock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import reactor.core.publisher.Flux;

/**
 * DashScope Chat Model using dashscope-sdk-java Conversation API.
 *
 * <p><b>Architecture Design (Model-Based API Routing):</b>
 * This class unifies Generation and MultiModalConversation APIs into a single entry point.
 * It automatically routes to the appropriate API based on model name pattern matching (aligned with Python implementation):
 * <ul>
 *   <li>Models starting with "qvq" or containing "-vl": → MultiModalConversation API
 *   <li>All other models: → Generation API
 * </ul>
 *
 * <p><b>Alignment with Python Implementation:</b>
 * <ul>
 *   <li>Uses identical model name pattern matching: {@code modelName.startsWith("qvq") || modelName.contains("-vl")}
 *   <li>Ensures consistent API routing behavior across Java and Python implementations
 *   <li>Follows the same design principles for multimodal model detection
 * </ul>
 *
 * <p><b>Design Rationale - Why Message Conversion Happens in Model:</b>
 * Unlike Python where both APIs accept the same dict format, the DashScope Java SDK requires different
 * types ({@code List<Message>} vs {@code List<MultiModalMessage>}). Therefore, message conversion
 * for multimodal models happens in this class rather than in the Formatter layer. This is a necessary
 * adaptation to Java SDK constraints while maintaining logical alignment with Python's design.
 *
 * <p>Supports streaming and non-streaming modes, tool calls, thinking content, and usage parsing.
 */
public class DashScopeChatModel implements Model {

    private static final Logger log = LoggerFactory.getLogger(DashScopeChatModel.class);
    private static final ObjectMapper objectMapper = new ObjectMapper();

    private final String apiKey;
    private final String modelName;
    private final boolean stream;
    private final Boolean enableThinking; // nullable
    private final GenerateOptions defaultOptions;
    private final String protocol; // HTTP or WEBSOCKET
    private final String baseUrl; // Optional custom base URL
    private final Formatter<Message, GenerationResult, GenerationParam> formatter;

    /**
     * Check if model requires MultiModalConversation API based on model name.
     *
     * <p><b>Alignment with Python:</b> Uses the same model name pattern matching as Python implementation:
     * <ul>
     *   <li>Models starting with "qvq" (e.g., qvq-72b, qvq-7b) → MultiModalConversation API</li>
     *   <li>Models containing "-vl" (e.g., qwen-vl-plus, qwen-vl-max) → MultiModalConversation API</li>
     *   <li>All other models → Generation API</li>
     * </ul>
     *
     * <p>This approach ensures consistent behavior with Python implementation and allows
     * proper API routing based on model capabilities rather than message content.
     *
     * @return true if model requires MultiModalConversation API
     */
    private boolean requiresMultiModalConversationAPI() {
        if (modelName == null) {
            return false;
        }
        return modelName.startsWith("qvq") || modelName.contains("-vl");
    }

    public DashScopeChatModel(
            String apiKey,
            String modelName,
            boolean stream,
            Boolean enableThinking,
            GenerateOptions defaultOptions,
            String protocol,
            String baseUrl,
            Formatter<Message, GenerationResult, GenerationParam> formatter) {
        this.apiKey = apiKey;
        this.modelName = modelName;
        this.stream = enableThinking != null && enableThinking ? true : stream;
        this.enableThinking = enableThinking;
        this.defaultOptions =
                defaultOptions != null ? defaultOptions : GenerateOptions.builder().build();
        this.protocol = protocol != null ? protocol : Protocol.HTTP.getValue();
        this.baseUrl = baseUrl;
        this.formatter = formatter != null ? formatter : new DashScopeChatFormatter();
    }

    public static Builder builder() {
        return new Builder();
    }

    @Override
    public Flux<ChatResponse> stream(
            List<Msg> messages, List<ToolSchema> tools, GenerateOptions options) {
        // Route to appropriate API based on model name (alignment with Python implementation)
        // If model requires MultiModalConversation API (qvq* or *-vl*), use that API
        // Otherwise, use Generation API
        if (requiresMultiModalConversationAPI()) {
            log.debug(
                    "Routing to MultiModalConversation API: model={}, requires_multimodal_api=true",
                    modelName);
            return streamWithMultiModalAPI(messages, tools, options);
        } else {
            log.debug(
                    "Routing to Generation API: model={}, requires_multimodal_api=false",
                    modelName);
            return streamWithGenerationAPI(messages, tools, options);
        }
    }

    /**
     * Stream using MultiModalConversation API (for vision models like qwen-vl-max).
     *
     * <p>This method handles vision-capable models by using DashScope's MultiModalConversation
     * API, which supports both text and image content.
     *
     * @param messages The conversation messages
     * @param tools Tool schemas (currently not supported by MultiModalConversation API)
     * @param options Generation options
     * @return Flux of streaming chat responses
     */
    private Flux<ChatResponse> streamWithMultiModalAPI(
            List<Msg> messages, List<ToolSchema> tools, GenerateOptions options) {
        Instant start = Instant.now();
        MultiModalConversation conv = new MultiModalConversation();

        // Convert Msg to MultiModalMessage format using formatter
        // Cast is safe because we always use AbstractDashScopeFormatter for DashScope models
        // This supports both DashScopeChatFormatter and DashScopeMultiAgentFormatter
        AbstractDashScopeFormatter dashScopeFormatter = (AbstractDashScopeFormatter) formatter;
        List<MultiModalMessage> multiModalMessages = dashScopeFormatter.formatMultiModal(messages);

        MultiModalConversationParam param =
                MultiModalConversationParam.builder()
                        .apiKey(apiKey)
                        .model(modelName)
                        .messages(multiModalMessages)
                        .build();

        // Apply tools if provided (MultiModalConversation API supports tools)
        applyMultiModalTools(param, tools);

        if (stream) {
            // Streaming mode
            return Flux.create(
                    sink -> {
                        param.setIncrementalOutput(Boolean.TRUE);
                        applyMultiModalOptions(param, options);

                        ResultCallback<MultiModalConversationResult> cb =
                                new ResultCallback<>() {
                                    @Override
                                    public void onEvent(MultiModalConversationResult message) {
                                        try {
                                            ChatResponse chunk =
                                                    parseMultiModalResponse(message, start);
                                            if (chunk != null) sink.next(chunk);
                                        } catch (Exception ex) {
                                            log.warn(
                                                    "MultiModalConversation stream parse error: {}",
                                                    ex.getMessage(),
                                                    ex);
                                            sink.error(ex);
                                        }
                                    }

                                    @Override
                                    public void onError(Exception e) {
                                        log.error(
                                                "MultiModalConversation stream error: {}",
                                                e.getMessage(),
                                                e);
                                        sink.error(e);
                                    }

                                    @Override
                                    public void onComplete() {
                                        sink.complete();
                                    }
                                };

                        try {
                            log.debug(
                                    "MultiModalConversation streaming call: model={}, messages={}",
                                    modelName,
                                    messages != null ? messages.size() : 0);
                            conv.streamCall(param, cb);
                        } catch (Exception e) {
                            sink.error(e);
                        }
                    });
        } else {
            // Non-streaming mode
            return Flux.defer(
                    () -> {
                        try {
                            param.setIncrementalOutput(Boolean.FALSE);
                            applyMultiModalOptions(param, options);

                            log.debug(
                                    "MultiModalConversation synchronous call: model={},"
                                            + " messages={}",
                                    modelName,
                                    messages != null ? messages.size() : 0);
                            MultiModalConversationResult result = conv.call(param);
                            ChatResponse response = parseMultiModalResponse(result, start);
                            return Flux.just(response);
                        } catch (Exception e) {
                            log.error(
                                    "MultiModalConversation synchronous call error: {}",
                                    e.getMessage(),
                                    e);
                            return Flux.error(
                                    new RuntimeException(
                                            "MultiModalConversation API call failed: "
                                                    + e.getMessage(),
                                            e));
                        }
                    });
        }
    }

    /**
     * Stream using Generation API (for text-only models).
     */
    private Flux<ChatResponse> streamWithGenerationAPI(
            List<Msg> messages, List<ToolSchema> tools, GenerateOptions options) {
        Instant start = Instant.now();
        Generation generation =
                baseUrl != null && !baseUrl.isEmpty()
                        ? new Generation(protocol, baseUrl)
                        : new Generation(protocol);

        GenerationParamBuilder<?, ?> builder = GenerationParam.builder();
        builder.model(modelName);
        GenerationParam param = builder.build();
        param.setApiKey(apiKey);
        param.setResultFormat("message");

        // Use formatter to convert Msg to DashScope Message
        List<Message> dashscopeMessages = formatter.format(messages);
        param.setMessages(dashscopeMessages);

        if (stream) {
            // Streaming mode: use incremental output
            return Flux.create(
                    sink -> {
                        param.setIncrementalOutput(Boolean.TRUE);
                        applyModelSpecificOptions(param, options, true);
                        formatter.applyTools(param, tools);

                        ResultCallback<GenerationResult> cb =
                                new ResultCallback<>() {
                                    @Override
                                    public void onEvent(GenerationResult message) {
                                        try {
                                            ChatResponse chunk =
                                                    formatter.parseResponse(message, start);
                                            if (chunk != null) sink.next(chunk);
                                        } catch (Exception ex) {
                                            log.warn(
                                                    "DashScope stream parse error: {}",
                                                    ex.getMessage(),
                                                    ex);
                                            sink.error(ex);
                                        }
                                    }

                                    @Override
                                    public void onError(Exception e) {
                                        log.error("DashScope stream error: {}", e.getMessage(), e);
                                        sink.error(e);
                                    }

                                    @Override
                                    public void onComplete() {
                                        sink.complete();
                                    }
                                };

                        try {
                            log.debug(
                                    "DashScope streaming call: model={}, messages={}",
                                    modelName,
                                    messages != null ? messages.size() : 0);
                            generation.streamCall(param, cb);
                        } catch (Exception e) {
                            sink.error(e);
                        }
                    });
        } else {
            // Non-streaming mode: use synchronous call and return as single-item Flux
            return Flux.defer(
                    () -> {
                        try {
                            param.setIncrementalOutput(Boolean.FALSE);
                            applyModelSpecificOptions(param, options, false);
                            formatter.applyTools(param, tools);

                            log.debug(
                                    "DashScope synchronous call: model={}, messages={}",
                                    modelName,
                                    messages != null ? messages.size() : 0);
                            GenerationResult result = generation.call(param);
                            ChatResponse response = formatter.parseResponse(result, start);
                            return Flux.just(response);
                        } catch (Exception e) {
                            log.error("DashScope synchronous call error: {}", e.getMessage(), e);
                            return Flux.error(
                                    new RuntimeException(
                                            "DashScope API call failed: " + e.getMessage(), e));
                        }
                    });
        }
    }

    private void applyModelSpecificOptions(
            GenerationParam param, GenerateOptions options, boolean isStream) {
        // Apply generation options via formatter
        formatter.applyOptions(param, options, defaultOptions);

        // Validate thinking configuration
        GenerateOptions opt = options != null ? options : defaultOptions;
        if (opt.getThinkingBudget() != null && !Boolean.TRUE.equals(enableThinking)) {
            throw new IllegalStateException(
                    "thinkingBudget is set but enableThinking is not enabled. To use thinking mode"
                        + " with budget control, you must explicitly enable thinking by calling"
                        + " .enableThinking(true) on the model builder. Example:"
                        + " DashScopeChatModel.builder().enableThinking(true)"
                        + ".defaultOptions(GenerateOptions.builder().thinkingBudget(1000).build())");
        }

        // Model-specific settings for thinking mode
        if (Boolean.TRUE.equals(enableThinking)) {
            param.setEnableThinking(Boolean.TRUE);
            if (isStream) {
                param.setIncrementalOutput(Boolean.TRUE);
            }
        }
    }

    /**
     * Apply tools to MultiModalConversationParam.
     *
     * <p>MultiModalConversation API supports tool calling similar to Generation API.
     */
    private void applyMultiModalTools(MultiModalConversationParam param, List<ToolSchema> tools) {
        if (tools == null || tools.isEmpty()) {
            return;
        }

        // Use formatter to convert ToolSchema to DashScope format
        // This leverages existing logic in formatter for consistency
        com.alibaba.dashscope.aigc.generation.GenerationParam tempParam =
                com.alibaba.dashscope.aigc.generation.GenerationParam.builder()
                        .model(modelName)
                        .build();
        formatter.applyTools(tempParam, tools);

        // Transfer tools from temporary param to MultiModalConversationParam
        param.setTools(tempParam.getTools());

        log.debug("Applied {} tools to MultiModalConversation API", tools.size());
    }

    /**
     * Apply options to MultiModalConversationParam.
     */
    private void applyMultiModalOptions(
            MultiModalConversationParam param, GenerateOptions options) {
        GenerateOptions opt = options != null ? options : defaultOptions;
        if (opt.getTemperature() != null) {
            param.setTemperature(opt.getTemperature().floatValue());
        }
        if (opt.getTopP() != null) {
            param.setTopP(opt.getTopP());
        }
        if (opt.getMaxTokens() != null) {
            param.setMaxTokens(opt.getMaxTokens());
        }
        // Note: MultiModalConversation API may not support all options like thinking
    }

    /**
     * Parse MultiModalConversationResult to ChatResponse.
     * Extracts text content and tool calls from the multimodal response.
     */
    private ChatResponse parseMultiModalResponse(
            MultiModalConversationResult result, Instant startTime) {
        try {
            List<ContentBlock> blocks = new ArrayList<>();

            if (result.getOutput() != null
                    && result.getOutput().getChoices() != null
                    && !result.getOutput().getChoices().isEmpty()) {

                var message = result.getOutput().getChoices().get(0).getMessage();
                if (message != null) {
                    // Extract text content
                    if (message.getContent() != null) {
                        // MultiModalConversation returns content as List<Map<String, Object>>
                        for (var contentItem : message.getContent()) {
                            Object textObj = contentItem.get("text");
                            if (textObj != null) {
                                blocks.add(TextBlock.builder().text(textObj.toString()).build());
                            }
                        }
                    }

                    // Extract tool calls (similar to Generation API)
                    addToolCallsFromMultiModalMessage(message, blocks);
                }
            }

            // Parse usage if available
            ChatUsage usage = null;
            if (result.getUsage() != null) {
                var u = result.getUsage();
                usage =
                        ChatUsage.builder()
                                .inputTokens(
                                        u.getInputTokens() != null
                                                ? u.getInputTokens().intValue()
                                                : 0)
                                .outputTokens(
                                        u.getOutputTokens() != null
                                                ? u.getOutputTokens().intValue()
                                                : 0)
                                .time(
                                        java.time.Duration.between(startTime, Instant.now())
                                                        .toMillis()
                                                / 1000.0)
                                .build();
            }

            return ChatResponse.builder()
                    .id(result.getRequestId())
                    .content(blocks)
                    .usage(usage)
                    .build();
        } catch (Exception e) {
            log.error("Failed to parse MultiModalConversation result: {}", e.getMessage(), e);
            throw new RuntimeException(
                    "Failed to parse MultiModalConversation result: " + e.getMessage(), e);
        }
    }

    /**
     * Extract tool calls from MultiModalMessage and add to content blocks.
     *
     * <p>This method handles tool calls from vision models, similar to how
     * {@link DashScopeChatFormatter#addToolCallsFromSdkMessage} handles them for text models.
     */
    private void addToolCallsFromMultiModalMessage(
            MultiModalMessage message, List<ContentBlock> blocks) {
        List<ToolCallBase> tcs = message.getToolCalls();
        if (tcs == null || tcs.isEmpty()) return;

        int idx = 0;
        for (ToolCallBase base : tcs) {
            String id = base.getId();
            if (base instanceof ToolCallFunction fcall) {
                ToolCallFunction.CallFunction cf = fcall.getFunction();
                if (cf == null) continue;

                String name = cf.getName();
                String argsJson = cf.getArguments();
                Map<String, Object> argsMap = new HashMap<>();
                String rawContent = null;

                if (argsJson != null && !argsJson.isEmpty()) {
                    rawContent = argsJson;
                    try {
                        @SuppressWarnings("unchecked")
                        Map<String, Object> parsed = objectMapper.readValue(argsJson, Map.class);
                        if (parsed != null) argsMap.putAll(parsed);
                    } catch (Exception ignored) {
                        // Keep raw content for streaming tool calls
                    }
                }

                if (name != null) {
                    // Complete tool call
                    String callId =
                            id != null
                                    ? id
                                    : ("tool_call_" + System.currentTimeMillis() + "_" + idx);
                    blocks.add(
                            ToolUseBlock.builder()
                                    .id(callId)
                                    .name(name)
                                    .input(argsMap)
                                    .content(rawContent)
                                    .build());
                } else if (rawContent != null && !rawContent.isEmpty()) {
                    // Streaming fragment (for accumulation)
                    String callId =
                            id != null
                                    ? id
                                    : ("fragment_" + System.currentTimeMillis() + "_" + idx);
                    blocks.add(
                            ToolUseBlock.builder()
                                    .id(callId)
                                    .name("__fragment__")
                                    .input(new HashMap<>())
                                    .content(rawContent)
                                    .build());
                }
            }
            idx++;
        }
    }

    @Override
    public String getModelName() {
        return modelName;
    }

    public static class Builder {
        private String apiKey;
        private String modelName;
        private boolean stream = true;
        private Boolean enableThinking;
        private GenerateOptions defaultOptions = GenerateOptions.builder().build();
        private String protocol = Protocol.HTTP.getValue();
        private String baseUrl;
        private Formatter<Message, GenerationResult, GenerationParam> formatter;

        public Builder apiKey(String apiKey) {
            this.apiKey = apiKey;
            return this;
        }

        public Builder modelName(String modelName) {
            this.modelName = modelName;
            return this;
        }

        public Builder stream(boolean stream) {
            this.stream = stream;
            return this;
        }

        public Builder enableThinking(Boolean enableThinking) {
            this.enableThinking = enableThinking;
            return this;
        }

        public Builder defaultOptions(GenerateOptions options) {
            this.defaultOptions = options;
            return this;
        }

        public Builder protocol(String protocol) {
            this.protocol = protocol;
            return this;
        }

        public Builder baseUrl(String baseUrl) {
            this.baseUrl = baseUrl;
            return this;
        }

        public Builder formatter(Formatter<Message, GenerationResult, GenerationParam> formatter) {
            this.formatter = formatter;
            return this;
        }

        public DashScopeChatModel build() {
            return new DashScopeChatModel(
                    apiKey,
                    modelName,
                    stream,
                    enableThinking,
                    defaultOptions,
                    protocol,
                    baseUrl,
                    formatter);
        }
    }
}
