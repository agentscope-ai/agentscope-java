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

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;

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
 * Unit tests for SequentialPipeline.
 *
 * <p>These tests verify sequential execution, error propagation, edge cases, and builder support.
 */
@Tag("unit")
@DisplayName("SequentialPipeline Unit Tests")
class SequentialPipelineTest {

    private static final Duration TIMEOUT = Duration.ofSeconds(3);

    private MockModel model1;
    private MockModel model2;
    private MockModel model3;
    private InMemoryMemory memory;
    private Toolkit toolkit;

    @BeforeEach
    void setUp() {
        model1 = new MockModel("Response from agent 1");
        model2 = new MockModel("Response from agent 2");
        model3 = new MockModel("Response from agent 3");
        memory = new InMemoryMemory();
        toolkit = new Toolkit();
    }

    @Test
    @DisplayName("Should execute agents sequentially and return final response")
    void shouldExecuteAgentsSequentially() {
        SequentialPipeline pipeline =
                new SequentialPipeline(
                        List.of(createAgent("Agent1", model1), createAgent("Agent2", model2)));

        Msg input = TestUtils.createUserMessage("User", "sequential execution");
        Msg result = pipeline.execute(input).block(TIMEOUT);

        assertNotNull(result, "Pipeline should produce a response");
        assertEquals(
                "Response from agent 2",
                TestUtils.extractTextContent(result),
                "Final agent output should be returned");
        assertEquals(1, model1.getCallCount(), "First model should be invoked once");
        assertEquals(1, model2.getCallCount(), "Second model should be invoked once");
    }

    @Test
    @DisplayName("Should propagate errors from failing agents")
    void shouldPropagateErrors() {
        MockModel errorModel = new MockModel("Error response").withError("Simulated error");
        SequentialPipeline pipeline =
                new SequentialPipeline(List.of(createAgent("ErrorAgent", errorModel)));

        Msg input = TestUtils.createUserMessage("User", "error propagation");

        RuntimeException exception =
                assertThrows(
                        RuntimeException.class,
                        () -> pipeline.execute(input).block(TIMEOUT),
                        "Sequential pipeline should propagate agent errors");

        assertEquals("Simulated error", exception.getMessage(), "Unexpected exception message");
        assertEquals(1, errorModel.getCallCount(), "Failing agent should be invoked");
    }

    @Test
    @DisplayName("Should return original input when pipeline has no agents")
    void shouldHandleEmptyPipeline() {
        SequentialPipeline pipeline = new SequentialPipeline(List.of());
        Msg input = TestUtils.createUserMessage("User", "empty pipeline");

        Msg result = pipeline.execute(input).block(TIMEOUT);

        assertSame(input, result, "Empty pipeline should yield the original message");
    }

    @Test
    @DisplayName("Should execute single agent and return its response")
    void shouldHandleSingleAgent() {
        SequentialPipeline pipeline =
                new SequentialPipeline(List.of(createAgent("SoloAgent", model1)));

        Msg input = TestUtils.createUserMessage("User", "single agent");
        Msg result = pipeline.execute(input).block(TIMEOUT);

        assertNotNull(result, "Single agent pipeline should produce a response");
        assertEquals(
                "Response from agent 1",
                TestUtils.extractTextContent(result),
                "Single agent response mismatch");
        assertEquals(1, model1.getCallCount(), "Model should be invoked once");
    }

    @Test
    @DisplayName("Should process multiple agents and return last output")
    void shouldHandleMultipleAgents() {
        SequentialPipeline pipeline =
                new SequentialPipeline(
                        List.of(
                                createAgent("Agent1", model1),
                                createAgent("Agent2", model2),
                                createAgent("Agent3", model3)));

        Msg input = TestUtils.createUserMessage("User", "multi agent");
        Msg result = pipeline.execute(input).block(TIMEOUT);

        assertNotNull(result, "Pipeline should produce a response");
        assertEquals(
                "Response from agent 3",
                TestUtils.extractTextContent(result),
                "Final agent response mismatch");
        assertEquals(1, model1.getCallCount(), "First model should be invoked once");
        assertEquals(1, model2.getCallCount(), "Second model should be invoked once");
        assertEquals(1, model3.getCallCount(), "Third model should be invoked once");
    }

    @Test
    @DisplayName("Should configure pipeline via builder")
    void shouldBuildPipelineUsingBuilder() {
        SequentialPipeline pipeline =
                SequentialPipeline.builder()
                        .addAgent(createAgent("Agent1", model1))
                        .addAgents(
                                List.of(
                                        createAgent("Agent2", model2),
                                        createAgent("Agent3", model3)))
                        .build();

        assertNotNull(pipeline, "Builder should create pipeline");
        assertEquals(3, pipeline.size(), "Builder should include all agents");

        Msg input = TestUtils.createUserMessage("User", "builder pipeline");
        Msg result = pipeline.execute(input).block(TIMEOUT);

        assertNotNull(result, "Builder pipeline should execute");
        assertEquals(
                "Response from agent 3",
                TestUtils.extractTextContent(result),
                "Builder pipeline should return last agent response");
    }

    private ReActAgent createAgent(String name, MockModel model) {
        return ReActAgent.builder()
                .name(name)
                .sysPrompt("Test agent")
                .model(model)
                .toolkit(toolkit)
                .memory(memory)
                .build();
    }
}
