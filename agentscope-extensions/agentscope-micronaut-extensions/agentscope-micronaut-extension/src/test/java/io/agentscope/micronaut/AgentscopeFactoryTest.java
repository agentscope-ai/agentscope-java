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
package io.agentscope.micronaut;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.agentscope.core.ReActAgent;
import io.agentscope.core.memory.Memory;
import io.agentscope.core.model.AnthropicChatModel;
import io.agentscope.core.model.DashScopeChatModel;
import io.agentscope.core.model.GeminiChatModel;
import io.agentscope.core.model.Model;
import io.agentscope.core.model.OpenAIChatModel;
import io.agentscope.core.tool.Toolkit;
import io.micronaut.context.ApplicationContext;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Integration tests for Micronaut factory bean creation.
 *
 * <p>Tests verify that:
 * <ul>
 *   <li>Default beans are created when configuration is minimal</li>
 *   <li>ReActAgent creation can be disabled via configuration</li>
 *   <li>Different model providers create the correct model instances</li>
 *   <li>Missing API keys or required configuration throws appropriate errors</li>
 * </ul>
 */
@DisplayName("AgentscopeFactory Integration Tests")
class AgentscopeFactoryTest {

    @Test
    @DisplayName("Should create default beans when minimal configuration is provided")
    void shouldCreateDefaultBeansWhenEnabled() {
        try (ApplicationContext context =
                ApplicationContext.run(
                        Map.of(
                                "agentscope.dashscope.api-key", "test-api-key",
                                "agentscope.agent.enabled", "true"))) {

            // Verify Memory bean is created
            assertNotNull(context.getBean(Memory.class), "Memory bean should be created");

            // Verify Toolkit bean is created
            assertNotNull(context.getBean(Toolkit.class), "Toolkit bean should be created");

            // Verify Model bean is created (defaults to DashScope)
            assertNotNull(context.getBean(Model.class), "Model bean should be created");
            assertInstanceOf(
                    DashScopeChatModel.class,
                    context.getBean(Model.class),
                    "Default model should be DashScopeChatModel");

            // Verify ReActAgent bean is created
            assertNotNull(context.getBean(ReActAgent.class), "ReActAgent bean should be created");
        }
    }

    @Test
    @DisplayName("Should not create ReActAgent when agent is disabled")
    void shouldNotCreateReActAgentWhenDisabled() {
        try (ApplicationContext context =
                ApplicationContext.run(
                        Map.of(
                                "agentscope.dashscope.api-key", "test-api-key",
                                "agentscope.agent.enabled", "false"))) {

            // Memory and Toolkit should still be created
            assertNotNull(
                    context.getBean(Memory.class),
                    "Memory bean should be created even when agent is disabled");
            assertNotNull(
                    context.getBean(Toolkit.class),
                    "Toolkit bean should be created even when agent is disabled");

            // ReActAgent should NOT be created
            assertFalse(
                    context.containsBean(ReActAgent.class),
                    "ReActAgent bean should not be created when disabled");
        }
    }

    @Test
    @DisplayName("Should create DashScopeChatModel when using default provider")
    void shouldCreateDashScopeModelByDefault() {
        try (ApplicationContext context =
                ApplicationContext.run(
                        Map.of(
                                "agentscope.dashscope.api-key", "sk-test123",
                                "agentscope.dashscope.model-name", "qwen-plus"))) {

            Model model = context.getBean(Model.class);
            assertInstanceOf(
                    DashScopeChatModel.class,
                    model,
                    "Default provider should create DashScopeChatModel");
        }
    }

    @Test
    @DisplayName("Should create OpenAIChatModel when provider is openai")
    void shouldCreateOpenAIModelWhenProviderIsOpenAI() {
        try (ApplicationContext context =
                ApplicationContext.run(
                        Map.of(
                                "agentscope.model.provider", "openai",
                                "agentscope.openai.api-key", "sk-openai-test",
                                "agentscope.openai.model-name", "gpt-4-turbo"))) {

            Model model = context.getBean(Model.class);
            assertInstanceOf(
                    OpenAIChatModel.class,
                    model,
                    "When provider is openai, should create OpenAIChatModel");
        }
    }

    @Test
    @DisplayName("Should create GeminiChatModel when provider is gemini")
    void shouldCreateGeminiModelWhenProviderIsGemini() {
        try (ApplicationContext context =
                ApplicationContext.run(
                        Map.of(
                                "agentscope.model.provider", "gemini",
                                "agentscope.gemini.api-key", "gemini-test-key",
                                "agentscope.gemini.model-name", "gemini-2.0-flash"))) {

            Model model = context.getBean(Model.class);
            assertInstanceOf(
                    GeminiChatModel.class,
                    model,
                    "When provider is gemini, should create GeminiChatModel");
        }
    }

    @Test
    @DisplayName("Should create AnthropicChatModel when provider is anthropic")
    void shouldCreateAnthropicModelWhenProviderIsAnthropic() {
        try (ApplicationContext context =
                ApplicationContext.run(
                        Map.of(
                                "agentscope.model.provider", "anthropic",
                                "agentscope.anthropic.api-key", "ant-test-key",
                                "agentscope.anthropic.model-name", "claude-3-sonnet"))) {

            Model model = context.getBean(Model.class);
            assertInstanceOf(
                    AnthropicChatModel.class,
                    model,
                    "When provider is anthropic, should create AnthropicChatModel");
        }
    }

    @Test
    @DisplayName("Should create ReActAgent with valid configuration")
    void shouldCreateReActAgentWithValidConfiguration() {
        try (ApplicationContext context =
                ApplicationContext.run(
                        Map.of(
                                "agentscope.dashscope.api-key", "test-key",
                                "agentscope.agent.enabled", "true",
                                "agentscope.agent.name", "TestAssistant",
                                "agentscope.agent.sys-prompt", "You are a helpful AI",
                                "agentscope.agent.max-iters", "10"))) {

            ReActAgent agent = context.getBean(ReActAgent.class);
            assertNotNull(agent, "ReActAgent should be created with valid configuration");
        }
    }

    @Test
    @DisplayName("Should fail when OpenAI API key is missing but provider is selected")
    void shouldFailWhenOpenAIApiKeyIsMissing() {
        assertThrows(
                Exception.class,
                () -> {
                    try (ApplicationContext context =
                            ApplicationContext.run(
                                    Map.of(
                                            "agentscope.model.provider", "openai",
                                            "agentscope.openai.api-key", ""))) {
                        // Empty API key should fail
                        context.getBean(Model.class);
                    }
                },
                "Should throw exception when OpenAI API key is empty");
    }

    @Test
    @DisplayName("Should fail when ReActAgent name is missing")
    void shouldFailWhenAgentNameIsMissing() {
        assertThrows(
                Exception.class,
                () -> {
                    try (ApplicationContext context =
                            ApplicationContext.run(
                                    Map.of(
                                            "agentscope.dashscope.api-key", "test-key",
                                            "agentscope.agent.enabled", "true",
                                            "agentscope.agent.name", ""))) {
                        // Empty agent name should fail
                        context.getBean(ReActAgent.class);
                    }
                },
                "Should throw exception when agent name is empty");
    }

    @Test
    @DisplayName("Should fail when ReActAgent sysPrompt is missing")
    void shouldFailWhenAgentSysPromptIsMissing() {
        assertThrows(
                Exception.class,
                () -> {
                    try (ApplicationContext context =
                            ApplicationContext.run(
                                    Map.of(
                                            "agentscope.dashscope.api-key", "test-key",
                                            "agentscope.agent.enabled", "true",
                                            "agentscope.agent.name", "TestAgent",
                                            "agentscope.agent.sys-prompt", ""))) {
                        // Empty sys-prompt should fail
                        context.getBean(ReActAgent.class);
                    }
                },
                "Should throw exception when agent sysPrompt is empty");
    }

    @Test
    @DisplayName("Should fail when ReActAgent maxIters is invalid")
    void shouldFailWhenAgentMaxItersIsInvalid() {
        assertThrows(
                Exception.class,
                () -> {
                    try (ApplicationContext context =
                            ApplicationContext.run(
                                    Map.of(
                                            "agentscope.dashscope.api-key", "test-key",
                                            "agentscope.agent.enabled", "true",
                                            "agentscope.agent.name", "TestAgent",
                                            "agentscope.agent.sys-prompt", "You are helpful",
                                            "agentscope.agent.max-iters", "0"))) {
                        // Zero or negative max-iters should fail
                        context.getBean(ReActAgent.class);
                    }
                },
                "Should throw exception when agent maxIters is not positive");
    }

    @Test
    @DisplayName("Should use default values when optional configuration is omitted")
    void shouldUseDefaultValuesForOptionalConfig() {
        try (ApplicationContext context =
                ApplicationContext.run(
                        Map.of(
                                "agentscope.dashscope.api-key", "test-key"
                                // Don't specify optional properties like stream, model-name
                                ))) {

            Model model = context.getBean(Model.class);
            assertNotNull(model, "Model should be created with default optional properties");
        }
    }

    @Test
    @DisplayName("Should support case-insensitive provider names")
    void shouldSupportCaseInsensitiveProviderNames() {
        try (ApplicationContext context =
                ApplicationContext.run(
                        Map.of(
                                "agentscope.model.provider", "OPENAI",
                                "agentscope.openai.api-key", "test-key",
                                "agentscope.openai.model-name", "gpt-4"))) {

            Model model = context.getBean(Model.class);
            assertInstanceOf(
                    OpenAIChatModel.class, model, "Provider names should be case-insensitive");
        }
    }

    @Test
    void testModelBeanCreated() {
        // Start context with minimal config
        try (ApplicationContext ctx =
                ApplicationContext.run(
                        Map.of(
                                "agentscope.dashscope.enabled",
                                "true",
                                "agentscope.dashscope.api-key",
                                "test-key"))) {
            assertTrue(ctx.containsBean(Model.class), "Model bean should be created");
        }
    }

    @Test
    void testReActAgentBeanCreated() {
        // Start context with minimal config
        try (ApplicationContext ctx =
                ApplicationContext.run(
                        Map.of(
                                "agentscope.dashscope.enabled",
                                "true",
                                "agentscope.dashscope.api-key",
                                "test-key",
                                "agentscope.agent.enabled",
                                "true",
                                "agentscope.agent.name",
                                "TestAgent",
                                "agentscope.agent.sys-prompt",
                                "You are helpful",
                                "agentscope.agent.max-iters",
                                "5"))) {
            assertTrue(ctx.containsBean(ReActAgent.class), "ReActAgent bean should be created");
        }
    }
}
