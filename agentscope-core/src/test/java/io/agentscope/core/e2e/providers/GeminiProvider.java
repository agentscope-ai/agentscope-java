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

/**
 * Native provider for Google Gemini API.
 *
 * <p>
 * This provider directly implements ModelProvider interface similar to
 * OpenAINativeProvider,
 * supporting various Gemini models including Gemini 2.5 Flash and Gemini 3
 * series with thinking
 * capabilities.
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

    private final boolean supportsThinking;

    public GeminiProvider(String modelName, boolean multiAgentFormatter, boolean supportsThinking) {
        super(API_KEY_ENV, modelName, multiAgentFormatter);
        this.supportsThinking = supportsThinking;
    }

    public GeminiProvider(String modelName, boolean multiAgentFormatter) {
        this(modelName, multiAgentFormatter, false);
    }

    @Override
    public ReActAgent createAgent(String name, Toolkit toolkit, String sysPrompt) {
        ReActAgent.Builder builder = createAgentBuilder(name, toolkit);
        if (sysPrompt != null && !sysPrompt.isEmpty()) {
            builder.sysPrompt(sysPrompt);
        }
        return builder.build();
    }

    @Override
    protected ReActAgent.Builder doCreateAgentBuilder(String name, Toolkit toolkit, String apiKey) {
        String baseUrl = System.getenv("GOOGLE_API_BASE_URL"); // Optional custom endpoint

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
            builder.baseUrl(baseUrl);
        }

        return ReActAgent.builder()
                .name(name)
                .model(builder.build())
                .toolkit(toolkit)
                .memory(new InMemoryMemory())
                .maxIters(3); // Prevent infinite loops in multi-agent scenarios
    }

    @Override
    public String getProviderName() {
        return "Gemini-Native";
    }

    @Override
    public boolean supportsThinking() {
        return supportsThinking;
    }

    // ==========================================================================
    // Provider Instances
    // ==========================================================================

    /** Gemini 2.5 Flash - Fast multimodal model. */
    public static class Gemini25Flash extends GeminiProvider {
        public Gemini25Flash() {
            super("gemini-2.5-flash", false, true);
        }

        @Override
        public String getProviderName() {
            return "Gemini";
        }
    }

    /** Gemini 2.5 Flash with multi-agent formatter. */
    public static class Gemini25FlashMultiAgent extends GeminiProvider {
        public Gemini25FlashMultiAgent() {
            super("gemini-2.5-flash", true, true);
        }

        @Override
        public String getProviderName() {
            return "Gemini (Multi-Agent)";
        }
    }

    /** Gemini 3 Pro Preview - Advanced thinking model. */
    public static class Gemini3Pro extends GeminiProvider {
        public Gemini3Pro() {
            super("gemini-3-pro-preview", false, true);
        }

        @Override
        public String getProviderName() {
            return "Gemini";
        }
    }

    /** Gemini 3 Pro Preview with multi-agent formatter. */
    public static class Gemini3ProMultiAgent extends GeminiProvider {
        public Gemini3ProMultiAgent() {
            super("gemini-3-pro-preview", true, true);
        }

        @Override
        public String getProviderName() {
            return "Gemini (Multi-Agent)";
        }
    }

    /** Gemini 3 Flash Preview - Fast thinking model. */
    public static class Gemini3Flash extends GeminiProvider {
        public Gemini3Flash() {
            super("gemini-3-flash-preview", false, true);
        }

        @Override
        public String getProviderName() {
            return "Gemini";
        }
    }

    /** Gemini 3 Flash Preview with multi-agent formatter. */
    public static class Gemini3FlashMultiAgent extends GeminiProvider {
        public Gemini3FlashMultiAgent() {
            super("gemini-3-flash-preview", true, true);
        }

        @Override
        public String getProviderName() {
            return "Gemini (Multi-Agent)";
        }
    }

    /** Gemini 1.5 Pro - Stable production model. */
    public static class Gemini15Pro extends GeminiProvider {
        public Gemini15Pro() {
            super("gemini-1.5-pro", false, false);
        }

        @Override
        public String getProviderName() {
            return "Gemini";
        }
    }

    /** Gemini 1.5 Pro with multi-agent formatter. */
    public static class Gemini15ProMultiAgent extends GeminiProvider {
        public Gemini15ProMultiAgent() {
            super("gemini-1.5-pro", true, false);
        }

        @Override
        public String getProviderName() {
            return "Gemini (Multi-Agent)";
        }
    }

    /** Gemini 1.5 Flash - Fast production model. */
    public static class Gemini15Flash extends GeminiProvider {
        public Gemini15Flash() {
            super("gemini-1.5-flash", false, false);
        }

        @Override
        public String getProviderName() {
            return "Gemini";
        }
    }

    /** Gemini 1.5 Flash with multi-agent formatter. */
    public static class Gemini15FlashMultiAgent extends GeminiProvider {
        public Gemini15FlashMultiAgent() {
            super("gemini-1.5-flash", true, false);
        }

        @Override
        public String getProviderName() {
            return "Gemini (Multi-Agent)";
        }
    }
}
