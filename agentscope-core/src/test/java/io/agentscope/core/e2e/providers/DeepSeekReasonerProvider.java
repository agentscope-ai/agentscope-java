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
import io.agentscope.core.formatter.openai.DeepSeekFormatter;
import io.agentscope.core.formatter.openai.DeepSeekMultiAgentFormatter;
import io.agentscope.core.memory.InMemoryMemory;
import io.agentscope.core.model.OpenAIChatModel;
import io.agentscope.core.model.StructuredOutputReminder;
import io.agentscope.core.tool.Toolkit;
import java.util.HashSet;
import java.util.Set;

/**
 * Provider for DeepSeek API - OpenAI compatible.
 *
 * <p>Supports DeepSeek Chat (V3) and DeepSeek R1 (Reasoner) models.
 */
@ModelCapabilities({ModelCapability.BASIC, ModelCapability.TOOL_CALLING})
public class DeepSeekReasonerProvider extends BaseModelProvider {

    private static final String API_KEY_ENV = "DEEPSEEK_API_KEY";
    private static final String DEEPSEEK_BASE_URL = "https://api.deepseek.com";

    public DeepSeekReasonerProvider(String modelName, boolean multiAgentFormatter) {
        super(API_KEY_ENV, modelName, multiAgentFormatter);
    }

    @Override
    protected ReActAgent.Builder doCreateAgentBuilder(String name, Toolkit toolkit, String apiKey) {
        OpenAIChatModel model =
                OpenAIChatModel.builder()
                        .baseUrl(DEEPSEEK_BASE_URL)
                        .apiKey(apiKey)
                        .modelName(getModelName())
                        .stream(true)
                        .formatter(
                                isMultiAgentFormatter()
                                        ? new DeepSeekMultiAgentFormatter(true)
                                        : new DeepSeekFormatter(true))
                        .build();

        return ReActAgent.builder()
                .name(name)
                .model(model)
                .toolkit(toolkit)
                .structuredOutputReminder(StructuredOutputReminder.PROMPT)
                .memory(new InMemoryMemory());
    }

    @Override
    public String getProviderName() {
        return "DeepSeek";
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

    /** DeepSeek R1 (Reasoner) - Thinking model, does NOT support tool calling. */
    @ModelCapabilities({
        ModelCapability.BASIC,
        ModelCapability.TOOL_CALLING,
        ModelCapability.THINKING
    })
    public static class DeepSeekR1 extends DeepSeekReasonerProvider {
        public DeepSeekR1() {
            super("deepseek-reasoner", false);
        }

        @Override
        public String getProviderName() {
            return "DeepSeek R1";
        }
    }

    /** DeepSeek R1 (Reasoner) with Multi-Agent Formatter. */
    @ModelCapabilities({
        ModelCapability.BASIC,
        ModelCapability.TOOL_CALLING,
        ModelCapability.THINKING,
        ModelCapability.MULTI_AGENT_FORMATTER
    })
    public static class DeepSeekR1MultiAgent extends DeepSeekReasonerProvider {
        public DeepSeekR1MultiAgent() {
            super("deepseek-reasoner", true);
        }

        @Override
        public String getProviderName() {
            return "DeepSeek R1 (MultiAgent)";
        }
    }
}
