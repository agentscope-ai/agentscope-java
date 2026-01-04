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
 * Provider for DashScope OpenAI-compatible API.
 *
 * <p>Uses the OpenAI-compatible endpoint at dashscope.aliyuncs.com/compatible-mode/v1.
 */
@ModelCapabilities({ModelCapability.BASIC, ModelCapability.TOOL_CALLING})
public class DashScopeCompatibleProvider extends BaseModelProvider {

    private static final String API_KEY_ENV = "DASHSCOPE_API_KEY";
    private static final String COMPATIBLE_BASE_URL =
            "https://dashscope.aliyuncs.com/compatible-mode/v1";

    public DashScopeCompatibleProvider(String modelName, boolean multiAgentFormatter) {
        super(API_KEY_ENV, modelName, multiAgentFormatter);
    }

    @Override
    protected ReActAgent.Builder doCreateAgentBuilder(String name, Toolkit toolkit, String apiKey) {
        OpenAIChatModel model =
                OpenAIChatModel.builder()
                        .baseUrl(COMPATIBLE_BASE_URL)
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
        return "DashScope-Compatible";
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

    /** Qwen3-Omni-Flash - Multimodal omni model. */
    @ModelCapabilities({
        ModelCapability.BASIC,
        ModelCapability.TOOL_CALLING,
        ModelCapability.IMAGE,
        ModelCapability.AUDIO
    })
    public static class Qwen3OmniFlashOpenAI extends DashScopeCompatibleProvider {
        public Qwen3OmniFlashOpenAI() {
            super("qwen3-omni-flash", false);
        }

        @Override
        public String getProviderName() {
            return "OpenAI to DashScope";
        }
    }

    /** Qwen3-Omni-Flash with multi-agent formatter. */
    @ModelCapabilities({
        ModelCapability.BASIC,
        ModelCapability.TOOL_CALLING,
        ModelCapability.IMAGE,
        ModelCapability.AUDIO,
        ModelCapability.MULTI_AGENT_FORMATTER
    })
    public static class Qwen3OmniFlashMultiAgentOpenAI extends DashScopeCompatibleProvider {
        public Qwen3OmniFlashMultiAgentOpenAI() {
            super("qwen3-omni-flash", true);
        }

        @Override
        public String getProviderName() {
            return "OpenAI to DashScope (Multi-Agent)";
        }
    }

    /** Qwen3-VL-Plus via OpenAI compatible API. */
    @ModelCapabilities({
        ModelCapability.BASIC,
        ModelCapability.TOOL_CALLING,
        ModelCapability.IMAGE,
        ModelCapability.VIDEO
    })
    public static class Qwen3VlPlusOpenAI extends DashScopeCompatibleProvider {
        public Qwen3VlPlusOpenAI() {
            super("qwen3-vl-plus", false);
        }

        @Override
        public String getProviderName() {
            return "OpenAI to DashScope";
        }
    }

    /** Qwen3-VL-Plus with multi-agent formatter. */
    @ModelCapabilities({
        ModelCapability.BASIC,
        ModelCapability.TOOL_CALLING,
        ModelCapability.IMAGE,
        ModelCapability.VIDEO,
        ModelCapability.MULTI_AGENT_FORMATTER
    })
    public static class Qwen3VlPlusMultiAgentOpenAI extends DashScopeCompatibleProvider {
        public Qwen3VlPlusMultiAgentOpenAI() {
            super("qwen3-vl-plus", true);
        }

        @Override
        public String getProviderName() {
            return "OpenAI to DashScope (Multi-Agent)";
        }
    }

    /** Qwen-Plus - Standard text model via OpenAI compatible API. */
    @ModelCapabilities({ModelCapability.BASIC, ModelCapability.TOOL_CALLING})
    public static class QwenPlusOpenAI extends DashScopeCompatibleProvider {
        public QwenPlusOpenAI() {
            super("qwen-plus", false);
        }

        @Override
        public String getProviderName() {
            return "OpenAI to DashScope";
        }
    }

    /** Qwen-Plus with multi-agent formatter. */
    @ModelCapabilities({
        ModelCapability.BASIC,
        ModelCapability.TOOL_CALLING,
        ModelCapability.MULTI_AGENT_FORMATTER
    })
    public static class QwenPlusMultiAgentOpenAI extends DashScopeCompatibleProvider {
        public QwenPlusMultiAgentOpenAI() {
            super("qwen-plus", true);
        }

        @Override
        public String getProviderName() {
            return "OpenAI to DashScope (Multi-Agent)";
        }
    }
}
