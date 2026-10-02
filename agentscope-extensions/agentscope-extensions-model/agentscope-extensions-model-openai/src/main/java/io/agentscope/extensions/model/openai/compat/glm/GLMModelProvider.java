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

import static io.agentscope.core.model.ModelProviderSupport.firstNonBlank;
import static io.agentscope.core.model.ModelProviderSupport.trimToNull;
import static io.agentscope.extensions.model.openai.OpenAIModelProviderSupport.applyAdvancedOptions;

import io.agentscope.core.formatter.Formatter;
import io.agentscope.core.model.GenerateOptions;
import io.agentscope.core.model.Model;
import io.agentscope.core.model.ModelContextWindows;
import io.agentscope.core.model.ModelCreationContext;
import io.agentscope.core.model.spi.ModelProvider;
import io.agentscope.extensions.model.openai.OpenAIChatModel;
import java.util.Map;
import java.util.function.Function;
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

        String apiKey = resolveApiKey(context, System::getenv);
        if (apiKey == null) {
            throw new IllegalStateException(
                    "Environment variable ZAI_API_KEY/GLM_API_KEY/ZHIPUAI_API_KEY is required to"
                            + " auto-create model: "
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
                        .formatter(new GLMFormatter())
                        .nativeStructuredOutput(false)
                        .contextWindowSize(
                                ModelContextWindows.lookup(modelName, ModelContextWindows.GLM));

        GenerateOptions userOptions = context.component(GenerateOptions.class);
        GenerateOptions glmDefaults = glmDefaultOptions(context);
        applyAdvancedOptions(
                builder, context, GenerateOptions.mergeOptions(userOptions, glmDefaults));
        return builder.build();
    }

    private static GenerateOptions glmDefaultOptions(ModelCreationContext context) {
        Boolean thinkingEnabled = context.getEnableThinking();
        if (thinkingEnabled == null) {
            return null;
        }

        return GenerateOptions.builder()
                .additionalBodyParam(
                        "thinking", Map.of("type", thinkingEnabled ? "enabled" : "disabled"))
                .build();
    }

    static String resolveApiKey(ModelCreationContext context, Function<String, String> env) {
        return firstNonBlank(
                context.getApiKey(),
                env.apply("ZAI_API_KEY"),
                env.apply("GLM_API_KEY"),
                env.apply("ZHIPUAI_API_KEY"));
    }
}
