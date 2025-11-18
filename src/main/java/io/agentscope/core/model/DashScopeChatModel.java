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
import io.agentscope.core.Version;
import io.agentscope.core.formatter.Formatter;
import io.agentscope.core.formatter.dashscope.DashScopeChatFormatter;
import io.agentscope.core.formatter.dashscope.DashScopeMultiAgentFormatter;
import io.agentscope.core.formatter.dashscope.DashScopeToolsHelper;
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
import reactor.core.scheduler.Schedulers;

/**
 * DashScope Chat Model supporting both text and vision models.
 *
 * <p>This implementation provides unified access to DashScope's Generation and MultiModalConversation
 * APIs through automatic model-based routing:
 * <ul>
 *   <li>Vision models (names starting with "qvq" or containing "-vl") use MultiModalConversation API
 *   <li>Text models use Generation API
 * </ul>
 *
 * <p>Supports streaming, tool calling, thinking mode, and automatic message format conversion.
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
     * <p>Model names starting with "qvq" or containing "-vl" use the MultiModalConversation API:
     * <ul>
     *   <li>Models starting with "qvq" (e.g., qvq-72b, qvq-7b) → MultiModalConversation API</li>
     *   <li>Models containing "-vl" (e.g., qwen-vl-plus, qwen-vl-max) → MultiModalConversation API</li>
     *   <li>All other models → Generation API</li>
     * </ul>
     *
     * <p>This approach ensures proper API routing based on model capabilities rather than message content.
     *
     * @return true if model requires MultiModalConversation API
     */
    private boolean requiresMultiModalConversationAPI() {
        if (modelName == null) {
            return false;
        }
        return modelName.startsWith("qvq") || modelName.contains("-vl");
    }

    /**
     * Creates a new DashScope chat model instance.
     *
     * <p>This constructor creates a unified model that automatically routes requests to either
     * the Generation API or MultiModalConversation API based on the model name pattern.
     *
     * @param apiKey the API key for DashScope authentication
     * @param modelName the model name (e.g., "qwen-max", "qwen-vl-plus")
     * @param stream whether streaming should be enabled (ignored if enableThinking is true)
     * @param enableThinking whether thinking mode should be enabled (null for disabled)
     * @param defaultOptions default generation options (null for defaults)
     * @param protocol the protocol to use ("HTTP" or "WEBSOCKET", null for HTTP)
     * @param baseUrl custom base URL for DashScope API (null for default)
     * @param formatter the message formatter to use (null for default DashScope formatter)
     */
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

    /**
     * Creates a new builder for DashScopeChatModel.
     *
     * @return a new Builder instance
     */
    public static Builder builder() {
        return new Builder();
    }

    /**
     * Stream chat completion responses from DashScope's API.
     *
     * <p>This method automatically routes to the appropriate API based on the model name:
     * <ul>
     *   <li>Vision models (qvq* or *-vl*) → MultiModalConversation API</li>
     *   <li>Text models → Generation API</li>
     * </ul>
     *
     * <p>Supports timeout and retry configuration through GenerateOptions:
     * <ul>
     *   <li>Request timeout: Cancels the request if it exceeds the specified duration</li>
     *   <li>Retry config: Automatically retries failed requests with exponential backoff</li>
     * </ul>
     *
     * @param messages AgentScope messages to send to the model
     * @param tools Optional list of tool schemas (supported for both APIs)
     * @param options Optional generation options (null to use defaults)
     * @return Flux stream of chat responses
     */
    @Override
    public Flux<ChatResponse> stream(
            List<Msg> messages, List<ToolSchema> tools, GenerateOptions options) {
        // Route to appropriate API based on model name pattern matching
        Flux<ChatResponse> responseFlux;
        if (requiresMultiModalConversationAPI()) {
            log.debug(
                    "Routing to MultiModalConversation API: model={}, requires_multimodal_api=true",
                    modelName);
            responseFlux = streamWithMultiModalAPI(messages, tools, options);
        } else {
            log.debug(
                    "Routing to Generation API: model={}, requires_multimodal_api=false",
                    modelName);
            responseFlux = streamWithGenerationAPI(messages, tools, options);
        }

        // Apply timeout and retry if configured
        return ModelUtils.applyTimeoutAndRetry(
                responseFlux, options, defaultOptions, modelName, "dashscope", log);
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
        // Both DashScopeChatFormatter and DashScopeMultiAgentFormatter support formatMultiModal()
        List<MultiModalMessage> multiModalMessages;
        if (formatter instanceof DashScopeChatFormatter chatFormatter) {
            multiModalMessages = chatFormatter.formatMultiModal(messages);
        } else if (formatter instanceof DashScopeMultiAgentFormatter multiAgentFormatter) {
            multiModalMessages = multiAgentFormatter.formatMultiModal(messages);
        } else {
            throw new IllegalStateException(
                    "DashScope vision models require DashScopeChatFormatter or"
                            + " DashScopeMultiAgentFormatter, but got: "
                            + formatter.getClass().getName());
        }

        MultiModalConversationParam param =
                MultiModalConversationParam.builder()
                        .apiKey(apiKey)
                        .model(modelName)
                        .messages(multiModalMessages)
                        .header("user-agent", Version.getUserAgent())
                        .build();

        // Apply tools if provided (MultiModalConversation API supports tools)
        applyMultiModalTools(param, tools);

        if (stream) {
            // Streaming mode
            return Flux.<ChatResponse>create(
                            sink -> {
                                param.setIncrementalOutput(Boolean.TRUE);
                                applyMultiModalOptions(param, options);

                                ResultCallback<MultiModalConversationResult> cb =
                                        new ResultCallback<>() {
                                            @Override
                                            public void onEvent(
                                                    MultiModalConversationResult message) {
                                                try {
                                                    ChatResponse chunk =
                                                            parseMultiModalResponse(message, start);
                                                    if (chunk != null) sink.next(chunk);
                                                } catch (Exception ex) {
                                                    log.warn(
                                                            "MultiModalConversation stream parse"
                                                                    + " error: {}",
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
                                            "MultiModalConversation streaming call: model={},"
                                                    + " messages={}",
                                            modelName,
                                            messages != null ? messages.size() : 0);
                                    conv.streamCall(param, cb);
                                } catch (Exception e) {
                                    sink.error(e);
                                }
                            })
                    .publishOn(Schedulers.boundedElastic());
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
        // Set unified AgentScope User-Agent (overrides DashScope SDK default)
        builder.header("user-agent", Version.getUserAgent());
        GenerationParam param = builder.build();
        param.setApiKey(apiKey);
        param.setResultFormat("message");

        // Use formatter to convert Msg to DashScope Message
        List<Message> dashscopeMessages = formatter.format(messages);
        param.setMessages(dashscopeMessages);

        if (stream) {
            // Streaming mode: use incremental output
            return Flux.<ChatResponse>create(
                            sink -> {
                                param.setIncrementalOutput(Boolean.TRUE);
                                applyModelSpecificOptions(param, options, true);
                                formatter.applyTools(param, tools);
                                applyToolChoiceIfAvailable(param, options);

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
                                                log.error(
                                                        "DashScope stream error: {}",
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
                                            "DashScope streaming call: model={}, messages={}",
                                            modelName,
                                            messages != null ? messages.size() : 0);
                                    generation.streamCall(param, cb);
                                } catch (Exception e) {
                                    sink.error(e);
                                }
                            })
                    .publishOn(Schedulers.boundedElastic());
        } else {
            // Non-streaming mode: use synchronous call and return as single-item Flux
            return Flux.defer(
                    () -> {
                        try {
                            param.setIncrementalOutput(Boolean.FALSE);
                            applyModelSpecificOptions(param, options, false);
                            formatter.applyTools(param, tools);
                            applyToolChoiceIfAvailable(param, options);

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
     * Apply tool choice configuration if available in options.
     *
     * @param param DashScope generation parameters
     * @param options Generation options containing tool choice
     */
    private void applyToolChoiceIfAvailable(GenerationParam param, GenerateOptions options) {
        GenerateOptions opt = options != null ? options : defaultOptions;
        if (opt.getToolChoice() != null) {
            formatter.applyToolChoice(param, opt.getToolChoice());
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
        GenerationParam tempParam = GenerationParam.builder().model(modelName).build();
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

        // Apply tool choice configuration
        if (opt.getToolChoice() != null) {
            // Create helper to apply toolChoice to MultiModalConversation API
            DashScopeToolsHelper toolsHelper = new DashScopeToolsHelper();
            toolsHelper.applyToolChoice(param, opt.getToolChoice());
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

    /**
     * Gets the model name for logging and identification.
     *
     * @return the model name
     */
    @Override
    public String getModelName() {
        return modelName;
    }

    public static class Builder {
        private String apiKey;
        private String modelName;
        private boolean stream = true;
        private Boolean enableThinking;
        private GenerateOptions defaultOptions = null;
        private String protocol = Protocol.HTTP.getValue();
        private String baseUrl;
        private Formatter<Message, GenerationResult, GenerationParam> formatter;

        /**
         * Sets the API key for DashScope authentication.
         *
         * @param apiKey the API key
         * @return this builder instance
         */
        public Builder apiKey(String apiKey) {
            this.apiKey = apiKey;
            return this;
        }

        /**
         * Sets the model name to use.
         *
         * <p>The model name determines which API is used:
         * <ul>
         *   <li>Vision models (qvq* or *-vl*) → MultiModalConversation API</li>
         *   <li>Text models → Generation API</li>
         * </ul>
         *
         * @param modelName the model name (e.g., "qwen-max", "qwen-vl-plus")
         * @return this builder instance
         */
        public Builder modelName(String modelName) {
            this.modelName = modelName;
            return this;
        }

        /**
         * Sets whether streaming should be enabled.
         *
         * <p>This setting is ignored if enableThinking is set to true, as thinking mode
         * automatically enables streaming.
         *
         * @param stream true to enable streaming, false for non-streaming
         * @return this builder instance
         */
        public Builder stream(boolean stream) {
            this.stream = stream;
            return this;
        }

        /**
         * Sets whether thinking mode should be enabled.
         *
         * <p>When enabled, this automatically enables streaming and may override the stream setting.
         * Thinking mode allows the model to show its reasoning process.
         *
         * @param enableThinking true to enable thinking mode, false to disable, null for default (disabled)
         * @return this builder instance
         */
        public Builder enableThinking(Boolean enableThinking) {
            this.enableThinking = enableThinking;
            return this;
        }

        /**
         * Sets the default generation options.
         *
         * @param options the default options to use (null for defaults)
         * @return this builder instance
         */
        public Builder defaultOptions(GenerateOptions options) {
            this.defaultOptions = options;
            return this;
        }

        /**
         * Sets the protocol to use for API communication.
         *
         * @param protocol the protocol ("HTTP" or "WEBSOCKET", null for HTTP)
         * @return this builder instance
         */
        public Builder protocol(String protocol) {
            this.protocol = protocol;
            return this;
        }

        /**
         * Sets a custom base URL for DashScope API.
         *
         * @param baseUrl the base URL (null for default)
         * @return this builder instance
         */
        public Builder baseUrl(String baseUrl) {
            this.baseUrl = baseUrl;
            return this;
        }

        /**
         * Sets the message formatter to use.
         *
         * @param formatter the formatter (null for default DashScope formatter)
         * @return this builder instance
         */
        public Builder formatter(Formatter<Message, GenerationResult, GenerationParam> formatter) {
            this.formatter = formatter;
            return this;
        }

        /**
         * Builds the DashScopeChatModel instance.
         *
         * <p>This method ensures that the defaultOptions always has proper executionConfig
         * applied: - If no defaultOptions are provided, uses MODEL_DEFAULTS for
         * executionConfig - If defaultOptions are provided but executionConfig is null, merges
         * user-provided options with MODEL_DEFAULTS
         *
         * <p>Uses ModelUtils.ensureDefaultExecutionConfig() to apply defaults consistently across
         * all model implementations.
         *
         * @return configured DashScopeChatModel instance
         */
        public DashScopeChatModel build() {
            GenerateOptions effectiveOptions =
                    ModelUtils.ensureDefaultExecutionConfig(defaultOptions);

            return new DashScopeChatModel(
                    apiKey,
                    modelName,
                    stream,
                    enableThinking,
                    effectiveOptions,
                    protocol,
                    baseUrl,
                    formatter);
        }
    }
}
