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
package io.agentscope.extensions.model.ollama;

import io.agentscope.core.formatter.Formatter;
import io.agentscope.core.model.Model;
import io.agentscope.core.model.ModelCreationContext;
import io.agentscope.core.model.spi.ModelProvider;
import io.agentscope.core.model.transport.HttpTransport;
import io.agentscope.core.model.transport.ProxyConfig;
import io.agentscope.extensions.model.ollama.dto.OllamaMessage;
import io.agentscope.extensions.model.ollama.dto.OllamaRequest;
import io.agentscope.extensions.model.ollama.dto.OllamaResponse;
import io.agentscope.extensions.model.ollama.options.OllamaOptions;
import java.util.regex.Pattern;

/** Ollama provider registered through {@link java.util.ServiceLoader}. */
public final class OllamaModelProvider implements ModelProvider {

    private static final String PREFIX = "ollama:";
    private static final Pattern MODEL_ID = Pattern.compile("ollama:.+");
    private static final String OPTION_CONTEXT_WINDOW_SIZE = "contextWindowSize";

    @Override
    public String providerId() {
        return "ollama";
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
            throw new IllegalArgumentException("Unsupported Ollama model id: " + modelId);
        }
        String modelName = modelId.substring(PREFIX.length());
        String baseUrl = firstNonBlank(context.getBaseUrl(), System.getenv("OLLAMA_BASE_URL"));
        if (baseUrl == null) {
            baseUrl = OllamaHttpClient.DEFAULT_BASE_URL;
        }
        OllamaChatModel.Builder builder =
                OllamaChatModel.builder().modelName(modelName).baseUrl(baseUrl);
        applyAdvancedOptions(builder, context);
        return builder.build();
    }

    @SuppressWarnings("unchecked")
    private static void applyAdvancedOptions(
            OllamaChatModel.Builder builder, ModelCreationContext context) {
        OllamaOptions defaultOptions = context.component(OllamaOptions.class);
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
        Formatter<OllamaMessage, OllamaResponse, OllamaRequest> formatter =
                (Formatter<OllamaMessage, OllamaResponse, OllamaRequest>)
                        findAssignableComponent(context, Formatter.class);
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
}
