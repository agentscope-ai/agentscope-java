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
package io.agentscope.core.formatter.openai.dto;

import com.fasterxml.jackson.annotation.JsonAnyGetter;
import com.fasterxml.jackson.annotation.JsonAnySetter;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * OpenAI Chat Completion API request DTO.
 *
 * <p>This class represents the request structure for OpenAI's Chat Completion API.
 *
 * <p>Example JSON:
 * <pre>{@code
 * {
 *   "model": "gpt-4o",
 *   "messages": [...],
 *   "stream": true,
 *   "temperature": 0.7,
 *   "max_tokens": 1000
 * }
 * }</pre>
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public class OpenAIRequest {

    /** The model to use (e.g., "gpt-4o", "gpt-3.5-turbo"). */
    @JsonProperty("model")
    private String model;

    /** The list of messages in the conversation. */
    @JsonProperty("messages")
    private List<OpenAIMessage> messages;

    /** Whether to stream the response. */
    @JsonProperty("stream")
    private Boolean stream;

    /** Options for streaming response. */
    @JsonProperty("stream_options")
    private OpenAIStreamOptions streamOptions;

    /** Sampling temperature (0.0-2.0). */
    @JsonProperty("temperature")
    private Double temperature;

    /** Nucleus sampling parameter (0.0-1.0). */
    @JsonProperty("top_p")
    private Double topP;

    /** Maximum tokens to generate. */
    @JsonProperty("max_tokens")
    private Integer maxTokens;

    /** Maximum completion tokens (alternative to max_tokens). */
    @JsonProperty("max_completion_tokens")
    private Integer maxCompletionTokens;

    /** Frequency penalty (-2.0 to 2.0). */
    @JsonProperty("frequency_penalty")
    private Double frequencyPenalty;

    /** Presence penalty (-2.0 to 2.0). */
    @JsonProperty("presence_penalty")
    private Double presencePenalty;

    /** Stop sequences. */
    @JsonProperty("stop")
    private Object stop;

    /** Random seed for reproducibility. */
    @JsonProperty("seed")
    private Integer seed;

    /** Number of completions to generate. */
    @JsonProperty("n")
    private Integer n;

    /** List of available tools. */
    @JsonProperty("tools")
    private List<OpenAITool> tools;

    /**
     * Tool choice configuration.
     * Can be "auto", "none", "required", or a specific tool object.
     */
    @JsonProperty("tool_choice")
    private Object toolChoice;

    /** User identifier for abuse monitoring. */
    @JsonProperty("user")
    private String user;

    /** Response format configuration (supports both Map and ResponseFormat object). */
    @JsonProperty("response_format")
    private Object responseFormat;

    /** Log probabilities configuration. */
    @JsonProperty("logprobs")
    private Boolean logprobs;

    /** Top log probabilities to return. */
    @JsonProperty("top_logprobs")
    private Integer topLogprobs;

    /**
     * Reasoning effort for o1 models.
     * Values: "low", "medium", "high".
     */
    @JsonProperty("reasoning_effort")
    private String reasoningEffort;

    /**
     * Controls whether to allow parallel tool calls.
     * Set to false to disable parallel tool calling.
     */
    @JsonProperty("parallel_tool_calls")
    private Boolean parallelToolCalls;

    /**
     * Service tier for the request.
     * Values: "auto", "default".
     */
    @JsonProperty("service_tier")
    private String serviceTier;

    /**
     * Whether to store the conversation for model distillation.
     */
    @JsonProperty("store")
    private Boolean store;

    /**
     * Metadata for the request (used with store=true).
     */
    @JsonProperty("metadata")
    private Map<String, Object> metadata;

    /**
     * Prediction configuration for latency optimization.
     */
    @JsonProperty("prediction")
    private Map<String, Object> prediction;

    /**
     * Output modalities for the response.
     * Example: ["text", "audio"]
     */
    @JsonProperty("modalities")
    private java.util.List<String> modalities;

    /**
     * Audio output configuration.
     */
    @JsonProperty("audio")
    private Map<String, Object> audio;

    public OpenAIRequest() {}

    /**
     * Whether to include reasoning details in the response.
     * Required for some models (e.g., Gemini 3 on OpenRouter) to support tool calling correctly.
     */
    @JsonProperty("include_reasoning")
    private Boolean includeReasoning;

    /** Additional parameters for the request. */
    private Map<String, Object> extraParams;

    public String getModel() {
        return model;
    }

    public void setModel(String model) {
        this.model = model;
    }

    public List<OpenAIMessage> getMessages() {
        return messages;
    }

    public void setMessages(List<OpenAIMessage> messages) {
        this.messages = messages;
    }

    public Boolean getStream() {
        return stream;
    }

    public void setStream(Boolean stream) {
        this.stream = stream;
    }

    public OpenAIStreamOptions getStreamOptions() {
        return streamOptions;
    }

    public void setStreamOptions(OpenAIStreamOptions streamOptions) {
        this.streamOptions = streamOptions;
    }

    public Double getTemperature() {
        return temperature;
    }

    public void setTemperature(Double temperature) {
        this.temperature = temperature;
    }

    public Double getTopP() {
        return topP;
    }

    public void setTopP(Double topP) {
        this.topP = topP;
    }

    public Integer getMaxTokens() {
        return maxTokens;
    }

    public void setMaxTokens(Integer maxTokens) {
        this.maxTokens = maxTokens;
    }

    public Integer getMaxCompletionTokens() {
        return maxCompletionTokens;
    }

    public void setMaxCompletionTokens(Integer maxCompletionTokens) {
        this.maxCompletionTokens = maxCompletionTokens;
    }

    public Double getFrequencyPenalty() {
        return frequencyPenalty;
    }

    public void setFrequencyPenalty(Double frequencyPenalty) {
        this.frequencyPenalty = frequencyPenalty;
    }

    public Double getPresencePenalty() {
        return presencePenalty;
    }

    public void setPresencePenalty(Double presencePenalty) {
        this.presencePenalty = presencePenalty;
    }

    public Object getStop() {
        return stop;
    }

    public void setStop(Object stop) {
        this.stop = stop;
    }

    public Integer getSeed() {
        return seed;
    }

    public void setSeed(Integer seed) {
        this.seed = seed;
    }

    public Integer getN() {
        return n;
    }

    public void setN(Integer n) {
        this.n = n;
    }

    public List<OpenAITool> getTools() {
        return tools;
    }

    public void setTools(List<OpenAITool> tools) {
        this.tools = tools;
    }

    public Object getToolChoice() {
        return toolChoice;
    }

    public void setToolChoice(Object toolChoice) {
        this.toolChoice = toolChoice;
    }

    public String getUser() {
        return user;
    }

    public void setUser(String user) {
        this.user = user;
    }

    public Object getResponseFormat() {
        return responseFormat;
    }

    public void setResponseFormat(Object responseFormat) {
        this.responseFormat = responseFormat;
    }

    public Boolean getLogprobs() {
        return logprobs;
    }

    public void setLogprobs(Boolean logprobs) {
        this.logprobs = logprobs;
    }

    public Integer getTopLogprobs() {
        return topLogprobs;
    }

    public void setTopLogprobs(Integer topLogprobs) {
        this.topLogprobs = topLogprobs;
    }

    public String getReasoningEffort() {
        return reasoningEffort;
    }

    public void setReasoningEffort(String reasoningEffort) {
        this.reasoningEffort = reasoningEffort;
    }

    public Boolean getParallelToolCalls() {
        return parallelToolCalls;
    }

    public void setParallelToolCalls(Boolean parallelToolCalls) {
        this.parallelToolCalls = parallelToolCalls;
    }

    public String getServiceTier() {
        return serviceTier;
    }

    public void setServiceTier(String serviceTier) {
        this.serviceTier = serviceTier;
    }

    public Boolean getStore() {
        return store;
    }

    public void setStore(Boolean store) {
        this.store = store;
    }

    public Map<String, Object> getMetadata() {
        return metadata;
    }

    public void setMetadata(Map<String, Object> metadata) {
        this.metadata = metadata;
    }

    public Map<String, Object> getPrediction() {
        return prediction;
    }

    public void setPrediction(Map<String, Object> prediction) {
        this.prediction = prediction;
    }

    public java.util.List<String> getModalities() {
        return modalities;
    }

    public void setModalities(java.util.List<String> modalities) {
        this.modalities = modalities;
    }

    public Map<String, Object> getAudio() {
        return audio;
    }

    public void setAudio(Map<String, Object> audio) {
        this.audio = audio;
    }

    public Boolean getIncludeReasoning() {
        return includeReasoning;
    }

    public void setIncludeReasoning(Boolean includeReasoning) {
        this.includeReasoning = includeReasoning;
    }

    @JsonAnyGetter
    public Map<String, Object> getExtraParams() {
        return extraParams;
    }

    @JsonAnySetter
    public void addExtraParam(String key, Object value) {
        if (this.extraParams == null) {
            this.extraParams = new HashMap<>();
        }
        this.extraParams.put(key, value);
    }

    public void setExtraParams(Map<String, Object> extraParams) {
        this.extraParams = extraParams;
    }

    public static Builder builder() {
        return new Builder();
    }

    public static class Builder {
        private OpenAIRequest request = new OpenAIRequest();

        public Builder model(String model) {
            request.setModel(model);
            return this;
        }

        public Builder messages(List<OpenAIMessage> messages) {
            request.setMessages(messages);
            return this;
        }

        public Builder stream(Boolean stream) {
            request.setStream(stream);
            return this;
        }

        public Builder streamOptions(OpenAIStreamOptions streamOptions) {
            request.setStreamOptions(streamOptions);
            return this;
        }

        public Builder temperature(Double temperature) {
            request.setTemperature(temperature);
            return this;
        }

        public Builder topP(Double topP) {
            request.setTopP(topP);
            return this;
        }

        public Builder maxTokens(Integer maxTokens) {
            request.setMaxTokens(maxTokens);
            return this;
        }

        public Builder maxCompletionTokens(Integer maxCompletionTokens) {
            request.setMaxCompletionTokens(maxCompletionTokens);
            return this;
        }

        public Builder frequencyPenalty(Double frequencyPenalty) {
            request.setFrequencyPenalty(frequencyPenalty);
            return this;
        }

        public Builder presencePenalty(Double presencePenalty) {
            request.setPresencePenalty(presencePenalty);
            return this;
        }

        public Builder stop(Object stop) {
            request.setStop(stop);
            return this;
        }

        public Builder seed(Integer seed) {
            request.setSeed(seed);
            return this;
        }

        public Builder n(Integer n) {
            request.setN(n);
            return this;
        }

        public Builder tools(List<OpenAITool> tools) {
            request.setTools(tools);
            return this;
        }

        public Builder toolChoice(Object toolChoice) {
            request.setToolChoice(toolChoice);
            return this;
        }

        public Builder user(String user) {
            request.setUser(user);
            return this;
        }

        public Builder responseFormat(Map<String, Object> responseFormat) {
            request.setResponseFormat(responseFormat);
            return this;
        }

        public Builder includeReasoning(Boolean includeReasoning) {
            request.setIncludeReasoning(includeReasoning);
            return this;
        }

        public Builder extraParam(String key, Object value) {
            request.addExtraParam(key, value);
            return this;
        }

        public Builder extraParams(Map<String, Object> extraParams) {
            request.setExtraParams(extraParams);
            return this;
        }

        public OpenAIRequest build() {
            OpenAIRequest result = request;
            request = new OpenAIRequest();
            return result;
        }

        public Builder logprobs(Boolean logprobs) {
            request.setLogprobs(logprobs);
            return this;
        }

        public Builder topLogprobs(Integer topLogprobs) {
            request.setTopLogprobs(topLogprobs);
            return this;
        }

        public Builder reasoningEffort(String reasoningEffort) {
            request.setReasoningEffort(reasoningEffort);
            return this;
        }

        public Builder parallelToolCalls(Boolean parallelToolCalls) {
            request.setParallelToolCalls(parallelToolCalls);
            return this;
        }

        public Builder serviceTier(String serviceTier) {
            request.setServiceTier(serviceTier);
            return this;
        }

        public Builder store(Boolean store) {
            request.setStore(store);
            return this;
        }

        public Builder metadata(Map<String, Object> metadata) {
            request.setMetadata(metadata);
            return this;
        }

        public Builder prediction(Map<String, Object> prediction) {
            request.setPrediction(prediction);
            return this;
        }

        public Builder modalities(java.util.List<String> modalities) {
            request.setModalities(modalities);
            return this;
        }

        public Builder audio(Map<String, Object> audio) {
            request.setAudio(audio);
            return this;
        }
    }
}
