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
package io.agentscope.extensions.model.dashscope;

import io.agentscope.core.formatter.Formatter;
import io.agentscope.core.model.GenerateOptions;
import io.agentscope.core.model.Model;
import io.agentscope.core.model.ModelCreationContext;
import io.agentscope.core.model.spi.ModelProvider;
import io.agentscope.core.model.transport.HttpTransport;
import io.agentscope.core.model.transport.ProxyConfig;
import io.agentscope.extensions.model.dashscope.dto.DashScopeMessage;
import io.agentscope.extensions.model.dashscope.dto.DashScopeRequest;
import io.agentscope.extensions.model.dashscope.dto.DashScopeResponse;
import java.util.regex.Pattern;

/** DashScope provider registered through {@link java.util.ServiceLoader}. */
public final class DashScopeModelProvider implements ModelProvider {

    private static final String PREFIX = "dashscope:";
    private static final Pattern DASH_SCOPE_MODEL_ID = Pattern.compile("dashscope:.+");
    private static final Pattern QWEN_SHORT_MODEL_ID = Pattern.compile("qwen.+");
    private static final String OPTION_CONTEXT_WINDOW_SIZE = "contextWindowSize";
    private static final String OPTION_ENABLE_ENCRYPT = "enableEncrypt";
    private static final String OPTION_ENABLE_SEARCH = "enableSearch";
    private static final String OPTION_ENDPOINT_TYPE = "endpointType";
    private static final String OPTION_NATIVE_STRUCTURED_OUTPUT = "nativeStructuredOutput";
    private static final String OPTION_NATIVE_STRUCTURED_OUTPUT_WITH_TOOLS =
            "nativeStructuredOutputWithTools";

    @Override
    public String providerId() {
        return "dashscope";
    }

    @Override
    public boolean supports(String modelId) {
        return modelId != null
                && (DASH_SCOPE_MODEL_ID.matcher(modelId).matches()
                        || QWEN_SHORT_MODEL_ID.matcher(modelId).matches());
    }

    @Override
    public Model create(String modelId) {
        return create(modelId, ModelCreationContext.empty());
    }

    @Override
    public Model create(String modelId, ModelCreationContext context) {
        if (!supports(modelId)) {
            throw new IllegalArgumentException("Unsupported DashScope model id: " + modelId);
        }
        String modelName =
                modelId.startsWith(PREFIX) ? modelId.substring(PREFIX.length()) : modelId;
        String apiKey = firstNonBlank(context.getApiKey(), System.getenv("DASHSCOPE_API_KEY"));
        if (apiKey == null) {
            throw new IllegalStateException(
                    "Environment variable DASHSCOPE_API_KEY is required to auto-create model: "
                            + modelId);
        }
        DashScopeChatModel.Builder builder =
                DashScopeChatModel.builder().apiKey(apiKey).modelName(modelName).stream(
                        context.getStream() != null ? context.getStream() : true);
        String baseUrl = trimToNull(context.getBaseUrl());
        if (baseUrl != null) {
            builder.baseUrl(baseUrl);
        }
        if (context.getEnableThinking() != null) {
            builder.enableThinking(context.getEnableThinking());
        }
        applyAdvancedOptions(builder, context);
        return builder.build();
    }

    @SuppressWarnings("unchecked")
    private static void applyAdvancedOptions(
            DashScopeChatModel.Builder builder, ModelCreationContext context) {
        GenerateOptions defaultOptions = context.component(GenerateOptions.class);
        if (defaultOptions != null) {
            builder.defaultOptions(defaultOptions);
        }
        HttpTransport httpTransport = context.component(HttpTransport.class);
        if (httpTransport != null) {
            builder.httpTransport(httpTransport);
        }
        ProxyConfig proxyConfig = context.component(ProxyConfig.class);
        if (proxyConfig != null) {
            builder.proxy(proxyConfig);
        }
        Formatter<DashScopeMessage, DashScopeResponse, DashScopeRequest> formatter =
                (Formatter<DashScopeMessage, DashScopeResponse, DashScopeRequest>)
                        findAssignableComponent(context, Formatter.class);
        if (formatter != null) {
            builder.formatter(formatter);
        }
        Boolean enableSearch = booleanOption(context, OPTION_ENABLE_SEARCH);
        if (enableSearch != null) {
            builder.enableSearch(enableSearch);
        }
        EndpointType endpointType = endpointTypeOption(context, OPTION_ENDPOINT_TYPE);
        if (endpointType != null) {
            builder.endpointType(endpointType);
        }
        Boolean enableEncrypt = booleanOption(context, OPTION_ENABLE_ENCRYPT);
        if (enableEncrypt != null) {
            builder.enableEncrypt(enableEncrypt);
        }
        Integer contextWindowSize = intOption(context, OPTION_CONTEXT_WINDOW_SIZE);
        if (contextWindowSize != null) {
            builder.contextWindowSize(contextWindowSize);
        }
        Boolean nativeStructuredOutput = booleanOption(context, OPTION_NATIVE_STRUCTURED_OUTPUT);
        if (nativeStructuredOutput != null) {
            builder.nativeStructuredOutput(nativeStructuredOutput);
        }
        Boolean nativeStructuredOutputWithTools =
                booleanOption(context, OPTION_NATIVE_STRUCTURED_OUTPUT_WITH_TOOLS);
        if (nativeStructuredOutputWithTools != null) {
            builder.nativeStructuredOutputWithTools(nativeStructuredOutputWithTools);
        }
    }

    private static String firstNonBlank(String preferred, String fallback) {
        String normalized = trimToNull(preferred);
        return normalized != null ? normalized : trimToNull(fallback);
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

    private static EndpointType endpointTypeOption(ModelCreationContext context, String key) {
        Object value = context.option(key);
        if (value == null) {
            return null;
        }
        if (value instanceof EndpointType endpointType) {
            return endpointType;
        }
        if (value instanceof String text) {
            return EndpointType.valueOf(text.trim());
        }
        throw new IllegalArgumentException(
                "ModelCreationContext option " + key + " must be an EndpointType or string");
    }
}
