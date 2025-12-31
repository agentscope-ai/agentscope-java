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

/**
 * Provider for DeepSeek API - OpenAI compatible.
 */
public class DeepSeekProvider implements ModelProvider {

    private static final String DEEPSEEK_BASE_URL = "https://api.deepseek.com";
    private final String modelName;
    private final boolean multiAgentFormatter;

    public DeepSeekProvider(String modelName, boolean multiAgentFormatter) {
        this.modelName = modelName;
        this.multiAgentFormatter = multiAgentFormatter;
    }

    @Override
    public ReActAgent createAgent(String name, Toolkit toolkit) {
        String apiKey = System.getenv("DEEPSEEK_API_KEY");
        if (apiKey == null || apiKey.isEmpty()) {
            apiKey = System.getProperty("DEEPSEEK_API_KEY");
        }
        if (apiKey == null || apiKey.isEmpty()) {
            throw new IllegalStateException("DEEPSEEK_API_KEY environment variable is required");
        }

        OpenAIChatModel model =
                OpenAIChatModel.builder()
                        .baseUrl(DEEPSEEK_BASE_URL)
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
        return "DeepSeek";
    }

    @Override
    public boolean supportsThinking() {
        return false;
    }

    @Override
    public boolean isEnabled() {
        String apiKey = System.getenv("DEEPSEEK_API_KEY");
        if (apiKey == null || apiKey.isEmpty()) {
            apiKey = System.getProperty("DEEPSEEK_API_KEY");
        }
        return apiKey != null && !apiKey.isEmpty();
    }

    @Override
    public String getModelName() {
        return modelName;
    }

    /**
     * DeepSeek Chat (V3).
     */
    public static class DeepSeekChat extends DeepSeekProvider {
        public DeepSeekChat() {
            super("deepseek-chat", false);
        }

        @Override
        public String getProviderName() {
            return "DeepSeek Chat (V3)";
        }
    }

    /**
     * DeepSeek Chat (V3) with Multi-Agent Formatter.
     */
    public static class DeepSeekChatMultiAgent extends DeepSeekProvider {
        public DeepSeekChatMultiAgent() {
            super("deepseek-chat", true);
        }

        @Override
        public String getProviderName() {
            return "DeepSeek Chat (V3) (MultiAgent)";
        }
    }

    /**
     * DeepSeek R1 (Reasoner).
     */
    public static class DeepSeekR1 extends DeepSeekProvider {
        public DeepSeekR1() {
            super("deepseek-reasoner", false);
        }

        @Override
        public String getProviderName() {
            return "DeepSeek R1";
        }

        @Override
        public boolean supportsThinking() {
            return true;
        }
    }

    /**
     * DeepSeek R1 (Reasoner) with Multi-Agent Formatter.
     */
    public static class DeepSeekR1MultiAgent extends DeepSeekProvider {
        public DeepSeekR1MultiAgent() {
            super("deepseek-reasoner", true);
        }

        @Override
        public String getProviderName() {
            return "DeepSeek R1 (MultiAgent)";
        }

        @Override
        public boolean supportsThinking() {
            return true;
        }
    }
}
