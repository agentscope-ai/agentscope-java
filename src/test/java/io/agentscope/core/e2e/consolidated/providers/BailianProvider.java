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
import io.agentscope.core.memory.InMemoryMemory;
import io.agentscope.core.model.OpenAIChatModel;
import io.agentscope.core.tool.Toolkit;

/**
 * Provider for Bailian API using OpenAI SDK with Bailian-specific models.
 *
 * <p>Based on BailianOpenAICompatibleE2ETest:
 * - Endpoint: https://dashscope.aliyuncs.com/compatible-mode/v1 (same as DashScope compatible)
 * - Model: qwen-omni-turbo (specialized multimodal model for Bailian)
 *
 * <p>Important: Bailian uses the same endpoint as DashScope Compatible but with different
 * model configurations that provide Bailian-specific capabilities.
 */
public class BailianProvider implements ModelProvider {

    private static final String BAILIAN_BASE_URL =
            "https://dashscope.aliyuncs.com/compatible-mode/v1";
    private final String modelName;

    /** Default constructor using qwen-omni-turbo model. */
    public BailianProvider() {
        this("qwen-omni-turbo");
    }

    /**
     * Constructor with specific model name.
     *
     * @param modelName The Bailian model name
     */
    public BailianProvider(String modelName) {
        this.modelName = modelName;
    }

    @Override
    public ReActAgent createAgent(String name, Toolkit toolkit) {
        String apiKey = System.getenv("DASHSCOPE_API_KEY");
        if (apiKey == null || apiKey.isEmpty()) {
            throw new IllegalStateException(
                    "DASHSCOPE_API_KEY environment variable is required for Bailian");
        }

        OpenAIChatModel model =
                OpenAIChatModel.builder()
                        .baseUrl(BAILIAN_BASE_URL)
                        .apiKey(apiKey)
                        .modelName(modelName)
                        .stream(true)
                        .build();

        return ReActAgent.builder()
                .name(name)
                .model(model)
                .toolkit(toolkit)
                .memory(new InMemoryMemory())
                .build();
    }

    @Override
    public String getProviderName() {
        return "Bailian";
    }

    @Override
    public boolean supportsThinking() {
        // Bailian multimodal models don't support thinking mode
        return false;
    }

    @Override
    public boolean isEnabled() {
        String apiKey = System.getenv("DASHSCOPE_API_KEY");
        return apiKey != null && !apiKey.isEmpty();
    }

    @Override
    public String getModelName() {
        return modelName;
    }

    /**
     * Provider for Bailian audio capabilities.
     */
    public static class AudioProvider extends BailianProvider {
        public AudioProvider() {
            super("qwen-omni-turbo"); // Multimodal model with audio support
        }

        @Override
        public String getProviderName() {
            return "Bailian-Audio";
        }
    }
}
