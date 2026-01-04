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
package io.agentscope.core.e2e.providers;

import com.google.genai.types.HttpOptions;
import io.agentscope.core.ReActAgent;
import io.agentscope.core.formatter.gemini.GeminiChatFormatter;
import io.agentscope.core.formatter.gemini.GeminiMultiAgentFormatter;
import io.agentscope.core.memory.InMemoryMemory;
import io.agentscope.core.model.GeminiChatModel;
import io.agentscope.core.model.GenerateOptions;
import io.agentscope.core.tool.Toolkit;
import java.util.HashSet;
import java.util.Set;

/**
 * Provider for Google Gemini API.
 *
 * <p>Supports Gemini 2.5 Flash and other Gemini models with multimodal capabilities.
 */
@ModelCapabilities({
    ModelCapability.BASIC,
    ModelCapability.TOOL_CALLING,
    ModelCapability.IMAGE,
    ModelCapability.AUDIO,
    ModelCapability.VIDEO,
    ModelCapability.THINKING
})
public class GeminiProvider extends BaseModelProvider {

    private static final String API_KEY_ENV = "GOOGLE_API_KEY";
    private static final String BASE_URL_ENV = "GOOGLE_API_BASE_URL";

    public GeminiProvider(String modelName, boolean multiAgentFormatter) {
        super(API_KEY_ENV, modelName, multiAgentFormatter);
    }

    @Override
    protected ReActAgent.Builder doCreateAgentBuilder(String name, Toolkit toolkit, String apiKey) {
        String baseUrl = System.getenv(BASE_URL_ENV);

        GeminiChatModel.Builder builder =
                GeminiChatModel.builder()
                        .apiKey(apiKey)
                        .modelName(getModelName())
                        .formatter(
                                isMultiAgentFormatter()
                                        ? new GeminiMultiAgentFormatter()
                                        : new GeminiChatFormatter())
                        .defaultOptions(GenerateOptions.builder().build());

        if (baseUrl != null && !baseUrl.isEmpty()) {
            builder.httpOptions(HttpOptions.builder().baseUrl(baseUrl).build());
        }

        return ReActAgent.builder()
                .name(name)
                .model(builder.build())
                .toolkit(toolkit)
                .memory(new InMemoryMemory());
    }

    @Override
    public String getProviderName() {
        return "Gemini";
    }

    @Override
    public Set<ModelCapability> getCapabilities() {
        Set<ModelCapability> caps = new HashSet<>(super.getCapabilities());
        if (isMultiAgentFormatter()) {
            caps.add(ModelCapability.MULTI_AGENT_FORMATTER);
        }
        return caps;
    }

    // ==========================================================================
    // Provider Instances
    // ==========================================================================

    /** Gemini 2.5 Flash - Fast multimodal model. */
    @ModelCapabilities({
        ModelCapability.BASIC,
        ModelCapability.TOOL_CALLING,
        ModelCapability.IMAGE,
        ModelCapability.AUDIO,
        ModelCapability.VIDEO,
        ModelCapability.THINKING
    })
    public static class Gemini25FlashGemini extends GeminiProvider {
        public Gemini25FlashGemini() {
            super("gemini-2.5-flash", false);
        }

        @Override
        public String getProviderName() {
            return "Google";
        }
    }

    /** Gemini 2.5 Flash with multi-agent formatter. */
    @ModelCapabilities({
        ModelCapability.BASIC,
        ModelCapability.TOOL_CALLING,
        ModelCapability.IMAGE,
        ModelCapability.AUDIO,
        ModelCapability.VIDEO,
        ModelCapability.THINKING,
        ModelCapability.MULTI_AGENT_FORMATTER
    })
    public static class Gemini25FlashMultiAgentGemini extends GeminiProvider {
        public Gemini25FlashMultiAgentGemini() {
            super("gemini-2.5-flash", true);
        }

        @Override
        public String getProviderName() {
            return "Google (Multi-Agent)";
        }
    }
}
