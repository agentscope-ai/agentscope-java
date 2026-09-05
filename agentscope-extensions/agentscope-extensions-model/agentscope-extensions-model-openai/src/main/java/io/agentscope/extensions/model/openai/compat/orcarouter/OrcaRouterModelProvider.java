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
package io.agentscope.extensions.model.openai.compat.orcarouter;

import static io.agentscope.core.model.ModelProviderSupport.firstNonBlank;
import static io.agentscope.core.model.ModelProviderSupport.trimToNull;
import static io.agentscope.extensions.model.openai.OpenAIModelProviderSupport.applyAdvancedOptions;

import io.agentscope.core.formatter.Formatter;
import io.agentscope.core.model.GenerateOptions;
import io.agentscope.core.model.Model;
import io.agentscope.core.model.ModelCreationContext;
import io.agentscope.core.model.spi.ModelProvider;
import io.agentscope.extensions.model.openai.OpenAIChatModel;
import io.agentscope.extensions.model.openai.formatter.OpenAIChatFormatter;
import java.util.regex.Pattern;

/**
 * OrcaRouter provider registered through {@link java.util.ServiceLoader}.
 *
 * <p>OrcaRouter is an OpenAI-compatible AI gateway that exposes a provider/model namespace
 * across many models — the same Chat Completions shape as OpenRouter — so this provider creates
 * {@link OpenAIChatModel} instances preconfigured for OrcaRouter:
 * <ul>
 *   <li>Base URL defaults to {@code https://api.orcarouter.ai/v1}</li>
 *   <li>Formatter defaults to {@link OpenAIChatFormatter}; a custom {@link Formatter} component
 *       in the {@link ModelCreationContext} takes precedence</li>
 *   <li>Native structured output defaults to disabled, because OrcaRouter routes to a mix of
 *       upstream providers whose {@code response_format} support varies; the agent falls back to
 *       the {@code generate_response} tool instead</li>
 * </ul>
 *
 * <p>The API key is taken from {@link ModelCreationContext#getApiKey()}, then from the
 * {@code ORCAROUTER_API_KEY} environment variable.
 *
 * <p>Usage:
 * <pre>{@code
 * ReActAgent agent = ReActAgent.builder()
 *     .name("assistant")
 *     .model("orcarouter:openai/gpt-5.5") // resolved by ModelRegistry through this provider
 *     .build();
 * }</pre>
 */
public final class OrcaRouterModelProvider implements ModelProvider {

    private static final String PREFIX = "orcarouter:";
    private static final Pattern MODEL_ID = Pattern.compile("orcarouter:.+");
    private static final String DEFAULT_BASE_URL = "https://api.orcarouter.ai/v1";

    @Override
    public String providerId() {
        return "orcarouter";
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
            throw new IllegalArgumentException("Unsupported OrcaRouter model id: " + modelId);
        }

        String apiKey = firstNonBlank(context.getApiKey(), System.getenv("ORCAROUTER_API_KEY"));
        if (apiKey == null) {
            throw new IllegalStateException(
                    "Environment variable ORCAROUTER_API_KEY is required to auto-create model: "
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
                        .formatter(new OpenAIChatFormatter())
                        .nativeStructuredOutput(false);

        GenerateOptions userOptions = context.component(GenerateOptions.class);
        applyAdvancedOptions(builder, context, userOptions);
        return builder.build();
    }
}
