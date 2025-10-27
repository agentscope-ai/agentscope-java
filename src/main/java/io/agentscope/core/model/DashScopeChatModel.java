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
import com.alibaba.dashscope.common.Message;
import com.alibaba.dashscope.common.ResultCallback;
import com.alibaba.dashscope.protocol.Protocol;
import io.agentscope.core.formatter.DashScopeChatFormatter;
import io.agentscope.core.formatter.Formatter;
import io.agentscope.core.message.Msg;
import java.time.Instant;
import java.util.List;
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
    private final Formatter<Message, GenerationResult, GenerationParam> formatter;

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

    // Intentionally removed unused safeJson helper to satisfy linter.

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
