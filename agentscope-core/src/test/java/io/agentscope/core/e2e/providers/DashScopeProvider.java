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
import io.agentscope.core.formatter.dashscope.DashScopeChatFormatter;
import io.agentscope.core.formatter.dashscope.DashScopeMultiAgentFormatter;
import io.agentscope.core.memory.InMemoryMemory;
import io.agentscope.core.model.DashScopeChatModel;
import io.agentscope.core.model.GenerateOptions;
import io.agentscope.core.tool.Toolkit;

public class DashScopeProvider implements ModelProvider {

    private final String modelName;
    private final boolean enableThinking;
    private final int thinkingBudget;
    private final boolean multiAgentFormatter;

    public DashScopeProvider(String modelName, boolean multiAgentFormatter) {
        this(modelName, false, 0, multiAgentFormatter);
    }

    public DashScopeProvider(
            String modelName,
            boolean enableThinking,
            int thinkingBudget,
            boolean multiAgentFormatter) {
        this.modelName = modelName;
        this.enableThinking = enableThinking;
        this.thinkingBudget = thinkingBudget;
        this.multiAgentFormatter = multiAgentFormatter;
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
                        .formatter(
                                multiAgentFormatter
                                        ? new DashScopeMultiAgentFormatter()
                                        : new DashScopeChatFormatter());

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

    public static class QwenVlMaxDashScope extends DashScopeProvider {
        public QwenVlMaxDashScope() {
            super("qwen-vl-max", false);
        }

        @Override
        public String getProviderName() {
            return "DashScope";
        }
    }

    public static class QwenVlMaxMultiAgentDashScope extends DashScopeProvider {
        public QwenVlMaxMultiAgentDashScope() {
            super("qwen-vl-max", true);
        }

        @Override
        public String getProviderName() {
            return "DashScope";
        }
    }

    public static class Qwen3VlPlusDashScope extends DashScopeProvider {
        public Qwen3VlPlusDashScope() {
            super("qwen3-vl-plus", false);
        }

        @Override
        public String getProviderName() {
            return "DashScope";
        }
    }

    public static class Qwen3VlPlusMultiAgentDashScope extends DashScopeProvider {
        public Qwen3VlPlusMultiAgentDashScope() {
            super("qwen3-vl-plus", true);
        }

        @Override
        public String getProviderName() {
            return "DashScope";
        }
    }

    public static class QwenPlusThinkingDashScope extends DashScopeProvider {
        public QwenPlusThinkingDashScope() {
            super("qwen-plus", true, 5000, false);
        }

        public QwenPlusThinkingDashScope(int thinkingBudget) {
            super("qwen-plus", true, thinkingBudget, false);
        }

        @Override
        public String getProviderName() {
            return "DashScope";
        }
    }

    public static class QwenPlusThinkingMultiAgentDashScope extends DashScopeProvider {
        public QwenPlusThinkingMultiAgentDashScope() {
            super("qwen-plus", true, 5000, true);
        }

        public QwenPlusThinkingMultiAgentDashScope(int thinkingBudget) {
            super("qwen-plus", true, thinkingBudget, true);
        }

        @Override
        public String getProviderName() {
            return "DashScope";
        }
    }

    public static class QwenPlusDashScope extends DashScopeProvider {
        public QwenPlusDashScope() {
            super("qwen-plus", false);
        }

        @Override
        public String getProviderName() {
            return "DashScope";
        }
    }

    public static class QwenPlusMultiAgentDashScope extends DashScopeProvider {
        public QwenPlusMultiAgentDashScope() {
            super("qwen-plus", true);
        }

        @Override
        public String getProviderName() {
            return "DashScope";
        }
    }
}
