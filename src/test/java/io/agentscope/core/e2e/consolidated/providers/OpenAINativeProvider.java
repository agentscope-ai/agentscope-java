/*
 * Copyright 2024-2025 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      https://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package io.agentscope.core.e2e.consolidated.providers;

import io.agentscope.core.ReActAgent;
import io.agentscope.core.formatter.OpenAIChatFormatter;
import io.agentscope.core.memory.InMemoryMemory;
import io.agentscope.core.model.GenerateOptions;
import io.agentscope.core.model.OpenAIChatModel;
import io.agentscope.core.tool.Toolkit;

/**
 * Provider for OpenAI Native API using official OpenAI models.
 *
 * <p>Based on models used in OpenAIE2ETest:
 * - openai/gpt-5-mini (chat)
 * - openai/gpt-5-image-mini (vision)
 * - openai/gpt-4o-audio-preview (audio)
 * - openai/gpt-4o (multimodal)
 */
public class OpenAINativeProvider implements ModelProvider {

    private final String modelName;

    /** Default constructor using gpt-5-mini model. */
    public OpenAINativeProvider() {
        this("openai/gpt-5-mini");
    }

    /**
     * Constructor with specific model name.
     *
     * @param modelName The OpenAI model name (with prefix)
     */
    public OpenAINativeProvider(String modelName) {
        this.modelName = modelName;
    }

    @Override
    public ReActAgent createAgent(String name, Toolkit toolkit) {
        String apiKey = System.getenv("OPENAI_API_KEY");
        if (apiKey == null || apiKey.isEmpty()) {
            throw new IllegalStateException("OPENAI_API_KEY environment variable is required");
        }

        String baseUrl = System.getenv("OPENAI_BASE_URL"); // Optional custom endpoint

        OpenAIChatModel.Builder builder =
                OpenAIChatModel.builder().apiKey(apiKey).modelName(modelName).stream(true)
                        .formatter(new OpenAIChatFormatter())
                        .defaultOptions(GenerateOptions.builder().build());

        if (baseUrl != null && !baseUrl.isEmpty()) {
            builder.baseUrl(baseUrl);
        }

        return ReActAgent.builder()
                .name(name)
                .model(builder.build())
                .toolkit(toolkit)
                .memory(new InMemoryMemory())
                .build();
    }

    @Override
    public String getProviderName() {
        return "OpenAI-Native";
    }

    @Override
    public boolean supportsThinking() {
        // OpenAI models don't support thinking mode in the same way as DashScope
        return false;
    }

    @Override
    public boolean isEnabled() {
        String apiKey = System.getenv("OPENAI_API_KEY");
        return apiKey != null && !apiKey.isEmpty();
    }

    @Override
    public String getModelName() {
        return modelName;
    }

    /**
     * Provider for OpenAI multimodal capabilities.
     */
    public static class MultimodalProvider extends OpenAINativeProvider {
        public MultimodalProvider() {
            super("openai/gpt-4o"); // Full multimodal model
        }

        public MultimodalProvider(String modelName) {
            super(modelName);
        }

        @Override
        public String getProviderName() {
            return "OpenAI-Native-Multimodal";
        }
    }

    /**
     * Provider for OpenAI vision capabilities.
     */
    public static class VisionProvider extends OpenAINativeProvider {
        public VisionProvider() {
            super("openai/gpt-5-image-mini");
        }

        @Override
        public String getProviderName() {
            return "OpenAI-Native-Vision";
        }
    }

    /**
     * Provider for OpenAI audio capabilities.
     */
    public static class AudioProvider extends OpenAINativeProvider {
        public AudioProvider() {
            super("openai/gpt-4o-audio-preview");
        }

        @Override
        public String getProviderName() {
            return "OpenAI-Native-Audio";
        }
    }
}
