/*
 * Copyright 2024-2026 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * You may not use this file except in compliance with the License.
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

package io.agentscope.core.model.ollama;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonInclude.Include;
import com.fasterxml.jackson.annotation.JsonProperty;
import io.agentscope.core.model.ExecutionConfig;
import io.agentscope.core.model.GenerateOptions;
import io.agentscope.core.util.JsonUtils;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * AgentScope implementation of Ollama model configuration options.
 * Provides a strongly-typed builder for setting model parameters such as temperature, context size, and penalties.
 * <p>
 * This class maps to the Ollama API parameters for model creation and inference.
 * It supports both advanced model loading parameters (like GPU offloading, memory management)
 * and runtime generation options (like sampling parameters, penalties).
 * <p>
 * The class provides utility methods for conversion to/from generic {@link GenerateOptions},
 * allowing seamless integration with AgentScope's general model interface.
 *
 */
@JsonInclude(Include.NON_NULL)
public class OllamaOptions {

    // --------------------------------------------------------------------------
    // Advanced Model Loading Parameters
    // See: https://github.com/ggerganov/llama.cpp/blob/master/examples/main/README.md
    // --------------------------------------------------------------------------

    // @formatter:off

    /**
     * Toggle for Non-Uniform Memory Access (NUMA) optimization.
     * Beneficial for systems with multiple CPU sockets to improve memory access speed.
     */
    @JsonProperty("numa")
    private Boolean useNUMA;

    /**
     * Specifies the maximum context length (token count) for the model's operation window.
     * Defaults to 2048 if not specified.
     */
    @JsonProperty("num_ctx")
    private Integer numCtx;

    /**
     * Defines the number of tokens to process in a single batch during prompt evaluation.
     * Defaults to 512.
     */
    @JsonProperty("num_batch")
    private Integer numBatch;

    /**
     * Designates the number of model layers to offload to the GPU accelerator.
     * Set to -1 to offload all layers dynamically.
     */
    @JsonProperty("num_gpu")
    private Integer numGPU;

    /**
     * Identifies the primary GPU index when using multiple GPUs.
     * This GPU will store the scratch buffers and overhead. Defaults to 0.
     */
    @JsonProperty("main_gpu")
    private Integer mainGPU;

    /**
     * Enables a memory-conserving mode for GPUs with limited VRAM.
     * May reduce performance but allows larger models to load.
     */
    @JsonProperty("low_vram")
    private Boolean lowVRAM;

    /**
     * Toggles the usage of 16-bit floating point precision for the Key-Value cache.
     * Defaults to true for better performance/memory balance.
     */
    @JsonProperty("f16_kv")
    private Boolean f16KV;

    /**
     * Whether to compute and return logits for every token in the prompt, not just the generated ones.
     * Essential for tasks requiring log-probabilities of the input.
     */
    @JsonProperty("logits_all")
    private Boolean logitsAll;

    /**
     * If true, loads only the vocabulary data without the full model weights.
     * Useful for lightweight operations that only need tokenization.
     */
    @JsonProperty("vocab_only")
    private Boolean vocabOnly;

    /**
     * Controls the use of memory-mapped files (mmap) for loading the model.
     * If null, the system chooses the best strategy automatically.
     */
    @JsonProperty("use_mmap")
    private Boolean useMMap;

    /**
     * If enabled, locks the model data in RAM to prevent it from being swapped out to disk.
     * Can improve performance stability but requires sufficient physical memory.
     */
    @JsonProperty("use_mlock")
    private Boolean useMLock;

    /**
     * Sets the number of CPU threads allocated for generation tasks.
     * For optimal performance, this should typically match the number of physical CPU cores.
     */
    @JsonProperty("num_thread")
    private Integer numThread;

    // --------------------------------------------------------------------------
    // Runtime Generation & Prediction Options
    // --------------------------------------------------------------------------

    /**
     * Determines how many tokens from the initial prompt should be retained in the context.
     * Defaults to 4.
     */
    @JsonProperty("num_keep")
    private Integer numKeep;

    /**
     * Sets the random number generator seed to ensure reproducible results.
     * Set to -1 for a random seed on each run.
     */
    @JsonProperty("seed")
    private Integer seed;

    /**
     * Maximum number of tokens to generate in the response.
     * Set to -1 for infinite generation, or -2 to fill the remaining context.
     */
    @JsonProperty("num_predict")
    private Integer numPredict;

    /**
     * Limits the next token selection to the top K most probable candidates.
     * Standard value is 40.
     */
    @JsonProperty("top_k")
    private Integer topK;

    /**
     * Nucleus sampling: selects the smallest set of tokens whose cumulative probability exceeds P.
     * Standard value is 0.9.
     */
    @JsonProperty("top_p")
    private Double topP;

    /**
     * Minimum probability threshold relative to the most likely token.
     * Tokens with lower probability are filtered out. Default is 0.0.
     */
    @JsonProperty("min_p")
    private Double minP;

    /**
     * Tail Free Sampling (TFS) parameter to reduce the impact of low-probability tokens.
     * A value of 1.0 disables this feature.
     */
    @JsonProperty("tfs_z")
    private Float tfsZ;

    /**
     * Typical P sampling parameter to balance diversity and likelihood.
     * Default is 1.0.
     */
    @JsonProperty("typical_p")
    private Float typicalP;

    /**
     * The window size (in tokens) to check for repetition penalty application.
     * Default is 64. Use 0 to disable, or -1 to cover the full context.
     */
    @JsonProperty("repeat_last_n")
    private Integer repeatLastN;

    /**
     * Controls the randomness of the output.
     * Higher values produce more creative results, while lower values make the output more deterministic.
     */
    @JsonProperty("temperature")
    private Double temperature;

    /**
     * Penalty factor applied to repeated tokens to discourage repetition.
     * Default is 1.1.
     */
    @JsonProperty("repeat_penalty")
    private Double repeatPenalty;

    /**
     * Penalty applied based on whether a token has appeared in the text so far.
     * Discourages repeating topics.
     */
    @JsonProperty("presence_penalty")
    private Double presencePenalty;

    /**
     * Penalty applied based on how many times a token has appeared.
     * Discourages repeating exact words.
     */
    @JsonProperty("frequency_penalty")
    private Double frequencyPenalty;

    /**
     * Enables Mirostat sampling for controlling perplexity.
     * 0 = disabled, 1 = Mirostat v1, 2 = Mirostat v2.
     */
    @JsonProperty("mirostat")
    private Integer mirostat;

    /**
     * Target entropy parameter for Mirostat sampling.
     * Default is 5.0.
     */
    @JsonProperty("mirostat_tau")
    private Float mirostatTau;

    /**
     * Learning rate parameter for Mirostat sampling.
     * Default is 0.1.
     */
    @JsonProperty("mirostat_eta")
    private Float mirostatEta;

    /**
     * If true, applies a penalty to newline tokens to encourage single-line output.
     * Default is true.
     */
    @JsonProperty("penalize_newline")
    private Boolean penalizeNewline;

    /**
     * A list of strings that, if generated, will terminate the response generation immediately.
     */
    @JsonProperty("stop")
    private List<String> stop;

    // --------------------------------------------------------------------------
    // Request-Specific Overrides
    // --------------------------------------------------------------------------

    /**
     * Overrides the model name for this specific request.
     */
    @JsonProperty("model")
    private String model;

    /**
     * Specifies the desired output format.
     * Currently supports "json" or null (text).
     */
    @JsonProperty("format")
    private Object format;

    /**
     * Duration to keep the model loaded in memory after the request completes.
     * Example: "5m" for 5 minutes.
     */
    @JsonProperty("keep_alive")
    private String keepAlive;

    /**
     * Whether to truncate the input prompt if it exceeds the context window.
     * Default is true.
     */
    @JsonProperty("truncate")
    private Boolean truncate;

    /**
     * Configuration for chain-of-thought reasoning capabilities.
     * Allows enabling/disabling thinking or setting reasoning levels for supported models.
     *
     * @see ThinkOption
     */
    @JsonProperty("think")
    private ThinkOption thinkOption;

    private ExecutionConfig executionConfig;

    public static Builder builder() {
        return new Builder();
    }

    public static OllamaOptions fromOptions(OllamaOptions fromOptions) {
        return builder()
                .model(fromOptions.getModel())
                .format(fromOptions.getFormat())
                .keepAlive(fromOptions.getKeepAlive())
                .truncate(fromOptions.getTruncate())
                .thinkOption(fromOptions.getThinkOption())
                .useNUMA(fromOptions.getUseNUMA())
                .numCtx(fromOptions.getNumCtx())
                .numBatch(fromOptions.getNumBatch())
                .numGPU(fromOptions.getNumGPU())
                .mainGPU(fromOptions.getMainGPU())
                .lowVRAM(fromOptions.getLowVRAM())
                .f16KV(fromOptions.getF16KV())
                .logitsAll(fromOptions.getLogitsAll())
                .vocabOnly(fromOptions.getVocabOnly())
                .useMMap(fromOptions.getUseMMap())
                .useMLock(fromOptions.getUseMLock())
                .numThread(fromOptions.getNumThread())
                .numKeep(fromOptions.getNumKeep())
                .seed(fromOptions.getSeed())
                .numPredict(fromOptions.getNumPredict())
                .topK(fromOptions.getTopK())
                .topP(fromOptions.getTopP())
                .minP(fromOptions.getMinP())
                .tfsZ(fromOptions.getTfsZ())
                .typicalP(fromOptions.getTypicalP())
                .repeatLastN(fromOptions.getRepeatLastN())
                .temperature(fromOptions.getTemperature())
                .repeatPenalty(fromOptions.getRepeatPenalty())
                .presencePenalty(fromOptions.getPresencePenalty())
                .frequencyPenalty(fromOptions.getFrequencyPenalty())
                .mirostat(fromOptions.getMirostat())
                .mirostatTau(fromOptions.getMirostatTau())
                .mirostatEta(fromOptions.getMirostatEta())
                .penalizeNewline(fromOptions.getPenalizeNewline())
                .stop(fromOptions.getStop())
                .build();
    }

    /**
     * Converts generic {@link GenerateOptions} to {@link OllamaOptions}.
     * <p>
     * This adapter method maps standard framework options to Ollama-specific parameters.
     * Note that some fields (like execution config or tools) are not mapped as they belong to different layers.
     *
     * @param genOptions The generic options to convert.
     * @return A configured {@link OllamaOptions} instance.
     */
    public static OllamaOptions fromGenerateOptions(GenerateOptions genOptions) {
        if (genOptions == null) {
            return builder().build();
        }

        Builder builder = builder();

        if (genOptions.getTemperature() != null) {
            builder.temperature(genOptions.getTemperature());
        }
        if (genOptions.getTopP() != null) {
            builder.topP(genOptions.getTopP());
        }
        if (genOptions.getTopK() != null) {
            builder.topK(genOptions.getTopK());
        }
        if (genOptions.getMaxTokens() != null) {
            builder.numPredict(genOptions.getMaxTokens());
        }
        if (genOptions.getFrequencyPenalty() != null) {
            builder.frequencyPenalty(genOptions.getFrequencyPenalty());
        }
        if (genOptions.getPresencePenalty() != null) {
            builder.presencePenalty(genOptions.getPresencePenalty());
        }
        if (genOptions.getSeed() != null) {
            // Ollama uses Integer for seed, GenerateOptions uses Long
            builder.seed(genOptions.getSeed().intValue());
        }

        // Map thinking budget to enable thinking if budget > 0
        if (genOptions.getThinkingBudget() != null && genOptions.getThinkingBudget() > 0) {
            builder.thinkOption(ThinkOption.ThinkBoolean.ENABLED);
        }

        // Map additional parameters from map
        if (genOptions.getAdditionalBodyParams() != null) {
            applyAdditionalParams(builder, genOptions.getAdditionalBodyParams());
        }

        return builder.build();
    }

    /**
     * Converts this {@link OllamaOptions} to generic {@link GenerateOptions}.
     *
     * @return A configured {@link GenerateOptions} instance.
     */
    public GenerateOptions toGenerateOptions() {
        GenerateOptions.Builder builder = GenerateOptions.builder();

        if (this.temperature != null) {
            builder.temperature(this.temperature);
        }
        if (this.topP != null) {
            builder.topP(this.topP);
        }
        if (this.topK != null) {
            builder.topK(this.topK);
        }
        if (this.numPredict != null) {
            builder.maxTokens(this.numPredict);
        }
        if (this.frequencyPenalty != null) {
            builder.frequencyPenalty(this.frequencyPenalty);
        }
        if (this.presencePenalty != null) {
            builder.presencePenalty(this.presencePenalty);
        }
        if (this.seed != null) {
            builder.seed(this.seed.longValue());
        }
        if (this.executionConfig != null) {
            builder.executionConfig(this.executionConfig);
        }

        // Add other Ollama-specific options as additional parameters
        Map<String, Object> map = this.toMap();
        // Remove standard options that are already mapped
        map.remove("temperature");
        map.remove("top_p");
        map.remove("top_k");
        map.remove("num_predict");
        map.remove("frequency_penalty");
        map.remove("presence_penalty");
        map.remove("seed");

        // Add remaining as additional params
        for (Map.Entry<String, Object> entry : map.entrySet()) {
            if (entry.getValue() != null) {
                builder.additionalBodyParam(entry.getKey(), entry.getValue());
            }
        }

        return builder.build();
    }

    private static void applyAdditionalParams(Builder builder, Map<String, Object> params) {
        if (params == null || params.isEmpty()) {
            return;
        }

        for (Map.Entry<String, Object> entry : params.entrySet()) {
            String key = entry.getKey();
            Object value = entry.getValue();

            if (value == null) {
                continue;
            }

            // Try to match key to ParamKey values
            // We can use string switch
            // Note: Casting logic is simple, might need more robust conversion for production
            switch (key) {
                case "numa":
                    if (value instanceof Boolean) builder.useNUMA((Boolean) value);
                    break;
                case "num_ctx":
                    if (value instanceof Number) builder.numCtx(((Number) value).intValue());
                    break;
                case "num_batch":
                    if (value instanceof Number) builder.numBatch(((Number) value).intValue());
                    break;
                case "num_gpu":
                    if (value instanceof Number) builder.numGPU(((Number) value).intValue());
                    break;
                case "main_gpu":
                    if (value instanceof Number) builder.mainGPU(((Number) value).intValue());
                    break;
                case "low_vram":
                    if (value instanceof Boolean) builder.lowVRAM((Boolean) value);
                    break;
                case "f16_kv":
                    if (value instanceof Boolean) builder.f16KV((Boolean) value);
                    break;
                case "logits_all":
                    if (value instanceof Boolean) builder.logitsAll((Boolean) value);
                    break;
                case "vocab_only":
                    if (value instanceof Boolean) builder.vocabOnly((Boolean) value);
                    break;
                case "use_mmap":
                    if (value instanceof Boolean) builder.useMMap((Boolean) value);
                    break;
                case "use_mlock":
                    if (value instanceof Boolean) builder.useMLock((Boolean) value);
                    break;
                case "num_thread":
                    if (value instanceof Number) builder.numThread(((Number) value).intValue());
                    break;
                case "num_keep":
                    if (value instanceof Number) builder.numKeep(((Number) value).intValue());
                    break;
                case "seed":
                    if (value instanceof Number) builder.seed(((Number) value).intValue());
                    break;
                case "num_predict":
                    if (value instanceof Number) builder.numPredict(((Number) value).intValue());
                    break;
                case "top_k":
                    if (value instanceof Number) builder.topK(((Number) value).intValue());
                    break;
                case "top_p":
                    if (value instanceof Number) builder.topP(((Number) value).doubleValue());
                    break;
                case "min_p":
                    if (value instanceof Number) builder.minP(((Number) value).doubleValue());
                    break;
                case "tfs_z":
                    if (value instanceof Number) builder.tfsZ(((Number) value).floatValue());
                    break;
                case "typical_p":
                    if (value instanceof Number) builder.typicalP(((Number) value).floatValue());
                    break;
                case "repeat_last_n":
                    if (value instanceof Number) builder.repeatLastN(((Number) value).intValue());
                    break;
                case "temperature":
                    if (value instanceof Number)
                        builder.temperature(((Number) value).doubleValue());
                    break;
                case "repeat_penalty":
                    if (value instanceof Number)
                        builder.repeatPenalty(((Number) value).doubleValue());
                    break;
                case "presence_penalty":
                    if (value instanceof Number)
                        builder.presencePenalty(((Number) value).doubleValue());
                    break;
                case "frequency_penalty":
                    if (value instanceof Number)
                        builder.frequencyPenalty(((Number) value).doubleValue());
                    break;
                case "mirostat":
                    if (value instanceof Number) builder.mirostat(((Number) value).intValue());
                    break;
                case "mirostat_tau":
                    if (value instanceof Number) builder.mirostatTau(((Number) value).floatValue());
                    break;
                case "mirostat_eta":
                    if (value instanceof Number) builder.mirostatEta(((Number) value).floatValue());
                    break;
                case "penalize_newline":
                    if (value instanceof Boolean) builder.penalizeNewline((Boolean) value);
                    break;
                case "stop":
                    if (value instanceof List) builder.stop((List<String>) value);
                    break;
                case "model":
                    if (value instanceof String) builder.model((String) value);
                    break;
                case "format":
                    builder.format(value);
                    break;
                case "keep_alive":
                    if (value instanceof String) builder.keepAlive((String) value);
                    break;
                case "truncate":
                    if (value instanceof Boolean) builder.truncate((Boolean) value);
                    break;
                case "think":
                    if (value instanceof Boolean) {
                        builder.thinkOption(
                                (Boolean) value
                                        ? ThinkOption.ThinkBoolean.ENABLED
                                        : ThinkOption.ThinkBoolean.DISABLED);
                    } else if (value instanceof String) {
                        builder.thinkOption(new ThinkOption.ThinkLevel((String) value));
                    } else if (value instanceof Map) {
                        builder.thinkOption(
                                JsonUtils.getJsonCodec().convertValue(value, ThinkOption.class));
                    }
                    break;
            }
        }
    }

    public String getModel() {
        return this.model;
    }

    public void setModel(String model) {
        this.model = model;
    }

    public Object getFormat() {
        return this.format;
    }

    public void setFormat(Object format) {
        this.format = format;
    }

    public String getKeepAlive() {
        return this.keepAlive;
    }

    public void setKeepAlive(String keepAlive) {
        this.keepAlive = keepAlive;
    }

    public Boolean getUseNUMA() {
        return this.useNUMA;
    }

    public void setUseNUMA(Boolean useNUMA) {
        this.useNUMA = useNUMA;
    }

    public Integer getNumCtx() {
        return this.numCtx;
    }

    public void setNumCtx(Integer numCtx) {
        this.numCtx = numCtx;
    }

    public Integer getNumBatch() {
        return this.numBatch;
    }

    public void setNumBatch(Integer numBatch) {
        this.numBatch = numBatch;
    }

    public Integer getNumGPU() {
        return this.numGPU;
    }

    public void setNumGPU(Integer numGPU) {
        this.numGPU = numGPU;
    }

    public Integer getMainGPU() {
        return this.mainGPU;
    }

    public void setMainGPU(Integer mainGPU) {
        this.mainGPU = mainGPU;
    }

    public Boolean getLowVRAM() {
        return this.lowVRAM;
    }

    public void setLowVRAM(Boolean lowVRAM) {
        this.lowVRAM = lowVRAM;
    }

    public Boolean getF16KV() {
        return this.f16KV;
    }

    public void setF16KV(Boolean f16kv) {
        this.f16KV = f16kv;
    }

    public Boolean getLogitsAll() {
        return this.logitsAll;
    }

    public void setLogitsAll(Boolean logitsAll) {
        this.logitsAll = logitsAll;
    }

    public Boolean getVocabOnly() {
        return this.vocabOnly;
    }

    public void setVocabOnly(Boolean vocabOnly) {
        this.vocabOnly = vocabOnly;
    }

    public Boolean getUseMMap() {
        return this.useMMap;
    }

    public void setUseMMap(Boolean useMMap) {
        this.useMMap = useMMap;
    }

    public Boolean getUseMLock() {
        return this.useMLock;
    }

    public void setUseMLock(Boolean useMLock) {
        this.useMLock = useMLock;
    }

    public Integer getNumThread() {
        return this.numThread;
    }

    public void setNumThread(Integer numThread) {
        this.numThread = numThread;
    }

    public Integer getNumKeep() {
        return this.numKeep;
    }

    public void setNumKeep(Integer numKeep) {
        this.numKeep = numKeep;
    }

    public Integer getSeed() {
        return this.seed;
    }

    public void setSeed(Integer seed) {
        this.seed = seed;
    }

    public Integer getMaxTokens() {
        return getNumPredict();
    }

    public void setMaxTokens(Integer maxTokens) {
        setNumPredict(maxTokens);
    }

    public Integer getNumPredict() {
        return this.numPredict;
    }

    public void setNumPredict(Integer numPredict) {
        this.numPredict = numPredict;
    }

    public Integer getTopK() {
        return this.topK;
    }

    public void setTopK(Integer topK) {
        this.topK = topK;
    }

    public Double getTopP() {
        return this.topP;
    }

    public void setTopP(Double topP) {
        this.topP = topP;
    }

    public Double getMinP() {
        return this.minP;
    }

    public void setMinP(Double minP) {
        this.minP = minP;
    }

    public Float getTfsZ() {
        return this.tfsZ;
    }

    public void setTfsZ(Float tfsZ) {
        this.tfsZ = tfsZ;
    }

    public Float getTypicalP() {
        return this.typicalP;
    }

    public void setTypicalP(Float typicalP) {
        this.typicalP = typicalP;
    }

    public Integer getRepeatLastN() {
        return this.repeatLastN;
    }

    public void setRepeatLastN(Integer repeatLastN) {
        this.repeatLastN = repeatLastN;
    }

    public Double getTemperature() {
        return this.temperature;
    }

    public void setTemperature(Double temperature) {
        this.temperature = temperature;
    }

    public Double getRepeatPenalty() {
        return this.repeatPenalty;
    }

    public void setRepeatPenalty(Double repeatPenalty) {
        this.repeatPenalty = repeatPenalty;
    }

    public Double getPresencePenalty() {
        return this.presencePenalty;
    }

    public void setPresencePenalty(Double presencePenalty) {
        this.presencePenalty = presencePenalty;
    }

    public Double getFrequencyPenalty() {
        return this.frequencyPenalty;
    }

    public void setFrequencyPenalty(Double frequencyPenalty) {
        this.frequencyPenalty = frequencyPenalty;
    }

    public Integer getMirostat() {
        return this.mirostat;
    }

    public void setMirostat(Integer mirostat) {
        this.mirostat = mirostat;
    }

    public Float getMirostatTau() {
        return this.mirostatTau;
    }

    public void setMirostatTau(Float mirostatTau) {
        this.mirostatTau = mirostatTau;
    }

    public Float getMirostatEta() {
        return this.mirostatEta;
    }

    public void setMirostatEta(Float mirostatEta) {
        this.mirostatEta = mirostatEta;
    }

    public Boolean getPenalizeNewline() {
        return this.penalizeNewline;
    }

    public void setPenalizeNewline(Boolean penalizeNewline) {
        this.penalizeNewline = penalizeNewline;
    }

    public List<String> getStopSequences() {
        return getStop();
    }

    public void setStopSequences(List<String> stopSequences) {
        setStop(stopSequences);
    }

    public List<String> getStop() {
        return this.stop;
    }

    public void setStop(List<String> stop) {
        this.stop = stop;
    }

    public Boolean getTruncate() {
        return this.truncate;
    }

    public void setTruncate(Boolean truncate) {
        this.truncate = truncate;
    }

    public ThinkOption getThinkOption() {
        return this.thinkOption;
    }

    public void setThinkOption(ThinkOption thinkOption) {
        this.thinkOption = thinkOption;
    }

    public ExecutionConfig getExecutionConfig() {
        return this.executionConfig;
    }

    public void setExecutionConfig(ExecutionConfig executionConfig) {
        this.executionConfig = executionConfig;
    }

    /**
     * Serializes the {@link OllamaOptions} into a {@link Map} representation.
     * @return A map containing the configured options.
     */
    public Map<String, Object> toMap() {
        return JsonUtils.getJsonCodec().convertValue(this, Map.class);
    }

    public OllamaOptions copy() {
        return fromOptions(this);
    }

    /**
     * Merges the given {@link GenerateOptions} into a new instance, with the given options taking precedence.
     * <p>
     * This is a convenience method that converts the {@link GenerateOptions} to {@link OllamaOptions}
     * and then merges it.
     *
     * @param other The options to merge (can be null).
     * @return A new merged {@link OllamaOptions} instance.
     */
    public OllamaOptions merge(GenerateOptions other) {
        if (other == null) {
            return this.copy();
        }
        return this.merge(OllamaOptions.fromGenerateOptions(other));
    }

    /**
     * Merges the given options into a new instance, with the given options taking precedence.
     *
     * @param other The options to merge (can be null).
     * @return A new merged {@link OllamaOptions} instance.
     */
    public OllamaOptions merge(OllamaOptions other) {
        if (other == null) {
            return this.copy();
        }

        Builder builder = this.toBuilder();

        if (other.getModel() != null) builder.model(other.getModel());
        if (other.getFormat() != null) builder.format(other.getFormat());
        if (other.getKeepAlive() != null) builder.keepAlive(other.getKeepAlive());
        if (other.getTruncate() != null) builder.truncate(other.getTruncate());
        if (other.getThinkOption() != null) builder.thinkOption(other.getThinkOption());
        if (other.getUseNUMA() != null) builder.useNUMA(other.getUseNUMA());
        if (other.getNumCtx() != null) builder.numCtx(other.getNumCtx());
        if (other.getNumBatch() != null) builder.numBatch(other.getNumBatch());
        if (other.getNumGPU() != null) builder.numGPU(other.getNumGPU());
        if (other.getMainGPU() != null) builder.mainGPU(other.getMainGPU());
        if (other.getLowVRAM() != null) builder.lowVRAM(other.getLowVRAM());
        if (other.getF16KV() != null) builder.f16KV(other.getF16KV());
        if (other.getLogitsAll() != null) builder.logitsAll(other.getLogitsAll());
        if (other.getVocabOnly() != null) builder.vocabOnly(other.getVocabOnly());
        if (other.getUseMMap() != null) builder.useMMap(other.getUseMMap());
        if (other.getUseMLock() != null) builder.useMLock(other.getUseMLock());
        if (other.getNumThread() != null) builder.numThread(other.getNumThread());
        if (other.getNumKeep() != null) builder.numKeep(other.getNumKeep());
        if (other.getSeed() != null) builder.seed(other.getSeed());
        if (other.getNumPredict() != null) builder.numPredict(other.getNumPredict());
        if (other.getTopK() != null) builder.topK(other.getTopK());
        if (other.getTopP() != null) builder.topP(other.getTopP());
        if (other.getMinP() != null) builder.minP(other.getMinP());
        if (other.getTfsZ() != null) builder.tfsZ(other.getTfsZ());
        if (other.getTypicalP() != null) builder.typicalP(other.getTypicalP());
        if (other.getRepeatLastN() != null) builder.repeatLastN(other.getRepeatLastN());
        if (other.getTemperature() != null) builder.temperature(other.getTemperature());
        if (other.getRepeatPenalty() != null) builder.repeatPenalty(other.getRepeatPenalty());
        if (other.getPresencePenalty() != null) builder.presencePenalty(other.getPresencePenalty());
        if (other.getFrequencyPenalty() != null)
            builder.frequencyPenalty(other.getFrequencyPenalty());
        if (other.getMirostat() != null) builder.mirostat(other.getMirostat());
        if (other.getMirostatTau() != null) builder.mirostatTau(other.getMirostatTau());
        if (other.getMirostatEta() != null) builder.mirostatEta(other.getMirostatEta());
        if (other.getPenalizeNewline() != null) builder.penalizeNewline(other.getPenalizeNewline());
        if (other.getStop() != null) builder.stop(other.getStop());
        if (other.getExecutionConfig() != null) builder.executionConfig(other.getExecutionConfig());

        return builder.build();
    }

    public Builder toBuilder() {
        return builder()
                .model(this.model)
                .format(this.format)
                .keepAlive(this.keepAlive)
                .truncate(this.truncate)
                .thinkOption(this.thinkOption)
                .useNUMA(this.useNUMA)
                .numCtx(this.numCtx)
                .numBatch(this.numBatch)
                .numGPU(this.numGPU)
                .mainGPU(this.mainGPU)
                .lowVRAM(this.lowVRAM)
                .f16KV(this.f16KV)
                .logitsAll(this.logitsAll)
                .vocabOnly(this.vocabOnly)
                .useMMap(this.useMMap)
                .useMLock(this.useMLock)
                .numThread(this.numThread)
                .numKeep(this.numKeep)
                .seed(this.seed)
                .numPredict(this.numPredict)
                .topK(this.topK)
                .topP(this.topP)
                .minP(this.minP)
                .tfsZ(this.tfsZ)
                .typicalP(this.typicalP)
                .repeatLastN(this.repeatLastN)
                .temperature(this.temperature)
                .repeatPenalty(this.repeatPenalty)
                .presencePenalty(this.presencePenalty)
                .frequencyPenalty(this.frequencyPenalty)
                .mirostat(this.mirostat)
                .mirostatTau(this.mirostatTau)
                .mirostatEta(this.mirostatEta)
                .penalizeNewline(this.penalizeNewline)
                .stop(this.stop);
    }

    // @formatter:on

    @Override
    public boolean equals(Object o) {
        if (this == o) {
            return true;
        }
        if (o == null || getClass() != o.getClass()) {
            return false;
        }
        OllamaOptions that = (OllamaOptions) o;
        return Objects.equals(this.model, that.model)
                && Objects.equals(this.format, that.format)
                && Objects.equals(this.keepAlive, that.keepAlive)
                && Objects.equals(this.truncate, that.truncate)
                && Objects.equals(this.thinkOption, that.thinkOption)
                && Objects.equals(this.useNUMA, that.useNUMA)
                && Objects.equals(this.numCtx, that.numCtx)
                && Objects.equals(this.numBatch, that.numBatch)
                && Objects.equals(this.numGPU, that.numGPU)
                && Objects.equals(this.mainGPU, that.mainGPU)
                && Objects.equals(this.lowVRAM, that.lowVRAM)
                && Objects.equals(this.f16KV, that.f16KV)
                && Objects.equals(this.logitsAll, that.logitsAll)
                && Objects.equals(this.vocabOnly, that.vocabOnly)
                && Objects.equals(this.useMMap, that.useMMap)
                && Objects.equals(this.useMLock, that.useMLock)
                && Objects.equals(this.numThread, that.numThread)
                && Objects.equals(this.numKeep, that.numKeep)
                && Objects.equals(this.seed, that.seed)
                && Objects.equals(this.numPredict, that.numPredict)
                && Objects.equals(this.topK, that.topK)
                && Objects.equals(this.topP, that.topP)
                && Objects.equals(this.minP, that.minP)
                && Objects.equals(this.tfsZ, that.tfsZ)
                && Objects.equals(this.typicalP, that.typicalP)
                && Objects.equals(this.repeatLastN, that.repeatLastN)
                && Objects.equals(this.temperature, that.temperature)
                && Objects.equals(this.repeatPenalty, that.repeatPenalty)
                && Objects.equals(this.presencePenalty, that.presencePenalty)
                && Objects.equals(this.frequencyPenalty, that.frequencyPenalty)
                && Objects.equals(this.mirostat, that.mirostat)
                && Objects.equals(this.mirostatTau, that.mirostatTau)
                && Objects.equals(this.mirostatEta, that.mirostatEta)
                && Objects.equals(this.penalizeNewline, that.penalizeNewline)
                && Objects.equals(this.stop, that.stop);
    }

    @Override
    public int hashCode() {
        return Objects.hash(
                this.model,
                this.format,
                this.keepAlive,
                this.truncate,
                this.thinkOption,
                this.useNUMA,
                this.numCtx,
                this.numBatch,
                this.numGPU,
                this.mainGPU,
                this.lowVRAM,
                this.f16KV,
                this.logitsAll,
                this.vocabOnly,
                this.useMMap,
                this.useMLock,
                this.numThread,
                this.numKeep,
                this.seed,
                this.numPredict,
                this.topK,
                this.topP,
                this.minP,
                this.tfsZ,
                this.typicalP,
                this.repeatLastN,
                this.temperature,
                this.repeatPenalty,
                this.presencePenalty,
                this.frequencyPenalty,
                this.mirostat,
                this.mirostatTau,
                this.mirostatEta,
                this.penalizeNewline,
                this.stop);
    }

    /**
     * Keys for Ollama parameters.
     */
    public enum ParamKey {
        NUMA("numa"),
        NUM_CTX("num_ctx"),
        NUM_BATCH("num_batch"),
        NUM_GPU("num_gpu"),
        MAIN_GPU("main_gpu"),
        LOW_VRAM("low_vram"),
        F16_KV("f16_kv"),
        LOGITS_ALL("logits_all"),
        VOCAB_ONLY("vocab_only"),
        USE_MMAP("use_mmap"),
        USE_MLOCK("use_mlock"),
        NUM_THREAD("num_thread"),
        NUM_KEEP("num_keep"),
        SEED("seed"),
        NUM_PREDICT("num_predict"),
        TOP_K("top_k"),
        TOP_P("top_p"),
        MIN_P("min_p"),
        TFS_Z("tfs_z"),
        TYPICAL_P("typical_p"),
        REPEAT_LAST_N("repeat_last_n"),
        TEMPERATURE("temperature"),
        REPEAT_PENALTY("repeat_penalty"),
        PRESENCE_PENALTY("presence_penalty"),
        FREQUENCY_PENALTY("frequency_penalty"),
        MIROSTAT("mirostat"),
        MIROSTAT_TAU("mirostat_tau"),
        MIROSTAT_ETA("mirostat_eta"),
        PENALIZE_NEWLINE("penalize_newline"),
        STOP("stop"),
        MODEL("model"),
        FORMAT("format"),
        KEEP_ALIVE("keep_alive"),
        TRUNCATE("truncate"),
        THINK("think");

        private final String key;

        ParamKey(String key) {
            this.key = key;
        }

        public String getKey() {
            return key;
        }
    }

    public static final class Builder {

        private final OllamaOptions options = new OllamaOptions();

        public Builder model(String model) {
            this.options.model = model;
            return this;
        }

        public Builder format(Object format) {
            this.options.format = format;
            return this;
        }

        public Builder keepAlive(String keepAlive) {
            this.options.keepAlive = keepAlive;
            return this;
        }

        public Builder truncate(Boolean truncate) {
            this.options.truncate = truncate;
            return this;
        }

        public Builder executionConfig(ExecutionConfig executionConfig) {
            this.options.setExecutionConfig(executionConfig);
            return this;
        }

        public Builder useNUMA(Boolean useNUMA) {
            this.options.useNUMA = useNUMA;
            return this;
        }

        public Builder numCtx(Integer numCtx) {
            this.options.numCtx = numCtx;
            return this;
        }

        public Builder numBatch(Integer numBatch) {
            this.options.numBatch = numBatch;
            return this;
        }

        public Builder numGPU(Integer numGPU) {
            this.options.numGPU = numGPU;
            return this;
        }

        public Builder mainGPU(Integer mainGPU) {
            this.options.mainGPU = mainGPU;
            return this;
        }

        public Builder lowVRAM(Boolean lowVRAM) {
            this.options.lowVRAM = lowVRAM;
            return this;
        }

        public Builder f16KV(Boolean f16KV) {
            this.options.f16KV = f16KV;
            return this;
        }

        public Builder logitsAll(Boolean logitsAll) {
            this.options.logitsAll = logitsAll;
            return this;
        }

        public Builder vocabOnly(Boolean vocabOnly) {
            this.options.vocabOnly = vocabOnly;
            return this;
        }

        public Builder useMMap(Boolean useMMap) {
            this.options.useMMap = useMMap;
            return this;
        }

        public Builder useMLock(Boolean useMLock) {
            this.options.useMLock = useMLock;
            return this;
        }

        public Builder numThread(Integer numThread) {
            this.options.numThread = numThread;
            return this;
        }

        public Builder numKeep(Integer numKeep) {
            this.options.numKeep = numKeep;
            return this;
        }

        public Builder seed(Integer seed) {
            this.options.seed = seed;
            return this;
        }

        public Builder numPredict(Integer numPredict) {
            this.options.numPredict = numPredict;
            return this;
        }

        public Builder topK(Integer topK) {
            this.options.topK = topK;
            return this;
        }

        public Builder topP(Double topP) {
            this.options.topP = topP;
            return this;
        }

        public Builder minP(Double minP) {
            this.options.minP = minP;
            return this;
        }

        public Builder tfsZ(Float tfsZ) {
            this.options.tfsZ = tfsZ;
            return this;
        }

        public Builder typicalP(Float typicalP) {
            this.options.typicalP = typicalP;
            return this;
        }

        public Builder repeatLastN(Integer repeatLastN) {
            this.options.repeatLastN = repeatLastN;
            return this;
        }

        public Builder temperature(Double temperature) {
            this.options.temperature = temperature;
            return this;
        }

        public Builder repeatPenalty(Double repeatPenalty) {
            this.options.repeatPenalty = repeatPenalty;
            return this;
        }

        public Builder presencePenalty(Double presencePenalty) {
            this.options.presencePenalty = presencePenalty;
            return this;
        }

        public Builder frequencyPenalty(Double frequencyPenalty) {
            this.options.frequencyPenalty = frequencyPenalty;
            return this;
        }

        public Builder mirostat(Integer mirostat) {
            this.options.mirostat = mirostat;
            return this;
        }

        public Builder mirostatTau(Float mirostatTau) {
            this.options.mirostatTau = mirostatTau;
            return this;
        }

        public Builder mirostatEta(Float mirostatEta) {
            this.options.mirostatEta = mirostatEta;
            return this;
        }

        public Builder penalizeNewline(Boolean penalizeNewline) {
            this.options.penalizeNewline = penalizeNewline;
            return this;
        }

        public Builder stop(List<String> stop) {
            this.options.stop = stop;
            return this;
        }

        public Builder thinkOption(ThinkOption thinkOption) {
            this.options.thinkOption = thinkOption;
            return this;
        }

        public OllamaOptions build() {
            return this.options;
        }
    }
}
