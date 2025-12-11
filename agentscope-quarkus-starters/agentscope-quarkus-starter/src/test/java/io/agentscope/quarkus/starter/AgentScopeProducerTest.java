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
package io.agentscope.quarkus.starter;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

import io.agentscope.core.ReActAgent;
import io.agentscope.core.memory.Memory;
import io.agentscope.core.model.Model;
import io.agentscope.core.tool.Toolkit;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.QuarkusTestProfile;
import io.quarkus.test.junit.TestProfile;
import jakarta.inject.Inject;
import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * Tests for {@link AgentScopeProducer}.
 *
 * <p>These tests verify that the CDI producer creates the expected beans under different
 * configuration profiles.
 */
@QuarkusTest
@TestProfile(AgentScopeProducerTest.DefaultProfile.class)
class AgentScopeProducerTest {

    @Inject Model model;

    @Inject Memory memory;

    @Inject Toolkit toolkit;

    @Inject ReActAgent agent;

    @Test
    void shouldCreateDefaultBeansWithDashScope() {
        assertNotNull(model, "Model bean should be created");
        assertNotNull(memory, "Memory bean should be created");
        assertNotNull(toolkit, "Toolkit bean should be created");
        assertNotNull(agent, "ReActAgent bean should be created");
        assertEquals("MyAssistant", agent.getName(), "Agent name should match configuration");
    }

    /** Default test profile with DashScope configuration. */
    public static class DefaultProfile implements QuarkusTestProfile {
        @Override
        public Map<String, String> getConfigOverrides() {
            return Map.of(
                    "agentscope.model.provider",
                    "dashscope",
                    "agentscope.dashscope.api-key",
                    "test-api-key",
                    "agentscope.dashscope.model-name",
                    "qwen-plus",
                    "agentscope.agent.name",
                    "MyAssistant",
                    "agentscope.agent.sys-prompt",
                    "You are a helpful AI assistant.");
        }
    }

    /** Test profile for OpenAI provider. */
    @QuarkusTest
    @TestProfile(OpenAIProfile.class)
    static class OpenAIProviderTest {

        @Inject Model model;

        @Test
        void shouldCreateOpenAIModel() {
            assertNotNull(model, "Model bean should be created");
        }
    }

    public static class OpenAIProfile implements QuarkusTestProfile {
        @Override
        public Map<String, String> getConfigOverrides() {
            return Map.of(
                    "agentscope.model.provider",
                    "openai",
                    "agentscope.openai.api-key",
                    "test-openai-key",
                    "agentscope.openai.model-name",
                    "gpt-4",
                    "agentscope.agent.name",
                    "TestAgent");
        }
    }

    /** Test profile for Gemini provider. */
    @QuarkusTest
    @TestProfile(GeminiProfile.class)
    static class GeminiProviderTest {

        @Inject Model model;

        @Test
        void shouldCreateGeminiModel() {
            assertNotNull(model, "Model bean should be created");
        }
    }

    public static class GeminiProfile implements QuarkusTestProfile {
        @Override
        public Map<String, String> getConfigOverrides() {
            return Map.of(
                    "agentscope.model.provider",
                    "gemini",
                    "agentscope.gemini.api-key",
                    "test-gemini-key",
                    "agentscope.gemini.model-name",
                    "gemini-2.0-flash",
                    "agentscope.agent.name",
                    "TestAgent");
        }
    }

    /** Test profile for Anthropic provider. */
    @QuarkusTest
    @TestProfile(AnthropicProfile.class)
    static class AnthropicProviderTest {

        @Inject Model model;

        @Test
        void shouldCreateAnthropicModel() {
            assertNotNull(model, "Model bean should be created");
        }
    }

    public static class AnthropicProfile implements QuarkusTestProfile {
        @Override
        public Map<String, String> getConfigOverrides() {
            return Map.of(
                    "agentscope.model.provider",
                    "anthropic",
                    "agentscope.anthropic.api-key",
                    "test-anthropic-key",
                    "agentscope.anthropic.model-name",
                    "claude-3-5-sonnet-20241022",
                    "agentscope.agent.name",
                    "TestAgent");
        }
    }
}
