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
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.agentscope.core.ReActAgent;
import io.agentscope.core.agent.Event;
import io.agentscope.core.agent.EventType;
import io.agentscope.core.agent.StreamOptions;
import io.agentscope.core.agent.test.MockModel;
import io.agentscope.core.agent.test.TestUtils;
import io.agentscope.core.exception.CompositeAgentException;
import io.agentscope.core.memory.InMemoryMemory;
import io.agentscope.core.message.Msg;
import io.agentscope.core.tool.Toolkit;
import java.time.Duration;
import java.util.List;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;
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

    @BeforeEach
    void setUp() {
        model1 = new MockModel("Response from agent 1");
        model2 = new MockModel("Response from agent 2");
        model3 = new MockModel("Response from agent 3");
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

        assertInstanceOf(CompositeAgentException.class, exception);
        CompositeAgentException compositeException = (CompositeAgentException) exception;
        assertEquals(1, compositeException.getCauses().size(), "Expected one cause");
        assertTrue(
                exception.getMessage().contains("Simulated error"), "Unexpected exception message");
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

        // In concurrent execution, either error could be captured first
        String errorMsg = exception.getMessage();
        assertNotNull(errorMsg, "Should return an error");
        assertTrue(
                errorMsg.contains("Error 1"),
                "Should return either Error 1 or Error 2, got: " + errorMsg);
        assertTrue(
                errorMsg.contains("Error 2"),
                "Should return either Error 1 or Error 2, got: " + errorMsg);
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
        return ReActAgent.builder()
                .name(name)
                .sysPrompt("Test agent")
                .model(model)
                .toolkit(new Toolkit()) // Each agent gets independent toolkit for thread safety
                .memory(new InMemoryMemory()) // Each agent gets independent memory for thread
                // safety
                .build();
    }

    // ==================== Streaming Tests ====================

    @Test
    @DisplayName("Should stream events from all agents when running concurrently")
    void shouldStreamEventsConcurrently() {
        ReActAgent agent1 = createAgent("Agent1", model1);
        ReActAgent agent2 = createAgent("Agent2", model2);

        FanoutPipeline pipeline = new FanoutPipeline(List.of(agent1, agent2));

        Msg input = TestUtils.createUserMessage("User", "stream fanout");
        List<Event> events = pipeline.stream(input).collectList().block(TIMEOUT);

        assertNotNull(events, "Streaming pipeline should produce events");
        assertFalse(events.isEmpty(), "Events should not be empty");

        // Verify we got AGENT_RESULT events from both agents
        long agentResultCount =
                events.stream().filter(e -> e.getType() == EventType.AGENT_RESULT).count();
        assertEquals(2, agentResultCount, "Expected AGENT_RESULT from each agent");

        // Verify models were called
        assertEquals(1, model1.getCallCount(), "First model should be invoked once");
        assertEquals(1, model2.getCallCount(), "Second model should be invoked once");
    }

    @Test
    @DisplayName("Should stream events sequentially when configured")
    void shouldStreamEventsSequentially() {
        ReActAgent agent1 = createAgent("Agent1", model1);
        ReActAgent agent2 = createAgent("Agent2", model2);

        FanoutPipeline pipeline = new FanoutPipeline(List.of(agent1, agent2), false);

        Msg input = TestUtils.createUserMessage("User", "sequential stream");
        List<Event> events = pipeline.stream(input).collectList().block(TIMEOUT);

        assertNotNull(events, "Sequential streaming should return events");
        assertFalse(pipeline.isConcurrentEnabled(), "Pipeline should be sequential");

        // Verify we got AGENT_RESULT events
        long agentResultCount =
                events.stream().filter(e -> e.getType() == EventType.AGENT_RESULT).count();
        assertEquals(2, agentResultCount, "Expected AGENT_RESULT from each agent");

        // Find agent result events and verify order
        List<Event> agentResults =
                events.stream().filter(e -> e.getType() == EventType.AGENT_RESULT).toList();

        assertEquals(
                "Agent1",
                agentResults.get(0).getMessage().getName(),
                "First agent response should lead");
        assertEquals(
                "Agent2",
                agentResults.get(1).getMessage().getName(),
                "Second agent response should follow");
    }

    @Test
    @DisplayName("Should stream with custom StreamOptions")
    void shouldStreamWithCustomOptions() {
        ReActAgent agent1 = createAgent("Agent1", model1);
        ReActAgent agent2 = createAgent("Agent2", model2);

        FanoutPipeline pipeline = new FanoutPipeline(List.of(agent1, agent2));

        // Only stream AGENT_RESULT events
        StreamOptions options =
                StreamOptions.builder()
                        .eventTypes(EventType.AGENT_RESULT)
                        .incremental(true)
                        .build();

        Msg input = TestUtils.createUserMessage("User", "options test");
        List<Event> events = pipeline.stream(input, options).collectList().block(TIMEOUT);

        assertNotNull(events, "Streaming with options should return events");

        // All events should be AGENT_RESULT type
        boolean allAgentResults =
                events.stream().allMatch(e -> e.getType() == EventType.AGENT_RESULT);
        assertTrue(allAgentResults, "All events should be AGENT_RESULT");
    }

    @Test
    @DisplayName("Should return empty flux for empty pipeline")
    void shouldReturnEmptyFluxForEmptyPipeline() {
        FanoutPipeline pipeline = new FanoutPipeline(List.of());

        Msg input = TestUtils.createUserMessage("User", "empty pipeline");
        List<Event> events = pipeline.stream(input).collectList().block(TIMEOUT);

        assertNotNull(events, "Should return empty list");
        assertTrue(events.isEmpty(), "Empty pipeline should produce no events");
    }

    @Test
    @DisplayName("Should stream events through builder-created pipeline")
    void shouldStreamEventsViaBuilder() {
        ReActAgent agent1 = createAgent("Agent1", model1);
        ReActAgent agent2 = createAgent("Agent2", model2);
        ReActAgent agent3 = createAgent("Agent3", model3);

        FanoutPipeline pipeline =
                FanoutPipeline.builder()
                        .addAgent(agent1)
                        .addAgents(List.of(agent2, agent3))
                        .sequential()
                        .build();

        Msg input = TestUtils.createUserMessage("User", "builder stream");
        List<Event> events = pipeline.stream(input).collectList().block(TIMEOUT);

        assertNotNull(events, "Builder-produced pipeline should stream events");

        // Verify agent results in order (sequential mode)
        List<String> agentNames =
                events.stream()
                        .filter(e -> e.getType() == EventType.AGENT_RESULT)
                        .map(e -> e.getMessage().getName())
                        .toList();

        assertEquals(
                List.of("Agent1", "Agent2", "Agent3"),
                agentNames,
                "Sequential streaming should maintain insertion order");
    }

    @Test
    @DisplayName("Should collect events count correctly in concurrent streaming")
    void shouldCollectCorrectEventCountConcurrently() {
        ReActAgent agent1 = createAgent("Agent1", model1);
        ReActAgent agent2 = createAgent("Agent2", model2);
        ReActAgent agent3 = createAgent("Agent3", model3);

        FanoutPipeline pipeline = new FanoutPipeline(List.of(agent1, agent2, agent3));

        Msg input = TestUtils.createUserMessage("User", "count test");

        AtomicInteger eventCount = new AtomicInteger(0);
        pipeline.stream(input).doOnNext(event -> eventCount.incrementAndGet()).blockLast(TIMEOUT);

        assertTrue(
                eventCount.get() >= 3,
                "Should have at least 3 AGENT_RESULT events (one per agent)");
    }

    @Test
    @DisplayName("Should stream with null options using defaults")
    void shouldStreamWithNullOptions() {
        ReActAgent agent1 = createAgent("Agent1", model1);
        ReActAgent agent2 = createAgent("Agent2", model2);

        FanoutPipeline pipeline = new FanoutPipeline(List.of(agent1, agent2));

        Msg input = TestUtils.createUserMessage("User", "null options test");
        // Pass null options explicitly to test the null handling branch
        List<Event> events = pipeline.stream(input, null, null).collectList().block(TIMEOUT);

        assertNotNull(events, "Should handle null options gracefully");
        assertFalse(events.isEmpty(), "Events should not be empty");
    }

    @Test
    @DisplayName("Should stream with structured output class in concurrent mode")
    void shouldStreamWithStructuredOutputConcurrent() {
        ReActAgent agent1 = createAgent("Agent1", model1);
        ReActAgent agent2 = createAgent("Agent2", model2);

        FanoutPipeline pipeline = new FanoutPipeline(List.of(agent1, agent2), true);

        Msg input = TestUtils.createUserMessage("User", "structured output test");
        StreamOptions options = StreamOptions.defaults();

        // Test with a structured output class
        List<Event> events =
                pipeline.stream(input, options, String.class).collectList().block(TIMEOUT);

        assertNotNull(events, "Should handle structured output class");
    }

    @Test
    @DisplayName("Should stream with structured output class in sequential mode")
    void shouldStreamWithStructuredOutputSequential() {
        ReActAgent agent1 = createAgent("Agent1", model1);
        ReActAgent agent2 = createAgent("Agent2", model2);

        FanoutPipeline pipeline = new FanoutPipeline(List.of(agent1, agent2), false);

        Msg input = TestUtils.createUserMessage("User", "sequential structured test");
        StreamOptions options = StreamOptions.defaults();

        // Test sequential mode with structured output class
        List<Event> events =
                pipeline.stream(input, options, String.class).collectList().block(TIMEOUT);

        assertNotNull(events, "Should handle structured output in sequential mode");
    }

    @Test
    @DisplayName("Should handle streaming errors and collect them")
    void shouldHandleStreamingErrors() {
        MockModel errorModel = new MockModel("Error response").withError("Streaming error");
        ReActAgent successAgent = createAgent("SuccessAgent", model1);
        ReActAgent failingAgent = createAgent("ErrorAgent", errorModel);

        FanoutPipeline pipeline = new FanoutPipeline(List.of(successAgent, failingAgent));
        Msg input = TestUtils.createUserMessage("User", "streaming error test");

        // The streaming should complete but may throw on complete if errors occurred
        assertThrows(
                CompositeAgentException.class,
                () -> pipeline.stream(input).collectList().block(TIMEOUT),
                "Should throw CompositeAgentException when agent streaming fails");
    }
}
