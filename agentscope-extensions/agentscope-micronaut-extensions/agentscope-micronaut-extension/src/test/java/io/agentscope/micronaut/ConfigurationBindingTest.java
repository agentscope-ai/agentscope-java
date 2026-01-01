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
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.agentscope.micronaut.properties.AgentscopeProperties;
import io.micronaut.context.ApplicationContext;
import io.micronaut.test.extensions.junit5.annotation.MicronautTest;
import jakarta.inject.Inject;
import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * Tests to verify that Micronaut properly binds nested configuration properties.
 */
@MicronautTest
class ConfigurationBindingTest {

    @Inject ApplicationContext context;

    @Test
    void testNestedPropertiesBinding() {
        // Test with complete nested configuration
        try (ApplicationContext ctx =
                ApplicationContext.run(
                        Map.of(
                                "agentscope.model.provider",
                                "openai",
                                "agentscope.agent.enabled",
                                "true",
                                "agentscope.agent.name",
                                "TestAgent",
                                "agentscope.agent.sys-prompt",
                                "You are a test assistant",
                                "agentscope.agent.max-iters",
                                "5",
                                "agentscope.openai.enabled",
                                "true",
                                "agentscope.openai.api-key",
                                "test-key-123",
                                "agentscope.openai.model-name",
                                "gpt-4",
                                "agentscope.openai.stream",
                                "false"))) {

            AgentscopeProperties props = ctx.getBean(AgentscopeProperties.class);
            assertNotNull(props, "AgentscopeProperties bean should exist");

            // Verify model properties
            assertNotNull(props.getModel(), "Model properties should not be null");
            assertEquals("openai", props.getModel().getProvider());

            // Verify agent properties
            assertNotNull(props.getAgent(), "Agent properties should not be null");
            assertTrue(props.getAgent().isEnabled());
            assertEquals("TestAgent", props.getAgent().getName());
            assertEquals("You are a test assistant", props.getAgent().getSysPrompt());
            assertEquals(5, props.getAgent().getMaxIters());

            // Verify OpenAI properties
            assertNotNull(props.getOpenai(), "OpenAI properties should not be null");
            assertTrue(props.getOpenai().isEnabled());
            assertEquals("test-key-123", props.getOpenai().getApiKey());
            assertEquals("gpt-4", props.getOpenai().getModelName());
            assertEquals(false, props.getOpenai().isStream());
        }
    }

    @Test
    void testDashScopePropertiesBinding() {
        try (ApplicationContext ctx =
                ApplicationContext.run(
                        Map.of(
                                "agentscope.dashscope.enabled",
                                "true",
                                "agentscope.dashscope.api-key",
                                "sk-abc123",
                                "agentscope.dashscope.model-name",
                                "qwen-max",
                                "agentscope.dashscope.stream",
                                "true",
                                "agentscope.dashscope.enable-thinking",
                                "true"))) {

            AgentscopeProperties props = ctx.getBean(AgentscopeProperties.class);
            assertNotNull(props.getDashscope());
            assertTrue(props.getDashscope().isEnabled());
            assertEquals("sk-abc123", props.getDashscope().getApiKey());
            assertEquals("qwen-max", props.getDashscope().getModelName());
            assertTrue(props.getDashscope().isStream());
            assertEquals(true, props.getDashscope().getEnableThinking());
        }
    }

    @Test
    void testGeminiPropertiesBinding() {
        try (ApplicationContext ctx =
                ApplicationContext.run(
                        Map.of(
                                "agentscope.gemini.enabled",
                                "true",
                                "agentscope.gemini.api-key",
                                "gemini-key",
                                "agentscope.gemini.model-name",
                                "gemini-pro",
                                "agentscope.gemini.project",
                                "my-project",
                                "agentscope.gemini.location",
                                "us-west1",
                                "agentscope.gemini.vertex-ai",
                                "true"))) {

            AgentscopeProperties props = ctx.getBean(AgentscopeProperties.class);
            assertNotNull(props.getGemini());
            assertTrue(props.getGemini().isEnabled());
            assertEquals("gemini-key", props.getGemini().getApiKey());
            assertEquals("gemini-pro", props.getGemini().getModelName());
            assertEquals("my-project", props.getGemini().getProject());
            assertEquals("us-west1", props.getGemini().getLocation());
            assertEquals(true, props.getGemini().getVertexAI());
        }
    }

    @Test
    void testAnthropicPropertiesBinding() {
        try (ApplicationContext ctx =
                ApplicationContext.run(
                        Map.of(
                                "agentscope.anthropic.enabled",
                                "true",
                                "agentscope.anthropic.api-key",
                                "ant-key",
                                "agentscope.anthropic.model-name",
                                "claude-3-opus",
                                "agentscope.anthropic.base-url",
                                "https://api.anthropic.com",
                                "agentscope.anthropic.stream",
                                "false"))) {

            AgentscopeProperties props = ctx.getBean(AgentscopeProperties.class);
            assertNotNull(props.getAnthropic());
            assertTrue(props.getAnthropic().isEnabled());
            assertEquals("ant-key", props.getAnthropic().getApiKey());
            assertEquals("claude-3-opus", props.getAnthropic().getModelName());
            assertEquals("https://api.anthropic.com", props.getAnthropic().getBaseUrl());
            assertEquals(false, props.getAnthropic().isStream());
        }
    }
}
