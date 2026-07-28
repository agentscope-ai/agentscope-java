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
package io.agentscope.extensions.model.gemini;

import com.google.auth.oauth2.GoogleCredentials;
import com.google.genai.types.ClientOptions;
import com.google.genai.types.Content;
import com.google.genai.types.GenerateContentConfig;
import com.google.genai.types.GenerateContentResponse;
import com.google.genai.types.HttpOptions;
import io.agentscope.core.formatter.Formatter;
import io.agentscope.core.model.GenerateOptions;
import io.agentscope.core.model.Model;
import io.agentscope.core.model.ModelCreationContext;
import io.agentscope.core.model.spi.ModelProvider;
import io.agentscope.core.model.transport.ProxyConfig;
import java.util.regex.Pattern;

/** Gemini provider registered through {@link java.util.ServiceLoader}. */
public final class GeminiModelProvider implements ModelProvider {

    private static final String PREFIX = "gemini:";
    private static final Pattern MODEL_ID = Pattern.compile("gemini:.+");
    private static final String OPTION_CONTEXT_WINDOW_SIZE = "contextWindowSize";
    private static final String OPTION_PROJECT = "project";
    private static final String OPTION_LOCATION = "location";
    private static final String OPTION_VERTEX_AI = "vertexAI";

    @Override
    public String providerId() {
        return "gemini";
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
            throw new IllegalArgumentException("Unsupported Gemini model id: " + modelId);
        }
        String modelName = modelId.substring(PREFIX.length());
        GeminiChatModel.Builder builder =
                GeminiChatModel.builder()
                        .modelName(modelName)
                        .streamEnabled(context.getStream() != null ? context.getStream() : true);

        // true to use Vertex AI, false/null for Gemini API
        Boolean vertexAI = booleanOption(context, OPTION_VERTEX_AI);
        if (Boolean.TRUE.equals(vertexAI)) {
            String project = stringOption(context, OPTION_PROJECT);
            if (project == null) {
                throw new IllegalStateException(
                        "ModelCreationContext option project is required to auto-create Vertex AI"
                                + " model: "
                                + modelId);
            }
            builder.project(project)
                    .location(stringOption(context, OPTION_LOCATION))
                    .vertexAI(true);

            // Optional Vertex AI credential override. If omitted, the Google GenAI SDK falls back
            // to Application Default Credentials.
            GoogleCredentials credentials = context.component(GoogleCredentials.class);
            if (credentials != null) {
                builder.credentials(credentials);
            }
        } else {
            String apiKey = firstNonBlank(context.getApiKey(), System.getenv("GEMINI_API_KEY"));
            if (apiKey == null) {
                throw new IllegalStateException(
                        "Environment variable GEMINI_API_KEY is required to auto-create model: "
                                + modelId);
            }
            builder.apiKey(apiKey);
            if (Boolean.FALSE.equals(vertexAI)) {
                builder.vertexAI(false);
            }
        }
        String baseUrl = trimToNull(context.getBaseUrl());
        if (baseUrl != null) {
            builder.baseUrl(baseUrl);
        }
        applyAdvancedOptions(builder, context);
        return builder.build();
    }

    @SuppressWarnings("unchecked")
    private static void applyAdvancedOptions(
            GeminiChatModel.Builder builder, ModelCreationContext context) {
        HttpOptions httpOptions = context.component(HttpOptions.class);
        if (httpOptions != null) {
            builder.httpOptions(httpOptions);
        }
        ClientOptions clientOptions = context.component(ClientOptions.class);
        if (clientOptions != null) {
            builder.clientOptions(clientOptions);
        }
        GenerateOptions defaultOptions = context.component(GenerateOptions.class);
        if (defaultOptions != null) {
            builder.defaultOptions(defaultOptions);
        }
        ProxyConfig proxyConfig = context.component(ProxyConfig.class);
        if (proxyConfig != null) {
            builder.proxy(proxyConfig);
        }
        Formatter<Content, GenerateContentResponse, GenerateContentConfig.Builder> formatter =
                (Formatter<Content, GenerateContentResponse, GenerateContentConfig.Builder>)
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

    private static String stringOption(ModelCreationContext context, String key) {
        Object value = context.option(key);
        if (value == null) {
            return null;
        }
        if (value instanceof String text) {
            return trimToNull(text);
        }
        throw new IllegalArgumentException(
                "ModelCreationContext option " + key + " must be a string");
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
