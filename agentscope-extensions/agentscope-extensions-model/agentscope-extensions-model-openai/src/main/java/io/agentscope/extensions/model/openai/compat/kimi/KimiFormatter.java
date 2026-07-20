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
package io.agentscope.extensions.model.openai.compat.kimi;

import io.agentscope.core.model.GenerateOptions;
import io.agentscope.core.model.ToolChoice;
import io.agentscope.extensions.model.openai.dto.OpenAIRequest;
import io.agentscope.extensions.model.openai.formatter.OpenAIChatFormatter;
import java.util.HashMap;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Formatter for Kimi (Moonshot AI) models (kimi-k3, kimi-k2.7-code, kimi-k2.6, kimi-k2.5,
 * moonshot-v1 series).
 *
 * <p>Adapted to the latest Kimi open platform Chat Completions API
 * ({@code https://api.moonshot.cn/v1/chat/completions}):
 * <ul>
 *   <li>On {@code kimi-*} models, {@code temperature} / {@code top_p} /
 *       {@code frequency_penalty} / {@code presence_penalty} are fixed by the platform and the
 *       API rejects requests carrying other values; these parameters are stripped from the
 *       request. The {@code moonshot-v1} series still accepts them, so they are kept there.</li>
 *   <li>{@code reasoning_effort} ({@code "low"} / {@code "high"} / {@code "max"}, default
 *       {@code "max"}) is only supported by {@code kimi-k3}; it is stripped for other models</li>
 *   <li>{@code thinking_budget} is not a Kimi parameter and is always stripped; K2.x thinking is
 *       controlled through the {@code thinking} body parameter instead (see below)</li>
 *   <li>Only {@code max_tokens} is documented; {@code max_completion_tokens} is mapped to
 *       {@code max_tokens} when the latter is not set. Reasoning tokens count towards
 *       {@code max_tokens}, so the official guide recommends {@code max_tokens >= 16000} for
 *       thinking models</li>
 *   <li>{@code tool_choice} supports {@code auto} / {@code none} on all models;
 *       {@code required} is only supported by {@code kimi-k3} and is degraded to {@code auto} on
 *       the K2.x series; forcing a specific function is incompatible with thinking enabled
 *       (HTTP 400), so it is degraded to {@code auto} on always-thinking models
 *       ({@code kimi-k3}, {@code kimi-k2.7-code})</li>
 *   <li>The {@code strict} parameter in tool definitions is not documented by Kimi and is not
 *       sent</li>
 *   <li>Assistant {@code reasoning_content} is preserved in the request history (inherited from
 *       the base message converter), as required by Preserved Thinking on {@code kimi-k3} /
 *       {@code kimi-k2.7-code} and by {@code thinking.keep = "all"} on {@code kimi-k2.6}</li>
 * </ul>
 *
 * <p>Thinking mode: {@code kimi-k2.6} / {@code kimi-k2.5} accept a {@code thinking} body
 * parameter (e.g. {@code {"type": "disabled"}}), which can be passed through
 * {@code GenerateOptions.additionalBodyParam("thinking", Map.of("type", "disabled"))}.
 * {@code kimi-k3} uses the top-level {@code reasoning_effort} option
 * ({@code GenerateOptions.reasoningEffort}) instead. JSON mode is enabled via
 * {@code response_format = {"type": "json_object"}}; {@code json_schema} is not supported.
 *
 * <p>Usage:
 * <pre>{@code
 * OpenAIChatModel.builder()
 *     .formatter(new KimiFormatter())
 *     .modelName("kimi-k3")
 *     .baseUrl("https://api.moonshot.cn/v1")
 *     .apiKey(apiKey)
 *     .build();
 * }</pre>
 *
 * @see <a href="https://platform.kimi.com/docs/api/overview">Kimi API overview</a>
 * @see <a href="https://platform.kimi.com/docs/api/models-overview">Kimi model parameter
 *     reference</a>
 * @see <a href="https://platform.kimi.com/docs/guide/use-kimi-k2-thinking-model">Kimi thinking
 *     mode guide</a>
 * @see <a href="https://platform.kimi.com/docs/guide/use-tool-choice">Kimi tool choice guide</a>
 */
public class KimiFormatter extends OpenAIChatFormatter {

    private static final Logger log = LoggerFactory.getLogger(KimiFormatter.class);

    public KimiFormatter() {
        super();
    }

    @Override
    protected boolean supportsStrict() {
        return false;
    }

    @Override
    public void applyOptions(
            OpenAIRequest request, GenerateOptions options, GenerateOptions defaultOptions) {
        super.applyOptions(request, options, defaultOptions);
        sanitizeKimiRequest(request);
    }

    @Override
    public void applyToolChoice(OpenAIRequest request, ToolChoice toolChoice) {
        applyKimiToolChoice(request, toolChoice);
    }

    /**
     * Whether the model belongs to the Kimi K series ({@code kimi-k3}, {@code kimi-k2.7-code},
     * {@code kimi-k2.6}, ...) whose sampling parameters are fixed by the platform.
     *
     * @param model the model name from the request (may be null)
     * @return true for {@code kimi-*} models
     */
    static boolean hasFixedSamplingParams(String model) {
        return model != null && model.startsWith("kimi-");
    }

    /**
     * Whether the model always runs with thinking enabled and cannot disable it
     * ({@code kimi-k3} and the {@code kimi-k2.7-code} series).
     *
     * @param model the model name from the request (may be null)
     * @return true for always-thinking Kimi models
     */
    static boolean isAlwaysThinkingModel(String model) {
        return model != null && (model.startsWith("kimi-k3") || model.startsWith("kimi-k2.7"));
    }

    /**
     * Whether the model supports the top-level {@code reasoning_effort} parameter.
     * Per the official parameter reference, only {@code kimi-k3} supports it.
     *
     * @param model the model name from the request (may be null)
     * @return true if {@code reasoning_effort} is supported
     */
    static boolean supportsReasoningEffort(String model) {
        return model != null && model.startsWith("kimi-k3");
    }

    /**
     * Whether the model supports {@code tool_choice = "required"}.
     * Per the official parameter reference, {@code kimi-k3} supports it while the K2.x series
     * ({@code kimi-k2.6}, {@code kimi-k2.7-code}, ...) rejects it.
     *
     * @param model the model name from the request (may be null)
     * @return true if {@code required} is supported
     */
    static boolean supportsRequiredToolChoice(String model) {
        return model == null || !model.startsWith("kimi-k2");
    }

    /**
     * Sanitize the request according to the Kimi Chat Completions API reference.
     *
     * <p>Applied adaptations:
     * <ul>
     *   <li>{@code temperature} / {@code top_p} / {@code frequency_penalty} /
     *       {@code presence_penalty} are removed on {@code kimi-*} models (fixed by the
     *       platform; other values are rejected)</li>
     *   <li>{@code reasoning_effort} is removed on models other than {@code kimi-k3}</li>
     *   <li>{@code thinking_budget} is always removed (not a Kimi parameter)</li>
     *   <li>{@code max_completion_tokens} is mapped to {@code max_tokens} when the latter is
     *       not set</li>
     * </ul>
     *
     * <p>This method is static to allow sharing with {@link KimiMultiAgentFormatter}.
     *
     * @param request the request to sanitize
     */
    protected static void sanitizeKimiRequest(OpenAIRequest request) {
        String model = request.getModel();

        if (hasFixedSamplingParams(model)) {
            if (request.getTemperature() != null) {
                log.debug(
                        "Kimi model {} does not allow overriding temperature, removing it", model);
                request.setTemperature(null);
            }
            if (request.getTopP() != null) {
                log.debug("Kimi model {} does not allow overriding top_p, removing it", model);
                request.setTopP(null);
            }
            if (request.getFrequencyPenalty() != null) {
                log.debug(
                        "Kimi model {} does not allow overriding frequency_penalty, removing it",
                        model);
                request.setFrequencyPenalty(null);
            }
            if (request.getPresencePenalty() != null) {
                log.debug(
                        "Kimi model {} does not allow overriding presence_penalty, removing it",
                        model);
                request.setPresencePenalty(null);
            }
        }

        if (request.getReasoningEffort() != null && !supportsReasoningEffort(model)) {
            log.debug(
                    "Kimi model {} does not support reasoning_effort (kimi-k3 only), removing it;"
                            + " use the 'thinking' body param on K2.x models instead",
                    model);
            request.setReasoningEffort(null);
        }

        if (request.getThinkingBudget() != null) {
            log.debug(
                    "Kimi does not support thinking_budget, removing it from the request; use the"
                            + " 'thinking' body param (K2.x) or reasoning_effort (kimi-k3)"
                            + " instead");
            request.setThinkingBudget(null);
        }

        // Kimi only documents max_tokens; map OpenAI-style max_completion_tokens onto it
        if (request.getMaxCompletionTokens() != null) {
            if (request.getMaxTokens() == null) {
                log.debug("Kimi only supports max_tokens, mapping max_completion_tokens to it");
                request.setMaxTokens(request.getMaxCompletionTokens());
            }
            request.setMaxCompletionTokens(null);
        }
    }

    /**
     * Apply Kimi-specific tool choice handling.
     *
     * <p>Per the official tool choice guide:
     * <ul>
     *   <li>{@code auto} / {@code none} are supported on all models</li>
     *   <li>{@code required} is only supported by {@code kimi-k3}; it is degraded to
     *       {@code auto} on the K2.x series, which rejects it</li>
     *   <li>Forcing a specific function ({@code {"type": "function", ...}}) is incompatible with
     *       thinking enabled (HTTP 400); it is degraded to {@code auto} on always-thinking
     *       models and passed through otherwise (on {@code kimi-k2.6} / {@code kimi-k2.5},
     *       disable thinking via the {@code thinking} body param to use it)</li>
     * </ul>
     *
     * <p>This method is static to allow sharing with {@link KimiMultiAgentFormatter}.
     *
     * @param request the request to apply tool choice to
     * @param toolChoice the requested tool choice
     */
    protected static void applyKimiToolChoice(OpenAIRequest request, ToolChoice toolChoice) {
        if (request.getTools() == null || request.getTools().isEmpty()) {
            return;
        }

        String model = request.getModel();

        if (toolChoice == null || toolChoice instanceof ToolChoice.Auto) {
            request.setToolChoice("auto");
        } else if (toolChoice instanceof ToolChoice.None) {
            request.setToolChoice("none");
        } else if (toolChoice instanceof ToolChoice.Required) {
            if (supportsRequiredToolChoice(model)) {
                request.setToolChoice("required");
            } else {
                log.info(
                        "Kimi model {} does not support tool_choice='required' (kimi-k3 only),"
                                + " degrading to 'auto'",
                        model);
                request.setToolChoice("auto");
            }
        } else if (toolChoice instanceof ToolChoice.Specific specific) {
            if (isAlwaysThinkingModel(model)) {
                // Specified tool_choice is incompatible with thinking enabled (HTTP 400), and
                // these models cannot disable thinking
                log.warn(
                        "Kimi model {} always runs with thinking enabled, which is incompatible"
                                + " with a specific tool_choice; degrading to 'auto'",
                        model);
                request.setToolChoice("auto");
            } else {
                Map<String, Object> namedToolChoice = new HashMap<>();
                namedToolChoice.put("type", "function");
                Map<String, Object> function = new HashMap<>();
                function.put("name", specific.toolName());
                namedToolChoice.put("function", function);
                request.setToolChoice(namedToolChoice);
                log.debug(
                        "Applied specific tool_choice '{}' for Kimi model {}; note it requires"
                                + " thinking to be disabled on thinking-capable models",
                        specific.toolName(),
                        model);
            }
        } else {
            request.setToolChoice("auto");
        }
    }
}
