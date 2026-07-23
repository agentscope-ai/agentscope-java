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
package io.agentscope.extensions.model.anthropic.formatter;

import static com.anthropic.models.messages.ToolChoice.ofAny;
import static com.anthropic.models.messages.ToolChoice.ofAuto;
import static com.anthropic.models.messages.ToolChoice.ofNone;
import static com.anthropic.models.messages.ToolChoice.ofTool;

import com.anthropic.core.JsonValue;
import com.anthropic.core.ObjectMappers;
import com.anthropic.models.messages.MessageCreateParams;
import com.anthropic.models.messages.Tool;
import com.anthropic.models.messages.ToolChoiceAny;
import com.anthropic.models.messages.ToolChoiceAuto;
import com.anthropic.models.messages.ToolChoiceNone;
import com.anthropic.models.messages.ToolChoiceTool;
import io.agentscope.core.model.GenerateOptions;
import io.agentscope.core.model.ToolChoice;
import io.agentscope.core.model.ToolSchema;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Helper class for tool registration and option application for Anthropic API.
 */
public class AnthropicToolsHelper {

    private static final Logger log = LoggerFactory.getLogger(AnthropicToolsHelper.class);

    /**
     * Apply tools to the message create params builder.
     *
     * @param builder The message create params builder
     * @param tools List of tool schemas
     * @param options Generate options containing tool choice
     */
    public static void applyTools(
            MessageCreateParams.Builder builder, List<ToolSchema> tools, GenerateOptions options) {
        if (tools == null || tools.isEmpty()) {
            return;
        }

        // Convert and add tools
        for (ToolSchema schema : tools) {
            Tool tool =
                    Tool.builder()
                            .name(schema.getName())
                            .description(schema.getDescription())
                            .inputSchema(convertToJsonValue(schema.getParameters()))
                            .build();

            builder.addTool(tool);
        }

        // Resolve effective parallelToolCalls and toolChoice
        Boolean parallelToolCalls = options != null ? options.getParallelToolCalls() : null;
        ToolChoice toolChoice = options != null ? options.getToolChoice() : null;

        if (toolChoice != null) {
            // Explicit tool choice — pass parallelToolCalls along
            applyToolChoice(builder, toolChoice, parallelToolCalls);
        } else if (parallelToolCalls != null) {
            // No explicit tool choice, but parallelToolCalls is set —
            // Anthropic requires disable_parallel_tool_use to be inside a tool_choice object,
            // so we create an implicit Auto with disableParallelToolUse
            applyToolChoice(builder, new ToolChoice.Auto(), parallelToolCalls);
        }
    }

    /**
     * Convert tool parameters map to Anthropic JsonValue.
     */
    private static JsonValue convertToJsonValue(Object parameters) {
        try {
            return JsonValue.from(ObjectMappers.jsonMapper().valueToTree(parameters));
        } catch (Exception e) {
            log.error("Failed to convert tool parameters to JsonValue", e);
            return JsonValue.from(null);
        }
    }

    /**
     * Apply tool choice to the builder.
     *
     * <p>Anthropic has no top-level {@code parallel_tool_calls} field. Instead, parallel tool
     * use is controlled via the {@code disable_parallel_tool_use} sub-field inside the {@code
     * tool_choice} object. The mapping is {@code disable_parallel_tool_use = !parallelToolCalls}.
     * {@link ToolChoiceNone} does not support this sub-field, so it is ignored in that case.
     *
     * @param parallelToolCalls the effective {@code parallelToolCalls} option, or {@code null} to
     *     leave Anthropic's default behavior unchanged
     */
    private static void applyToolChoice(
            MessageCreateParams.Builder builder, ToolChoice toolChoice, Boolean parallelToolCalls) {
        boolean disableParallel = parallelToolCalls != null && !parallelToolCalls;

        if (toolChoice instanceof ToolChoice.Auto) {
            ToolChoiceAuto.Builder tcBuilder = ToolChoiceAuto.builder();
            if (parallelToolCalls != null) {
                tcBuilder.disableParallelToolUse(disableParallel);
            }
            builder.toolChoice(ofAuto(tcBuilder.build()));
        } else if (toolChoice instanceof ToolChoice.None) {
            // ToolChoiceNone does not support disable_parallel_tool_use
            if (parallelToolCalls != null && disableParallel) {
                log.debug(
                        "disable_parallel_tool_use is not supported with ToolChoice.None,"
                                + " ignoring");
            }
            builder.toolChoice(ofNone(ToolChoiceNone.builder().build()));
        } else if (toolChoice instanceof ToolChoice.Required) {
            // Anthropic doesn't have a direct "required" option, use "any" which forces tool
            // use
            log.warn(
                    "Anthropic API doesn't support ToolChoice.Required directly, using 'any'"
                            + " instead");
            ToolChoiceAny.Builder tcBuilder = ToolChoiceAny.builder();
            if (parallelToolCalls != null) {
                tcBuilder.disableParallelToolUse(disableParallel);
            }
            builder.toolChoice(ofAny(tcBuilder.build()));
        } else if (toolChoice instanceof ToolChoice.Specific specific) {
            ToolChoiceTool.Builder tcBuilder = ToolChoiceTool.builder().name(specific.toolName());
            if (parallelToolCalls != null) {
                tcBuilder.disableParallelToolUse(disableParallel);
            }
            builder.toolChoice(ofTool(tcBuilder.build()));
        } else {
            log.warn("Unknown tool choice type: {}", toolChoice);
        }
    }

    /**
     * Apply generation options to the builder.
     *
     * @param builder The message create params builder
     * @param options Generate options
     * @param defaultOptions Default generate options
     */
    public static void applyOptions(
            MessageCreateParams.Builder builder,
            GenerateOptions options,
            GenerateOptions defaultOptions) {
        // Temperature
        Double temperature = getOption(options, defaultOptions, GenerateOptions::getTemperature);
        if (temperature != null) {
            builder.temperature(temperature);
        }

        // Top P
        Double topP = getOption(options, defaultOptions, GenerateOptions::getTopP);
        if (topP != null) {
            builder.topP(topP);
        }

        // Top K
        Integer topK = getOption(options, defaultOptions, GenerateOptions::getTopK);
        if (topK != null) {
            builder.topK(topK.longValue());
        }

        // Max tokens
        Integer maxTokens = getOption(options, defaultOptions, GenerateOptions::getMaxTokens);
        if (maxTokens != null) {
            builder.maxTokens(maxTokens);
        }

        // Apply additional parameters (merge defaultOptions first, then options to override)
        // Apply additional headers
        applyAdditionalHeaders(builder, defaultOptions);
        applyAdditionalHeaders(builder, options);

        // Apply additional body params
        applyAdditionalBodyParams(builder, defaultOptions);
        applyAdditionalBodyParams(builder, options);

        // Apply additional query params
        applyAdditionalQueryParams(builder, defaultOptions);
        applyAdditionalQueryParams(builder, options);
    }

    private static void applyAdditionalHeaders(
            MessageCreateParams.Builder builder, GenerateOptions opts) {
        if (opts == null) return;
        Map<String, String> headers = opts.getAdditionalHeaders();
        if (headers != null && !headers.isEmpty()) {
            for (Map.Entry<String, String> entry : headers.entrySet()) {
                builder.putAdditionalHeader(entry.getKey(), entry.getValue());
            }
            log.debug("Applied {} additional headers to Anthropic request", headers.size());
        }
    }

    private static void applyAdditionalBodyParams(
            MessageCreateParams.Builder builder, GenerateOptions opts) {
        if (opts == null) return;
        Map<String, Object> params = opts.getAdditionalBodyParams();
        if (params != null && !params.isEmpty()) {
            for (Map.Entry<String, Object> entry : params.entrySet()) {
                builder.putAdditionalBodyProperty(entry.getKey(), JsonValue.from(entry.getValue()));
            }
            log.debug("Applied {} additional body params to Anthropic request", params.size());
        }
    }

    private static void applyAdditionalQueryParams(
            MessageCreateParams.Builder builder, GenerateOptions opts) {
        if (opts == null) return;
        Map<String, String> params = opts.getAdditionalQueryParams();
        if (params != null && !params.isEmpty()) {
            for (Map.Entry<String, String> entry : params.entrySet()) {
                builder.putAdditionalQueryParam(entry.getKey(), entry.getValue());
            }
            log.debug("Applied {} additional query params to Anthropic request", params.size());
        }
    }

    /**
     * Get option value, preferring specific over default.
     */
    private static <T> T getOption(
            GenerateOptions options,
            GenerateOptions defaultOptions,
            Function<GenerateOptions, T> getter) {
        if (options != null) {
            T value = getter.apply(options);
            if (value != null) {
                return value;
            }
        }
        if (defaultOptions != null) {
            return getter.apply(defaultOptions);
        }
        return null;
    }
}
