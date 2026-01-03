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
import java.util.HashSet;
import java.util.Set;

/**
 * Provider for GLM (Zhipu AI) API - OpenAI compatible.
 *
 * <p>Supports GLM-4, GLM-4V, GLM-Z1, and GLM-4.5 models.
 */
@ModelCapabilities({
    ModelCapability.BASIC,
    ModelCapability.TOOL_CALLING,
    ModelCapability.STRUCTURED_OUTPUT
})
public class GLMProvider extends BaseModelProvider {

    private static final String API_KEY_ENV = "GLM_API_KEY";
    private static final String GLM_BASE_URL = "https://open.bigmodel.cn/api/paas/v4/";

    public GLMProvider(String modelName, boolean multiAgentFormatter) {
        super(API_KEY_ENV, modelName, multiAgentFormatter);
    }

    @Override
    protected ReActAgent.Builder doCreateAgentBuilder(String name, Toolkit toolkit, String apiKey) {
        OpenAIChatModel model =
                OpenAIChatModel.builder()
                        .baseUrl(GLM_BASE_URL)
                        .apiKey(apiKey)
                        .modelName(getModelName())
                        .stream(true)
                        .formatter(
                                isMultiAgentFormatter()
                                        ? new OpenAIMultiAgentFormatter()
                                        : new OpenAIChatFormatter())
                        .build();

        return ReActAgent.builder()
                .name(name)
                .model(model)
                .toolkit(toolkit)
                .memory(new InMemoryMemory());
    }

    @Override
    public String getProviderName() {
        return "GLM (Zhipu AI)";
    }

    @Override
    public Set<ModelCapability> getCapabilities() {
        Set<ModelCapability> caps = new HashSet<>(super.getCapabilities());
        if (isMultiAgentFormatter()) {
            caps.add(ModelCapability.MULTI_AGENT_FORMATTER);
        }
        return caps;
    }

    // ==========================================================================
    // Provider Instances
    // ==========================================================================

    /** GLM-4 Plus - Latest generation flagship model. */
    @ModelCapabilities({
        ModelCapability.BASIC,
        ModelCapability.TOOL_CALLING,
        ModelCapability.STRUCTURED_OUTPUT
    })
    public static class GLM4Plus extends GLMProvider {
        public GLM4Plus() {
            super("glm-4-plus", false);
        }

        @Override
        public String getProviderName() {
            return "GLM-4 Plus";
        }
    }

    /** GLM-4 Plus with Multi-Agent Formatter. */
    @ModelCapabilities({
        ModelCapability.BASIC,
        ModelCapability.TOOL_CALLING,
        ModelCapability.MULTI_AGENT_FORMATTER,
        ModelCapability.STRUCTURED_OUTPUT
    })
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
     *
     * <p>Note: GLM-4V series does NOT support tool calling (function calling).
     */
    @ModelCapabilities({
        ModelCapability.BASIC,
        ModelCapability.IMAGE,
        ModelCapability.AUDIO,
        ModelCapability.VIDEO
    })
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
     *
     * <p>Note: GLM-4V series does NOT support tool calling (function calling).
     */
    @ModelCapabilities({
        ModelCapability.BASIC,
        ModelCapability.IMAGE,
        ModelCapability.AUDIO,
        ModelCapability.VIDEO,
        ModelCapability.MULTI_AGENT_FORMATTER
    })
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
     *
     * <p>Uses reinforcement learning for deep reasoning on complex tasks.
     */
    @ModelCapabilities({
        ModelCapability.BASIC,
        ModelCapability.TOOL_CALLING,
        ModelCapability.THINKING
    })
    public static class GLMZ1Air extends GLMProvider {
        public GLMZ1Air() {
            super("glm-z1-air", false);
        }

        @Override
        public String getProviderName() {
            return "GLM-Z1-Air";
        }
    }

    /**
     * GLM-4.5 - Hybrid reasoning model with ARC (Agentic/Reasoning/Coding) capabilities.
     *
     * <p>Supports thinking mode toggle for complex reasoning tasks.
     */
    @ModelCapabilities({
        ModelCapability.BASIC,
        ModelCapability.TOOL_CALLING,
        ModelCapability.THINKING
    })
    public static class GLM45 extends GLMProvider {
        public GLM45() {
            super("glm-4.5", false);
        }

        @Override
        public String getProviderName() {
            return "GLM-4.5";
        }
    }
}
