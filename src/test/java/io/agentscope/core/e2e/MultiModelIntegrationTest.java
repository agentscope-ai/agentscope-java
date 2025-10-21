/*
 * Copyright 2024-2025 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * You may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package io.agentscope.core.e2e;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.agentscope.core.ReActAgent;
import io.agentscope.core.agent.test.TestUtils;
import io.agentscope.core.memory.InMemoryMemory;
import io.agentscope.core.message.Msg;
import io.agentscope.core.model.DashScopeChatModel;
import io.agentscope.core.model.Model;
import io.agentscope.core.model.OpenAIChatModel;
import io.agentscope.core.pipeline.Pipeline;
import io.agentscope.core.pipeline.SequentialPipeline;
import io.agentscope.core.tool.Toolkit;
import java.time.Duration;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;

/**
 * Integration tests for multi-model switching and model interoperability.
 *
 * <p>These tests verify that agents can switch between different LLM providers (DashScope, OpenAI)
 * and that multiple models can work together in pipelines.
 *
 * <p><b>Requirements:</b>
 *
 * <ul>
 *   <li>DASHSCOPE_API_KEY environment variable must be set
 *   <li>OPENAI_API_KEY environment variable optional (for full coverage)
 * </ul>
 */
@Tag("integration")
@Tag("e2e")
@EnabledIfEnvironmentVariable(
        named = "DASHSCOPE_API_KEY",
        matches = ".+",
        disabledReason = "Multi-model tests require at least DASHSCOPE_API_KEY")
@DisplayName("Multi-Model Integration Tests")
class MultiModelIntegrationTest {

    private static final Duration TEST_TIMEOUT = Duration.ofSeconds(30);
    private static final String DASHSCOPE_MODEL = "qwen-plus";
    private static final String OPENAI_MODEL = "gpt-3.5-turbo";

    private Toolkit toolkit;
    private InMemoryMemory memory;

    @BeforeEach
    void setUp() {
        toolkit = new Toolkit();
        memory = new InMemoryMemory();
        System.out.println("=== Multi-Model Integration Test Setup Complete ===");
    }

    @Test
    @DisplayName("Should switch from DashScope to OpenAI model")
    @EnabledIfEnvironmentVariable(
            named = "OPENAI_API_KEY",
            matches = ".+",
            disabledReason = "Requires OPENAI_API_KEY for model switching")
    void testDashScopeToOpenAI() {
        System.out.println("\n=== Test: DashScope to OpenAI Switching ===");

        String dashscopeKey = System.getenv("DASHSCOPE_API_KEY");
        String openaiKey = System.getenv("OPENAI_API_KEY");

        // Create agent with DashScope model
        Model dashscopeModel =
                DashScopeChatModel.builder().apiKey(dashscopeKey).modelName(DASHSCOPE_MODEL).stream(
                                true)
                        .build();

        ReActAgent agent =
                ReActAgent.builder()
                        .name("SwitchableAgent")
                        .sysPrompt("An agent that can switch between models")
                        .model(dashscopeModel)
                        .toolkit(toolkit)
                        .memory(memory)
                        .build();

        // First interaction with DashScope
        Msg question1 = TestUtils.createUserMessage("User", "What is 2+2?");
        System.out.println("Using DashScope: " + question1);

        Msg response1 = agent.call(question1).block(TEST_TIMEOUT);
        assertNotNull(response1, "Should receive response from DashScope");

        int memoryAfterDashScope = agent.getMemory().getMessages().size();
        System.out.println("Memory after DashScope: " + memoryAfterDashScope);

        // Switch to OpenAI model
        Model openaiModel =
                OpenAIChatModel.builder().apiKey(openaiKey).modelName(OPENAI_MODEL).stream(true)
                        .build();

        // Create new agent with OpenAI (simulating model switch)
        ReActAgent openaiAgent =
                ReActAgent.builder()
                        .name("OpenAIAgent")
                        .sysPrompt("An agent using OpenAI")
                        .model(openaiModel)
                        .toolkit(toolkit)
                        .memory(memory) // Reuse same memory
                        .build();

        // Second interaction with OpenAI
        Msg question2 = TestUtils.createUserMessage("User", "What is the capital of France?");
        System.out.println("Using OpenAI: " + question2);

        Msg response2 = openaiAgent.call(question2).block(TEST_TIMEOUT);
        assertNotNull(response2, "Should receive response from OpenAI");

        // Verify memory persisted across model switch
        int memoryAfterOpenAI = openaiAgent.getMemory().getMessages().size();
        System.out.println("Memory after OpenAI: " + memoryAfterOpenAI);
        assertTrue(
                memoryAfterOpenAI > memoryAfterDashScope,
                "Memory should accumulate across model switches");
    }

    @Test
    @DisplayName("Should handle model configuration changes")
    void testModelConfigurationSwitch() {
        System.out.println("\n=== Test: Model Configuration Switch ===");

        String apiKey = System.getenv("DASHSCOPE_API_KEY");

        // Configuration 1: Streaming enabled
        Model streamingModel =
                DashScopeChatModel.builder().apiKey(apiKey).modelName(DASHSCOPE_MODEL).stream(true)
                        .build();

        ReActAgent streamingAgent =
                ReActAgent.builder()
                        .name("StreamingAgent")
                        .sysPrompt("Agent with streaming")
                        .model(streamingModel)
                        .toolkit(toolkit)
                        .memory(memory)
                        .build();

        Msg question1 = TestUtils.createUserMessage("User", "Hello");
        Msg streamingResponse = streamingAgent.call(question1).block(TEST_TIMEOUT);

        assertNotNull(streamingResponse, "Streaming response should not be null");
        System.out.println("Streaming response: " + streamingResponse);

        // Configuration 2: Streaming disabled
        Model nonStreamingModel =
                DashScopeChatModel.builder().apiKey(apiKey).modelName(DASHSCOPE_MODEL).stream(false)
                        .build();

        ReActAgent nonStreamingAgent =
                ReActAgent.builder()
                        .name("NonStreamingAgent")
                        .sysPrompt("Agent without streaming")
                        .model(nonStreamingModel)
                        .toolkit(toolkit)
                        .memory(new InMemoryMemory())
                        .build();

        Msg question2 = TestUtils.createUserMessage("User", "Hello again");
        Msg nonStreamingResponse = nonStreamingAgent.call(question2).block(TEST_TIMEOUT);

        assertNotNull(nonStreamingResponse, "Non-streaming response should not be null");
        System.out.println("Non-streaming response: " + nonStreamingResponse);

        // Both configurations should work
        assertNotNull(streamingResponse, "Should have streaming response");
        assertNotNull(nonStreamingResponse, "Should have non-streaming response");
    }

    @Test
    @DisplayName("Should support multiple models in pipeline")
    void testMultipleModelsInPipeline() {
        System.out.println("\n=== Test: Multiple Models in Pipeline ===");

        String apiKey = System.getenv("DASHSCOPE_API_KEY");

        // Create two agents with different model configurations
        Model model1 =
                DashScopeChatModel.builder().apiKey(apiKey).modelName(DASHSCOPE_MODEL).stream(true)
                        .build();

        Model model2 =
                DashScopeChatModel.builder().apiKey(apiKey).modelName(DASHSCOPE_MODEL).stream(false)
                        .build();

        ReActAgent agent1 =
                ReActAgent.builder()
                        .name("Agent1")
                        .sysPrompt("First agent in pipeline")
                        .model(model1)
                        .toolkit(toolkit)
                        .memory(new InMemoryMemory())
                        .build();

        ReActAgent agent2 =
                ReActAgent.builder()
                        .name("Agent2")
                        .sysPrompt("Second agent in pipeline")
                        .model(model2)
                        .toolkit(toolkit)
                        .memory(new InMemoryMemory())
                        .build();

        // Create sequential pipeline
        Pipeline pipeline = new SequentialPipeline(List.of(agent1, agent2));

        // Execute pipeline
        Msg input = TestUtils.createUserMessage("User", "Process this message");
        System.out.println("Pipeline input: " + input);

        Object pipelineResult = pipeline.execute(input).block(TEST_TIMEOUT);

        assertNotNull(pipelineResult, "Pipeline should produce result");
        System.out.println("Pipeline completed successfully with result: " + pipelineResult);

        // Verify both agents processed messages
        assertTrue(
                agent1.getMemory().getMessages().size() > 0,
                "Agent1 should have processed messages");
        assertTrue(
                agent2.getMemory().getMessages().size() > 0,
                "Agent2 should have processed messages");
    }

    @Test
    @DisplayName("Should maintain separate contexts for different model instances")
    void testModelInstanceIsolation() {
        System.out.println("\n=== Test: Model Instance Isolation ===");

        String apiKey = System.getenv("DASHSCOPE_API_KEY");

        // Create two independent agents with same model type but different memories
        Model model1 =
                DashScopeChatModel.builder().apiKey(apiKey).modelName(DASHSCOPE_MODEL).stream(true)
                        .build();

        Model model2 =
                DashScopeChatModel.builder().apiKey(apiKey).modelName(DASHSCOPE_MODEL).stream(true)
                        .build();

        InMemoryMemory memory1 = new InMemoryMemory();
        InMemoryMemory memory2 = new InMemoryMemory();

        ReActAgent agent1 =
                ReActAgent.builder()
                        .name("IsolatedAgent1")
                        .sysPrompt("First isolated agent")
                        .model(model1)
                        .toolkit(toolkit)
                        .memory(memory1)
                        .build();

        ReActAgent agent2 =
                ReActAgent.builder()
                        .name("IsolatedAgent2")
                        .sysPrompt("Second isolated agent")
                        .model(model2)
                        .toolkit(toolkit)
                        .memory(memory2)
                        .build();

        // Send different messages to each agent
        Msg message1 = TestUtils.createUserMessage("User", "My favorite color is blue");
        Msg message2 = TestUtils.createUserMessage("User", "My favorite color is red");

        agent1.call(message1).block(TEST_TIMEOUT);
        agent2.call(message2).block(TEST_TIMEOUT);

        // Verify memories are isolated
        int memory1Size = memory1.getMessages().size();
        int memory2Size = memory2.getMessages().size();

        System.out.println("Agent1 memory: " + memory1Size + " messages");
        System.out.println("Agent2 memory: " + memory2Size + " messages");

        // Both should have messages (proving they work independently)
        assertTrue(memory1Size > 0, "Agent1 should have messages in memory");
        assertTrue(memory2Size > 0, "Agent2 should have messages in memory");

        // Verify each agent maintains its own context
        boolean agent1HasBlue =
                memory1.getMessages().stream()
                        .anyMatch(
                                m -> {
                                    String text = TestUtils.extractTextContent(m);
                                    return text != null && text.toLowerCase().contains("blue");
                                });

        boolean agent2HasRed =
                memory2.getMessages().stream()
                        .anyMatch(
                                m -> {
                                    String text = TestUtils.extractTextContent(m);
                                    return text != null && text.toLowerCase().contains("red");
                                });

        assertTrue(agent1HasBlue, "Agent1 should have 'blue' in context");
        assertTrue(agent2HasRed, "Agent2 should have 'red' in context");
    }
}
