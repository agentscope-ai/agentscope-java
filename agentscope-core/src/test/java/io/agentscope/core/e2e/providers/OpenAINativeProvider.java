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
import io.agentscope.core.model.GenerateOptions;
import io.agentscope.core.model.OpenAIChatModel;
import io.agentscope.core.tool.Toolkit;

public class OpenAINativeProvider implements ModelProvider {

    private final String modelName;
    private final boolean multiAgentFormatter;

    public OpenAINativeProvider(String modelName, boolean multiAgentFormatter) {
        this.modelName = modelName;
        this.multiAgentFormatter = multiAgentFormatter;
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
                        .formatter(
                                multiAgentFormatter
                                        ? new OpenAIMultiAgentFormatter()
                                        : new OpenAIChatFormatter())
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

    public static class Gpt5MiniOpenAI extends OpenAINativeProvider {
        public Gpt5MiniOpenAI() {
            super("openai/gpt-5-mini", false);
        }

        @Override
        public String getProviderName() {
            return "OpenAI";
        }
    }

    public static class Gpt5MiniMultiAgentOpenAI extends OpenAINativeProvider {
        public Gpt5MiniMultiAgentOpenAI() {
            super("openai/gpt-5-mini", true);
        }

        @Override
        public String getProviderName() {
            return "OpenAI";
        }
    }

    public static class Gpt4oAudioPreviewOpenAI extends OpenAINativeProvider {
        public Gpt4oAudioPreviewOpenAI() {
            super("openai/gpt-4o-audio-preview", false);
        }

        @Override
        public String getProviderName() {
            return "OpenAI";
        }
    }

    public static class Gpt4oAudioPreviewMultiAgentOpenAI extends OpenAINativeProvider {
        public Gpt4oAudioPreviewMultiAgentOpenAI() {
            super("openai/gpt-4o-audio-preview", true);
        }

        @Override
        public String getProviderName() {
            return "OpenAI";
        }
    }
}
