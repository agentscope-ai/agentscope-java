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

    public ReActAgent createAgent(String name, Toolkit toolkit) {
        String apiKey = System.getenv("GOOGLE_API_KEY");
        if (apiKey == null || apiKey.isEmpty()) {
            throw new IllegalStateException("GOOGLE_API_KEY environment variable is required");
        }

        GeminiChatModel.Builder builder =
                GeminiChatModel.builder()
                        .apiKey(apiKey)
                        .modelName(getModelName())
                        .formatter(
                                isMultiAgentFormatter()
                                        ? new GeminiMultiAgentFormatter()
                                        : new GeminiChatFormatter())
                        .defaultOptions(GenerateOptions.builder().build());

        return ReActAgent.builder()
                .name(name)
                .model(builder.build())
                .toolkit(toolkit)
                .memory(new InMemoryMemory());
    }

    @Override
    public ReActAgent createAgent(String name, Toolkit toolkit, String sysPrompt) {
        String apiKey = System.getenv("GOOGLE_API_KEY");
        if (apiKey == null || apiKey.isEmpty()) {
            throw new IllegalStateException("GOOGLE_API_KEY environment variable is required");
        }

        GeminiChatModel.Builder builder =
                GeminiChatModel.builder()
                        .apiKey(apiKey)
                        .modelName(modelName)
                        .formatter(
                                multiAgentFormatter
                                        ? new GeminiMultiAgentFormatter()
                                        : new GeminiChatFormatter())
                        .defaultOptions(GenerateOptions.builder().build());

        return ReActAgent.builder()
                .name(name)
                .sysPrompt(sysPrompt)
                .model(builder.build())
                .toolkit(toolkit)
                .memory(new InMemoryMemory())
                .build();
    }

    @Override
    public String getProviderName() {
        return "Google";
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
    public static class Gemini3ProGemini extends GeminiProvider {
        public Gemini3ProGemini() {
            super("gemini-3-pro-preview", false);
        }

        @Override
        public String getProviderName() {
            return "Google";
        }

        @Override
        public boolean supportsThinking() {
            return true; // Gemini 3 Pro supports thinking
        }
    }

    public static class Gemini3ProMultiAgentGemini extends GeminiProvider {
        public Gemini3ProMultiAgentGemini() {
            super("gemini-3-pro-preview", true);
        }

        @Override
        public String getProviderName() {
            return "Google";
        }

        @Override
        public boolean supportsThinking() {
            return true; // Gemini 3 Pro supports thinking
        }
    }

    public static class Gemini3FlashMultiAgentGemini extends GeminiProvider {
        public Gemini3FlashMultiAgentGemini() {
            super("gemini-3-flash-preview", true);
        }

        @Override
        public String getProviderName() {
            return "Google";
        }

        @Override
        public boolean supportsThinking() {
            return true; // Gemini 3 flush supports thinking
        }
    }

    public static class Gemini3FlashGemini extends GeminiProvider {
        public Gemini3FlashGemini() {
            super("gemini-3-flash-preview", false);
        }

        @Override
        public String getProviderName() {
            return "Google";
        }

        @Override
        public boolean supportsThinking() {
            return true; // Gemini 3 Flash supports thinking
        }
    }

    public static class Gemini25FlashGemini extends GeminiProvider {
        public Gemini25FlashGemini() {
            super("gemini-2.5-flash", false);
        }

        @Override
        public String getProviderName() {
            return "Google";
        }

        @Override
        public boolean supportsThinking() {
            return true; // Gemini 2.5 Flash supports thinking
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
