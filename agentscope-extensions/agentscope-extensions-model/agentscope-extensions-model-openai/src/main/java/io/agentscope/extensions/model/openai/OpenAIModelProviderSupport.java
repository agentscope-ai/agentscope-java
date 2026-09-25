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

import static io.agentscope.core.model.ModelProviderSupport.booleanOption;
import static io.agentscope.core.model.ModelProviderSupport.findAssignableComponent;
import static io.agentscope.core.model.ModelProviderSupport.intOption;

import io.agentscope.core.formatter.Formatter;
import io.agentscope.core.model.GenerateOptions;
import io.agentscope.core.model.ModelCreationContext;
import io.agentscope.core.model.transport.HttpTransport;
import io.agentscope.core.model.transport.ProxyConfig;
import io.agentscope.extensions.model.openai.dto.OpenAIMessage;
import io.agentscope.extensions.model.openai.dto.OpenAIRequest;
import io.agentscope.extensions.model.openai.dto.OpenAIResponse;

/** Shared helper methods for OpenAI-compatible model providers. */
public final class OpenAIModelProviderSupport {

    private static final String OPTION_CONTEXT_WINDOW_SIZE = "contextWindowSize";
    private static final String OPTION_NATIVE_STRUCTURED_OUTPUT = "nativeStructuredOutput";
    private static final String OPTION_NATIVE_STRUCTURED_OUTPUT_WITH_TOOLS =
            "nativeStructuredOutputWithTools";

    private OpenAIModelProviderSupport() {}

    public static void applyAdvancedOptions(
            OpenAIChatModel.Builder builder, ModelCreationContext context) {
        applyAdvancedOptions(builder, context, context.component(GenerateOptions.class));
    }

    @SuppressWarnings("unchecked")
    public static void applyAdvancedOptions(
            OpenAIChatModel.Builder builder,
            ModelCreationContext context,
            GenerateOptions generateOptions) {
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
                findAssignableComponent(context, Formatter.class);
        if (formatter != null) {
            builder.formatter(formatter);
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
}
