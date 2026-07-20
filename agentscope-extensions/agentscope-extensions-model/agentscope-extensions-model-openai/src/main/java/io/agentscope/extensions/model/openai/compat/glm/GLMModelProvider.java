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

import io.agentscope.core.formatter.Formatter;
import io.agentscope.core.model.GenerateOptions;
import io.agentscope.core.model.Model;
import io.agentscope.core.model.ModelCreationContext;
import io.agentscope.core.model.spi.ModelProvider;
import io.agentscope.core.model.transport.HttpTransport;
import io.agentscope.core.model.transport.ProxyConfig;
import io.agentscope.extensions.model.openai.OpenAIChatModel;
import io.agentscope.extensions.model.openai.dto.OpenAIMessage;
import io.agentscope.extensions.model.openai.dto.OpenAIRequest;
import io.agentscope.extensions.model.openai.dto.OpenAIResponse;
import java.util.regex.Pattern;

/**
 * Zhipu AI (Z.ai) GLM provider registered through {@link java.util.ServiceLoader}.
 *
 * <p>GLM exposes an OpenAI-compatible Chat Completions endpoint, so this provider creates
 * {@link OpenAIChatModel} instances preconfigured for GLM:
 * <ul>
 *   <li>Base URL defaults to {@code https://open.bigmodel.cn/api/paas/v4}</li>
 *   <li>Formatter defaults to {@link GLMFormatter} (a custom {@link Formatter} component in the
 *       {@link ModelCreationContext} takes precedence, e.g. {@link GLMMultiAgentFormatter})</li>
 *   <li>Native structured output defaults to disabled, because the GLM {@code response_format}
 *       only supports {@code json_object} (not {@code json_schema}); the agent falls back to the
 *       {@code generate_response} tool instead</li>
 * </ul>
 *
 * <p>The API key is taken from {@link ModelCreationContext#getApiKey()}, then from the
 * {@code GLM_API_KEY} environment variable, then from {@code ZHIPUAI_API_KEY}.
 *
 * <p>Usage:
 * <pre>{@code
 * ReActAgent agent = ReActAgent.builder()
 *     .name("assistant")
 *     .model("glm:glm-5.2") // resolved by ModelRegistry through this provider
 *     .build();
 * }</pre>
 */
public final class GLMModelProvider implements ModelProvider {

    private static final String PREFIX = "glm:";
    private static final Pattern MODEL_ID = Pattern.compile("glm:.+");
    private static final String DEFAULT_BASE_URL = "https://open.bigmodel.cn/api/paas/v4";
    private static final String OPTION_CONTEXT_WINDOW_SIZE = "contextWindowSize";
    private static final String OPTION_NATIVE_STRUCTURED_OUTPUT = "nativeStructuredOutput";
    private static final String OPTION_NATIVE_STRUCTURED_OUTPUT_WITH_TOOLS =
            "nativeStructuredOutputWithTools";

    @Override
    public String providerId() {
        return "glm";
    }

    @Override
    public boolean supports(String modelId) {
        return modelId != null && MODEL_ID.matcher(modelId).matches();
    }

    @Override
    public Model create(String modelId) {
        return create(modelId, ModelCreationContext.empty());
    }

    @Override
    public Model create(String modelId, ModelCreationContext context) {
        if (!supports(modelId)) {
            throw new IllegalArgumentException("Unsupported GLM model id: " + modelId);
        }
        String modelName = modelId.substring(PREFIX.length());
        String apiKey =
                firstNonBlank(
                        context.getApiKey(),
                        System.getenv("GLM_API_KEY"),
                        System.getenv("ZHIPUAI_API_KEY"));
        if (apiKey == null) {
            throw new IllegalStateException(
                    "Environment variable GLM_API_KEY (or ZHIPUAI_API_KEY) is required to"
                            + " auto-create model: "
                            + modelId);
        }
        String baseUrl = trimToNull(context.getBaseUrl());
        OpenAIChatModel.Builder builder =
                OpenAIChatModel.builder()
                        .apiKey(apiKey)
                        .modelName(modelName)
                        .baseUrl(baseUrl != null ? baseUrl : DEFAULT_BASE_URL)
                        .stream(context.getStream() != null ? context.getStream() : true);
        String endpointPath = trimToNull(context.getEndpointPath());
        if (endpointPath != null) {
            builder.endpointPath(endpointPath);
        }
        applyAdvancedOptions(builder, context);
        return builder.build();
    }

    @SuppressWarnings("unchecked")
    private static void applyAdvancedOptions(
            OpenAIChatModel.Builder builder, ModelCreationContext context) {
        GenerateOptions generateOptions = context.component(GenerateOptions.class);
        if (generateOptions != null) {
            builder.generateOptions(generateOptions);
        }
        HttpTransport httpTransport = context.component(HttpTransport.class);
        if (httpTransport != null) {
            builder.httpTransport(httpTransport);
        }
        ProxyConfig proxyConfig = context.component(ProxyConfig.class);
        if (proxyConfig != null) {
            builder.proxy(proxyConfig);
        }
        Formatter<OpenAIMessage, OpenAIResponse, OpenAIRequest> formatter =
                (Formatter<OpenAIMessage, OpenAIResponse, OpenAIRequest>)
                        findAssignableComponent(context, Formatter.class);
        builder.formatter(formatter != null ? formatter : new GLMFormatter());
        Integer contextWindowSize = intOption(context, OPTION_CONTEXT_WINDOW_SIZE);
        if (contextWindowSize != null) {
            builder.contextWindowSize(contextWindowSize);
        }
        // GLM response_format only supports json_object, so native structured output is
        // disabled by default; the option can explicitly re-enable it.
        Boolean nativeStructuredOutput = booleanOption(context, OPTION_NATIVE_STRUCTURED_OUTPUT);
        builder.nativeStructuredOutput(
                nativeStructuredOutput != null ? nativeStructuredOutput : false);
        Boolean nativeStructuredOutputWithTools =
                booleanOption(context, OPTION_NATIVE_STRUCTURED_OUTPUT_WITH_TOOLS);
        if (nativeStructuredOutputWithTools != null) {
            builder.nativeStructuredOutputWithTools(nativeStructuredOutputWithTools);
        }
    }

    private static String firstNonBlank(String... candidates) {
        for (String candidate : candidates) {
            String normalized = trimToNull(candidate);
            if (normalized != null) {
                return normalized;
            }
        }
        return null;
    }

    private static String trimToNull(String value) {
        if (value == null) {
            return null;
        }
        String trimmed = value.trim();
        return trimmed.isEmpty() ? null : trimmed;
    }

    private static Object findAssignableComponent(
            ModelCreationContext context, Class<?> componentType) {
        for (Object value : context.getComponents().values()) {
            if (componentType.isInstance(value)) {
                return value;
            }
        }
        return null;
    }

    private static Integer intOption(ModelCreationContext context, String key) {
        Object value = context.option(key);
        if (value == null) {
            return null;
        }
        if (value instanceof Number number) {
            return number.intValue();
        }
        throw new IllegalArgumentException(
                "ModelCreationContext option " + key + " must be a number");
    }

    private static Boolean booleanOption(ModelCreationContext context, String key) {
        Object value = context.option(key);
        if (value == null) {
            return null;
        }
        if (value instanceof Boolean bool) {
            return bool;
        }
        throw new IllegalArgumentException(
                "ModelCreationContext option " + key + " must be a boolean");
    }
}
