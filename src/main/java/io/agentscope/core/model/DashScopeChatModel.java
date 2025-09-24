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
import com.alibaba.dashscope.aigc.generation.GenerationOutput;
import com.alibaba.dashscope.aigc.generation.GenerationParam;
import com.alibaba.dashscope.aigc.generation.GenerationParam.GenerationParamBuilder;
import com.alibaba.dashscope.aigc.generation.GenerationResult;
import com.alibaba.dashscope.aigc.generation.GenerationUsage;
import com.alibaba.dashscope.common.Message;
import com.alibaba.dashscope.common.ResultCallback;
import com.alibaba.dashscope.protocol.Protocol;
import com.alibaba.dashscope.tools.FunctionDefinition;
import com.alibaba.dashscope.tools.ToolBase;
import com.alibaba.dashscope.tools.ToolCallBase;
import com.alibaba.dashscope.tools.ToolCallFunction;
import com.alibaba.dashscope.tools.ToolFunction;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.google.gson.Gson;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import io.agentscope.core.message.ContentBlock;
import io.agentscope.core.message.TextBlock;
import io.agentscope.core.message.ThinkingBlock;
import io.agentscope.core.message.ToolUseBlock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import reactor.core.publisher.Flux;

/**
 * DashScope Chat Model using dashscope-sdk-java Conversation API.
 *
 * Mirroring Python DashScopeChatModel: supports streaming and non-streaming,
 * tool calls, thinking content, and usage parsing.
 */
public class DashScopeChatModel implements Model {

    private static final Logger log = LoggerFactory.getLogger(DashScopeChatModel.class);

    private final String apiKey;
    private final String modelName;
    private final boolean stream;
    private final Boolean enableThinking; // nullable
    private final GenerateOptions defaultOptions;
    private final String protocol; // HTTP or WEBSOCKET
    private final String baseUrl; // Optional custom base URL
    private final ObjectMapper objectMapper = new ObjectMapper();

    public DashScopeChatModel(
            String apiKey,
            String modelName,
            boolean stream,
            Boolean enableThinking,
            GenerateOptions defaultOptions,
            String protocol,
            String baseUrl) {
        this.apiKey = apiKey;
        this.modelName = modelName;
        this.stream = enableThinking != null && enableThinking ? true : stream;
        this.enableThinking = enableThinking;
        this.defaultOptions = defaultOptions != null ? defaultOptions : new GenerateOptions();
        this.protocol = protocol != null ? protocol : Protocol.HTTP.getValue();
        this.baseUrl = baseUrl;
    }

    public static Builder builder() {
        return new Builder();
    }

    @Override
    public Flux<ChatResponse> streamFlux(
            FormattedMessageList messages, List<ToolSchema> tools, GenerateOptions options) {
        return Flux.create(
                sink -> {
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
                    param.setIncrementalOutput(Boolean.TRUE);
                    applyOptions(param, tools, options, true);
                    param.setMessages(convertToDashScopeMessages(messages.asMaps()));

                    ResultCallback<GenerationResult> cb =
                            new ResultCallback<>() {
                                @Override
                                public void onEvent(GenerationResult message) {
                                    try {
                                        ChatResponse chunk = parseGenerationResult(message, start);
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
                                "DashScope stream: model={}, messages={}",
                                modelName,
                                messages != null ? messages.size() : 0);
                        generation.streamCall(param, cb);
                    } catch (Exception e) {
                        sink.error(e);
                    }
                });
    }

    private void applyOptions(
            GenerationParam param,
            List<ToolSchema> tools,
            GenerateOptions options,
            boolean isStream) {
        GenerateOptions opt = options != null ? options : defaultOptions;
        if (opt.getTemperature() != null) param.setTemperature(opt.getTemperature().floatValue());
        if (opt.getTopP() != null) param.setTopP(opt.getTopP());
        if (opt.getMaxTokens() != null) param.setMaxTokens(opt.getMaxTokens());

        if (Boolean.TRUE.equals(enableThinking)) {
            param.setEnableThinking(Boolean.TRUE);
            if (isStream) {
                param.setIncrementalOutput(Boolean.TRUE);
            }
        }

        if (tools != null && !tools.isEmpty()) {
            Gson gson = new Gson();
            List<ToolBase> toolList = new ArrayList<>();
            for (ToolSchema t : tools) {
                FunctionDefinition.FunctionDefinitionBuilder<?, ?> fdb =
                        FunctionDefinition.builder();
                if (t.getName() != null) fdb.name(t.getName());
                if (t.getDescription() != null) fdb.description(t.getDescription());
                if (t.getParameters() != null) {
                    JsonElement el = gson.toJsonTree(t.getParameters());
                    if (el != null && el.isJsonObject()) {
                        fdb.parameters(el.getAsJsonObject());
                    } else {
                        fdb.parameters(new JsonObject());
                    }
                }
                FunctionDefinition fd = fdb.build();
                ToolFunction toolFn = ToolFunction.builder().type("function").function(fd).build();
                toolList.add(toolFn);
            }
            param.setTools(toolList);
            log.debug("DashScope tools registered: {}", toolList.size());
        }
    }

    private ChatResponse parseGenerationResult(GenerationResult result, Instant start) {
        try {
            List<ContentBlock> blocks = new ArrayList<>();
            GenerationOutput out = result.getOutput();
            if (out != null) {
                String text = out.getText();
                if (text != null && !text.isEmpty()) {
                    blocks.add(TextBlock.builder().text(text).build());
                }

                if (out.getChoices() != null && !out.getChoices().isEmpty()) {
                    Message message = out.getChoices().get(0).getMessage();
                    if (message != null) {
                        String reasoningContent = message.getReasoningContent();
                        if (reasoningContent != null && !reasoningContent.isEmpty()) {
                            blocks.add(ThinkingBlock.builder().text(reasoningContent).build());
                        }
                        String content = message.getContent();
                        if (content != null && !content.isEmpty()) {
                            blocks.add(TextBlock.builder().text(content).build());
                        }
                        // Parse tool calls via SDK types
                        addToolCallsFromSdkMessage(message, blocks);
                    }
                }
            }

            ChatUsage usage = null;
            GenerationUsage u = result.getUsage();
            if (u != null) {
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
                                .time(Duration.between(start, Instant.now()).toMillis() / 1000.0)
                                .build();
            }
            return ChatResponse.builder()
                    .id(result.getRequestId())
                    .content(blocks)
                    .usage(usage)
                    .build();
        } catch (Exception e) {
            log.error("Failed to parse DashScope result: {}", e.getMessage(), e);
            throw new RuntimeException("Failed to parse DashScope result: " + e.getMessage(), e);
        }
    }

    private void addToolCallsFromSdkMessage(Message message, List<ContentBlock> blocks) {
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
                        // Keep raw content for later aggregation when JSON parsing fails
                        // This handles streaming tool calls where arguments are fragmented
                    }
                }
                // For DashScope streaming tool calls:
                // - First chunk: has name, callId, and partial arguments
                // - Subsequent chunks: only have arguments fragments, no name/callId
                if (name != null) {
                    // First chunk with complete metadata
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
                    // Subsequent chunks with only argument fragments
                    // Use placeholder values for aggregation by ToolCallAccumulator
                    String callId =
                            id != null
                                    ? id
                                    : ("fragment_" + System.currentTimeMillis() + "_" + idx);
                    blocks.add(
                            ToolUseBlock.builder()
                                    .id(callId)
                                    .name("__fragment__") // Placeholder name for fragments
                                    .input(argsMap)
                                    .content(rawContent)
                                    .build());
                }
            }
            idx++;
        }
    }

    private ChatResponse aggregateFromFlux(Flux<ChatResponse> flux) {
        AtomicReference<List<ContentBlock>> accContent = new AtomicReference<>(new ArrayList<>());
        AtomicReference<ChatUsage> lastUsage = new AtomicReference<>(null);
        CountDownLatch latch = new CountDownLatch(1);
        AtomicReference<ChatResponse> finalResp = new AtomicReference<>();

        flux.doOnNext(
                        chunk -> {
                            accContent.get().addAll(chunk.getContent());
                            if (chunk.getUsage() != null) lastUsage.set(chunk.getUsage());
                        })
                .doOnError(
                        e -> {
                            latch.countDown();
                            throw new RuntimeException(e);
                        })
                .doOnComplete(
                        () -> {
                            finalResp.set(
                                    ChatResponse.builder()
                                            .content(accContent.get())
                                            .usage(lastUsage.get())
                                            .build());
                            latch.countDown();
                        })
                .subscribe();

        try {
            latch.await(120, TimeUnit.SECONDS);
        } catch (InterruptedException ignored) {
        }
        return finalResp.get();
    }

    // Intentionally removed unused safeJson helper to satisfy linter.

    private List<Message> convertToDashScopeMessages(List<Map<String, Object>> messages) {
        List<Message> list = new ArrayList<>();
        if (messages == null) return list;
        for (Map<String, Object> m : messages) {
            String role = String.valueOf(m.getOrDefault("role", "user"));
            String content = extractTextFromContent(m.get("content"));
            Message built = new Message();
            built.setRole(role);
            built.setContent(content);

            if ("assistant".equals(role)) {
                // Handle tool calls in assistant messages
                Object toolCallsObj = m.get("tool_calls");
                if (toolCallsObj instanceof List<?> toolCallsList) {
                    List<ToolCallBase> dashScopeToolCalls = new ArrayList<>();
                    for (Object toolCallObj : toolCallsList) {
                        if (toolCallObj instanceof Map<?, ?> toolCallMap) {
                            ToolCallBase dashScopeToolCall =
                                    convertToDashScopeToolCall(toolCallMap);
                            if (dashScopeToolCall != null) {
                                dashScopeToolCalls.add(dashScopeToolCall);
                            }
                        }
                    }
                    if (!dashScopeToolCalls.isEmpty()) {
                        built.setToolCalls(dashScopeToolCalls);
                    }
                }
            } else if ("tool".equals(role)) {
                Object toolCallId = m.get("tool_call_id");
                if (toolCallId != null) {
                    built.setToolCallId(String.valueOf(toolCallId));
                }
            }
            // Note: registering tools on the request is handled via applyOptions
            list.add(built);
        }
        return list;
    }

    /**
     * Convert a tool call from FormattedMessage format to DashScope ToolCallBase format.
     */
    @SuppressWarnings("unchecked")
    private ToolCallBase convertToDashScopeToolCall(Map<?, ?> toolCallMap) {
        try {
            Object idObj = toolCallMap.get("id");
            Object typeObj = toolCallMap.get("type");

            String id = idObj != null ? idObj.toString() : "";
            String type = typeObj != null ? typeObj.toString() : "function";

            if (!"function".equals(type)) {
                log.warn("Unsupported tool call type: {}", type);
                return null;
            }

            Object functionObj = toolCallMap.get("function");
            if (!(functionObj instanceof Map<?, ?>)) {
                log.warn("Tool call missing function object");
                return null;
            }

            Map<?, ?> functionMap = (Map<?, ?>) functionObj;
            Object nameObj = functionMap.get("name");
            Object argsObj = functionMap.get("arguments");

            String name = nameObj != null ? nameObj.toString() : "";
            String arguments = argsObj != null ? argsObj.toString() : "{}";

            // Create DashScope ToolCallFunction
            ToolCallFunction toolCallFunction = new ToolCallFunction();
            toolCallFunction.setId(id);

            // Create CallFunction as an inner class instance
            ToolCallFunction.CallFunction callFunction = toolCallFunction.new CallFunction();
            callFunction.setName(name);
            callFunction.setArguments(arguments);
            toolCallFunction.setFunction(callFunction);

            return toolCallFunction;

        } catch (Exception e) {
            log.error("Failed to convert tool call: {}", e.getMessage(), e);
            return null;
        }
    }

    private String extractTextFromContent(Object content) {
        if (content == null) return "";
        if (content instanceof String s) return s;
        if (content instanceof List<?>) {
            StringBuilder sb = new StringBuilder();
            for (Object o : (List<?>) content) {
                if (o instanceof Map<?, ?> om) {
                    Object t = om.get("text");
                    if (t instanceof String ts) {
                        if (sb.length() > 0) sb.append("\n");
                        sb.append(ts);
                    }
                }
            }
            return sb.toString();
        }
        return String.valueOf(content);
    }

    public static class Builder {
        private String apiKey;
        private String modelName;
        private boolean stream = true;
        private Boolean enableThinking;
        private GenerateOptions defaultOptions = new GenerateOptions();
        private String protocol = Protocol.HTTP.getValue();
        private String baseUrl;

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

        public DashScopeChatModel build() {
            return new DashScopeChatModel(
                    apiKey, modelName, stream, enableThinking, defaultOptions, protocol, baseUrl);
        }
    }
}
