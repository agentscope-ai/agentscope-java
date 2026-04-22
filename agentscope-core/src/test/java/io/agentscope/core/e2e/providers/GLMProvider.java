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
import io.agentscope.core.formatter.openai.GLMFormatter;
import io.agentscope.core.formatter.openai.GLMMultiAgentFormatter;
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
@ModelCapabilities({ModelCapability.BASIC, ModelCapability.TOOL_CALLING})
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
                                        ? new GLMMultiAgentFormatter()
                                        : new GLMFormatter())
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

    /** GLM-4.6 */
    @ModelCapabilities({ModelCapability.BASIC, ModelCapability.TOOL_CALLING})
    public static class GLM46 extends GLMProvider {
        public GLM46() {
            super("glm-4.6", false);
        }

        @Override
        public String getProviderName() {
            return "GLM-4.6";
        }
    }

    /** GLM-4.6 with Multi-Agent Formatter. */
    @ModelCapabilities({
        ModelCapability.BASIC,
        ModelCapability.TOOL_CALLING,
        ModelCapability.MULTI_AGENT_FORMATTER
    })
    public static class GLM46MultiAgent extends GLMProvider {
        public GLM46MultiAgent() {
            super("glm-4.6", true);
        }

        @Override
        public String getProviderName() {
            return "GLM-4.6 (MultiAgent)";
        }
    }

    /**
     * GLM-4.6V
     */
    @ModelCapabilities({
        ModelCapability.BASIC,
        ModelCapability.TOOL_CALLING,
        ModelCapability.IMAGE,
        ModelCapability.VIDEO
    })
    public static class GLM46VPlus extends GLMProvider {
        public GLM46VPlus() {
            super("glm-4.6v", false);
        }

        @Override
        public String getProviderName() {
            return "GLM-4.6V";
        }
    }

    /**
     * GLM-4.6V with Multi-Agent Formatter.
     */
    @ModelCapabilities({
        ModelCapability.BASIC,
        ModelCapability.TOOL_CALLING,
        ModelCapability.IMAGE,
        ModelCapability.VIDEO,
        ModelCapability.MULTI_AGENT_FORMATTER
    })
    public static class GLM46VMultiAgent extends GLMProvider {
        public GLM46VMultiAgent() {
            super("glm-4.6v", true);
        }

        @Override
        public String getProviderName() {
            return "GLM-4.6V (MultiAgent)";
        }
    }

    /**
     * GLM-4.5
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

    /**
     * GLM-4.7
     */
    @ModelCapabilities({
        ModelCapability.BASIC,
        ModelCapability.TOOL_CALLING,
        ModelCapability.THINKING
    })
    public static class GLM47 extends GLMProvider {
        public GLM47() {
            super("glm-4.7", false);
        }

        @Override
        public String getProviderName() {
            return "GLM-4.7";
        }
    }
}
