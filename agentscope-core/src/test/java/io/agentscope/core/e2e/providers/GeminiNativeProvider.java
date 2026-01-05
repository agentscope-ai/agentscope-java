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
import io.agentscope.core.e2e.ProviderFactory;
import io.agentscope.core.formatter.gemini.GeminiChatFormatter;
import io.agentscope.core.formatter.gemini.GeminiMultiAgentFormatter;
import io.agentscope.core.memory.InMemoryMemory;
import io.agentscope.core.model.GeminiChatModel;
import io.agentscope.core.model.GenerateOptions;
import io.agentscope.core.tool.Toolkit;
import java.util.HashSet;
import java.util.Set;

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
public class GeminiNativeProvider implements ModelProvider {

    private final String modelName;
    private final boolean multiAgentFormatter;
    private final boolean supportsThinking;

    public GeminiNativeProvider(
            String modelName, boolean multiAgentFormatter, boolean supportsThinking) {
        this.modelName = modelName;
        this.multiAgentFormatter = multiAgentFormatter;
        this.supportsThinking = supportsThinking;
    }

    public GeminiNativeProvider(String modelName, boolean multiAgentFormatter) {
        this(modelName, multiAgentFormatter, false);
    }

    @Override
    public ReActAgent createAgent(String name, Toolkit toolkit) {
        return createAgentBuilder(name, toolkit).build();
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
    public ReActAgent.Builder createAgentBuilder(String name, Toolkit toolkit) {
        String apiKey = System.getenv("GOOGLE_API_KEY");
        if (apiKey == null || apiKey.isEmpty()) {
            throw new IllegalStateException("GOOGLE_API_KEY environment variable is required");
        }

        String baseUrl = System.getenv("GOOGLE_API_BASE_URL"); // Optional custom endpoint

        GeminiChatModel.Builder builder =
                GeminiChatModel.builder()
                        .apiKey(apiKey)
                        .modelName(modelName)
                        .formatter(
                                multiAgentFormatter
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
                .memory(new InMemoryMemory());
    }

    @Override
    public String getProviderName() {
        return "Gemini-Native";
    }

    @Override
    public boolean supportsThinking() {
        return supportsThinking;
    }

    @Override
    public boolean isEnabled() {
        return ProviderFactory.hasGoogleKey();
    }

    @Override
    public String getModelName() {
        return modelName;
    }

    @Override
    public Set<ModelCapability> getCapabilities() {
        Set<ModelCapability> caps = new HashSet<>();
        caps.add(ModelCapability.BASIC);
        caps.add(ModelCapability.TOOL_CALLING);
        caps.add(ModelCapability.IMAGE);
        caps.add(ModelCapability.AUDIO);
        caps.add(ModelCapability.VIDEO);

        if (supportsThinking) {
            caps.add(ModelCapability.THINKING);
        }

        if (multiAgentFormatter) {
            caps.add(ModelCapability.MULTI_AGENT_FORMATTER);
        }

        return caps;
    }

    @Override
    public boolean supportsToolCalling() {
        return true; // All Gemini models support tool calling
    }

    // ==========================================================================
    // Provider Instances
    // ==========================================================================

    /** Gemini 2.5 Flash - Fast multimodal model. */
    public static class Gemini25FlashNative extends GeminiNativeProvider {
        public Gemini25FlashNative() {
            super("gemini-2.5-flash", false, true);
        }

        @Override
        public String getProviderName() {
            return "Gemini";
        }
    }

    /** Gemini 2.5 Flash with multi-agent formatter. */
    public static class Gemini25FlashMultiAgentNative extends GeminiNativeProvider {
        public Gemini25FlashMultiAgentNative() {
            super("gemini-2.5-flash", true, true);
        }

        @Override
        public String getProviderName() {
            return "Gemini (Multi-Agent)";
        }
    }

    /** Gemini 3 Pro Preview - Advanced thinking model. */
    public static class Gemini3ProNative extends GeminiNativeProvider {
        public Gemini3ProNative() {
            super("gemini-3-pro-preview", false, true);
        }

        @Override
        public String getProviderName() {
            return "Gemini";
        }
    }

    /** Gemini 3 Pro Preview with multi-agent formatter. */
    public static class Gemini3ProMultiAgentNative extends GeminiNativeProvider {
        public Gemini3ProMultiAgentNative() {
            super("gemini-3-pro-preview", true, true);
        }

        @Override
        public String getProviderName() {
            return "Gemini (Multi-Agent)";
        }
    }

    /** Gemini 3 Flash Preview - Fast thinking model. */
    public static class Gemini3FlashNative extends GeminiNativeProvider {
        public Gemini3FlashNative() {
            super("gemini-3-flash-preview", false, true);
        }

        @Override
        public String getProviderName() {
            return "Gemini";
        }
    }

    /** Gemini 3 Flash Preview with multi-agent formatter. */
    public static class Gemini3FlashMultiAgentNative extends GeminiNativeProvider {
        public Gemini3FlashMultiAgentNative() {
            super("gemini-3-flash-preview", true, true);
        }

        @Override
        public String getProviderName() {
            return "Gemini (Multi-Agent)";
        }
    }

    /** Gemini 1.5 Pro - Stable production model. */
    public static class Gemini15ProNative extends GeminiNativeProvider {
        public Gemini15ProNative() {
            super("gemini-1.5-pro", false, false);
        }

        @Override
        public String getProviderName() {
            return "Gemini";
        }
    }

    /** Gemini 1.5 Pro with multi-agent formatter. */
    public static class Gemini15ProMultiAgentNative extends GeminiNativeProvider {
        public Gemini15ProMultiAgentNative() {
            super("gemini-1.5-pro", true, false);
        }

        @Override
        public String getProviderName() {
            return "Gemini (Multi-Agent)";
        }
    }

    /** Gemini 1.5 Flash - Fast production model. */
    public static class Gemini15FlashNative extends GeminiNativeProvider {
        public Gemini15FlashNative() {
            super("gemini-1.5-flash", false, false);
        }

        @Override
        public String getProviderName() {
            return "Gemini";
        }
    }

    /** Gemini 1.5 Flash with multi-agent formatter. */
    public static class Gemini15FlashMultiAgentNative extends GeminiNativeProvider {
        public Gemini15FlashMultiAgentNative() {
            super("gemini-1.5-flash", true, false);
        }

        @Override
        public String getProviderName() {
            return "Gemini (Multi-Agent)";
        }
    }
}
