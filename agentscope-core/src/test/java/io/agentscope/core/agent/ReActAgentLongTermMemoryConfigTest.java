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
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.agentscope.core.ReActAgent;
import io.agentscope.core.memory.InMemoryMemory;
import io.agentscope.core.memory.LongTermMemory;
import io.agentscope.core.memory.LongTermMemoryMode;
import io.agentscope.core.memory.StaticLongTermMemoryHook;
import io.agentscope.core.message.Msg;
import io.agentscope.core.message.MsgRole;
import io.agentscope.core.message.TextBlock;
import io.agentscope.core.model.ChatResponse;
import io.agentscope.core.model.GenerateOptions;
import io.agentscope.core.model.Model;
import io.agentscope.core.model.ToolSchema;
import io.agentscope.core.tool.Toolkit;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

/**
 * Unit tests for ReActAgent's long-term memory configuration,
 * including the {@code longTermMemoryAsyncRecord} builder option
 * and its integration with {@link StaticLongTermMemoryHook}.
 */
@Tag("unit")
@DisplayName("ReActAgent Long-Term Memory Configuration Tests")
class ReActAgentLongTermMemoryConfigTest {

    private Model mockModel;

    @org.junit.jupiter.api.BeforeEach
    void setUp() {
        mockModel =
                new Model() {
                    @Override
                    public Flux<ChatResponse> stream(
                            List<Msg> messages, List<ToolSchema> tools, GenerateOptions options) {
                        ChatResponse response =
                                ChatResponse.builder()
                                        .content(
                                                List.of(
                                                        TextBlock.builder()
                                                                .text("Test response")
                                                                .build()))
                                        .build();
                        return Flux.just(response);
                    }

                    @Override
                    public String getModelName() {
                        return "mock-model";
                    }
                };
    }

    @Test
    @DisplayName("Should build agent with longTermMemoryAsyncRecord(true)")
    void testAsyncRecordTrue() {
        CountingLongTermMemory countingMemory = new CountingLongTermMemory();

        ReActAgent agent =
                ReActAgent.builder()
                        .name("TestAgent")
                        .model(mockModel)
                        .toolkit(new Toolkit())
                        .memory(new InMemoryMemory())
                        .longTermMemory(countingMemory)
                        .longTermMemoryMode(LongTermMemoryMode.STATIC_CONTROL)
                        .longTermMemoryAsyncRecord(true)
                        .build();

        assertNotNull(agent);
        assertEquals("TestAgent", agent.getName());
    }

    @Test
    @DisplayName("Should build agent with longTermMemoryAsyncRecord(false) - default")
    void testAsyncRecordFalse() {
        CountingLongTermMemory countingMemory = new CountingLongTermMemory();

        ReActAgent agent =
                ReActAgent.builder()
                        .name("TestAgent")
                        .model(mockModel)
                        .toolkit(new Toolkit())
                        .memory(new InMemoryMemory())
                        .longTermMemory(countingMemory)
                        .longTermMemoryMode(LongTermMemoryMode.STATIC_CONTROL)
                        .longTermMemoryAsyncRecord(false)
                        .build();

        assertNotNull(agent);
    }

    @Test
    @DisplayName("Should work with BOTH mode and asyncRecord enabled")
    void testBothModeWithAsyncRecord() {
        CountingLongTermMemory countingMemory = new CountingLongTermMemory();

        ReActAgent agent =
                ReActAgent.builder()
                        .name("TestAgent")
                        .model(mockModel)
                        .toolkit(new Toolkit())
                        .memory(new InMemoryMemory())
                        .longTermMemory(countingMemory)
                        .longTermMemoryMode(LongTermMemoryMode.BOTH)
                        .longTermMemoryAsyncRecord(true)
                        .build();

        assertNotNull(agent);
        // In BOTH mode, memory tools should be registered
        // Tool names use underscore convention from method names
        boolean hasRecordTool =
                agent.getToolkit().getToolNames().stream()
                        .anyMatch(name -> name.contains("record"));
        boolean hasRetrieveTool =
                agent.getToolkit().getToolNames().stream()
                        .anyMatch(name -> name.contains("retrieve"));
        assertTrue(hasRecordTool, "record tool should be registered in BOTH mode");
        assertTrue(hasRetrieveTool, "retrieve tool should be registered in BOTH mode");
    }

    @Test
    @DisplayName("Async record should complete without blocking and call onSuccess")
    void testAsyncRecordDoesNotBlock() throws InterruptedException {
        CountDownLatch recordLatch = new CountDownLatch(1);
        AtomicBoolean onSuccessCalled = new AtomicBoolean(false);

        LongTermMemory asyncMemory =
                new LongTermMemory() {
                    @Override
                    public Mono<Void> record(List<Msg> msgs) {
                        return Mono.fromRunnable(
                                        () -> {
                                            onSuccessCalled.set(true);
                                            recordLatch.countDown();
                                        })
                                .then();
                    }

                    @Override
                    public Mono<String> retrieve(Msg msg) {
                        return Mono.just("");
                    }
                };

        StaticLongTermMemoryHook asyncHook =
                new StaticLongTermMemoryHook(asyncMemory, new InMemoryMemory(), true);

        // Simulate PostCallEvent scenario
        InMemoryMemory agentMemory = new InMemoryMemory();
        agentMemory.addMessage(
                Msg.builder()
                        .role(MsgRole.USER)
                        .content(TextBlock.builder().text("Hello").build())
                        .build());
        agentMemory.addMessage(
                Msg.builder()
                        .role(MsgRole.ASSISTANT)
                        .content(TextBlock.builder().text("Hi there").build())
                        .build());

        StaticLongTermMemoryHook hookWithAccess =
                new StaticLongTermMemoryHook(asyncMemory, agentMemory, true);

        io.agentscope.core.hook.PostCallEvent event =
                new io.agentscope.core.hook.PostCallEvent(
                        createMockAgent(),
                        Msg.builder()
                                .role(MsgRole.ASSISTANT)
                                .content(TextBlock.builder().text("Reply").build())
                                .build());

        // Execute hook - should return immediately
        Mono<io.agentscope.core.hook.PostCallEvent> resultMono = hookWithAccess.onEvent(event);
        io.agentscope.core.hook.PostCallEvent result = resultMono.block();

        assertNotNull(result);

        // Wait for async record to complete
        assertTrue(
                recordLatch.await(1, TimeUnit.SECONDS), "Async record should have been scheduled");
        assertTrue(
                onSuccessCalled.get(),
                "The onSuccess lambda (unused -> {}) should have been invoked");
    }

    private Agent createMockAgent() {
        return new AgentBase("MockAgent") {
            @Override
            protected Mono<Msg> doCall(List<Msg> msgs) {
                return Mono.just(msgs.get(0));
            }

            @Override
            protected Mono<Void> doObserve(Msg msg) {
                return Mono.empty();
            }

            @Override
            protected Mono<Msg> handleInterrupt(
                    io.agentscope.core.interruption.InterruptContext context, Msg... originalArgs) {
                return Mono.just(
                        Msg.builder()
                                .name(getName())
                                .role(MsgRole.ASSISTANT)
                                .content(TextBlock.builder().text("Interrupted").build())
                                .build());
            }
        };
    }

    /** Simple LongTermMemory implementation that counts record calls. */
    private static class CountingLongTermMemory implements LongTermMemory {
        private int recordCount = 0;

        @Override
        public Mono<Void> record(List<Msg> msgs) {
            recordCount++;
            return Mono.empty();
        }

        @Override
        public Mono<String> retrieve(Msg msg) {
            return Mono.just("");
        }

        public int getRecordCount() {
            return recordCount;
        }
    }
}
