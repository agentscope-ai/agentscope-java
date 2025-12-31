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

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import io.agentscope.core.ReActAgent;
import io.agentscope.core.memory.InMemoryMemory;
import io.agentscope.core.memory.Memory;
import io.agentscope.core.model.AnthropicChatModel;
import io.agentscope.core.model.DashScopeChatModel;
import io.agentscope.core.model.GeminiChatModel;
import io.agentscope.core.model.Model;
import io.agentscope.core.model.OpenAIChatModel;
import io.agentscope.core.tool.Toolkit;
import java.lang.reflect.Field;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * Unit tests for AgentScopeProducer using mock configuration. Tests all provider types, error
 * conditions, and edge cases.
 */
class AgentScopeProducerUnitTest {

    private AgentScopeProducer producer;
    private AgentScopeConfig mockConfig;
    private AgentScopeConfig.ModelConfig mockModelConfig;
    private AgentScopeConfig.DashscopeConfig mockDashscopeConfig;
    private AgentScopeConfig.OpenAIConfig mockOpenAIConfig;
    private AgentScopeConfig.GeminiConfig mockGeminiConfig;
    private AgentScopeConfig.AnthropicConfig mockAnthropicConfig;
    private AgentScopeConfig.AgentConfig mockAgentConfig;

    @BeforeEach
    void setUp() throws Exception {
        producer = new AgentScopeProducer();

        // Create mock configuration
        mockConfig = mock(AgentScopeConfig.class);
        mockModelConfig = mock(AgentScopeConfig.ModelConfig.class);
        mockDashscopeConfig = mock(AgentScopeConfig.DashscopeConfig.class);
        mockOpenAIConfig = mock(AgentScopeConfig.OpenAIConfig.class);
        mockGeminiConfig = mock(AgentScopeConfig.GeminiConfig.class);
        mockAnthropicConfig = mock(AgentScopeConfig.AnthropicConfig.class);
        mockAgentConfig = mock(AgentScopeConfig.AgentConfig.class);

        when(mockConfig.model()).thenReturn(mockModelConfig);
        when(mockConfig.dashscope()).thenReturn(mockDashscopeConfig);
        when(mockConfig.openai()).thenReturn(mockOpenAIConfig);
        when(mockConfig.gemini()).thenReturn(mockGeminiConfig);
        when(mockConfig.anthropic()).thenReturn(mockAnthropicConfig);
        when(mockConfig.agent()).thenReturn(mockAgentConfig);

        // Inject mock config using reflection
        Field configField = AgentScopeProducer.class.getDeclaredField("config");
        configField.setAccessible(true);
        configField.set(producer, mockConfig);

        // Call init to initialize toolkit
        producer.init();
    }

    // ========== DashScope Provider Tests ==========

    @Test
    void testCreateModelWithDashscopeProvider() {
        when(mockModelConfig.provider()).thenReturn("dashscope");
        when(mockDashscopeConfig.apiKey()).thenReturn(Optional.of("test-dashscope-key"));
        when(mockDashscopeConfig.modelName()).thenReturn("qwen-plus");
        when(mockDashscopeConfig.stream()).thenReturn(false);
        when(mockDashscopeConfig.enableThinking()).thenReturn(false);
        when(mockDashscopeConfig.baseUrl()).thenReturn(Optional.empty());

        Model model = producer.createModel();

        assertNotNull(model);
        assertTrue(model instanceof DashScopeChatModel);
    }

    @Test
    void testCreateModelWithDashscopeProviderUpperCase() {
        when(mockModelConfig.provider()).thenReturn("DASHSCOPE");
        when(mockDashscopeConfig.apiKey()).thenReturn(Optional.of("test-dashscope-key"));
        when(mockDashscopeConfig.modelName()).thenReturn("qwen-plus");
        when(mockDashscopeConfig.stream()).thenReturn(false);
        when(mockDashscopeConfig.enableThinking()).thenReturn(false);
        when(mockDashscopeConfig.baseUrl()).thenReturn(Optional.empty());

        Model model = producer.createModel();

        assertNotNull(model);
        assertTrue(model instanceof DashScopeChatModel);
    }

    @Test
    void testCreateDashscopeModelWithThinking() {
        when(mockModelConfig.provider()).thenReturn("dashscope");
        when(mockDashscopeConfig.apiKey()).thenReturn(Optional.of("test-dashscope-key"));
        when(mockDashscopeConfig.modelName()).thenReturn("qwen-plus");
        when(mockDashscopeConfig.stream()).thenReturn(true);
        when(mockDashscopeConfig.enableThinking()).thenReturn(true);
        when(mockDashscopeConfig.baseUrl()).thenReturn(Optional.empty());

        Model model = producer.createModel();

        assertNotNull(model);
        assertTrue(model instanceof DashScopeChatModel);
    }

    @Test
    void testCreateDashscopeModelWithBaseUrl() {
        when(mockModelConfig.provider()).thenReturn("dashscope");
        when(mockDashscopeConfig.apiKey()).thenReturn(Optional.of("test-dashscope-key"));
        when(mockDashscopeConfig.modelName()).thenReturn("qwen-plus");
        when(mockDashscopeConfig.stream()).thenReturn(false);
        when(mockDashscopeConfig.enableThinking()).thenReturn(false);
        when(mockDashscopeConfig.baseUrl()).thenReturn(Optional.of("https://custom.dashscope.com"));

        Model model = producer.createModel();

        assertNotNull(model);
        assertTrue(model instanceof DashScopeChatModel);
    }

    @Test
    void testCreateDashscopeModelMissingApiKey() {
        when(mockModelConfig.provider()).thenReturn("dashscope");
        when(mockDashscopeConfig.apiKey()).thenReturn(Optional.empty());

        IllegalStateException exception =
                assertThrows(IllegalStateException.class, () -> producer.createModel());

        assertTrue(exception.getMessage().contains("DashScope API key is required"));
    }

    // ========== OpenAI Provider Tests ==========

    @Test
    void testCreateModelWithOpenAIProvider() {
        when(mockModelConfig.provider()).thenReturn("openai");
        when(mockOpenAIConfig.apiKey()).thenReturn(Optional.of("test-openai-key"));
        when(mockOpenAIConfig.modelName()).thenReturn("gpt-4");
        when(mockOpenAIConfig.stream()).thenReturn(false);
        when(mockOpenAIConfig.baseUrl()).thenReturn(Optional.empty());

        Model model = producer.createModel();

        assertNotNull(model);
        assertTrue(model instanceof OpenAIChatModel);
    }

    @Test
    void testCreateOpenAIModelWithBaseUrl() {
        when(mockModelConfig.provider()).thenReturn("openai");
        when(mockOpenAIConfig.apiKey()).thenReturn(Optional.of("test-openai-key"));
        when(mockOpenAIConfig.modelName()).thenReturn("gpt-4-turbo");
        when(mockOpenAIConfig.stream()).thenReturn(true);
        when(mockOpenAIConfig.baseUrl()).thenReturn(Optional.of("https://custom.openai.com"));

        Model model = producer.createModel();

        assertNotNull(model);
        assertTrue(model instanceof OpenAIChatModel);
    }

    @Test
    void testCreateOpenAIModelMissingApiKey() {
        when(mockModelConfig.provider()).thenReturn("openai");
        when(mockOpenAIConfig.apiKey()).thenReturn(Optional.empty());

        IllegalStateException exception =
                assertThrows(IllegalStateException.class, () -> producer.createModel());

        assertTrue(exception.getMessage().contains("OpenAI API key is required"));
    }

    // ========== Gemini Provider Tests ==========

    @Test
    void testCreateModelWithGeminiProvider() {
        when(mockModelConfig.provider()).thenReturn("gemini");
        when(mockGeminiConfig.apiKey()).thenReturn(Optional.of("test-gemini-key"));
        when(mockGeminiConfig.modelName()).thenReturn("gemini-2.0-flash-exp");
        when(mockGeminiConfig.stream()).thenReturn(false);
        when(mockGeminiConfig.useVertexAi()).thenReturn(false);

        Model model = producer.createModel();

        assertNotNull(model);
        assertTrue(model instanceof GeminiChatModel);
    }

    @Test
    void testCreateGeminiModelWithVertexAIThrowsWithoutCredentials() {
        // Vertex AI requires GCP credentials which are not available in unit tests
        // This test verifies that the configuration is correctly parsed and
        // the code attempts to create a Vertex AI model (which throws due to missing credentials)
        when(mockModelConfig.provider()).thenReturn("gemini");
        when(mockGeminiConfig.modelName()).thenReturn("gemini-2.0-flash-exp");
        when(mockGeminiConfig.stream()).thenReturn(true);
        when(mockGeminiConfig.useVertexAi()).thenReturn(true);
        when(mockGeminiConfig.project()).thenReturn(Optional.of("my-gcp-project"));
        when(mockGeminiConfig.location()).thenReturn(Optional.of("us-central1"));

        // Expect an exception because GCP credentials are not available in unit test environment
        assertThrows(Exception.class, () -> producer.createModel());
    }

    @Test
    void testCreateGeminiModelMissingApiKey() {
        when(mockModelConfig.provider()).thenReturn("gemini");
        when(mockGeminiConfig.apiKey()).thenReturn(Optional.empty());
        when(mockGeminiConfig.modelName()).thenReturn("gemini-2.0-flash-exp");
        when(mockGeminiConfig.stream()).thenReturn(false);
        when(mockGeminiConfig.useVertexAi()).thenReturn(false);

        IllegalStateException exception =
                assertThrows(IllegalStateException.class, () -> producer.createModel());

        assertTrue(exception.getMessage().contains("Gemini API key is required"));
    }

    @Test
    void testCreateGeminiModelVertexAIMissingProject() {
        when(mockModelConfig.provider()).thenReturn("gemini");
        when(mockGeminiConfig.modelName()).thenReturn("gemini-2.0-flash-exp");
        when(mockGeminiConfig.stream()).thenReturn(false);
        when(mockGeminiConfig.useVertexAi()).thenReturn(true);
        when(mockGeminiConfig.project()).thenReturn(Optional.empty());
        when(mockGeminiConfig.location()).thenReturn(Optional.of("us-central1"));

        IllegalStateException exception =
                assertThrows(IllegalStateException.class, () -> producer.createModel());

        assertTrue(exception.getMessage().contains("GCP project is required"));
    }

    @Test
    void testCreateGeminiModelVertexAIMissingLocation() {
        when(mockModelConfig.provider()).thenReturn("gemini");
        when(mockGeminiConfig.modelName()).thenReturn("gemini-2.0-flash-exp");
        when(mockGeminiConfig.stream()).thenReturn(false);
        when(mockGeminiConfig.useVertexAi()).thenReturn(true);
        when(mockGeminiConfig.project()).thenReturn(Optional.of("my-gcp-project"));
        when(mockGeminiConfig.location()).thenReturn(Optional.empty());

        IllegalStateException exception =
                assertThrows(IllegalStateException.class, () -> producer.createModel());

        assertTrue(exception.getMessage().contains("GCP location is required"));
    }

    // ========== Anthropic Provider Tests ==========

    @Test
    void testCreateModelWithAnthropicProvider() {
        when(mockModelConfig.provider()).thenReturn("anthropic");
        when(mockAnthropicConfig.apiKey()).thenReturn(Optional.of("test-anthropic-key"));
        when(mockAnthropicConfig.modelName()).thenReturn("claude-3-5-sonnet-20241022");
        when(mockAnthropicConfig.stream()).thenReturn(false);
        when(mockAnthropicConfig.baseUrl()).thenReturn(Optional.empty());

        Model model = producer.createModel();

        assertNotNull(model);
        assertTrue(model instanceof AnthropicChatModel);
    }

    @Test
    void testCreateAnthropicModelWithBaseUrl() {
        when(mockModelConfig.provider()).thenReturn("anthropic");
        when(mockAnthropicConfig.apiKey()).thenReturn(Optional.of("test-anthropic-key"));
        when(mockAnthropicConfig.modelName()).thenReturn("claude-3-5-sonnet-20241022");
        when(mockAnthropicConfig.stream()).thenReturn(true);
        when(mockAnthropicConfig.baseUrl()).thenReturn(Optional.of("https://custom.anthropic.com"));

        Model model = producer.createModel();

        assertNotNull(model);
        assertTrue(model instanceof AnthropicChatModel);
    }

    @Test
    void testCreateAnthropicModelMissingApiKey() {
        when(mockModelConfig.provider()).thenReturn("anthropic");
        when(mockAnthropicConfig.apiKey()).thenReturn(Optional.empty());

        IllegalStateException exception =
                assertThrows(IllegalStateException.class, () -> producer.createModel());

        assertTrue(exception.getMessage().contains("Anthropic API key is required"));
    }

    // ========== Invalid Provider Tests ==========

    @Test
    void testCreateModelWithUnsupportedProvider() {
        when(mockModelConfig.provider()).thenReturn("unsupported");

        IllegalArgumentException exception =
                assertThrows(IllegalArgumentException.class, () -> producer.createModel());

        assertTrue(exception.getMessage().contains("Unsupported model provider"));
        assertTrue(exception.getMessage().contains("unsupported"));
    }

    @Test
    void testCreateModelWithNullProvider() {
        when(mockModelConfig.provider()).thenReturn(null);

        IllegalArgumentException exception =
                assertThrows(IllegalArgumentException.class, () -> producer.createModel());

        assertTrue(exception.getMessage().contains("Model provider cannot be null or empty"));
    }

    @Test
    void testCreateModelWithEmptyProvider() {
        when(mockModelConfig.provider()).thenReturn("");

        IllegalArgumentException exception =
                assertThrows(IllegalArgumentException.class, () -> producer.createModel());

        assertTrue(exception.getMessage().contains("Model provider cannot be null or empty"));
    }

    @Test
    void testCreateModelWithBlankProvider() {
        when(mockModelConfig.provider()).thenReturn("   ");

        IllegalArgumentException exception =
                assertThrows(IllegalArgumentException.class, () -> producer.createModel());

        assertTrue(exception.getMessage().contains("Model provider cannot be null or empty"));
    }

    // ========== Memory and Toolkit Tests ==========

    @Test
    void testCreateMemory() {
        Memory memory = producer.createMemory();

        assertNotNull(memory);
        assertTrue(memory instanceof InMemoryMemory);
    }

    @Test
    void testCreateMemoryReturnsNewInstances() {
        Memory memory1 = producer.createMemory();
        Memory memory2 = producer.createMemory();

        assertNotNull(memory1);
        assertNotNull(memory2);
        assertNotSame(memory1, memory2, "Each call should return a new Memory instance");
    }

    @Test
    void testCreateToolkit() {
        Toolkit toolkit = producer.createToolkit();

        assertNotNull(toolkit);
    }

    @Test
    void testCreateToolkitReturnsSameInstance() {
        Toolkit toolkit1 = producer.createToolkit();
        Toolkit toolkit2 = producer.createToolkit();

        assertNotNull(toolkit1);
        assertNotNull(toolkit2);
        assertSame(toolkit1, toolkit2, "Should return the same Toolkit instance");
    }

    // ========== Agent Tests ==========

    @Test
    void testCreateAgent() {
        // Setup model config
        when(mockModelConfig.provider()).thenReturn("dashscope");
        when(mockDashscopeConfig.apiKey()).thenReturn(Optional.of("test-key"));
        when(mockDashscopeConfig.modelName()).thenReturn("qwen-plus");
        when(mockDashscopeConfig.stream()).thenReturn(false);
        when(mockDashscopeConfig.enableThinking()).thenReturn(false);
        when(mockDashscopeConfig.baseUrl()).thenReturn(Optional.empty());

        // Setup agent config
        when(mockAgentConfig.name()).thenReturn("TestAgent");
        when(mockAgentConfig.sysPrompt()).thenReturn("You are a test agent.");
        when(mockAgentConfig.maxIters()).thenReturn(5);

        Model model = producer.createModel();
        Memory memory = producer.createMemory();
        ReActAgent agent = producer.createAgent(model, memory);

        assertNotNull(agent);
        assertEquals("TestAgent", agent.getName());
    }

    @Test
    void testInitMethod() {
        // Create a new producer without calling init
        AgentScopeProducer newProducer = new AgentScopeProducer();

        // Before init, toolkit should be null
        // After init, toolkit should be initialized
        newProducer.init();

        // The init method should initialize toolkit without throwing
        assertDoesNotThrow(() -> newProducer.init());
    }
}
