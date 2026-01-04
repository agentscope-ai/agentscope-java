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
package io.agentscope.micronaut.properties;

import io.micronaut.context.annotation.ConfigurationProperties;
import jakarta.inject.Inject;

/**
 * Root configuration properties for AgentScope Micronaut starter.
 *
 * <p>At a high level, configuration is grouped as:
 *
 * <ul>
 *   <li>{@link AgentProperties} under {@code agentscope.agent}
 *   <li>{@link DashScopeProperties} under {@code agentscope.dashscope}
 *   <li>{@link ModelProperties} under {@code agentscope.model}
 *   <li>{@link OpenAIProperties} under {@code agentscope.openai}
 *   <li>{@link GeminiProperties} under {@code agentscope.gemini}
 *   <li>{@link AnthropicProperties} under {@code agentscope.anthropic}
 * </ul>
 */
@ConfigurationProperties("agentscope")
public class AgentscopeProperties {

    @Inject private AgentProperties agent;

    @Inject private DashScopeProperties dashscope;

    @Inject private ModelProperties model;

    @Inject private OpenAIProperties openai;

    @Inject private GeminiProperties gemini;

    @Inject private AnthropicProperties anthropic;

    public AgentProperties getAgent() {
        return agent;
    }

    public DashScopeProperties getDashscope() {
        return dashscope;
    }

    public ModelProperties getModel() {
        return model;
    }

    public OpenAIProperties getOpenai() {
        return openai;
    }

    public GeminiProperties getGemini() {
        return gemini;
    }

    public AnthropicProperties getAnthropic() {
        return anthropic;
    }
}
