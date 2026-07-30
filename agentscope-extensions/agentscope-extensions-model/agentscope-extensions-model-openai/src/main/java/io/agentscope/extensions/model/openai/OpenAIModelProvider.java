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
package io.agentscope.extensions.model.openai;

import static io.agentscope.core.model.ModelProviderSupport.firstNonBlank;
import static io.agentscope.core.model.ModelProviderSupport.trimToNull;
import static io.agentscope.extensions.model.openai.OpenAIModelProviderSupport.applyAdvancedOptions;

import io.agentscope.core.model.Model;
import io.agentscope.core.model.ModelContextWindows;
import io.agentscope.core.model.ModelCreationContext;
import io.agentscope.core.model.spi.ModelProvider;
import io.agentscope.extensions.model.openai.formatter.OpenAIChatFormatter;
import java.util.regex.Pattern;

/** OpenAI provider registered through {@link java.util.ServiceLoader}. */
public final class OpenAIModelProvider implements ModelProvider {

    private static final String PREFIX = "openai:";
    private static final Pattern MODEL_ID = Pattern.compile("openai:.+");
    private static final String DEFAULT_BASE_URL = OpenAIClient.DEFAULT_BASE_URL_WITH_VERSION;

    @Override
    public String providerId() {
        return "openai";
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
            throw new IllegalArgumentException("Unsupported OpenAI model id: " + modelId);
        }

        String apiKey = firstNonBlank(context.getApiKey(), System.getenv("OPENAI_API_KEY"));
        if (apiKey == null) {
            throw new IllegalStateException(
                    "Environment variable OPENAI_API_KEY is required to auto-create model: "
                            + modelId);
        }
        String modelName = modelId.substring(PREFIX.length());
        String baseUrl = firstNonBlank(context.getBaseUrl(), DEFAULT_BASE_URL);
        String endpointPath = trimToNull(context.getEndpointPath());
        boolean stream = context.getStream() != null ? context.getStream() : true;

        OpenAIChatModel.Builder builder =
                OpenAIChatModel.builder().apiKey(apiKey).modelName(modelName).stream(stream)
                        .baseUrl(baseUrl)
                        .endpointPath(endpointPath)
                        .formatter(new OpenAIChatFormatter())
                        .nativeStructuredOutput(true)
                        .contextWindowSize(
                                ModelContextWindows.lookup(modelName, ModelContextWindows.OPENAI));
        applyAdvancedOptions(builder, context);
        return builder.build();
    }
}
