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
package io.agentscope.extensions.model.openai.compat.deepseek;

import static io.agentscope.core.model.ModelProviderSupport.firstNonBlank;
import static io.agentscope.core.model.ModelProviderSupport.trimToNull;
import static io.agentscope.extensions.model.openai.OpenAIModelProviderSupport.applyAdvancedOptions;

import io.agentscope.core.model.GenerateOptions;
import io.agentscope.core.model.Model;
import io.agentscope.core.model.ModelContextWindows;
import io.agentscope.core.model.ModelCreationContext;
import io.agentscope.core.model.spi.ModelProvider;
import io.agentscope.extensions.model.openai.OpenAIChatModel;
import java.util.Map;
import java.util.regex.Pattern;

/** DeepSeek provider registered through {@link java.util.ServiceLoader}. */
public final class DeepSeekModelProvider implements ModelProvider {

    private static final String PREFIX = "deepseek:";
    private static final Pattern MODEL_ID = Pattern.compile("deepseek:.+");
    public static final String DEFAULT_BASE_URL = "https://api.deepseek.com";

    @Override
    public String providerId() {
        return "deepseek";
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
            throw new IllegalArgumentException("Unsupported DeepSeek model id: " + modelId);
        }

        String apiKey = firstNonBlank(context.getApiKey(), System.getenv("DEEPSEEK_API_KEY"));
        if (apiKey == null) {
            throw new IllegalStateException(
                    "Environment variable DEEPSEEK_API_KEY is required to auto-create model: "
                            + modelId);
        }
        String modelName = trimToNull(modelId.substring(PREFIX.length()));
        String baseUrl = firstNonBlank(context.getBaseUrl(), DEFAULT_BASE_URL);
        String endpointPath = trimToNull(context.getEndpointPath());
        boolean stream = context.getStream() != null ? context.getStream() : true;

        OpenAIChatModel.Builder builder =
                OpenAIChatModel.builder().apiKey(apiKey).modelName(modelName).stream(stream)
                        .baseUrl(baseUrl)
                        .endpointPath(endpointPath)
                        .formatter(new DeepSeekFormatter())
                        .nativeStructuredOutput(false)
                        .contextWindowSize(
                                ModelContextWindows.lookup(
                                        modelName, ModelContextWindows.DEEPSEEK));

        GenerateOptions userOptions = context.component(GenerateOptions.class);
        GenerateOptions deepSeekDefaults = deepSeekDefaultOptions(context);
        applyAdvancedOptions(
                builder, context, GenerateOptions.mergeOptions(userOptions, deepSeekDefaults));
        return builder.build();
    }

    private static GenerateOptions deepSeekDefaultOptions(ModelCreationContext context) {
        Boolean thinkingEnabled = context.getEnableThinking();
        if (thinkingEnabled == null) {
            return null;
        }

        return GenerateOptions.builder()
                .additionalBodyParam(
                        "thinking", Map.of("type", thinkingEnabled ? "enabled" : "disabled"))
                .build();
    }
}
