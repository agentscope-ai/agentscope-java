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

import com.openai.client.OpenAIClient;
import com.openai.client.okhttp.OpenAIOkHttpClient;
import com.openai.core.http.StreamResponse;
import com.openai.models.ChatModel;
import com.openai.models.chat.completions.ChatCompletion;
import com.openai.models.chat.completions.ChatCompletionChunk;
import com.openai.models.chat.completions.ChatCompletionCreateParams;
import com.openai.models.chat.completions.ChatCompletionFunctionTool;
import com.openai.models.chat.completions.ChatCompletionMessageParam;
import com.openai.models.chat.completions.ChatCompletionTool;
import com.openai.models.chat.completions.ChatCompletionToolChoiceOption;
import io.agentscope.core.formatter.Formatter;
import io.agentscope.core.formatter.OpenAIChatFormatter;
import io.agentscope.core.message.Msg;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import reactor.core.publisher.Flux;

/**
 * OpenAI Chat Model implementation using the official OpenAI Java SDK v3.5.3.
 * This implementation provides complete integration with OpenAI's Chat Completion API,
 * including tool calling and streaming support.
 */
public class OpenAIChatModel implements Model {

    private static final Logger log = LoggerFactory.getLogger(OpenAIChatModel.class);

    private final String baseUrl;
    private final String apiKey;
    private final String modelName;
    private final ChatModel model;
    private final boolean streamEnabled;
    private final OpenAIClient client;
    private final GenerateOptions defaultOptions;
    private final Formatter<ChatCompletionMessageParam, Object> formatter;

    public OpenAIChatModel(
            String baseUrl,
            String apiKey,
            String modelName,
            boolean streamEnabled,
            GenerateOptions defaultOptions,
            Formatter<ChatCompletionMessageParam, Object> formatter) {
        this.baseUrl = baseUrl;
        this.apiKey = apiKey;
        this.modelName = modelName;
        this.model = ChatModel.of(modelName);
        this.streamEnabled = streamEnabled;
        this.defaultOptions =
                defaultOptions != null ? defaultOptions : GenerateOptions.builder().build();
        this.formatter = formatter != null ? formatter : new OpenAIChatFormatter();

        // Initialize OpenAI client
        OpenAIOkHttpClient.Builder clientBuilder = OpenAIOkHttpClient.builder();

        if (apiKey != null) {
            clientBuilder.apiKey(apiKey);
        }

        if (baseUrl != null) {
            clientBuilder.baseUrl(baseUrl);
        }

        this.client = clientBuilder.build();
    }

    @Override
    public Flux<ChatResponse> stream(
            List<Msg> messages, List<ToolSchema> tools, GenerateOptions options) {
        Instant startTime = Instant.now();
        log.debug(
                "OpenAI stream: model={}, messages={}, tools_present={}",
                model,
                messages != null ? messages.size() : 0,
                tools != null && !tools.isEmpty());

        return Flux.defer(
                () -> {
                    try {
                        // Build chat completion request
                        ChatCompletionCreateParams.Builder paramsBuilder =
                                ChatCompletionCreateParams.builder().model(model);

                        // Use formatter to convert Msg to OpenAI ChatCompletionMessageParam
                        List<ChatCompletionMessageParam> formattedMessages =
                                formatter.format(messages);
                        for (ChatCompletionMessageParam param : formattedMessages) {
                            paramsBuilder.addMessage(param);
                        }

                        // Add tools if provided
                        if (tools != null && !tools.isEmpty()) {
                            addToolsToParams(paramsBuilder, tools);
                        }

                        // Apply generation options
                        applyGenerateOptions(paramsBuilder, options);

                        // Create the request
                        ChatCompletionCreateParams params = paramsBuilder.build();

                        if (streamEnabled) {
                            // Make streaming API call
                            StreamResponse<ChatCompletionChunk> streamResponse =
                                    client.chat().completions().createStreaming(params);

                            // Convert the SDK's Stream to Flux
                            return Flux.fromStream(streamResponse.stream())
                                    .map(chunk -> formatter.parseResponse(chunk, startTime))
                                    .filter(Objects::nonNull)
                                    .doFinally(
                                            signalType -> {
                                                try {
                                                    streamResponse.close();
                                                } catch (Exception ignored) {
                                                }
                                            });
                        } else {
                            // For non-streaming, make a single call and return as Flux
                            ChatCompletion completion = client.chat().completions().create(params);
                            ChatResponse response = formatter.parseResponse(completion, startTime);
                            return Flux.just(response);
                        }
                    } catch (Exception e) {
                        return Flux.error(
                                new RuntimeException(
                                        "Failed to stream OpenAI API: " + e.getMessage(), e));
                    }
                });
    }

    private void addToolsToParams(
            ChatCompletionCreateParams.Builder paramsBuilder, List<ToolSchema> tools) {
        try {
            for (ToolSchema toolSchema : tools) {
                // Convert ToolSchema to OpenAI ChatCompletionTool
                // Create function definition first
                com.openai.models.FunctionDefinition.Builder functionBuilder =
                        com.openai.models.FunctionDefinition.builder().name(toolSchema.getName());

                if (toolSchema.getDescription() != null) {
                    functionBuilder.description(toolSchema.getDescription());
                }

                // Convert parameters map to proper format for OpenAI
                if (toolSchema.getParameters() != null) {
                    // Convert Map<String, Object> to FunctionParameters
                    com.openai.models.FunctionParameters.Builder funcParamsBuilder =
                            com.openai.models.FunctionParameters.builder();
                    for (Map.Entry<String, Object> entry : toolSchema.getParameters().entrySet()) {
                        funcParamsBuilder.putAdditionalProperty(
                                entry.getKey(), com.openai.core.JsonValue.from(entry.getValue()));
                    }
                    functionBuilder.parameters(funcParamsBuilder.build());
                }

                // Create ChatCompletionFunctionTool
                ChatCompletionFunctionTool functionTool =
                        ChatCompletionFunctionTool.builder()
                                .function(functionBuilder.build())
                                .build();

                // Create ChatCompletionTool
                ChatCompletionTool tool = ChatCompletionTool.ofFunction(functionTool);
                paramsBuilder.addTool(tool);

                log.debug("Added tool to OpenAI request: {}", toolSchema.getName());
            }

            // Set tool choice to auto to allow the model to decide when to use tools
            paramsBuilder.toolChoice(
                    ChatCompletionToolChoiceOption.ofAuto(
                            ChatCompletionToolChoiceOption.Auto.AUTO));

        } catch (Exception e) {
            log.error("Failed to add tools to OpenAI request: {}", e.getMessage(), e);
        }
    }

    private void applyGenerateOptions(
            ChatCompletionCreateParams.Builder paramsBuilder, GenerateOptions options) {
        GenerateOptions opt = options != null ? options : defaultOptions;
        if (opt.getTemperature() != null) paramsBuilder.temperature(opt.getTemperature());
        if (opt.getMaxTokens() != null)
            paramsBuilder.maxCompletionTokens(opt.getMaxTokens().longValue());
        if (opt.getTopP() != null) paramsBuilder.topP(opt.getTopP());
        if (opt.getFrequencyPenalty() != null)
            paramsBuilder.frequencyPenalty(opt.getFrequencyPenalty());
        if (opt.getPresencePenalty() != null)
            paramsBuilder.presencePenalty(opt.getPresencePenalty());
    }

    @Override
    public String getModelName() {
        return modelName;
    }

    public static Builder builder() {
        return new Builder();
    }

    public static class Builder {
        private String baseUrl;
        private String apiKey;
        private String modelName;
        private boolean streamEnabled = true;
        private GenerateOptions defaultOptions = GenerateOptions.builder().build();
        private Formatter<ChatCompletionMessageParam, Object> formatter;

        private Builder() {}

        public Builder baseUrl(String baseUrl) {
            this.baseUrl = baseUrl;
            return this;
        }

        public Builder apiKey(String apiKey) {
            this.apiKey = apiKey;
            return this;
        }

        public Builder modelName(String modelName) {
            this.modelName = modelName;
            return this;
        }

        public Builder stream(boolean streamEnabled) {
            this.streamEnabled = streamEnabled;
            return this;
        }

        public Builder defaultOptions(GenerateOptions options) {
            this.defaultOptions = options;
            return this;
        }

        public Builder formatter(Formatter<ChatCompletionMessageParam, Object> formatter) {
            this.formatter = formatter;
            return this;
        }

        public OpenAIChatModel build() {
            return new OpenAIChatModel(
                    baseUrl, apiKey, modelName, streamEnabled, defaultOptions, formatter);
        }
    }
}
