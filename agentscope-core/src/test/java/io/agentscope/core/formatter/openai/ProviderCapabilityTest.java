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
package io.agentscope.core.formatter.openai;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

/**
 * Test for ProviderCapability enum.
 */
class ProviderCapabilityTest {

    @Test
    void testFromUrl() {
        // GLM detection
        assertEquals(
                ProviderCapability.GLM,
                ProviderCapability.fromUrl("https://open.bigmodel.cn/api/paas/v4/"));
        assertEquals(ProviderCapability.GLM, ProviderCapability.fromUrl("https://bigmodel.cn"));

        // OpenAI detection
        assertEquals(
                ProviderCapability.OPENAI,
                ProviderCapability.fromUrl("https://api.openai.com/v1/chat/completions"));

        // Anthropic detection
        assertEquals(
                ProviderCapability.ANTHROPIC,
                ProviderCapability.fromUrl("https://api.anthropic.com/v1/messages"));

        // Gemini detection
        assertEquals(
                ProviderCapability.GEMINI,
                ProviderCapability.fromUrl("https://generativelanguage.googleapis.com"));

        // Unknown provider defaults to OpenAI-compatible
        assertEquals(
                ProviderCapability.UNKNOWN,
                ProviderCapability.fromUrl("https://unknown-provider.com/api"));
    }

    @Test
    void testFromModelName() {
        // GLM models
        assertEquals(ProviderCapability.GLM, ProviderCapability.fromModelName("glm-4-plus"));
        assertEquals(ProviderCapability.GLM, ProviderCapability.fromModelName("zhipu/glm-4"));

        // OpenAI models
        assertEquals(ProviderCapability.OPENAI, ProviderCapability.fromModelName("gpt-4"));
        assertEquals(
                ProviderCapability.OPENAI,
                ProviderCapability.fromModelName("openai/gpt-3.5-turbo"));

        // Anthropic models
        assertEquals(
                ProviderCapability.ANTHROPIC,
                ProviderCapability.fromModelName("claude-3-5-sonnet"));
        assertEquals(
                ProviderCapability.ANTHROPIC,
                ProviderCapability.fromModelName("anthropic/claude-3"));

        // Gemini models
        assertEquals(
                ProviderCapability.GEMINI, ProviderCapability.fromModelName("gemini-2.0-flash"));
        assertEquals(
                ProviderCapability.GEMINI, ProviderCapability.fromModelName("models/gemini-pro"));

        // DashScope models
        assertEquals(ProviderCapability.DASHSCOPE, ProviderCapability.fromModelName("qwen-turbo"));
        assertEquals(
                ProviderCapability.DASHSCOPE, ProviderCapability.fromModelName("dashscope/qwen"));

        // DeepSeek models
        assertEquals(
                ProviderCapability.DEEPSEEK, ProviderCapability.fromModelName("deepseek-chat"));
        assertEquals(
                ProviderCapability.DEEPSEEK,
                ProviderCapability.fromModelName("deepseek/deepseek-r1"));
    }

    @Test
    void testGLMCapabilities() {
        ProviderCapability glm = ProviderCapability.GLM;

        // GLM supports auto, required, specific (based on actual API behavior)
        // Note: Official docs state "默认且仅支持 auto" but actual API shows
        // required and specific tool choice work correctly.
        assertFalse(glm.supportsNone(), "GLM should not support 'none'");
        assertTrue(glm.supportsRequired(), "GLM should support 'required'");
        assertTrue(glm.supportsSpecific(), "GLM should support specific tool choice");
    }

    @Test
    void testOpenAICapabilities() {
        ProviderCapability openai = ProviderCapability.OPENAI;

        // OpenAI supports everything
        assertTrue(openai.supportsNone(), "OpenAI should support 'none'");
        assertTrue(openai.supportsRequired(), "OpenAI should support 'required'");
        assertTrue(openai.supportsSpecific(), "OpenAI should support specific tool choice");
    }

    @Test
    void testAnthropicCapabilities() {
        ProviderCapability anthropic = ProviderCapability.ANTHROPIC;

        // Anthropic supports everything (with different format)
        assertTrue(anthropic.supportsNone(), "Anthropic should support 'none'");
        assertTrue(anthropic.supportsRequired(), "Anthropic should support 'required'");
        assertTrue(anthropic.supportsSpecific(), "Anthropic should support specific tool choice");
    }

    @Test
    void testGeminiCapabilities() {
        ProviderCapability gemini = ProviderCapability.GEMINI;

        // Gemini supports auto, none, required but not specific
        assertTrue(gemini.supportsNone(), "Gemini should support 'none'");
        assertTrue(gemini.supportsRequired(), "Gemini should support 'required'");
        assertFalse(gemini.supportsSpecific(), "Gemini should not support specific tool choice");
    }
}
