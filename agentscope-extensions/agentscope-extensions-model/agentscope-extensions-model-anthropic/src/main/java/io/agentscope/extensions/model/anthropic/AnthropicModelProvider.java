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
package io.agentscope.extensions.model.anthropic;

import io.agentscope.core.model.GenerateOptions;
import io.agentscope.core.model.Model;
import io.agentscope.core.model.ModelCreationContext;
import io.agentscope.core.model.spi.ModelProvider;
import io.agentscope.core.model.transport.ProxyConfig;
import io.agentscope.extensions.model.anthropic.formatter.AnthropicBaseFormatter;
import java.util.regex.Pattern;

/** Anthropic provider registered through {@link java.util.ServiceLoader}. */
public final class AnthropicModelProvider implements ModelProvider {

    private static final String PREFIX = "anthropic:";
    private static final Pattern MODEL_ID = Pattern.compile("anthropic:.+");
    private static final String OPTION_CONTEXT_WINDOW_SIZE = "contextWindowSize";

    @Override
    public String providerId() {
        return "anthropic";
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
            throw new IllegalArgumentException("Unsupported Anthropic model id: " + modelId);
        }
        String modelName = modelId.substring(PREFIX.length());
        String apiKey = firstNonBlank(context.getApiKey(), System.getenv("ANTHROPIC_API_KEY"));
        AnthropicChatModel.Builder builder =
                AnthropicChatModel.builder().apiKey(apiKey).modelName(modelName).stream(
                        context.getStream() != null ? context.getStream() : true);
        String baseUrl = trimToNull(context.getBaseUrl());
        if (baseUrl != null) {
            builder.baseUrl(baseUrl);
        }
        applyAdvancedOptions(builder, context);
        return builder.build();
    }

    private static void applyAdvancedOptions(
            AnthropicChatModel.Builder builder, ModelCreationContext context) {
        GenerateOptions defaultOptions = context.component(GenerateOptions.class);
        if (defaultOptions != null) {
            builder.defaultOptions(defaultOptions);
        }
        ProxyConfig proxyConfig = context.component(ProxyConfig.class);
        if (proxyConfig != null) {
            builder.proxy(proxyConfig);
        }
        AnthropicBaseFormatter formatter = context.component(AnthropicBaseFormatter.class);
        if (formatter != null) {
            builder.formatter(formatter);
        }
        Integer contextWindowSize = intOption(context, OPTION_CONTEXT_WINDOW_SIZE);
        if (contextWindowSize != null) {
            builder.contextWindowSize(contextWindowSize);
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
}
