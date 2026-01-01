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
package io.agentscope.quarkus.runtime;

import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

/**
 * Test class for AgentScopeConfig.
 */
@QuarkusTest
class AgentScopeConfigTest {

    @Inject AgentScopeConfig config;

    @Test
    void testConfigInjection() {
        Assertions.assertNotNull(config, "Config should be injected");
    }

    @Test
    void testModelProviderConfig() {
        Assertions.assertEquals("dashscope", config.model().provider());
    }

    @Test
    void testDashscopeConfig() {
        Assertions.assertTrue(config.dashscope().apiKey().isPresent());
        Assertions.assertEquals("test-api-key", config.dashscope().apiKey().get());
        Assertions.assertEquals("qwen-plus", config.dashscope().modelName());
        Assertions.assertFalse(config.dashscope().stream());
        Assertions.assertFalse(config.dashscope().enableThinking());
    }

    @Test
    void testAgentConfig() {
        Assertions.assertEquals("TestAssistant", config.agent().name());
        Assertions.assertEquals("You are a test assistant.", config.agent().sysPrompt());
        Assertions.assertEquals(5, config.agent().maxIters());
    }

    @Test
    void testOpenAIConfigDefaults() {
        Assertions.assertEquals("gpt-4", config.openai().modelName());
        Assertions.assertFalse(config.openai().stream());
    }

    @Test
    void testGeminiConfigDefaults() {
        Assertions.assertEquals("gemini-2.0-flash-exp", config.gemini().modelName());
        Assertions.assertFalse(config.gemini().stream());
        Assertions.assertFalse(config.gemini().useVertexAi());
    }

    @Test
    void testAnthropicConfigDefaults() {
        Assertions.assertEquals("claude-3-5-sonnet-20241022", config.anthropic().modelName());
        Assertions.assertFalse(config.anthropic().stream());
    }
}
