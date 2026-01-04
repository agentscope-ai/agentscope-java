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
package io.agentscope.spring.boot.properties;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Root configuration properties for AgentScope Spring Boot starter.
 *
 * <p>At a high level, configuration is grouped as:
 *
 * <ul>
 *   <li>{@link AgentProperties} under {@code agentscope.agent}</li>
 *   <li>{@link DashscopeProperties} under {@code agentscope.dashscope}</li>
 *   <li>{@link ModelProperties} under {@code agentscope.model}</li>
 *   <li>{@link OpenAIProperties} under {@code agentscope.openai}</li>
 *   <li>{@link GeminiProperties} under {@code agentscope.gemini}</li>
 *   <li>{@link AnthropicProperties} under {@code agentscope.anthropic}</li>
 * </ul>
 */
@ConfigurationProperties(prefix = "agentscope")
public class AgentscopeProperties {

    private final AgentProperties agent = new AgentProperties();

    private final DashscopeProperties dashscope = new DashscopeProperties();

    private final ModelProperties model = new ModelProperties();

    private final OpenAIProperties openai = new OpenAIProperties();

    private final GeminiProperties gemini = new GeminiProperties();

    private final AnthropicProperties anthropic = new AnthropicProperties();

    public AgentProperties getAgent() {
        return agent;
    }

    public DashscopeProperties getDashscope() {
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
