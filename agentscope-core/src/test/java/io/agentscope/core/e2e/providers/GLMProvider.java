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
 * Provider for GLM (Zhipu AI) API - OpenAI compatible.
 */
public class GLMProvider implements ModelProvider {

    private static final String GLM_BASE_URL = "https://open.bigmodel.cn/api/paas/v4/";
    private final String modelName;
    private final boolean multiAgentFormatter;

    public GLMProvider(String modelName, boolean multiAgentFormatter) {
        this.modelName = modelName;
        this.multiAgentFormatter = multiAgentFormatter;
    }

    @Override
    public ReActAgent createAgent(String name, Toolkit toolkit) {
        String apiKey = System.getenv("GLM_API_KEY");
        if (apiKey == null || apiKey.isEmpty()) {
            apiKey = System.getProperty("GLM_API_KEY");
        }
        if (apiKey == null || apiKey.isEmpty()) {
            throw new IllegalStateException("GLM_API_KEY environment variable is required");
        }

        OpenAIChatModel model =
                OpenAIChatModel.builder()
                        .baseUrl(GLM_BASE_URL)
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
        return "GLM (Zhipu AI)";
    }

    @Override
    public boolean supportsThinking() {
        return false;
    }

    @Override
    public boolean isEnabled() {
        String apiKey = System.getenv("GLM_API_KEY");
        if (apiKey == null || apiKey.isEmpty()) {
            apiKey = System.getProperty("GLM_API_KEY");
        }
        return apiKey != null && !apiKey.isEmpty();
    }

    @Override
    public String getModelName() {
        return modelName;
    }

    /**
     * GLM-4 Plus - Latest generation flagship model.
     */
    public static class GLM4Plus extends GLMProvider {
        public GLM4Plus() {
            super("glm-4-plus", false);
        }

        @Override
        public String getProviderName() {
            return "GLM-4 Plus";
        }
    }

    /**
     * GLM-4 Plus with Multi-Agent Formatter.
     */
    public static class GLM4PlusMultiAgent extends GLMProvider {
        public GLM4PlusMultiAgent() {
            super("glm-4-plus", true);
        }

        @Override
        public String getProviderName() {
            return "GLM-4 Plus (MultiAgent)";
        }
    }

    /**
     * GLM-4V Plus - Latest generation multimodal model.
     * <p>Note: GLM-4V series does NOT support tool calling (function calling).
     */
    public static class GLM4VPlus extends GLMProvider {
        public GLM4VPlus() {
            super("glm-4v-plus", false);
        }

        @Override
        public String getProviderName() {
            return "GLM-4V Plus";
        }

        @Override
        public boolean supportsToolCalling() {
            return false;
        }
    }

    /**
     * GLM-4V Plus with Multi-Agent Formatter.
     * <p>Note: GLM-4V series does NOT support tool calling (function calling).
     */
    public static class GLM4VPlusMultiAgent extends GLMProvider {
        public GLM4VPlusMultiAgent() {
            super("glm-4v-plus", true);
        }

        @Override
        public String getProviderName() {
            return "GLM-4V Plus (MultiAgent)";
        }

        @Override
        public boolean supportsToolCalling() {
            return false;
        }
    }

    /**
     * GLM-Z1-Air - Reasoning model with thinking mode support.
     * <p>Uses reinforcement learning for deep reasoning on complex tasks.
     * Supports thinking mode via thinking.type parameter.
     * <p>Reference: <a href="https://open.bigmodel.cn/dev/api/Reasoning-models/glm-z1">GLM-Z1 Documentation</a>
     */
    public static class GLMZ1Air extends GLMProvider {
        public GLMZ1Air() {
            super("glm-z1-air", false);
        }

        @Override
        public String getProviderName() {
            return "GLM-Z1-Air";
        }

        @Override
        public boolean supportsThinking() {
            return true;
        }
    }

    /**
     * GLM-4.5 - Hybrid reasoning model with ARC (Agentic/Reasoning/Coding) capabilities.
     * <p>Supports thinking mode toggle for complex reasoning tasks.
     * <p>Reference: <a href="https://docs.bigmodel.cn/cn/guide/models/text/glm-4.5">GLM-4.5 Documentation</a>
     */
    public static class GLM45 extends GLMProvider {
        public GLM45() {
            super("glm-4.5", false);
        }

        @Override
        public String getProviderName() {
            return "GLM-4.5";
        }

        @Override
        public boolean supportsThinking() {
            return true;
        }
    }
}
