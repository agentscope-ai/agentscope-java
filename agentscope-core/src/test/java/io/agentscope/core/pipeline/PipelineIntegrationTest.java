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
package io.agentscope.core.pipeline;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.agentscope.core.ReActAgent;
import io.agentscope.core.agent.test.MockModel;
import io.agentscope.core.agent.test.TestUtils;
import io.agentscope.core.memory.InMemoryMemory;
import io.agentscope.core.message.Msg;
import io.agentscope.core.tool.Toolkit;
import java.time.Duration;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/**
 * Integration tests for Pipeline functionality.
 *
 * <p>These tests verify complex workflows, nested pipelines, and pipeline composition.
 *
 * <p>Tagged as "integration" - tests component interaction.
 */
@Tag("integration")
@DisplayName("Pipeline Integration Tests")
class PipelineIntegrationTest {

    private MockModel model1;
    private MockModel model2;
    private MockModel model3;
    private InMemoryMemory memory;
    private Toolkit toolkit;

    @BeforeEach
    void setUp() {
        model1 = new MockModel("Agent 1 response");
        model2 = new MockModel("Agent 2 response");
        model3 = new MockModel("Agent 3 response");
        memory = new InMemoryMemory();
        toolkit = new Toolkit();
    }

    @Test
    @DisplayName("Should handle complex workflow with mixed pipelines")
    void testComplexWorkflow() {
        // Create agents
        ReActAgent agent1 =
                ReActAgent.builder()
                        .name("Agent1")
                        .sysPrompt("Analyzer")
                        .model(model1)
                        .toolkit(toolkit)
                        .memory(memory)
                        .build();

        ReActAgent agent2 =
                ReActAgent.builder()
                        .name("Agent2")
                        .sysPrompt("Reviewer")
                        .model(model2)
                        .toolkit(toolkit)
                        .memory(memory)
                        .build();

        ReActAgent agent3 =
                ReActAgent.builder()
                        .name("Agent3")
                        .sysPrompt("Finalizer")
                        .model(model3)
                        .toolkit(toolkit)
                        .memory(memory)
                        .build();

        // Create sequential pipeline
        SequentialPipeline sequentialPart = new SequentialPipeline(List.of(agent1, agent2));

        // Execute workflow
        Msg input = TestUtils.createUserMessage("User", "Complex workflow test");
        Msg intermediateResult = sequentialPart.execute(input).block(Duration.ofSeconds(5));

        assertNotNull(intermediateResult, "Sequential part should complete");

        // Continue with another agent
        Msg finalResult = agent3.call(intermediateResult).block(Duration.ofSeconds(5));

        assertNotNull(finalResult, "Final result should exist");

        // Verify all agents were called
        assertTrue(model1.getCallCount() >= 1, "Agent 1 should be called");
        assertTrue(model2.getCallCount() >= 1, "Agent 2 should be called");
        assertTrue(model3.getCallCount() >= 1, "Agent 3 should be called");
    }

    @Test
    @DisplayName("Should support nested pipeline structures")
    void testNestedPipelines() {
        // Create inner sequential pipeline
        ReActAgent innerAgent1 =
                ReActAgent.builder()
                        .name("InnerAgent1")
                        .sysPrompt("Inner 1")
                        .model(model1)
                        .toolkit(toolkit)
                        .memory(memory)
                        .build();

        ReActAgent innerAgent2 =
                ReActAgent.builder()
                        .name("InnerAgent2")
                        .sysPrompt("Inner 2")
                        .model(model2)
                        .toolkit(toolkit)
                        .memory(memory)
                        .build();

        SequentialPipeline innerPipeline =
                new SequentialPipeline(List.of(innerAgent1, innerAgent2));

        // Execute inner pipeline
        Msg input = TestUtils.createUserMessage("User", "Nested pipeline test");
        Msg innerResult = innerPipeline.execute(input).block(Duration.ofSeconds(5));

        assertNotNull(innerResult, "Inner pipeline should complete");

        // Create outer pipeline with another agent
        ReActAgent outerAgent =
                ReActAgent.builder()
                        .name("OuterAgent")
                        .sysPrompt("Outer")
                        .model(model3)
                        .toolkit(toolkit)
                        .memory(memory)
                        .build();

        SequentialPipeline outerPipeline = new SequentialPipeline(List.of(innerAgent1, outerAgent));

        Msg outerResult = outerPipeline.execute(input).block(Duration.ofSeconds(5));
        assertNotNull(outerResult, "Outer pipeline should complete");

        // Verify nested execution
        assertTrue(model1.getCallCount() >= 2, "Inner agent 1 called multiple times");
        assertTrue(model2.getCallCount() >= 1, "Inner agent 2 called");
        assertTrue(model3.getCallCount() >= 1, "Outer agent called");
    }

    @Test
    @DisplayName("Should compose pipelines effectively")
    void testPipelineComposition() {
        // Create first stage - sequential
        ReActAgent stage1Agent1 =
                ReActAgent.builder()
                        .name("Stage1Agent1")
                        .sysPrompt("Stage 1-1")
                        .model(model1)
                        .toolkit(toolkit)
                        .memory(memory)
                        .build();

        ReActAgent stage1Agent2 =
                ReActAgent.builder()
                        .name("Stage1Agent2")
                        .sysPrompt("Stage 1-2")
                        .model(model2)
                        .toolkit(toolkit)
                        .memory(memory)
                        .build();

        SequentialPipeline stage1 = new SequentialPipeline(List.of(stage1Agent1, stage1Agent2));

        // Execute first stage
        Msg input = TestUtils.createUserMessage("User", "Pipeline composition test");
        Msg stage1Result = stage1.execute(input).block(Duration.ofSeconds(5));

        assertNotNull(stage1Result, "Stage 1 should complete");

        // Create second stage - fanout (conceptually)
        ReActAgent stage2Agent =
                ReActAgent.builder()
                        .name("Stage2Agent")
                        .sysPrompt("Stage 2")
                        .model(model3)
                        .toolkit(toolkit)
                        .memory(memory)
                        .build();

        // Execute second stage with result from first
        Msg stage2Result = stage2Agent.call(stage1Result).block(Duration.ofSeconds(5));

        assertNotNull(stage2Result, "Stage 2 should complete");

        // Verify composition worked
        assertTrue(model1.getCallCount() >= 1, "Stage 1 agent 1 called");
        assertTrue(model2.getCallCount() >= 1, "Stage 1 agent 2 called");
        assertTrue(model3.getCallCount() >= 1, "Stage 2 agent called");
    }
}
