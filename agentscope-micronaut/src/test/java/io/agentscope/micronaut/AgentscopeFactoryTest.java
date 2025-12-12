/*
 * Copyright 2024-2025 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      https://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package io.agentscope.micronaut;

import io.agentscope.core.ReActAgent;
import io.agentscope.core.memory.Memory;
import io.agentscope.core.model.AnthropicChatModel;
import io.agentscope.core.model.GeminiChatModel;
import io.agentscope.core.model.Model;
import io.agentscope.core.model.OpenAIChatModel;
import io.micronaut.context.ApplicationContext;
import io.micronaut.test.extensions.junit5.annotation.MicronautTest;
import java.util.Map;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

@MicronautTest(startApplication = false)
public class AgentscopeFactoryTest {

    @Test
    public void shouldCreateDefaultBeansWhenEnabled() {
        try (ApplicationContext context =
                ApplicationContext.run(
                        Map.of(
                                "agentscope.dashscope.api-key", "test-api-key",
                                "agentscope.agent.enabled", "true"))) {
            context.start();
            Assertions.assertNotNull(context.getBean(Memory.class));
            Assertions.assertNotNull(context.getBean(ReActAgent.class));
        }
    }

    @Test
    public void shouldNotCreateReActAgentWhenDisabled() {
        try (ApplicationContext context =
                ApplicationContext.run(Map.of("agentscope.agent.enabled", "false"))) {
            context.start();
            Assertions.assertNotNull(context.getBean(Memory.class));
            Assertions.assertFalse(context.containsBean(ReActAgent.class));
        }
    }

    @Test
    public void shouldCreateOpenAIModelWhenProviderIsOpenAI() {
        try (ApplicationContext context =
                ApplicationContext.run(
                        Map.of(
                                "agentscope.model.provider", "openai",
                                "agentscope.openai.api-key", "test-api-key",
                                "agentscope.openai.model-name", "gpt-4.1-mini"))) {
            context.start();
            Assertions.assertNotNull(context.getBean(Memory.class));
            Assertions.assertNotNull(context.getBean(ReActAgent.class));
            Assertions.assertInstanceOf(OpenAIChatModel.class, context.getBean(Model.class));
        }
    }

    @Test
    void shouldCreateGeminiModelWhenProviderIsGemini() {
        try (ApplicationContext context =
                ApplicationContext.run(
                        Map.of(
                                "agentscope.model.provider", "gemini",
                                "agentscope.gemini.api-key", "test-api-key",
                                "agentscope.gemini.model-name", "model-name=gemini-2.0-flash"))) {
            context.start();
            Assertions.assertNotNull(context.getBean(Memory.class));
            Assertions.assertNotNull(context.getBean(ReActAgent.class));
            Assertions.assertInstanceOf(GeminiChatModel.class, context.getBean(Model.class));
        }
    }

    @Test
    void shouldCreateAnthropicModelWhenProviderIsAnthropic() {
        try (ApplicationContext context =
                ApplicationContext.run(
                        Map.of(
                                "agentscope.model.provider", "anthropic",
                                "agentscope.anthropic.api-key", "test-api-key",
                                "agentscope.anthropic.model-name", "claude-sonnet-4.5"))) {
            context.start();
            Assertions.assertNotNull(context.getBean(Memory.class));
            Assertions.assertNotNull(context.getBean(ReActAgent.class));
            Assertions.assertInstanceOf(AnthropicChatModel.class, context.getBean(Model.class));
        }
    }
}
