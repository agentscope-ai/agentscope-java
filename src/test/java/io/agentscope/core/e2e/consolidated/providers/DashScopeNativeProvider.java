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
import io.agentscope.core.formatter.DashScopeChatFormatter;
import io.agentscope.core.memory.InMemoryMemory;
import io.agentscope.core.model.DashScopeChatModel;
import io.agentscope.core.model.GenerateOptions;
import io.agentscope.core.tool.Toolkit;

/**
 * Provider for DashScope Native API using official DashScope SDK.
 *
 * <p>Based on various DashScope native tests:
 * - ReActE2ETest: qwen-plus
 * - VisionE2ETest: qwen-vl-max
 * - DashScopeQwen3VlPlusE2ETest: qwen3-vl-plus
 * - DashScopeThinkingE2ETest: qwen-plus with thinking enabled
 * - DashScopeMultimodalToolE2ETest: qwen-turbo
 */
public class DashScopeNativeProvider implements ModelProvider {

    private final String modelName;
    private final boolean enableThinking;
    private final int thinkingBudget;

    /** Default constructor using qwen-plus model without thinking. */
    public DashScopeNativeProvider() {
        this("qwen-plus", false, 0);
    }

    /**
     * Constructor with specific model name.
     *
     * @param modelName The DashScope model name
     */
    public DashScopeNativeProvider(String modelName) {
        this(modelName, false, 0);
    }

    /**
     * Constructor with model name and thinking configuration.
     *
     * @param modelName The DashScope model name
     * @param enableThinking Whether to enable thinking mode
     * @param thinkingBudget Thinking budget in tokens
     */
    public DashScopeNativeProvider(String modelName, boolean enableThinking, int thinkingBudget) {
        this.modelName = modelName;
        this.enableThinking = enableThinking;
        this.thinkingBudget = thinkingBudget;
    }

    @Override
    public ReActAgent createAgent(String name, Toolkit toolkit) {
        String apiKey = System.getenv("DASHSCOPE_API_KEY");
        if (apiKey == null || apiKey.isEmpty()) {
            throw new IllegalStateException("DASHSCOPE_API_KEY environment variable is required");
        }

        DashScopeChatModel.Builder builder =
                DashScopeChatModel.builder().apiKey(apiKey).modelName(modelName).stream(true)
                        .enableThinking(enableThinking)
                        .formatter(new DashScopeChatFormatter());

        if (enableThinking) {
            builder.defaultOptions(
                    GenerateOptions.builder().thinkingBudget(thinkingBudget).build());
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
        return enableThinking ? "DashScope-Native-Thinking" : "DashScope-Native";
    }

    @Override
    public boolean supportsThinking() {
        return true; // DashScope supports thinking mode
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
     * Provider for DashScope native vision capabilities.
     */
    public static class VisionProvider extends DashScopeNativeProvider {
        public VisionProvider() {
            super("qwen-vl-max");
        }

        public VisionProvider(String modelName) {
            super(modelName);
        }

        @Override
        public String getProviderName() {
            return "DashScope-Native-Vision";
        }
    }

    /**
     * Provider for DashScope native multimodal capabilities (including video).
     */
    public static class MultimodalProvider extends DashScopeNativeProvider {
        public MultimodalProvider() {
            super("qwen3-vl-plus");
        }

        public MultimodalProvider(String modelName) {
            super(modelName);
        }

        @Override
        public String getProviderName() {
            return "DashScope-Native-Multimodal";
        }
    }

    /**
     * Provider for DashScope native thinking mode.
     */
    public static class ThinkingProvider extends DashScopeNativeProvider {
        public ThinkingProvider() {
            super("qwen-plus", true, 5000);
        }

        public ThinkingProvider(int thinkingBudget) {
            super("qwen-plus", true, thinkingBudget);
        }

        @Override
        public String getProviderName() {
            return "DashScope-Native-Thinking";
        }
    }

    /**
     * Provider for DashScope tool testing (using qwen-plus).
     */
    public static class ToolProvider extends DashScopeNativeProvider {
        public ToolProvider() {
            super("qwen-plus");
        }

        @Override
        public String getProviderName() {
            return "DashScope-Native-Tool";
        }
    }

    /**
     * Provider for DashScope audio capabilities.
     */
    public static class AudioProvider extends DashScopeNativeProvider {
        public AudioProvider() {
            super("qwen3-omni-flash"); // Audio-enabled model
        }

        @Override
        public String getProviderName() {
            return "DashScope-Native-Audio";
        }
    }
}
