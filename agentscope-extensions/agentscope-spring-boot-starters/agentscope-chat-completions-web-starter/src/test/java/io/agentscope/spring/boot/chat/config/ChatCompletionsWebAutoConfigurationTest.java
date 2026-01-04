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

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.agentscope.core.ReActAgent;
import io.agentscope.core.chat.completions.builder.ChatCompletionsResponseBuilder;
import io.agentscope.core.chat.completions.converter.ChatMessageConverter;
import io.agentscope.core.chat.completions.streaming.ChatCompletionsStreamingAdapter;
import io.agentscope.spring.boot.chat.service.ChatCompletionsStreamingService;
import io.agentscope.spring.boot.chat.web.ChatCompletionsController;
import org.junit.jupiter.api.Test;
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
                    assertTrue(context.containsBean("chatMessageConverter"));
                    assertTrue(context.containsBean("chatCompletionsResponseBuilder"));
                    assertTrue(context.containsBean("chatCompletionsStreamingAdapter"));
                    assertTrue(context.containsBean("chatCompletionsStreamingService"));
                    assertTrue(context.containsBean("chatCompletionsController"));
                    assertNotNull(context.getBean(ChatMessageConverter.class));
                    assertNotNull(context.getBean(ChatCompletionsResponseBuilder.class));
                    assertNotNull(context.getBean(ChatCompletionsStreamingAdapter.class));
                    assertNotNull(context.getBean(ChatCompletionsStreamingService.class));
                    assertNotNull(context.getBean(ChatCompletionsController.class));
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
                            // ChatCompletionsProperties bean name may vary - check by type
                            assertNotNull(context.getBean(ChatCompletionsProperties.class));
                            ChatCompletionsProperties properties =
                                    context.getBean(ChatCompletionsProperties.class);
                            assertTrue(properties.isEnabled());
                            assertEquals("/api/chat", properties.getBasePath());
                        });
    }

    @Test
    void shouldCreateControllerWithDefaultBasePath() {
        contextRunner
                .withPropertyValues("agentscope.chat-completions.enabled=true")
                .run(
                        context -> {
                            assertTrue(context.containsBean("chatCompletionsController"));
                            ChatCompletionsProperties properties =
                                    context.getBean(ChatCompletionsProperties.class);
                            assertEquals("/v1/chat/completions", properties.getBasePath());
                        });
    }
}
