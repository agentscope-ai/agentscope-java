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
package io.agentscope.core.e2e.providers;

import io.agentscope.core.ReActAgent;
import io.agentscope.core.memory.InMemoryMemory;
import io.agentscope.core.model.OpenAIChatModel;
import io.agentscope.core.tool.Toolkit;

/**
 * Provider for DashScope Compatible API using OpenAI SDK with DashScope endpoint.
 *
 * <p>Based on OpenAICompatibleE2ETest:
 * - Endpoint: https://dashscope.aliyuncs.com/compatible-mode/v1
 * - Models: qwen-plus, qwen-turbo, qwen3-omni-flash
 */
public class DashScopeCompatibleProvider implements ModelProvider {

    private static final String COMPATIBLE_BASE_URL =
            "https://dashscope.aliyuncs.com/compatible-mode/v1";
    private final String modelName;

    /** Default constructor using qwen-plus model. */
    public DashScopeCompatibleProvider() {
        this("qwen-plus");
    }

    /**
     * Constructor with specific model name.
     *
     * @param modelName The Qwen model name for DashScope compatible endpoint
     */
    public DashScopeCompatibleProvider(String modelName) {
        this.modelName = modelName;
    }

    @Override
    public ReActAgent createAgent(String name, Toolkit toolkit) {
        String apiKey = System.getenv("DASHSCOPE_API_KEY");
        if (apiKey == null || apiKey.isEmpty()) {
            throw new IllegalStateException("DASHSCOPE_API_KEY environment variable is required");
        }

        OpenAIChatModel model =
                OpenAIChatModel.builder()
                        .baseUrl(COMPATIBLE_BASE_URL)
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
        return "DashScope-Compatible";
    }

    @Override
    public boolean supportsThinking() {
        // Compatible endpoint doesn't support thinking mode
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
     * Provider for DashScope compatible multimodal capabilities.
     */
    public static class MultimodalProvider extends DashScopeCompatibleProvider {
        public MultimodalProvider() {
            super("qwen3-omni-flash"); // Multimodal model from AudioE2ETest
        }

        @Override
        public String getProviderName() {
            return "DashScope-Compatible-Multimodal";
        }
    }
}
