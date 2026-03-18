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
package io.agentscope.core.tool;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

import io.agentscope.core.ReActAgent;
import io.agentscope.core.agent.test.MockModel;
import io.agentscope.core.hook.ActingChunkEvent;
import io.agentscope.core.hook.Hook;
import io.agentscope.core.hook.HookEvent;
import io.agentscope.core.memory.InMemoryMemory;
import io.agentscope.core.message.ContentBlock;
import io.agentscope.core.message.Msg;
import io.agentscope.core.message.MsgRole;
import io.agentscope.core.message.TextBlock;
import io.agentscope.core.message.ToolResultBlock;
import io.agentscope.core.message.ToolUseBlock;
import io.agentscope.core.model.ChatResponse;
import io.agentscope.core.model.ChatUsage;
import io.agentscope.core.util.JsonUtils;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import reactor.core.publisher.Mono;

/**
 * Integration test for ToolEmitter functionality with Hooks.
 */
class ToolEmitterIntegrationTest {

    private Toolkit toolkit;
    private List<ToolResultBlock> capturedChunks;
    private List<ToolUseBlock> capturedToolUseBlocks;

    @BeforeEach
    void setUp() {
        toolkit = new Toolkit();
        capturedChunks = new ArrayList<>();
        capturedToolUseBlocks = new ArrayList<>();
    }

    @Test
    @DisplayName("Tool with ToolEmitter should send chunks to Hook")
    void testToolEmitterWithHook() {
        // Register a tool that uses ToolEmitter
        toolkit.registerTool(
                new Object() {
                    @Tool(name = "stream_task", description = "A task that emits streaming chunks")
                    public ToolResultBlock execute(
                            @ToolParam(name = "input", description = "Input data") String input,
                            ToolEmitter emitter) {
                        // Emit intermediate chunks
                        emitter.emit(ToolResultBlock.text("Step 1: Initializing"));
                        emitter.emit(ToolResultBlock.text("Step 2: Processing " + input));
                        emitter.emit(ToolResultBlock.text("Step 3: Finalizing"));

                        // Return final result
                        return ToolResultBlock.text("Task completed for: " + input);
                    }
                });

        // Set up chunk callback
        toolkit.setChunkCallback(
                (toolUse, chunk) -> {
                    capturedChunks.add(chunk);
                    capturedToolUseBlocks.add(toolUse);
                });

        // Create a ToolUseBlock
        Map<String, Object> input = Map.of("input", "test-data");
        ToolUseBlock toolUseBlock =
                ToolUseBlock.builder()
                        .id("test-call-1")
                        .name("stream_task")
                        .input(input)
                        .content(JsonUtils.getJsonCodec().toJson(input))
                        .build();

        // Call the tool
        ToolResultBlock finalResponse =
                toolkit.callTool(ToolCallParam.builder().toolUseBlock(toolUseBlock).build())
                        .block();

        // Verify final response
        assertNotNull(finalResponse);
        // Output is now a List<ContentBlock>
        List<ContentBlock> outputs = finalResponse.getOutput();
        assertEquals(1, outputs.size());
        TextBlock textBlock = (TextBlock) outputs.get(0);
        assertEquals("Task completed for: test-data", textBlock.getText());

        // Verify chunks were captured
        assertEquals(3, capturedChunks.size(), "Should have captured 3 chunks");
        assertEquals("Step 1: Initializing", extractText(capturedChunks.get(0)));
        assertEquals("Step 2: Processing test-data", extractText(capturedChunks.get(1)));
        assertEquals("Step 3: Finalizing", extractText(capturedChunks.get(2)));

        // Verify all chunks came from the same tool call
        for (ToolUseBlock toolUse : capturedToolUseBlocks) {
            assertEquals("test-call-1", toolUse.getId());
            assertEquals("stream_task", toolUse.getName());
        }
    }

    @Test
    @DisplayName("Tool without ToolEmitter should work normally")
    void testToolWithoutEmitter() {
        // Register a normal tool without ToolEmitter
        toolkit.registerTool(
                new Object() {
                    @Tool(name = "simple_task", description = "A simple task")
                    public ToolResultBlock execute(
                            @ToolParam(name = "input", description = "Input") String input) {
                        return ToolResultBlock.text("Result: " + input);
                    }
                });

        // Set up chunk callback (should not be called)
        toolkit.setChunkCallback(
                (toolUse, chunk) -> {
                    capturedChunks.add(chunk);
                });

        // Call the tool
        Map<String, Object> input = Map.of("input", "hello");
        ToolUseBlock toolUseBlock =
                ToolUseBlock.builder()
                        .id("test-call-2")
                        .name("simple_task")
                        .input(input)
                        .content(JsonUtils.getJsonCodec().toJson(input))
                        .build();

        ToolResultBlock finalResponse =
                toolkit.callTool(ToolCallParam.builder().toolUseBlock(toolUseBlock).build())
                        .block();

        // Verify final response
        assertNotNull(finalResponse);
        assertEquals("Result: hello", extractText(finalResponse));

        // Verify no chunks were emitted
        assertEquals(
                0, capturedChunks.size(), "No chunks should be emitted for tools without emitter");
    }

    @Test
    @DisplayName("Multiple tools with emitters should emit chunks correctly")
    void testMultipleToolsWithEmitters() {
        // Register two tools with emitters
        toolkit.registerTool(
                new Object() {
                    @Tool(name = "task_a", description = "Task A")
                    public ToolResultBlock taskA(
                            @ToolParam(name = "data") String data, ToolEmitter emitter) {
                        emitter.emit(ToolResultBlock.text("A: Started"));
                        emitter.emit(ToolResultBlock.text("A: Processing"));
                        return ToolResultBlock.text("A: Done");
                    }
                });

        toolkit.registerTool(
                new Object() {
                    @Tool(name = "task_b", description = "Task B")
                    public ToolResultBlock taskB(
                            @ToolParam(name = "data") String data, ToolEmitter emitter) {
                        emitter.emit(ToolResultBlock.text("B: Started"));
                        return ToolResultBlock.text("B: Done");
                    }
                });

        toolkit.setChunkCallback(
                (toolUse, chunk) -> {
                    capturedChunks.add(chunk);
                    capturedToolUseBlocks.add(toolUse);
                });

        // Call task_a
        Map<String, Object> inputA = Map.of("data", "x");
        ToolUseBlock toolA =
                ToolUseBlock.builder()
                        .id("call-a")
                        .name("task_a")
                        .input(inputA)
                        .content(JsonUtils.getJsonCodec().toJson(inputA))
                        .build();
        toolkit.callTool(ToolCallParam.builder().toolUseBlock(toolA).build()).block();

        // Call task_b
        Map<String, Object> inputB = Map.of("data", "y");
        ToolUseBlock toolB =
                ToolUseBlock.builder()
                        .id("call-b")
                        .name("task_b")
                        .input(inputB)
                        .content(JsonUtils.getJsonCodec().toJson(inputB))
                        .build();
        toolkit.callTool(ToolCallParam.builder().toolUseBlock(toolB).build()).block();

        // Verify chunks
        assertEquals(3, capturedChunks.size());
        assertEquals("A: Started", extractText(capturedChunks.get(0)));
        assertEquals("A: Processing", extractText(capturedChunks.get(1)));
        assertEquals("B: Started", extractText(capturedChunks.get(2)));

        // Verify tool use blocks
        assertEquals("call-a", capturedToolUseBlocks.get(0).getId());
        assertEquals("call-a", capturedToolUseBlocks.get(1).getId());
        assertEquals("call-b", capturedToolUseBlocks.get(2).getId());
    }

    @Test
    @DisplayName("ToolEmitter should work after Toolkit copy")
    void testToolEmitterWithCopiedToolkit() {
        toolkit.registerTool(
                new Object() {
                    @Tool(name = "task_a", description = "Task A")
                    public ToolResultBlock taskA(
                            @ToolParam(name = "data") String data, ToolEmitter emitter) {
                        emitter.emit(ToolResultBlock.text("A: Started"));
                        emitter.emit(ToolResultBlock.text("A: Processing"));
                        return ToolResultBlock.text("A: Done");
                    }
                });
        var copiedToolkit = toolkit.copy();
        copiedToolkit.setChunkCallback(
                (toolUse, chunk) -> {
                    capturedChunks.add(chunk);
                    capturedToolUseBlocks.add(toolUse);
                });

        // Call task_a
        Map<String, Object> inputA = Map.of("data", "x");
        ToolUseBlock toolA =
                ToolUseBlock.builder()
                        .id("call-a")
                        .name("task_a")
                        .input(inputA)
                        .content(JsonUtils.getJsonCodec().toJson(inputA))
                        .build();
        copiedToolkit.callTool(ToolCallParam.builder().toolUseBlock(toolA).build()).block();

        // Verify chunks
        assertEquals(2, capturedChunks.size());
        assertEquals("A: Started", extractText(capturedChunks.get(0)));
        assertEquals("A: Processing", extractText(capturedChunks.get(1)));

        // Verify tool use blocks
        assertEquals("call-a", capturedToolUseBlocks.get(0).getId());
        assertEquals("call-a", capturedToolUseBlocks.get(1).getId());
    }

    @Test
    @DisplayName("ReActAgent should preserve user chunk callback while emitting ActingChunkEvent")
    void testReActAgentPreservesUserChunkCallback() {
        toolkit.registerTool(new StreamingTool());

        List<String> userChunks = new CopyOnWriteArrayList<>();
        toolkit.setChunkCallback((toolUse, chunk) -> userChunks.add(extractText(chunk)));

        List<String> hookChunks = new CopyOnWriteArrayList<>();
        Hook captureHook =
                new Hook() {
                    @Override
                    public <T extends HookEvent> Mono<T> onEvent(T event) {
                        if (event instanceof ActingChunkEvent actingChunkEvent) {
                            hookChunks.add(extractText(actingChunkEvent.getChunk()));
                        }
                        return Mono.just(event);
                    }
                };

        AtomicInteger modelCallCount = new AtomicInteger(0);
        MockModel model =
                new MockModel(
                        messages -> {
                            if (modelCallCount.getAndIncrement() == 0) {
                                return List.of(createToolCallResponse());
                            }
                            return List.of(createTextResponse("final-response"));
                        });

        ReActAgent agent =
                ReActAgent.builder()
                        .name("Issue870Agent")
                        .sysPrompt("You are a helpful assistant.")
                        .model(model)
                        .toolkit(toolkit)
                        .memory(new InMemoryMemory())
                        .hook(captureHook)
                        .build();

        Msg response = agent.call(createUserMessage()).block();

        assertNotNull(response);
        assertEquals("final-response", extractText(response));
        assertEquals(List.of("chunk:1:demo", "chunk:2:demo"), userChunks);
        assertEquals(List.of("chunk:1:demo", "chunk:2:demo"), hookChunks);
    }

    @Test
    @DisplayName("Internal chunk callback failure should not block user callback")
    void testInternalChunkCallbackFailureDoesNotBlockUserCallback() {
        toolkit.registerTool(new StreamingTool());

        List<String> userChunks = new CopyOnWriteArrayList<>();
        toolkit.setChunkCallback((toolUse, chunk) -> userChunks.add(extractText(chunk)));
        toolkit.setInternalChunkCallback(
                (toolUse, chunk) -> {
                    throw new IllegalStateException("boom");
                });

        ToolResultBlock finalResponse =
                toolkit.callTool(
                                ToolCallParam.builder()
                                        .toolUseBlock(
                                                createToolCall(
                                                        "stream_task", Map.of("input", "demo")))
                                        .build())
                        .block();

        assertNotNull(finalResponse);
        assertEquals("tool-result:demo", extractText(finalResponse));
        assertEquals(List.of("chunk:1:demo", "chunk:2:demo"), userChunks);
    }

    @Test
    @DisplayName("User chunk callback failure should not interrupt tool execution")
    void testUserChunkCallbackFailureDoesNotInterruptToolExecution() {
        toolkit.registerTool(new StreamingTool());
        toolkit.setChunkCallback(
                (toolUse, chunk) -> {
                    throw new IllegalStateException("user callback failure");
                });

        ToolResultBlock finalResponse =
                toolkit.callTool(
                                ToolCallParam.builder()
                                        .toolUseBlock(
                                                createToolCall(
                                                        "stream_task", Map.of("input", "demo")))
                                        .build())
                        .block();

        assertNotNull(finalResponse);
        assertEquals("tool-result:demo", extractText(finalResponse));
    }

    @Test
    @DisplayName("Internal chunk callback failure should not interrupt tool execution")
    void testInternalChunkCallbackFailureDoesNotInterruptToolExecution() {
        toolkit.registerTool(new StreamingTool());
        toolkit.setInternalChunkCallback(
                (toolUse, chunk) -> {
                    throw new IllegalStateException("internal callback failure");
                });

        ToolResultBlock finalResponse =
                toolkit.callTool(
                                ToolCallParam.builder()
                                        .toolUseBlock(
                                                createToolCall(
                                                        "stream_task", Map.of("input", "demo")))
                                        .build())
                        .block();

        assertNotNull(finalResponse);
        assertEquals("tool-result:demo", extractText(finalResponse));
    }

    /**
     * Helper method to extract text from ToolResultBlock.
     */
    private String extractText(ToolResultBlock response) {
        // ToolResultBlock output is now a List<ContentBlock>
        List<ContentBlock> outputs = response.getOutput();
        if (outputs.isEmpty()) return "";
        TextBlock block = (TextBlock) outputs.get(0);
        return block.getText();
    }

    private String extractText(Msg msg) {
        return ((TextBlock) msg.getContent().get(0)).getText();
    }

    private Msg createUserMessage() {
        return Msg.builder()
                .name("User")
                .role(MsgRole.USER)
                .content(TextBlock.builder().text("Use the streaming tool").build())
                .build();
    }

    private ChatResponse createToolCallResponse() {
        return ChatResponse.builder()
                .content(List.of(createToolCall("stream_task", Map.of("input", "demo"))))
                .usage(new ChatUsage(8, 15, 23))
                .build();
    }

    private ChatResponse createTextResponse(String text) {
        return ChatResponse.builder()
                .content(List.of(TextBlock.builder().text(text).build()))
                .usage(new ChatUsage(10, 20, 30))
                .build();
    }

    private ToolUseBlock createToolCall(String name, Map<String, Object> input) {
        return ToolUseBlock.builder()
                .id("call-1")
                .name(name)
                .input(input)
                .content(JsonUtils.getJsonCodec().toJson(input))
                .build();
    }

    static class StreamingTool {

        @Tool(name = "stream_task", description = "Emit streaming chunks and return a final result")
        public ToolResultBlock execute(
                @ToolParam(name = "input", description = "Input text") String input,
                ToolEmitter emitter) {
            emitter.emit(ToolResultBlock.text("chunk:1:" + input));
            emitter.emit(ToolResultBlock.text("chunk:2:" + input));
            return ToolResultBlock.text("tool-result:" + input);
        }
    }
}
