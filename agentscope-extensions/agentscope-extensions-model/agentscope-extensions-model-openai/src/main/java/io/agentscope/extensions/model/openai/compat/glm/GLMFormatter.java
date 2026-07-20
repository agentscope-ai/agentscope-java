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
package io.agentscope.extensions.model.openai.compat.glm;

import io.agentscope.core.message.Msg;
import io.agentscope.core.model.GenerateOptions;
import io.agentscope.core.model.ToolChoice;
import io.agentscope.extensions.model.openai.dto.OpenAIMessage;
import io.agentscope.extensions.model.openai.dto.OpenAIRequest;
import io.agentscope.extensions.model.openai.formatter.OpenAIChatFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Formatter for Zhipu AI (Z.ai) GLM models (glm-5.2, glm-5.1, glm-5, glm-4.7, glm-4.6, etc.).
 *
 * <p>Adapted to the latest Zhipu open platform Chat Completions API
 * ({@code https://open.bigmodel.cn/api/paas/v4/chat/completions}):
 * <ul>
 *   <li>At least one user message is required (error 1214 otherwise)</li>
 *   <li>{@code tool_choice} only supports {@code "auto"}; other values are degraded</li>
 *   <li>Does NOT support the {@code strict} parameter in tool definitions</li>
 *   <li>Does NOT support {@code frequency_penalty} / {@code presence_penalty} /
 *       {@code thinking_budget}; they are stripped from the request</li>
 *   <li>Only supports {@code max_tokens}; {@code max_completion_tokens} is mapped to
 *       {@code max_tokens} when the latter is not set</li>
 *   <li>{@code temperature} range is [0.0, 1.0] (OpenAI allows up to 2.0) and {@code top_p}
 *       range is [0.01, 1.0]; out-of-range values are clamped</li>
 *   <li>{@code temperature = 0} is not applicable on the GLM endpoint and is translated to
 *       {@code do_sample = false} (deterministic decoding) per the official OpenAI
 *       compatibility guide</li>
 * </ul>
 *
 * <p>Thinking mode: GLM-4.5 and later models accept a {@code thinking} object (e.g.
 * {@code {"type": "enabled"}}). GLM-4.7 and GLM-5 series enable thinking by default. It can be
 * controlled through {@code GenerateOptions.additionalBodyParam("thinking", Map.of("type",
 * "disabled"))}, which is passed through to the request body as-is. GLM-5.2 additionally supports
 * the standard {@code reasoning_effort} option ({@code GenerateOptions.reasoningEffort}, default
 * {@code max}) and streaming tool-call arguments via
 * {@code GenerateOptions.additionalBodyParam("tool_stream", true)}.
 *
 * <p>Usage:
 * <pre>{@code
 * OpenAIChatModel.builder()
 *     .formatter(new GLMFormatter())
 *     .modelName("glm-5.2")
 *     .baseUrl("https://open.bigmodel.cn/api/paas/v4")
 *     .apiKey(apiKey)
 *     .build();
 * }</pre>
 *
 * @see <a href="https://docs.bigmodel.cn/cn/api/introduction">Zhipu AI API reference</a>
 * @see <a href="https://docs.bigmodel.cn/cn/guide/capabilities/function-calling">Zhipu AI tool
 *     calling guide</a>
 * @see <a href="https://docs.bigmodel.cn/cn/guide/develop/openai/introduction">Zhipu AI OpenAI
 *     compatibility guide</a>
 */
public class GLMFormatter extends OpenAIChatFormatter {

    private static final Logger log = LoggerFactory.getLogger(GLMFormatter.class);

    public GLMFormatter() {
        super();
    }

    @Override
    protected List<OpenAIMessage> doFormat(List<Msg> msgs) {
        List<OpenAIMessage> messages = super.doFormat(msgs);
        return ensureUserMessage(messages);
    }

    @Override
    protected boolean supportsStrict() {
        return false;
    }

    @Override
    public void applyOptions(
            OpenAIRequest request, GenerateOptions options, GenerateOptions defaultOptions) {
        super.applyOptions(request, options, defaultOptions);
        stripUnsupportedSamplingParams(request);
    }

    @Override
    public void applyToolChoice(OpenAIRequest request, ToolChoice toolChoice) {
        applyGLMToolChoice(request, toolChoice);
    }

    /**
     * Ensure at least one user message exists in the conversation.
     *
     * <p>GLM API requires at least one user message. If no user message exists, a placeholder is
     * added at the end (error 1214 otherwise).
     *
     * <p>This method is static to allow sharing with {@link GLMMultiAgentFormatter}.
     *
     * @param messages the messages to check
     * @return messages with a user message ensured
     */
    protected static List<OpenAIMessage> ensureUserMessage(List<OpenAIMessage> messages) {
        boolean hasUserMessage = messages.stream().anyMatch(msg -> "user".equals(msg.getRole()));

        if (hasUserMessage) {
            return messages;
        }

        // GLM API returns error 1214 if there's no user message
        log.debug("GLM: adding placeholder user message to satisfy API requirement");
        List<OpenAIMessage> result = new ArrayList<>(messages);
        result.add(OpenAIMessage.builder().role("user").content("").build());
        return result;
    }

    /**
     * Apply GLM-specific tool choice handling.
     *
     * <p>Per the latest Zhipu API reference, {@code tool_choice} defaults to and only supports
     * {@code "auto"}. All other options are degraded to {@code "auto"}.
     *
     * <p>This method is static to allow sharing with {@link GLMMultiAgentFormatter}.
     *
     * @param request the request to apply tool choice to
     * @param toolChoice the requested tool choice
     */
    protected static void applyGLMToolChoice(OpenAIRequest request, ToolChoice toolChoice) {
        if (request.getTools() == null || request.getTools().isEmpty()) {
            return;
        }

        if (toolChoice != null && !(toolChoice instanceof ToolChoice.Auto)) {
            log.info(
                    "GLM only supports tool_choice='auto', degrading from '{}'",
                    toolChoice.getClass().getSimpleName());
        }

        request.setToolChoice("auto");
    }

    /**
     * Sanitize the request according to the GLM Chat Completions API reference.
     *
     * <p>Applied adaptations:
     * <ul>
     *   <li>{@code frequency_penalty} / {@code presence_penalty} / {@code thinking_budget} are
     *       not supported and removed</li>
     *   <li>{@code max_completion_tokens} is not supported; it is mapped to {@code max_tokens}
     *       when {@code max_tokens} is not set</li>
     *   <li>{@code temperature} is clamped to [0.0, 1.0] and {@code top_p} to [0.01, 1.0]</li>
     *   <li>{@code temperature = 0} is translated to {@code do_sample = false}, as documented in
     *       the official OpenAI compatibility guide</li>
     * </ul>
     *
     * <p>This method is static to allow sharing with {@link GLMMultiAgentFormatter}.
     *
     * @param request the request to sanitize
     */
    protected static void stripUnsupportedSamplingParams(OpenAIRequest request) {
        if (request.getFrequencyPenalty() != null) {
            log.debug("GLM does not support frequency_penalty, removing it from the request");
            request.setFrequencyPenalty(null);
        }
        if (request.getPresencePenalty() != null) {
            log.debug("GLM does not support presence_penalty, removing it from the request");
            request.setPresencePenalty(null);
        }
        if (request.getThinkingBudget() != null) {
            log.debug(
                    "GLM does not support thinking_budget, removing it from the request; use the"
                        + " 'thinking' body param or reasoning_effort (GLM-5.2 and later) instead");
            request.setThinkingBudget(null);
        }

        // GLM only supports max_tokens; map OpenAI-style max_completion_tokens onto it
        if (request.getMaxCompletionTokens() != null) {
            if (request.getMaxTokens() == null) {
                log.debug("GLM only supports max_tokens, mapping max_completion_tokens to it");
                request.setMaxTokens(request.getMaxCompletionTokens());
            }
            request.setMaxCompletionTokens(null);
        }

        normalizeSamplingRanges(request);
    }

    /**
     * Clamp sampling parameters to the ranges accepted by the GLM API and translate
     * {@code temperature = 0} to {@code do_sample = false}.
     *
     * @param request the request to normalize
     */
    private static void normalizeSamplingRanges(OpenAIRequest request) {
        Double temperature = request.getTemperature();
        if (temperature != null) {
            if (temperature == 0.0) {
                // Per the official OpenAI compatibility guide, temperature = 0 is not
                // applicable on the GLM endpoint; deterministic decoding uses do_sample=false
                log.debug("GLM: translating temperature=0 to do_sample=false");
                request.setTemperature(null);
                Map<String, Object> extraParams = request.getExtraParams();
                if (extraParams == null || !extraParams.containsKey("do_sample")) {
                    request.addExtraParam("do_sample", false);
                }
            } else if (temperature > 1.0) {
                log.warn("GLM temperature range is [0.0, 1.0], clamping {} to 1.0", temperature);
                request.setTemperature(1.0);
            }
        }

        Double topP = request.getTopP();
        if (topP != null) {
            if (topP < 0.01) {
                log.warn("GLM top_p range is [0.01, 1.0], clamping {} to 0.01", topP);
                request.setTopP(0.01);
            } else if (topP > 1.0) {
                log.warn("GLM top_p range is [0.01, 1.0], clamping {} to 1.0", topP);
                request.setTopP(1.0);
            }
        }
    }
}
