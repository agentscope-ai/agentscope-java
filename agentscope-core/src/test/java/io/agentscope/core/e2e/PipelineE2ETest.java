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
package io.agentscope.core.e2e;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import io.agentscope.core.ReActAgent;
import io.agentscope.core.agent.test.TestUtils;
import io.agentscope.core.e2e.providers.ModelProvider;
import io.agentscope.core.message.Msg;
import io.agentscope.core.pipeline.FanoutPipeline;
import io.agentscope.core.pipeline.MsgHub;
import io.agentscope.core.pipeline.Pipelines;
import io.agentscope.core.pipeline.SequentialPipeline;
import io.agentscope.core.tool.Toolkit;
import java.time.Duration;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.parallel.Execution;
import org.junit.jupiter.api.parallel.ExecutionMode;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

/**
 * E2E tests for Pipeline and MsgHub multi-agent orchestration.
 *
 * <p>Tests sequential pipelines, fanout pipelines, and MsgHub message broadcasting.
 */
@Tag("e2e")
@Tag("pipeline")
@ExtendWith(E2ETestCondition.class)
@Execution(ExecutionMode.CONCURRENT)
@DisplayName("Pipeline E2E Tests")
class PipelineE2ETest {

    private static final Duration TEST_TIMEOUT = Duration.ofSeconds(300);

    // ==================== Sequential Pipeline Tests ====================

    @ParameterizedTest
    @MethodSource("io.agentscope.core.e2e.ProviderFactory#getBasicProviders")
    @DisplayName("Should execute sequential pipeline with two agents")
    void testSequentialPipeline(ModelProvider provider) {
        System.out.println(
                "\n=== Test: Sequential Pipeline with " + provider.getProviderName() + " ===");

        Toolkit toolkit = new Toolkit();

        // Create two agents: analyzer and summarizer
        ReActAgent analyzer = provider.createAgentBuilder("Analyzer", toolkit).build();
        analyzer.observe(
                Msg.builder()
                        .textContent(
                                "You are an analyzer. Analyze the input and provide key points. "
                                        + "Keep your response concise.")
                        .build());

        ReActAgent summarizer = provider.createAgentBuilder("Summarizer", toolkit).build();
        summarizer.observe(
                Msg.builder()
                        .textContent(
                                "You are a summarizer. Take the analysis and create a brief "
                                        + "one-sentence summary.")
                        .build());

        // Create sequential pipeline
        SequentialPipeline pipeline = new SequentialPipeline(List.of(analyzer, summarizer));

        Msg input =
                TestUtils.createUserMessage(
                        "User", "Java is a programming language. Python is also a language.");

        Msg result = pipeline.execute(input).block(TEST_TIMEOUT);

        assertNotNull(result, "Pipeline should produce result");
        assertTrue(
                ContentValidator.hasMeaningfulContent(result),
                "Result should have content for " + provider.getModelName());

        System.out.println("Final result: " + TestUtils.extractTextContent(result));
        System.out.println(
                "✓ Sequential pipeline test completed for " + provider.getProviderName());
    }

    @ParameterizedTest
    @MethodSource("io.agentscope.core.e2e.ProviderFactory#getBasicProviders")
    @DisplayName("Should execute sequential pipeline using utility method")
    void testSequentialPipelineUtility(ModelProvider provider) {
        System.out.println(
                "\n=== Test: Sequential Pipeline Utility with "
                        + provider.getProviderName()
                        + " ===");

        Toolkit toolkit = new Toolkit();

        ReActAgent agent1 = provider.createAgentBuilder("Agent1", toolkit).build();
        agent1.observe(
                Msg.builder()
                        .textContent("Reply with 'Step 1 complete' followed by the input.")
                        .build());

        ReActAgent agent2 = provider.createAgentBuilder("Agent2", toolkit).build();
        agent2.observe(
                Msg.builder()
                        .textContent("Reply with 'Step 2 complete' followed by the input.")
                        .build());

        Msg input = TestUtils.createUserMessage("User", "Start");

        // Use utility method
        Msg result = Pipelines.sequential(List.of(agent1, agent2), input).block(TEST_TIMEOUT);

        assertNotNull(result, "Pipeline should produce result");
        assertTrue(
                ContentValidator.hasMeaningfulContent(result),
                "Result should have content for " + provider.getModelName());

        System.out.println("Result: " + TestUtils.extractTextContent(result));
        System.out.println(
                "✓ Sequential pipeline utility test completed for " + provider.getProviderName());
    }

    // ==================== Fanout Pipeline Tests ====================

    @ParameterizedTest
    @MethodSource("io.agentscope.core.e2e.ProviderFactory#getBasicProviders")
    @DisplayName("Should execute fanout pipeline with parallel agents")
    void testFanoutPipeline(ModelProvider provider) {
        System.out.println(
                "\n=== Test: Fanout Pipeline with " + provider.getProviderName() + " ===");

        Toolkit toolkit = new Toolkit();

        // Create multiple agents with different perspectives
        ReActAgent optimist =
                provider.createAgentBuilder("Optimist", toolkit)
                        .sysPrompt(
                                "You are an optimist. Find the positive aspects in the input. "
                                        + "Reply in one sentence.")
                        .build();

        ReActAgent pessimist =
                provider.createAgentBuilder("Pessimist", toolkit)
                        .sysPrompt(
                                "You are a pessimist. Find the potential problems in the input. "
                                        + "Reply in one sentence.")
                        .build();

        ReActAgent realist =
                provider.createAgentBuilder("Realist", toolkit)
                        .sysPrompt(
                                "You are a realist. Give a balanced view of the input. "
                                        + "Reply in one sentence.")
                        .build();

        // Create fanout pipeline (parallel execution)
        FanoutPipeline pipeline = new FanoutPipeline(List.of(optimist, pessimist, realist));

        Msg input = TestUtils.createUserMessage("User", "The weather will change tomorrow.");

        List<Msg> results = pipeline.execute(input).block(TEST_TIMEOUT);

        assertNotNull(results, "Pipeline should produce results");
        assertEquals(3, results.size(), "Should have 3 results from 3 agents");

        System.out.println("Fanout results:");
        for (int i = 0; i < results.size(); i++) {
            System.out.println(
                    "  Agent " + (i + 1) + ": " + TestUtils.extractTextContent(results.get(i)));
        }

        System.out.println("✓ Fanout pipeline test completed for " + provider.getProviderName());
    }

    @ParameterizedTest
    @MethodSource("io.agentscope.core.e2e.ProviderFactory#getBasicProviders")
    @DisplayName("Should execute fanout pipeline using utility method")
    void testFanoutPipelineUtility(ModelProvider provider) {
        System.out.println(
                "\n=== Test: Fanout Pipeline Utility with " + provider.getProviderName() + " ===");

        Toolkit toolkit = new Toolkit();

        ReActAgent agent1 =
                provider.createAgentBuilder("Agent1", toolkit)
                        .sysPrompt("Reply with 'Agent1: ' followed by the input.")
                        .build();

        ReActAgent agent2 =
                provider.createAgentBuilder("Agent2", toolkit)
                        .sysPrompt("Reply with 'Agent2: ' followed by the input.")
                        .build();

        Msg input = TestUtils.createUserMessage("User", "Hello");

        // Use utility method
        List<Msg> results = Pipelines.fanout(List.of(agent1, agent2), input).block(TEST_TIMEOUT);

        assertNotNull(results, "Pipeline should produce results");
        assertEquals(2, results.size(), "Should have 2 results");

        results.forEach(r -> System.out.println("  " + TestUtils.extractTextContent(r)));

        System.out.println(
                "✓ Fanout pipeline utility test completed for " + provider.getProviderName());
    }

    // ==================== MsgHub Tests ====================

    @ParameterizedTest
    @MethodSource("io.agentscope.core.e2e.ProviderFactory#getMultiAgentProviders")
    @DisplayName("Should broadcast messages in MsgHub")
    void testMsgHubBroadcast(ModelProvider provider) {
        assumeTrue(
                provider.getCapabilities()
                        .contains(
                                io.agentscope.core.e2e.providers.ModelCapability
                                        .MULTI_AGENT_FORMATTER),
                "Skipping: "
                        + provider.getProviderName()
                        + " does not support multi-agent formatter");

        System.out.println(
                "\n=== Test: MsgHub Broadcast with " + provider.getProviderName() + " ===");

        Toolkit toolkit = new Toolkit();

        // Create agents for conversation
        ReActAgent alice =
                provider.createAgentBuilder("Alice", toolkit)
                        .sysPrompt(
                                "You are Alice. Respond briefly to the conversation. "
                                        + "Keep responses under 20 words.")
                        .build();

        ReActAgent bob =
                provider.createAgentBuilder("Bob", toolkit)
                        .sysPrompt(
                                "You are Bob. Respond briefly to the conversation. "
                                        + "Keep responses under 20 words.")
                        .build();

        // Create announcement message
        Msg announcement = TestUtils.createUserMessage("Host", "Let's discuss the weather today.");

        try (MsgHub hub =
                MsgHub.builder().participants(alice, bob).announcement(announcement).build()) {

            // Enter the hub
            hub.enter().block(TEST_TIMEOUT);

            // Alice speaks first
            Msg aliceResponse = alice.call().block(TEST_TIMEOUT);
            assertNotNull(aliceResponse, "Alice should respond");
            System.out.println("Alice: " + TestUtils.extractTextContent(aliceResponse));

            // Bob responds (automatically sees Alice's message)
            Msg bobResponse = bob.call().block(TEST_TIMEOUT);
            assertNotNull(bobResponse, "Bob should respond");
            System.out.println("Bob: " + TestUtils.extractTextContent(bobResponse));

            System.out.println(
                    "✓ MsgHub broadcast test completed for " + provider.getProviderName());
        }
    }

    @ParameterizedTest
    @MethodSource("io.agentscope.core.e2e.ProviderFactory#getMultiAgentProviders")
    @DisplayName("Should handle dynamic participant addition in MsgHub")
    void testMsgHubDynamicParticipants(ModelProvider provider) {
        assumeTrue(
                provider.getCapabilities()
                        .contains(
                                io.agentscope.core.e2e.providers.ModelCapability
                                        .MULTI_AGENT_FORMATTER),
                "Skipping: "
                        + provider.getProviderName()
                        + " does not support multi-agent formatter");

        System.out.println(
                "\n=== Test: MsgHub Dynamic Participants with "
                        + provider.getProviderName()
                        + " ===");

        Toolkit toolkit = new Toolkit();

        ReActAgent alice =
                provider.createAgentBuilder("Alice", toolkit)
                        .sysPrompt("You are Alice. Say hello briefly.")
                        .build();

        ReActAgent bob =
                provider.createAgentBuilder("Bob", toolkit)
                        .sysPrompt("You are Bob. Say hello briefly.")
                        .build();

        ReActAgent charlie =
                provider.createAgentBuilder("Charlie", toolkit)
                        .sysPrompt("You are Charlie. Say hello briefly.")
                        .build();

        Msg announcement = TestUtils.createUserMessage("Host", "Welcome to the chat!");

        try (MsgHub hub =
                MsgHub.builder()
                        .participants(alice, bob) // Start with Alice and Bob
                        .announcement(announcement)
                        .build()) {

            hub.enter().block(TEST_TIMEOUT);

            // Alice speaks
            Msg aliceMsg = alice.call().block(TEST_TIMEOUT);
            System.out.println("Alice: " + TestUtils.extractTextContent(aliceMsg));

            // Add Charlie dynamically
            hub.add(charlie).block(TEST_TIMEOUT);
            System.out.println("(Charlie joined the chat)");

            // Bob speaks (Charlie should now be listening)
            Msg bobMsg = bob.call().block(TEST_TIMEOUT);
            System.out.println("Bob: " + TestUtils.extractTextContent(bobMsg));

            // Charlie speaks (should have context from previous messages)
            Msg charlieMsg = charlie.call().block(TEST_TIMEOUT);
            System.out.println("Charlie: " + TestUtils.extractTextContent(charlieMsg));

            // Verify all participants
            assertEquals(3, hub.getParticipants().size(), "Should have 3 participants");

            System.out.println(
                    "✓ MsgHub dynamic participants test completed for "
                            + provider.getProviderName());
        }
    }

    // ==================== Combined Pipeline Tests ====================

    @ParameterizedTest
    @MethodSource("io.agentscope.core.e2e.ProviderFactory#getBasicProviders")
    @DisplayName("Should combine fanout and sequential pipelines")
    void testCombinedPipelines(ModelProvider provider) {
        System.out.println(
                "\n=== Test: Combined Pipelines with " + provider.getProviderName() + " ===");

        Toolkit toolkit = new Toolkit();

        // First stage: fanout for parallel analysis
        ReActAgent analyst1 =
                provider.createAgentBuilder("Analyst1", toolkit)
                        .sysPrompt("Analyze the technical aspects. Reply in one sentence.")
                        .build();

        ReActAgent analyst2 =
                provider.createAgentBuilder("Analyst2", toolkit)
                        .sysPrompt("Analyze the business aspects. Reply in one sentence.")
                        .build();

        FanoutPipeline fanout = new FanoutPipeline(List.of(analyst1, analyst2));

        Msg input = TestUtils.createUserMessage("User", "New AI startup founded in 2025.");

        // Execute fanout
        List<Msg> analysisResults = fanout.execute(input).block(TEST_TIMEOUT);

        assertNotNull(analysisResults, "Fanout should produce results");
        assertEquals(2, analysisResults.size(), "Should have 2 analysis results");

        System.out.println("Analysis results:");
        analysisResults.forEach(r -> System.out.println("  - " + TestUtils.extractTextContent(r)));

        // Second stage: sequential synthesis
        ReActAgent synthesizer =
                provider.createAgentBuilder("Synthesizer", toolkit)
                        .sysPrompt(
                                "Combine the analyses into a final summary. Reply in one sentence.")
                        .build();

        // Combine analysis results as input to synthesizer
        String combinedAnalysis =
                analysisResults.stream()
                        .map(TestUtils::extractTextContent)
                        .reduce((a, b) -> a + "\n" + b)
                        .orElse("");

        Msg synthesisInput = TestUtils.createUserMessage("User", combinedAnalysis);
        Msg finalResult = synthesizer.call(synthesisInput).block(TEST_TIMEOUT);

        assertNotNull(finalResult, "Synthesis should produce result");
        System.out.println("Final synthesis: " + TestUtils.extractTextContent(finalResult));

        System.out.println("✓ Combined pipelines test completed for " + provider.getProviderName());
    }
}
