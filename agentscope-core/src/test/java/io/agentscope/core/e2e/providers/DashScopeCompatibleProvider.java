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
import io.agentscope.core.formatter.openai.OpenAIChatFormatter;
import io.agentscope.core.formatter.openai.OpenAIMultiAgentFormatter;
import io.agentscope.core.memory.InMemoryMemory;
import io.agentscope.core.model.OpenAIChatModel;
import io.agentscope.core.tool.Toolkit;

public class DashScopeCompatibleProvider implements ModelProvider {

    private static final String COMPATIBLE_BASE_URL =
            "https://dashscope.aliyuncs.com/compatible-mode/v1";
    private final String modelName;
    private final boolean multiAgentFormatter;

    public DashScopeCompatibleProvider(String modelName, boolean multiAgentFormatter) {
        this.modelName = modelName;
        this.multiAgentFormatter = multiAgentFormatter;
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
                        .formatter(
                                multiAgentFormatter
                                        ? new OpenAIMultiAgentFormatter()
                                        : new OpenAIChatFormatter())
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

    public static class Qwen3OmniFlashOpenAI extends DashScopeCompatibleProvider {
        public Qwen3OmniFlashOpenAI() {
            super("qwen3-omni-flash", false);
        }

        @Override
        public String getProviderName() {
            return "OpenAI to DashScope";
        }
    }

    public static class Qwen3OmniFlashMultiAgentOpenAI extends DashScopeCompatibleProvider {
        public Qwen3OmniFlashMultiAgentOpenAI() {
            super("qwen3-omni-flash", true);
        }

        @Override
        public String getProviderName() {
            return "OpenAI to DashScope";
        }
    }

    public static class Qwen3VlPlusOpenAI extends DashScopeCompatibleProvider {
        public Qwen3VlPlusOpenAI() {
            super("qwen3-vl-plus", false);
        }

        @Override
        public String getProviderName() {
            return "OpenAI to DashScope";
        }
    }

    public static class Qwen3VlPlusMultiAgentOpenAI extends DashScopeCompatibleProvider {
        public Qwen3VlPlusMultiAgentOpenAI() {
            super("qwen3-vl-plus", true);
        }

        @Override
        public String getProviderName() {
            return "OpenAI to DashScope";
        }
    }

    public static class QwenPlusOpenAI extends DashScopeCompatibleProvider {
        public QwenPlusOpenAI() {
            super("qwen-plus", false);
        }

        @Override
        public String getProviderName() {
            return "OpenAI to DashScope";
        }
    }

    public static class QwenPlusMultiAgentOpenAI extends DashScopeCompatibleProvider {
        public QwenPlusMultiAgentOpenAI() {
            super("qwen-plus", true);
        }

        @Override
        public String getProviderName() {
            return "OpenAI to DashScope";
        }
    }

    public static class QwenOmniTurboOpenAI extends DashScopeCompatibleProvider {
        public QwenOmniTurboOpenAI() {
            super("qwen-omni-turbo", false);
        }

        @Override
        public String getProviderName() {
            return "OpenAI to DashScope";
        }
    }

    public static class QwenOmniTurboMultiAgentOpenAI extends DashScopeCompatibleProvider {
        public QwenOmniTurboMultiAgentOpenAI() {
            super("qwen-omni-turbo", true);
        }

        @Override
        public String getProviderName() {
            return "OpenAI to DashScope";
        }
    }
}
