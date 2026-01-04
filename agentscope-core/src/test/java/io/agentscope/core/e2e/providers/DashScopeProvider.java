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
import io.agentscope.core.model.StructuredOutputReminder;
import io.agentscope.core.tool.Toolkit;
import java.util.HashSet;
import java.util.Set;

/**
 * Provider for DashScope native API.
 *
 * <p>Supports models: qwen-plus, qwen-vl-max, qwen3-vl-plus with optional thinking mode.
 */
@ModelCapabilities({ModelCapability.BASIC, ModelCapability.TOOL_CALLING})
public class DashScopeProvider extends BaseModelProvider {

    private static final String API_KEY_ENV = "DASHSCOPE_API_KEY";

    private final boolean enableThinking;
    private final int thinkingBudget;

    public DashScopeProvider(String modelName, boolean multiAgentFormatter) {
        this(modelName, false, 0, multiAgentFormatter);
    }

    public DashScopeProvider(
            String modelName,
            boolean enableThinking,
            int thinkingBudget,
            boolean multiAgentFormatter) {
        super(API_KEY_ENV, modelName, multiAgentFormatter);
        this.enableThinking = enableThinking;
        this.thinkingBudget = thinkingBudget;
    }

    @Override
    protected ReActAgent.Builder doCreateAgentBuilder(String name, Toolkit toolkit, String apiKey) {
        DashScopeChatModel.Builder builder =
                DashScopeChatModel.builder().apiKey(apiKey).modelName(getModelName()).stream(true)
                        .enableThinking(enableThinking)
                        .formatter(
                                isMultiAgentFormatter()
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
                .structuredOutputReminder(
                        enableThinking
                                ? StructuredOutputReminder.PROMPT
                                : StructuredOutputReminder.PROMPT)
                .memory(new InMemoryMemory());
    }

    @Override
    public String getProviderName() {
        return enableThinking ? "DashScope-Native-Thinking" : "DashScope-Native";
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

    /**
     * Qwen3-VL-Plus - Advanced vision model.
     *
     * <p>Note: qwen3-vl-plus supports IMAGE and VIDEO but NOT AUDIO.
     */
    @ModelCapabilities({
        ModelCapability.BASIC,
        ModelCapability.TOOL_CALLING,
        ModelCapability.IMAGE,
        ModelCapability.VIDEO
    })
    public static class Qwen3VlPlusDashScope extends DashScopeProvider {
        public Qwen3VlPlusDashScope() {
            super("qwen3-vl-plus", false);
        }

        @Override
        public String getProviderName() {
            return "DashScope";
        }
    }

    /**
     * Qwen3-VL-Plus with multi-agent formatter.
     *
     * <p>Note: qwen3-vl-plus supports IMAGE and VIDEO but NOT AUDIO.
     */
    @ModelCapabilities({
        ModelCapability.BASIC,
        ModelCapability.TOOL_CALLING,
        ModelCapability.IMAGE,
        ModelCapability.VIDEO,
        ModelCapability.MULTI_AGENT_FORMATTER
    })
    public static class Qwen3VlPlusMultiAgentDashScope extends DashScopeProvider {
        public Qwen3VlPlusMultiAgentDashScope() {
            super("qwen3-vl-plus", true);
        }

        @Override
        public String getProviderName() {
            return "DashScope (Multi-Agent)";
        }
    }

    /**
     * Qwen-Plus with thinking mode.
     */
    @ModelCapabilities({
        ModelCapability.BASIC,
        ModelCapability.TOOL_CALLING,
        ModelCapability.THINKING
    })
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

    /**
     * Qwen-Plus with thinking mode and multi-agent formatter.
     */
    @ModelCapabilities({
        ModelCapability.BASIC,
        ModelCapability.TOOL_CALLING,
        ModelCapability.THINKING,
        ModelCapability.MULTI_AGENT_FORMATTER
    })
    public static class QwenPlusThinkingMultiAgentDashScope extends DashScopeProvider {
        public QwenPlusThinkingMultiAgentDashScope() {
            super("qwen-plus", true, 5000, true);
        }

        public QwenPlusThinkingMultiAgentDashScope(int thinkingBudget) {
            super("qwen-plus", true, thinkingBudget, true);
        }

        @Override
        public String getProviderName() {
            return "DashScope (Multi-Agent)";
        }
    }

    /**
     * Qwen-Plus - Standard text model.
     */
    @ModelCapabilities({ModelCapability.BASIC, ModelCapability.TOOL_CALLING})
    public static class QwenPlusDashScope extends DashScopeProvider {
        public QwenPlusDashScope() {
            super("qwen-plus", false);
        }

        @Override
        public String getProviderName() {
            return "DashScope";
        }
    }

    /**
     * Qwen-Plus with multi-agent formatter.
     */
    @ModelCapabilities({
        ModelCapability.BASIC,
        ModelCapability.TOOL_CALLING,
        ModelCapability.MULTI_AGENT_FORMATTER
    })
    public static class QwenPlusMultiAgentDashScope extends DashScopeProvider {
        public QwenPlusMultiAgentDashScope() {
            super("qwen-plus", true);
        }

        @Override
        public String getProviderName() {
            return "DashScope (Multi-Agent)";
        }
    }
}
