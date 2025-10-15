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
package io.agentscope.core.pipeline;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

import io.agentscope.core.ReActAgent;
import io.agentscope.core.agent.test.MockModel;
import io.agentscope.core.agent.test.TestUtils;
import io.agentscope.core.memory.InMemoryMemory;
import io.agentscope.core.message.Msg;
import io.agentscope.core.tool.Toolkit;
import java.time.Duration;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/**
 * Unit tests for FanoutPipeline.
 *
 * <p>These tests verify fanout execution, result aggregation, failure propagation, and builder
 * configuration.
 */
@Tag("unit")
@DisplayName("FanoutPipeline Unit Tests")
class FanoutPipelineTest {

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
    @DisplayName("Should execute all agents when running concurrently")
    void shouldExecuteAgentsConcurrently() {
        ReActAgent agent1 = createAgent("Agent1", model1);
        ReActAgent agent2 = createAgent("Agent2", model2);

        FanoutPipeline pipeline = new FanoutPipeline(List.of(agent1, agent2));

        Msg input = TestUtils.createUserMessage("User", "fanout");
        List<Msg> results = pipeline.execute(input).block(TIMEOUT);

        assertNotNull(results, "Fanout pipeline should produce results");
        assertEquals(2, results.size(), "Expected one response per agent");

        Set<String> agentNames = results.stream().map(Msg::getName).collect(Collectors.toSet());
        assertEquals(Set.of("Agent1", "Agent2"), agentNames, "Each agent should respond once");

        Set<String> payloads =
                results.stream().map(TestUtils::extractTextContent).collect(Collectors.toSet());
        assertEquals(
                Set.of("Response from agent 1", "Response from agent 2"),
                payloads,
                "Responses should contain agent outputs");

        assertEquals(1, model1.getCallCount(), "First model should be invoked once");
        assertEquals(1, model2.getCallCount(), "Second model should be invoked once");
    }

    @Test
    @DisplayName("Should preserve agent order when running sequentially")
    void shouldExecuteAgentsSequentiallyWhenDisabled() {
        ReActAgent agent1 = createAgent("Agent1", model1);
        ReActAgent agent2 = createAgent("Agent2", model2);

        FanoutPipeline pipeline = new FanoutPipeline(List.of(agent1, agent2), false);

        Msg input = TestUtils.createUserMessage("User", "sequential fanout");
        List<Msg> results = pipeline.execute(input).block(TIMEOUT);

        assertNotNull(results, "Sequential fanout should return results");
        assertEquals(2, results.size(), "Expected two results in order");
        assertFalse(
                pipeline.isConcurrentEnabled(),
                "Pipeline should be configured for sequential execution");

        assertEquals("Agent1", results.get(0).getName(), "First agent response should lead");
        assertEquals(
                "Response from agent 1",
                TestUtils.extractTextContent(results.get(0)),
                "First agent response payload mismatch");
        assertEquals("Agent2", results.get(1).getName(), "Second agent response should follow");
        assertEquals(
                "Response from agent 2",
                TestUtils.extractTextContent(results.get(1)),
                "Second agent response payload mismatch");
    }

    @Test
    @DisplayName("Should propagate the first failure when a single agent fails")
    void shouldPropagatePartialFailure() {
        MockModel errorModel = new MockModel("Error response").withError("Simulated error");
        ReActAgent successAgent = createAgent("SuccessAgent", model1);
        ReActAgent failingAgent = createAgent("ErrorAgent", errorModel);

        FanoutPipeline pipeline = new FanoutPipeline(List.of(successAgent, failingAgent));
        Msg input = TestUtils.createUserMessage("User", "partial failure");

        RuntimeException exception =
                assertThrows(
                        RuntimeException.class,
                        () -> pipeline.execute(input).block(TIMEOUT),
                        "Fanout pipeline should surface the failure");

        assertEquals("Simulated error", exception.getMessage(), "Unexpected exception message");
        assertEquals(1, model1.getCallCount(), "Successful agent should still be invoked");
        assertEquals(1, errorModel.getCallCount(), "Failing agent should be invoked once");
    }

    @Test
    @DisplayName("Should propagate errors when all agents fail")
    void shouldPropagateAllFailures() {
        MockModel errorModel1 = new MockModel("Error 1").withError("Error 1");
        MockModel errorModel2 = new MockModel("Error 2").withError("Error 2");

        ReActAgent agent1 = createAgent("ErrorAgent1", errorModel1);
        ReActAgent agent2 = createAgent("ErrorAgent2", errorModel2);

        FanoutPipeline pipeline = new FanoutPipeline(List.of(agent1, agent2));
        Msg input = TestUtils.createUserMessage("User", "all failure");

        RuntimeException exception =
                assertThrows(RuntimeException.class, () -> pipeline.execute(input).block(TIMEOUT));

        assertEquals(
                "Error 1", exception.getMessage(), "Should return the first error encountered");
        assertEquals(1, errorModel1.getCallCount(), "First failing agent should be invoked");
        assertEquals(1, errorModel2.getCallCount(), "Second failing agent should be invoked");
    }

    @Test
    @DisplayName("Should configure pipeline through builder")
    void shouldBuildPipelineViaBuilder() {
        ReActAgent agent1 = createAgent("Agent1", model1);
        ReActAgent agent2 = createAgent("Agent2", model2);
        ReActAgent agent3 = createAgent("Agent3", model3);

        FanoutPipeline pipeline =
                FanoutPipeline.builder()
                        .addAgent(agent1)
                        .addAgents(List.of(agent2, agent3))
                        .sequential()
                        .build();

        assertNotNull(pipeline, "Builder should create pipeline");
        assertEquals(3, pipeline.size(), "Pipeline should include all registered agents");
        assertFalse(pipeline.isConcurrentEnabled(), "Builder should respect sequential flag");

        Msg input = TestUtils.createUserMessage("User", "builder validation");
        List<Msg> results = pipeline.execute(input).block(TIMEOUT);

        assertNotNull(results, "Builder-produced pipeline should execute");
        assertEquals(
                List.of("Agent1", "Agent2", "Agent3"),
                results.stream().map(Msg::getName).toList(),
                "Sequential builder should maintain insertion order");
        assertEquals(
                List.of("Response from agent 1", "Response from agent 2", "Response from agent 3"),
                results.stream().map(TestUtils::extractTextContent).toList(),
                "Result payloads should match agent outputs");
    }

    private ReActAgent createAgent(String name, MockModel model) {
        return new ReActAgent(name, "Test agent", model, toolkit, memory);
    }
}
