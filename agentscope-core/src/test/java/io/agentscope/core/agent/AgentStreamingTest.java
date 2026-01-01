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
package io.agentscope.core.agent;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.agentscope.core.interruption.InterruptContext;
import io.agentscope.core.message.Msg;
import io.agentscope.core.message.MsgRole;
import io.agentscope.core.message.TextBlock;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;

/**
 * Integration tests for {@link Agent#stream(Msg, StreamOptions)} API.
 * Tests both the streaming functionality and StreamingHook behavior.
 */
class AgentStreamingTest {

    /**
     * Simple test agent that extends AgentBase for testing stream functionality.
     */
    static class TestStreamingAgent extends AgentBase {
        private String responseText = "Test response";
        private boolean shouldFail = false;

        TestStreamingAgent(String name) {
            super(name);
        }

        void setResponseText(String text) {
            this.responseText = text;
        }

        void setShouldFail(boolean shouldFail) {
            this.shouldFail = shouldFail;
        }

        @Override
        protected Mono<Msg> doCall(List<Msg> msgs) {
            if (shouldFail) {
                return Mono.error(new RuntimeException("Test error"));
            }

            Msg response =
                    Msg.builder()
                            .name(getName())
                            .role(MsgRole.ASSISTANT)
                            .content(TextBlock.builder().text(responseText).build())
                            .build();
            return Mono.just(response);
        }

        @Override
        protected Mono<Void> doObserve(Msg msg) {
            return Mono.empty();
        }

        @Override
        protected Mono<Msg> handleInterrupt(InterruptContext context, Msg... originalArgs) {
            return Mono.just(
                    Msg.builder()
                            .name(getName())
                            .role(MsgRole.ASSISTANT)
                            .content(TextBlock.builder().text("Interrupted").build())
                            .build());
        }
    }

    @Test
    void testStreamWithIncludeAgentResult() {
        TestStreamingAgent agent = new TestStreamingAgent("test-agent");
        agent.setResponseText("Response");

        Msg inputMsg =
                Msg.builder()
                        .name("user")
                        .role(MsgRole.USER)
                        .content(List.of(TextBlock.builder().text("Test").build()))
                        .build();

        List<Event> events = new ArrayList<>();
        agent.stream(inputMsg).doOnNext(events::add).blockLast();

        // Should receive the final AGENT_RESULT event
        assertFalse(events.isEmpty());
        Event lastEvent = events.get(events.size() - 1);
        assertEquals(EventType.AGENT_RESULT, lastEvent.getType());
        assertTrue(lastEvent.isLast());
        assertNotNull(lastEvent.getMessage());
    }

    @Test
    void testStreamWithSpecificEventTypes() {
        TestStreamingAgent agent = new TestStreamingAgent("test-agent");
        agent.setResponseText("Response");

        Msg inputMsg =
                Msg.builder()
                        .name("user")
                        .role(MsgRole.USER)
                        .content(List.of(TextBlock.builder().text("Test").build()))
                        .build();

        // Test with specific event types
        StreamOptions options = StreamOptions.builder().eventTypes(EventType.REASONING).build();

        List<Event> events = new ArrayList<>();
        agent.stream(inputMsg, options).doOnNext(events::add).blockLast();

        // Should only receive AGENT_RESULT since we don't have REASONING events in this simple
        // agent
        assertTrue(events.stream().noneMatch(e -> e.getType() == EventType.TOOL_RESULT));
        assertTrue(events.stream().noneMatch(e -> e.getType() == EventType.HINT));
    }

    @Test
    void testStreamWithCumulativeChunkMode() {
        TestStreamingAgent agent = new TestStreamingAgent("test-agent");
        agent.setResponseText("Response");

        Msg inputMsg =
                Msg.builder()
                        .name("user")
                        .role(MsgRole.USER)
                        .content(List.of(TextBlock.builder().text("Test").build()))
                        .build();

        // Test with cumulative mode (incremental = false)
        StreamOptions options = StreamOptions.builder().incremental(false).build();

        List<Event> events = new ArrayList<>();
        agent.stream(inputMsg, options).doOnNext(events::add).blockLast();

        // Verify incremental mode is set correctly
        assertFalse(options.isIncremental());
        assertFalse(events.isEmpty());
    }

    @Test
    void testStreamWithIncrementalChunkMode() {
        TestStreamingAgent agent = new TestStreamingAgent("test-agent");
        agent.setResponseText("Response");

        Msg inputMsg =
                Msg.builder()
                        .name("user")
                        .role(MsgRole.USER)
                        .content(List.of(TextBlock.builder().text("Test").build()))
                        .build();

        // Test with incremental mode (default)
        StreamOptions options = StreamOptions.builder().incremental(true).build();

        List<Event> events = new ArrayList<>();
        agent.stream(inputMsg, options).doOnNext(events::add).blockLast();

        // Verify incremental mode is set correctly
        assertTrue(options.isIncremental());
        assertFalse(events.isEmpty());
    }

    @Test
    void testStreamWithError() {
        TestStreamingAgent agent = new TestStreamingAgent("test-agent");
        agent.setShouldFail(true);

        Msg inputMsg =
                Msg.builder()
                        .name("user")
                        .role(MsgRole.USER)
                        .content(List.of(TextBlock.builder().text("Test").build()))
                        .build();

        // Test error propagation
        StepVerifier.create(agent.stream(inputMsg))
                .expectError(RuntimeException.class)
                .verify(Duration.ofSeconds(5));
    }

    @Test
    void testStreamWithListOfMessages() {
        TestStreamingAgent agent = new TestStreamingAgent("test-agent");
        agent.setResponseText("Response");

        List<Msg> inputMsgs =
                List.of(
                        Msg.builder()
                                .name("user")
                                .role(MsgRole.USER)
                                .content(List.of(TextBlock.builder().text("First").build()))
                                .build(),
                        Msg.builder()
                                .name("user")
                                .role(MsgRole.USER)
                                .content(List.of(TextBlock.builder().text("Second").build()))
                                .build());

        // Test streaming with list of messages
        StreamOptions options = StreamOptions.builder().build();

        List<Event> events = new ArrayList<>();
        agent.stream(inputMsgs, options).doOnNext(events::add).blockLast();

        assertFalse(events.isEmpty());
        Event lastEvent = events.get(events.size() - 1);
        assertEquals(EventType.AGENT_RESULT, lastEvent.getType());
    }

    @Test
    void testStreamEventCount() {
        TestStreamingAgent agent = new TestStreamingAgent("test-agent");
        agent.setResponseText("Response");

        Msg inputMsg =
                Msg.builder()
                        .name("user")
                        .role(MsgRole.USER)
                        .content(List.of(TextBlock.builder().text("Test").build()))
                        .build();

        StreamOptions options = StreamOptions.builder().build();

        AtomicInteger eventCount = new AtomicInteger(0);
        agent.stream(inputMsg, options).doOnNext(event -> eventCount.incrementAndGet()).blockLast();

        // Should have at least one event (the agent result)
        assertTrue(eventCount.get() >= 1);
    }
}
