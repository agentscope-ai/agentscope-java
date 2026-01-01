/*
 * Copyright 2024-2026 the original author or authors.
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
package io.agentscope.spring.boot.chat.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

import io.agentscope.core.ReActAgent;
import io.agentscope.core.chat.completions.builder.ChatCompletionsResponseBuilder;
import io.agentscope.core.chat.completions.converter.ChatMessageConverter;
import io.agentscope.core.session.Session;
import io.agentscope.spring.boot.chat.service.ChatCompletionsAgentService;
import io.agentscope.spring.boot.chat.service.ChatCompletionsStreamingService;
import io.agentscope.spring.boot.chat.web.ChatCompletionsController;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.runner.WebApplicationContextRunner;

/**
 * Tests for {@link ChatCompletionsWebAutoConfiguration}.
 *
 * <p>These tests verify that the auto-configuration creates the expected beans under different
 * property setups.
 */
class ChatCompletionsWebAutoConfigurationTest {

    private final WebApplicationContextRunner contextRunner =
            new WebApplicationContextRunner()
                    .withConfiguration(
                            AutoConfigurations.of(ChatCompletionsWebAutoConfiguration.class))
                    .withBean(
                            ReActAgent.class, () -> ReActAgent.builder().name("testAgent").build())
                    .withPropertyValues(
                            "agentscope.chat-completions.enabled=true",
                            "agentscope.chat-completions.base-path=/v1/chat/completions",
                            "agentscope.dashscope.api-key=test-api-key",
                            "agentscope.agent.enabled=true");

    @Test
    void shouldCreateDefaultBeansWhenEnabled() {
        contextRunner.run(
                context -> {
                    assertThat(context).hasSingleBean(ChatCompletionsAgentService.class);
                    assertThat(context).hasSingleBean(ChatMessageConverter.class);
                    assertThat(context).hasSingleBean(ChatCompletionsResponseBuilder.class);
                    assertThat(context).hasSingleBean(ChatCompletionsStreamingService.class);
                    assertThat(context).hasSingleBean(ChatCompletionsController.class);
                    assertThat(context).hasSingleBean(Session.class);
                });
    }

    @Test
    void shouldCreateAgentServiceWithSessionSupport() {
        contextRunner.run(
                context -> {
                    assertThat(context).hasSingleBean(ChatCompletionsAgentService.class);
                    ChatCompletionsAgentService agentService =
                            context.getBean(ChatCompletionsAgentService.class);
                    assertThat(agentService).isNotNull();

                    // Test that resolveSessionId works
                    String resolved = agentService.resolveSessionId(null);
                    assertThat(resolved).isNotNull().isNotEmpty();

                    // Test that resolveSessionId returns the same ID if provided
                    String customId = "custom-session-id";
                    assertThat(agentService.resolveSessionId(customId)).isEqualTo(customId);

                    // Test that session is available
                    assertThat(agentService.getSession()).isNotNull();
                });
    }

    @Test
    void shouldUseCustomAgentServiceWhenProvided() {
        // Create mock objects for the constructor
        @SuppressWarnings("unchecked")
        ObjectProvider<ReActAgent> mockAgentProvider = mock(ObjectProvider.class);
        Session mockSession = mock(Session.class);

        ChatCompletionsAgentService customService =
                new ChatCompletionsAgentService(mockAgentProvider, mockSession) {
                    @Override
                    public ReActAgent getAgent(String sessionId) {
                        return null;
                    }
                };

        contextRunner
                .withBean(ChatCompletionsAgentService.class, () -> customService)
                .run(
                        context -> {
                            assertThat(context).hasSingleBean(ChatCompletionsAgentService.class);
                            assertThat(context.getBean(ChatCompletionsAgentService.class))
                                    .isSameAs(customService);
                        });
    }

    @Test
    void shouldBindChatCompletionsProperties() {
        contextRunner
                .withPropertyValues(
                        "agentscope.chat-completions.enabled=true",
                        "agentscope.chat-completions.base-path=/api/chat")
                .run(
                        context -> {
                            assertThat(context).hasSingleBean(ChatCompletionsProperties.class);
                            ChatCompletionsProperties properties =
                                    context.getBean(ChatCompletionsProperties.class);
                            assertThat(properties.isEnabled()).isTrue();
                            assertThat(properties.getBasePath()).isEqualTo("/api/chat");
                        });
    }

    @Test
    void shouldCreateControllerWithDefaultBasePath() {
        contextRunner
                .withPropertyValues("agentscope.chat-completions.enabled=true")
                .run(
                        context -> {
                            assertThat(context).hasSingleBean(ChatCompletionsController.class);
                            ChatCompletionsProperties properties =
                                    context.getBean(ChatCompletionsProperties.class);
                            assertThat(properties.getBasePath()).isEqualTo("/v1/chat/completions");
                        });
    }
}
