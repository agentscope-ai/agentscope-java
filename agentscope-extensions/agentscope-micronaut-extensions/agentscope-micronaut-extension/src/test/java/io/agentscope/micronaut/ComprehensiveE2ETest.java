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

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.agentscope.core.ReActAgent;
import io.agentscope.core.memory.Memory;
import io.agentscope.core.model.Model;
import io.agentscope.core.tool.Toolkit;
import io.agentscope.micronaut.properties.AgentscopeProperties;
import io.micronaut.context.ApplicationContext;
import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * Comprehensive end-to-end tests for all AgentScope Micronaut features and providers.
 */
class ComprehensiveE2ETest {

    @Test
    void testDashScopeProviderConfiguration() {
        try (ApplicationContext ctx =
                ApplicationContext.run(
                        Map.of(
                                "agentscope.model.provider",
                                "dashscope",
                                "agentscope.dashscope.enabled",
                                "true",
                                "agentscope.dashscope.api-key",
                                "test-key",
                                "agentscope.dashscope.model-name",
                                "qwen-max",
                                "agentscope.dashscope.stream",
                                "false",
                                "agentscope.dashscope.enable-thinking",
                                "true"))) {

            AgentscopeProperties props = ctx.getBean(AgentscopeProperties.class);
            assertEquals("dashscope", props.getModel().getProvider());
            assertEquals("test-key", props.getDashscope().getApiKey());
            assertEquals("qwen-max", props.getDashscope().getModelName());
            assertFalse(props.getDashscope().isStream());
            assertEquals(true, props.getDashscope().getEnableThinking());

            Model model = ctx.getBean(Model.class);
            assertNotNull(model);
            assertEquals("qwen-max", model.getModelName());
        }
    }

    @Test
    void testOpenAIProviderConfiguration() {
        try (ApplicationContext ctx =
                ApplicationContext.run(
                        Map.of(
                                "agentscope.model.provider",
                                "openai",
                                "agentscope.openai.enabled",
                                "true",
                                "agentscope.openai.api-key",
                                "sk-test123",
                                "agentscope.openai.model-name",
                                "gpt-4",
                                "agentscope.openai.base-url",
                                "https://custom.openai.com",
                                "agentscope.openai.stream",
                                "true"))) {

            AgentscopeProperties props = ctx.getBean(AgentscopeProperties.class);
            assertEquals("openai", props.getModel().getProvider());
            assertEquals("sk-test123", props.getOpenai().getApiKey());
            assertEquals("gpt-4", props.getOpenai().getModelName());
            assertEquals("https://custom.openai.com", props.getOpenai().getBaseUrl());
            assertTrue(props.getOpenai().isStream());

            Model model = ctx.getBean(Model.class);
            assertNotNull(model);
            assertEquals("gpt-4", model.getModelName());
        }
    }

    @Test
    void testGeminiProviderConfiguration() {
        try (ApplicationContext ctx =
                ApplicationContext.run(
                        Map.of(
                                "agentscope.model.provider",
                                "gemini",
                                "agentscope.gemini.enabled",
                                "true",
                                "agentscope.gemini.api-key",
                                "gemini-key-123",
                                "agentscope.gemini.model-name",
                                "gemini-pro",
                                "agentscope.gemini.stream",
                                "false"))) {

            AgentscopeProperties props = ctx.getBean(AgentscopeProperties.class);
            assertEquals("gemini", props.getModel().getProvider());
            assertEquals("gemini-key-123", props.getGemini().getApiKey());
            assertEquals("gemini-pro", props.getGemini().getModelName());
            assertFalse(props.getGemini().isStream());

            Model model = ctx.getBean(Model.class);
            assertNotNull(model);
            assertEquals("gemini-pro", model.getModelName());
        }
    }

    @Test
    void testGeminiVertexAIConfiguration() {
        try (ApplicationContext ctx =
                ApplicationContext.run(
                        Map.of(
                                "agentscope.model.provider",
                                "gemini",
                                "agentscope.gemini.enabled",
                                "true",
                                "agentscope.gemini.project",
                                "my-gcp-project",
                                "agentscope.gemini.location",
                                "us-central1",
                                "agentscope.gemini.model-name",
                                "gemini-pro",
                                "agentscope.gemini.vertex-ai",
                                "true"))) {

            AgentscopeProperties props = ctx.getBean(AgentscopeProperties.class);
            assertEquals("my-gcp-project", props.getGemini().getProject());
            assertEquals("us-central1", props.getGemini().getLocation());
            assertEquals(true, props.getGemini().getVertexAI());
        }
    }

    @Test
    void testAnthropicProviderConfiguration() {
        try (ApplicationContext ctx =
                ApplicationContext.run(
                        Map.of(
                                "agentscope.model.provider",
                                "anthropic",
                                "agentscope.anthropic.enabled",
                                "true",
                                "agentscope.anthropic.api-key",
                                "ant-key-456",
                                "agentscope.anthropic.model-name",
                                "claude-3-sonnet",
                                "agentscope.anthropic.base-url",
                                "https://custom.anthropic.com",
                                "agentscope.anthropic.stream",
                                "false"))) {

            AgentscopeProperties props = ctx.getBean(AgentscopeProperties.class);
            assertEquals("anthropic", props.getModel().getProvider());
            assertEquals("ant-key-456", props.getAnthropic().getApiKey());
            assertEquals("claude-3-sonnet", props.getAnthropic().getModelName());
            assertEquals("https://custom.anthropic.com", props.getAnthropic().getBaseUrl());
            assertFalse(props.getAnthropic().isStream());

            Model model = ctx.getBean(Model.class);
            assertNotNull(model);
            assertEquals("claude-3-sonnet", model.getModelName());
        }
    }

    @Test
    void testAgentConfiguration() {
        try (ApplicationContext ctx =
                ApplicationContext.run(
                        Map.of(
                                "agentscope.agent.enabled",
                                "true",
                                "agentscope.agent.name",
                                "CustomAgent",
                                "agentscope.agent.sys-prompt",
                                "Custom system prompt",
                                "agentscope.agent.max-iters",
                                "15",
                                "agentscope.dashscope.enabled",
                                "true",
                                "agentscope.dashscope.api-key",
                                "test-key"))) {

            AgentscopeProperties props = ctx.getBean(AgentscopeProperties.class);
            assertTrue(props.getAgent().isEnabled());
            assertEquals("CustomAgent", props.getAgent().getName());
            assertEquals("Custom system prompt", props.getAgent().getSysPrompt());
            assertEquals(15, props.getAgent().getMaxIters());

            ReActAgent agent = ctx.getBean(ReActAgent.class);
            assertNotNull(agent);
            assertEquals("CustomAgent", agent.getName());
        }
    }

    @Test
    void testAgentDisabled() {
        try (ApplicationContext ctx =
                ApplicationContext.run(
                        Map.of(
                                "agentscope.agent.enabled",
                                "false",
                                "agentscope.dashscope.enabled",
                                "true",
                                "agentscope.dashscope.api-key",
                                "test-key"))) {

            assertFalse(ctx.containsBean(ReActAgent.class));
        }
    }

    @Test
    void testPrototypeScopedBeans() {
        try (ApplicationContext ctx =
                ApplicationContext.run(
                        Map.of(
                                "agentscope.dashscope.enabled",
                                "true",
                                "agentscope.dashscope.api-key",
                                "test-key",
                                "agentscope.agent.enabled",
                                "true"))) {

            // Test prototype scoped Memory beans
            Memory memory1 = ctx.getBean(Memory.class);
            Memory memory2 = ctx.getBean(Memory.class);
            assertNotSame(memory1, memory2, "Memory beans should be prototype scoped");

            // Test prototype scoped Toolkit beans
            Toolkit toolkit1 = ctx.getBean(Toolkit.class);
            Toolkit toolkit2 = ctx.getBean(Toolkit.class);
            assertNotSame(toolkit1, toolkit2, "Toolkit beans should be prototype scoped");

            // Test prototype scoped ReActAgent beans
            ReActAgent agent1 = ctx.getBean(ReActAgent.class);
            ReActAgent agent2 = ctx.getBean(ReActAgent.class);
            assertNotSame(agent1, agent2, "ReActAgent beans should be prototype scoped");
        }
    }

    @Test
    void testSingletonScopedModel() {
        try (ApplicationContext ctx =
                ApplicationContext.run(
                        Map.of(
                                "agentscope.dashscope.enabled",
                                "true",
                                "agentscope.dashscope.api-key",
                                "test-key"))) {

            Model model1 = ctx.getBean(Model.class);
            Model model2 = ctx.getBean(Model.class);
            assertSame(model1, model2, "Model bean should be singleton scoped");
        }
    }

    @Test
    void testDefaultValues() {
        try (ApplicationContext ctx =
                ApplicationContext.run(
                        Map.of(
                                "agentscope.dashscope.enabled",
                                "true",
                                "agentscope.dashscope.api-key",
                                "test-key"))) {

            AgentscopeProperties props = ctx.getBean(AgentscopeProperties.class);

            // Test default values for Agent
            assertTrue(props.getAgent().isEnabled());
            assertEquals("Assistant", props.getAgent().getName());
            assertEquals("You are a helpful AI assistant.", props.getAgent().getSysPrompt());
            assertEquals(10, props.getAgent().getMaxIters());

            // Test default values for DashScope
            assertTrue(props.getDashscope().isEnabled());
            assertEquals("qwen-plus", props.getDashscope().getModelName());
            assertTrue(props.getDashscope().isStream());
        }
    }

    @Test
    void testCompleteWorkflow() {
        try (ApplicationContext ctx =
                ApplicationContext.run(
                        Map.of(
                                "agentscope.model.provider",
                                "dashscope",
                                "agentscope.dashscope.enabled",
                                "true",
                                "agentscope.dashscope.api-key",
                                "test-key",
                                "agentscope.dashscope.model-name",
                                "qwen-turbo",
                                "agentscope.agent.enabled",
                                "true",
                                "agentscope.agent.name",
                                "WorkflowAgent",
                                "agentscope.agent.max-iters",
                                "5"))) {

            // Verify all beans are properly configured
            AgentscopeProperties props = ctx.getBean(AgentscopeProperties.class);
            assertNotNull(props);

            Model model = ctx.getBean(Model.class);
            assertNotNull(model);
            assertEquals("qwen-turbo", model.getModelName());

            Memory memory = ctx.getBean(Memory.class);
            assertNotNull(memory);

            Toolkit toolkit = ctx.getBean(Toolkit.class);
            assertNotNull(toolkit);

            ReActAgent agent = ctx.getBean(ReActAgent.class);
            assertNotNull(agent);
            assertEquals("WorkflowAgent", agent.getName());

            // Verify beans are properly wired
            assertTrue(ctx.isRunning());
        }
    }
}
