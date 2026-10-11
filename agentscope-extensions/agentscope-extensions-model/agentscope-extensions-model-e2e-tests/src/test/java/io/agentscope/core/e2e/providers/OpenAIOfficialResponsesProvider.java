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
import io.agentscope.core.tool.Toolkit;
import io.agentscope.extensions.model.openaiofficial.OpenAIResponsesChatModel;
import io.agentscope.extensions.model.openaiofficial.ResponsesMultiAgentFormatter;
import java.util.HashSet;
import java.util.Set;

/**
 * Provider for OpenAI models via the official OpenAI Java SDK (Responses API).
 */
@ModelCapabilities({ModelCapability.BASIC, ModelCapability.TOOL_CALLING})
public class OpenAIOfficialResponsesProvider extends BaseModelProvider {

    private static final String API_KEY_ENV = "OPENAI_API_KEY";
    private static final String BASE_URL_ENV = "OPENAI_BASE_URL";

    public OpenAIOfficialResponsesProvider(String modelName, boolean multiAgentFormatter) {
        super(API_KEY_ENV, modelName, multiAgentFormatter);
    }

    @Override
    protected ReActAgent.Builder doCreateAgentBuilder(String name, Toolkit toolkit, String apiKey) {
        String baseUrl = System.getenv(BASE_URL_ENV);

        OpenAIResponsesChatModel.Builder builder =
                OpenAIResponsesChatModel.builder().apiKey(apiKey).modelName(getModelName());

        if (baseUrl != null && !baseUrl.isEmpty()) {
            builder.baseUrl(baseUrl);
        }

        if (isMultiAgentFormatter()) {
            builder.formatter(new ResponsesMultiAgentFormatter());
        }

        return ReActAgent.builder().name(name).model(builder.build()).toolkit(toolkit);
    }

    @Override
    public String getProviderName() {
        return "OpenAI-Official";
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

    /** GPT-5.4 via OpenAI Official SDK (Responses API). */
    @ModelCapabilities({
        ModelCapability.BASIC,
        ModelCapability.TOOL_CALLING,
        ModelCapability.THINKING
    })
    public static class Gpt54 extends OpenAIOfficialResponsesProvider {
        public Gpt54() {
            super("gpt-5.4", false);
        }

        @Override
        public String getProviderName() {
            return "OpenAI-Official";
        }
    }

    /** GPT-5.4 with Multi-Agent Formatter. */
    @ModelCapabilities({
        ModelCapability.BASIC,
        ModelCapability.TOOL_CALLING,
        ModelCapability.THINKING,
        ModelCapability.MULTI_AGENT_FORMATTER
    })
    public static class Gpt54MultiAgent extends OpenAIOfficialResponsesProvider {
        public Gpt54MultiAgent() {
            super("gpt-5.4", true);
        }

        @Override
        public String getProviderName() {
            return "OpenAI-Official (Multi-Agent)";
        }
    }
}
