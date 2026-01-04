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

import com.google.genai.types.HttpOptions;
import io.agentscope.core.ReActAgent;
import io.agentscope.core.formatter.gemini.GeminiChatFormatter;
import io.agentscope.core.formatter.gemini.GeminiMultiAgentFormatter;
import io.agentscope.core.memory.InMemoryMemory;
import io.agentscope.core.model.GeminiChatModel;
import io.agentscope.core.model.GenerateOptions;
import io.agentscope.core.tool.Toolkit;

public class GeminiProvider implements ModelProvider {

    private final String modelName;
    private final boolean multiAgentFormatter;

    public GeminiProvider(String modelName, boolean multiAgentFormatter) {
        this.modelName = modelName;
        this.multiAgentFormatter = multiAgentFormatter;
    }

    @Override
    public ReActAgent createAgent(String name, Toolkit toolkit) {
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
            builder.httpOptions(HttpOptions.builder().baseUrl(baseUrl).build());
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
        return "Gemini";
    }

    @Override
    public boolean supportsThinking() {
        return false;
    }

    @Override
    public boolean isEnabled() {
        String apiKey = System.getenv("GOOGLE_API_KEY");
        return apiKey != null && !apiKey.isEmpty();
    }

    @Override
    public String getModelName() {
        return modelName;
    }

    public static class Gemini25FlashGemini extends GeminiProvider {
        public Gemini25FlashGemini() {
            super("gemini-2.5-flash", false);
        }

        @Override
        public String getProviderName() {
            return "Google";
        }
    }

    public static class Gemini25FlashMultiAgentGemini extends GeminiProvider {
        public Gemini25FlashMultiAgentGemini() {
            super("gemini-2.5-flash", true);
        }

        @Override
        public String getProviderName() {
            return "Google";
        }
    }
}
