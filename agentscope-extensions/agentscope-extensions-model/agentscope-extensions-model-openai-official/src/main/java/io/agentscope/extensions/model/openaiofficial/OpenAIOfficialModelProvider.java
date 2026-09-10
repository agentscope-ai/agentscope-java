/*
 * Copyright 2024-2026 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package io.agentscope.extensions.model.openaiofficial;

import static io.agentscope.core.model.ModelProviderSupport.booleanOption;
import static io.agentscope.core.model.ModelProviderSupport.findAssignableComponent;
import static io.agentscope.core.model.ModelProviderSupport.firstNonBlank;
import static io.agentscope.core.model.ModelProviderSupport.intOption;

import io.agentscope.core.model.GenerateOptions;
import io.agentscope.core.model.Model;
import io.agentscope.core.model.ModelCreationContext;
import io.agentscope.core.model.spi.ModelProvider;
import java.util.Map;
import java.util.regex.Pattern;

public final class OpenAIOfficialModelProvider implements ModelProvider {

    private static final String PREFIX = "openai-official:";
    private static final Pattern MODEL_ID = Pattern.compile("openai-official:.+");

    @Override
    public String providerId() {
        return OpenAIOfficialConstants.PROVIDER_ID;
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
        String baseUrl = firstNonBlank(context.getBaseUrl(), System.getenv("OPENAI_BASE_URL"));
        boolean stream = context.getStream() != null ? context.getStream() : true;

        OpenAIResponsesChatModel.Builder builder =
                OpenAIResponsesChatModel.builder()
                        .apiKey(apiKey)
                        .baseUrl(baseUrl)
                        .modelName(modelName)
                        .stream(stream);

        applyAdvancedOptions(builder, context);
        return builder.build();
    }

    @SuppressWarnings("unchecked")
    private static void applyAdvancedOptions(
            OpenAIResponsesChatModel.Builder builder, ModelCreationContext context) {
        GenerateOptions generateOptions = context.component(GenerateOptions.class);
        if (generateOptions != null) {
            builder.generateOptions(generateOptions);
        }

        Boolean strictTools = booleanOption(context, "strictTools");
        if (strictTools != null) {
            builder.strictTools(strictTools);
        }

        Boolean strictJsonSchema = booleanOption(context, "strictJsonSchema");
        if (strictJsonSchema != null) {
            builder.strictJsonSchema(strictJsonSchema);
        }

        Integer contextWindowSize = intOption(context, "contextWindowSize");
        if (contextWindowSize != null) {
            builder.contextWindowSize(contextWindowSize);
        }

        ResponsesMultiAgentFormatter formatter =
                findAssignableComponent(context, ResponsesMultiAgentFormatter.class);
        if (formatter != null) {
            builder.formatter(formatter);
        }

        Object raw = context.option("additionalHeaders");
        if (raw instanceof Map<?, ?> map) {
            builder.additionalHeaders((Map<String, String>) map);
        }
    }
}
