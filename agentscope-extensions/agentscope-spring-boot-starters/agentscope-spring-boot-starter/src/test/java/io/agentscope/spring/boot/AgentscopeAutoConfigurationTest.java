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
package io.agentscope.spring.boot;

import static org.assertj.core.api.Assertions.assertThat;

import io.agentscope.core.ReActAgent;
import io.agentscope.core.memory.Memory;
import io.agentscope.core.model.AnthropicChatModel;
import io.agentscope.core.model.GeminiChatModel;
import io.agentscope.core.model.Model;
import io.agentscope.core.model.OpenAIChatModel;
import io.agentscope.core.tool.Toolkit;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

/**
 * Tests for {@link AgentscopeAutoConfiguration}.
 *
 * <p>These tests verify that the auto-configuration creates the expected beans under different
 * property setups.
 */
class AgentscopeAutoConfigurationTest {

    private final ApplicationContextRunner contextRunner =
            new ApplicationContextRunner()
                    .withConfiguration(AutoConfigurations.of(AgentscopeAutoConfiguration.class))
                    .withPropertyValues(
                            "agentscope.agent.enabled=true",
                            "agentscope.dashscope.api-key=test-api-key");

    @Test
    void shouldCreateDefaultBeansWhenEnabled() {
        contextRunner.run(
                context -> {
                    assertThat(context).hasSingleBean(Memory.class);
                    assertThat(context).hasSingleBean(Toolkit.class);
                    assertThat(context).hasSingleBean(Model.class);
                    assertThat(context).hasSingleBean(ReActAgent.class);
                });
    }

    @Test
    void shouldNotCreateReActAgentWhenDisabled() {
        contextRunner
                .withPropertyValues("agentscope.agent.enabled=false")
                .run(
                        context -> {
                            assertThat(context).doesNotHaveBean(ReActAgent.class);
                            assertThat(context).doesNotHaveBean(Memory.class);
                            assertThat(context).doesNotHaveBean(Toolkit.class);
                            assertThat(context).doesNotHaveBean(Model.class);
                        });
    }

    @Test
    void shouldFailWhenApiKeyMissing() {
        new ApplicationContextRunner()
                .withConfiguration(AutoConfigurations.of(AgentscopeAutoConfiguration.class))
                .withPropertyValues("agentscope.agent.enabled=true")
                .run(
                        context ->
                                assertThat(context.getStartupFailure())
                                        .isNotNull()
                                        .hasMessageContaining(
                                                "agentscope.dashscope.api-key must be configured"));
    }

    @Test
    void shouldCreateOpenAIModelWhenProviderIsOpenAI() {
        new ApplicationContextRunner()
                .withConfiguration(AutoConfigurations.of(AgentscopeAutoConfiguration.class))
                .withPropertyValues(
                        "agentscope.agent.enabled=true",
                        "agentscope.model.provider=openai",
                        "agentscope.openai.api-key=test-openai-key",
                        "agentscope.openai.model-name=gpt-4.1-mini")
                .run(
                        context -> {
                            assertThat(context).hasSingleBean(Model.class);
                            assertThat(context.getBean(Model.class))
                                    .isInstanceOf(OpenAIChatModel.class);
                        });
    }

    @Test
    void shouldCreateOpenAIModelWithCustomEndpointPath() {
        new ApplicationContextRunner()
                .withConfiguration(AutoConfigurations.of(AgentscopeAutoConfiguration.class))
                .withPropertyValues(
                        "agentscope.agent.enabled=true",
                        "agentscope.model.provider=openai",
                        "agentscope.openai.api-key=test-openai-key",
                        "agentscope.openai.model-name=gpt-4.1-mini",
                        "agentscope.openai.endpoint-path=/v4/chat/completions")
                .run(
                        context -> {
                            assertThat(context).hasSingleBean(Model.class);
                            assertThat(context.getBean(Model.class))
                                    .isInstanceOf(OpenAIChatModel.class);
                        });
    }

    @Test
    void shouldCreateGeminiModelWhenProviderIsGemini() {
        new ApplicationContextRunner()
                .withConfiguration(AutoConfigurations.of(AgentscopeAutoConfiguration.class))
                .withPropertyValues(
                        "agentscope.agent.enabled=true",
                        "agentscope.model.provider=gemini",
                        "agentscope.gemini.api-key=test-gemini-key",
                        "agentscope.gemini.model-name=gemini-2.0-flash")
                .run(
                        context -> {
                            assertThat(context).hasSingleBean(Model.class);
                            assertThat(context.getBean(Model.class))
                                    .isInstanceOf(GeminiChatModel.class);
                        });
    }

    @Test
    void shouldCreateAnthropicModelWhenProviderIsAnthropic() {
        new ApplicationContextRunner()
                .withConfiguration(AutoConfigurations.of(AgentscopeAutoConfiguration.class))
                .withPropertyValues(
                        "agentscope.agent.enabled=true",
                        "agentscope.model.provider=anthropic",
                        "agentscope.anthropic.api-key=test-anthropic-key",
                        "agentscope.anthropic.model-name=claude-sonnet-4.5")
                .run(
                        context -> {
                            assertThat(context).hasSingleBean(Model.class);
                            assertThat(context.getBean(Model.class))
                                    .isInstanceOf(AnthropicChatModel.class);
                        });
    }
}
