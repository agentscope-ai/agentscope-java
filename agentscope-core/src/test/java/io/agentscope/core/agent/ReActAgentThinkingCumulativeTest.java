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

import io.agentscope.core.ReActAgent;
import io.agentscope.core.agent.test.TestUtils;
import io.agentscope.core.hook.Hook;
import io.agentscope.core.hook.HookEvent;
import io.agentscope.core.hook.ReasoningChunkEvent;
import io.agentscope.core.memory.InMemoryMemory;
import io.agentscope.core.message.Msg;
import io.agentscope.core.message.ThinkingBlock;
import io.agentscope.core.model.ChatResponse;
import io.agentscope.core.model.ChatUsage;
import io.agentscope.core.model.GenerateOptions;
import io.agentscope.core.model.Model;
import io.agentscope.core.model.ToolSchema;
import io.agentscope.core.tool.Toolkit;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

/**
 * Unit tests for ReActAgent ThinkingBlock accumulated vs incremental chunk support.
 *
 * <p>Tests verify that ReasoningChunkEvent provides both incremental and accumulated
 * thinking content, allowing hooks to choose which to use.
 */
@DisplayName("ReActAgent ThinkingBlock Chunk Mode Tests")
class ReActAgentThinkingCumulativeTest {

    private InMemoryMemory memory;
    private List<String> receivedThinkingChunks;

    @BeforeEach
    void setUp() {
        memory = new InMemoryMemory();
        receivedThinkingChunks = new ArrayList<>();
    }

    @Test
    @DisplayName("Should provide accumulated ThinkingBlock chunks via ReasoningChunkEvent")
    void testThinkingBlockCumulativeMode() {
        // Create a mock model that streams ThinkingBlock chunks
        Model mockModel = new StreamingThinkingModel(List.of("I think ", "this is ", "correct."));

        // Create a hook that collects accumulated chunks from ReasoningChunkEvent
        Hook cumulativeHook =
                new Hook() {
                    @Override
                    public <T extends HookEvent> Mono<T> onEvent(T event) {
                        if (event instanceof ReasoningChunkEvent) {
                            ReasoningChunkEvent e = (ReasoningChunkEvent) event;
                            // Get accumulated chunk instead of incremental
                            Msg accumulated = e.getAccumulated();
                            ThinkingBlock block =
                                    (ThinkingBlock) accumulated.getFirstContentBlock();
                            if (block != null) {
                                receivedThinkingChunks.add(block.getThinking());
                            }
                        }
                        return Mono.just(event);
                    }
                };

        // Create agent with the hook
        ReActAgent agent =
                ReActAgent.builder()
                        .name("TestAgent")
                        .sysPrompt("You are a test agent")
                        .model(mockModel)
                        .toolkit(new EmptyToolkit())
                        .memory(memory)
                        .hook(cumulativeHook)
                        .build();

        // Execute agent call
        Msg userMsg = TestUtils.createUserMessage("User", "Hello");
        Msg response = agent.call(userMsg).block(Duration.ofSeconds(5));

        // Verify response is not null
        assertNotNull(response);

        // Verify that chunks are cumulative
        assertEquals(3, receivedThinkingChunks.size(), "Should receive 3 chunks");
        assertEquals("I think ", receivedThinkingChunks.get(0), "First chunk should be 'I think '");
        assertEquals(
                "I think this is ",
                receivedThinkingChunks.get(1),
                "Second chunk should be cumulative: 'I think this is '");
        assertEquals(
                "I think this is correct.",
                receivedThinkingChunks.get(2),
                "Third chunk should be fully cumulative: 'I think this is correct.'");
    }

    @Test
    @DisplayName("Should provide incremental ThinkingBlock chunks via ReasoningChunkEvent")
    void testThinkingBlockIncrementalMode() {
        // Create a mock model that streams ThinkingBlock chunks
        Model mockModel = new StreamingThinkingModel(List.of("I think ", "this is ", "correct."));

        // Create a hook that collects incremental chunks from ReasoningChunkEvent
        Hook incrementalHook =
                new Hook() {
                    @Override
                    public <T extends HookEvent> Mono<T> onEvent(T event) {
                        if (event instanceof ReasoningChunkEvent) {
                            ReasoningChunkEvent e = (ReasoningChunkEvent) event;
                            // Get incremental chunk instead of accumulated
                            Msg incremental = e.getIncrementalChunk();
                            ThinkingBlock block =
                                    (ThinkingBlock) incremental.getFirstContentBlock();
                            if (block != null) {
                                receivedThinkingChunks.add(block.getThinking());
                            }
                        }
                        return Mono.just(event);
                    }
                };

        // Create agent with the hook
        ReActAgent agent =
                ReActAgent.builder()
                        .name("TestAgent")
                        .sysPrompt("You are a test agent")
                        .model(mockModel)
                        .toolkit(new EmptyToolkit())
                        .memory(memory)
                        .hook(incrementalHook)
                        .build();

        // Execute agent call
        Msg userMsg = TestUtils.createUserMessage("User", "Hello");
        Msg response = agent.call(userMsg).block(Duration.ofSeconds(5));

        // Verify response is not null
        assertNotNull(response);

        // Verify that chunks are incremental (not cumulative)
        assertEquals(3, receivedThinkingChunks.size(), "Should receive 3 chunks");
        assertEquals("I think ", receivedThinkingChunks.get(0), "First chunk should be 'I think '");
        assertEquals(
                "this is ", receivedThinkingChunks.get(1), "Second chunk should be 'this is '");
        assertEquals("correct.", receivedThinkingChunks.get(2), "Third chunk should be 'correct.'");
    }

    /**
     * Mock model that streams ThinkingBlock chunks.
     */
    private static class StreamingThinkingModel implements Model {

        private final List<String> chunks;

        StreamingThinkingModel(List<String> chunks) {
            this.chunks = chunks;
        }

        @Override
        public Flux<ChatResponse> stream(
                List<Msg> messages, List<ToolSchema> tools, GenerateOptions options) {

            List<ChatResponse> responses = new ArrayList<>();

            // Create a ChatResponse for each thinking chunk
            for (String chunk : chunks) {
                responses.add(
                        ChatResponse.builder()
                                .id("msg_" + UUID.randomUUID().toString())
                                .content(List.of(ThinkingBlock.builder().thinking(chunk).build()))
                                .usage(new ChatUsage(5, 10, 15))
                                .build());
            }

            return Flux.fromIterable(responses);
        }

        @Override
        public String getModelName() {
            return "streaming-thinking-model";
        }
    }

    /**
     * Empty toolkit for testing (no tools).
     */
    private static class EmptyToolkit extends Toolkit {

        EmptyToolkit() {
            super();
        }
    }
}
