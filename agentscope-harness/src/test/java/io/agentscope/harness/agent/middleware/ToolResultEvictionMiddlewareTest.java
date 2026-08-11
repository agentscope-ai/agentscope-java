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
package io.agentscope.harness.agent.middleware;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.agentscope.core.agent.Agent;
import io.agentscope.core.agent.AgentBase;
import io.agentscope.core.agent.RuntimeContext;
import io.agentscope.core.interruption.InterruptContext;
import io.agentscope.core.message.ContentBlock;
import io.agentscope.core.message.Msg;
import io.agentscope.core.message.MsgRole;
import io.agentscope.core.message.TextBlock;
import io.agentscope.core.message.ToolResultBlock;
import io.agentscope.core.message.ToolResultState;
import io.agentscope.core.middleware.ReasoningInput;
import io.agentscope.core.state.AgentState;
import io.agentscope.harness.agent.filesystem.local.LocalFilesystem;
import io.agentscope.harness.agent.filesystem.model.WriteResult;
import io.agentscope.harness.agent.memory.compaction.ToolResultEvictionConfig;
import io.agentscope.harness.agent.workspace.LocalFsMode;
import io.agentscope.harness.agent.workspace.PathPolicy;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

class ToolResultEvictionMiddlewareTest {

    private static final String FULL_OUTPUT = "0123456789".repeat(10);

    @TempDir Path tempDir;

    @Test
    void evictsImmediateReasoningInputAndAgentStateUsingDefaultLocalPath() throws IOException {
        ToolResultEvictionConfig config =
                ToolResultEvictionConfig.builder().maxResultChars(20).previewChars(4).build();
        assertEquals("large_tool_results", config.getEvictionPath());

        LocalFilesystem filesystem =
                new LocalFilesystem(tempDir, LocalFsMode.ROOTED, PathPolicy.empty(), 10, null);
        ToolResultEvictionMiddleware middleware =
                new ToolResultEvictionMiddleware(filesystem, config);

        Msg stateToolMessage = toolMessage(toolResult(FULL_OUTPUT));
        AgentState state = AgentState.builder().addMessage(stateToolMessage).build();
        RuntimeContext ctx = RuntimeContext.builder().agentState(state).build();

        Msg systemMessage = Msg.builder().role(MsgRole.SYSTEM).textContent("system prompt").build();
        Msg hookMessage = Msg.builder().role(MsgRole.ASSISTANT).textContent("hook message").build();
        Msg inputToolMessage = toolMessage(toolResult(FULL_OUTPUT));
        ReasoningInput input =
                new ReasoningInput(
                        List.of(systemMessage, hookMessage, inputToolMessage), List.of(), null);
        AtomicReference<ReasoningInput> forwarded = new AtomicReference<>();

        middleware
                .onReasoning(
                        agent("Test Agent"),
                        ctx,
                        input,
                        compacted -> {
                            forwarded.set(compacted);
                            return Flux.empty();
                        })
                .collectList()
                .block();

        ReasoningInput compacted = forwarded.get();
        assertNotNull(compacted);
        assertNotSame(input, compacted);
        assertSame(systemMessage, compacted.messages().get(0));
        assertSame(hookMessage, compacted.messages().get(1));

        String modelToolOutput = toolOutput(compacted.messages().get(2));
        assertTrue(modelToolOutput.contains("Tool output was too large"));
        assertFalse(modelToolOutput.contains(FULL_OUTPUT));

        assertEquals(1, state.getContext().size());
        String stateToolOutput = toolOutput(state.getContext().get(0));
        assertTrue(stateToolOutput.contains("Tool output was too large"));
        assertFalse(stateToolOutput.contains(FULL_OUTPUT));

        ToolResultBlock compactedBlock =
                compacted.messages().get(2).getContentBlocks(ToolResultBlock.class).get(0);
        assertEquals("test", compactedBlock.getMetadata().get("source"));
        assertEquals(
                true,
                compactedBlock
                        .getMetadata()
                        .get(ToolResultEvictionMiddleware.EVICTED_METADATA_KEY));
        assertEquals(ToolResultState.SUCCESS, compactedBlock.getState());

        // The original copied input remains unchanged, proving the downstream call received a
        // newly rebuilt ReasoningInput rather than relying on AgentState mutation.
        assertEquals(FULL_OUTPUT, toolOutput(inputToolMessage));
        Path storedFile;
        try (var files = Files.list(tempDir.resolve("large_tool_results/Test_Agent"))) {
            storedFile = files.findFirst().orElseThrow();
        }
        String relativePath = tempDir.relativize(storedFile).toString().replace('\\', '/');
        assertTrue(storedFile.getFileName().toString().startsWith("call_1-"));
        assertTrue(modelToolOutput.contains("`" + relativePath + "`"));
        assertEquals(FULL_OUTPUT, Files.readString(storedFile));
    }

    @Test
    void evictsCanonicalAgentStateWhenReasoningHookRemovedLargeResult() throws IOException {
        LocalFilesystem filesystem =
                new LocalFilesystem(tempDir, LocalFsMode.ROOTED, PathPolicy.empty(), 10, null);
        ToolResultEvictionMiddleware middleware =
                new ToolResultEvictionMiddleware(filesystem, testConfig());
        AgentState state =
                AgentState.builder().addMessage(toolMessage(toolResult(FULL_OUTPUT))).build();
        RuntimeContext ctx = RuntimeContext.builder().agentState(state).build();
        Msg hookMessage = Msg.builder().role(MsgRole.ASSISTANT).textContent("hook view").build();
        ReasoningInput hookModifiedInput =
                new ReasoningInput(List.of(hookMessage), List.of(), null);
        AtomicReference<ReasoningInput> forwarded = new AtomicReference<>();

        middleware
                .onReasoning(
                        agent("Test Agent"),
                        ctx,
                        hookModifiedInput,
                        downstream -> {
                            forwarded.set(downstream);
                            return Flux.empty();
                        })
                .collectList()
                .block();

        assertSame(
                hookModifiedInput,
                forwarded.get(),
                "Evicting canonical state must not reintroduce a result removed by the hook");
        String persistedOutput = toolOutput(state.getContext().get(0));
        assertTrue(persistedOutput.contains("Tool output was too large"));
        assertFalse(persistedOutput.contains(FULL_OUTPUT));
        try (var files = Files.list(tempDir.resolve("large_tool_results/Test_Agent"))) {
            Path stored = files.findFirst().orElseThrow();
            assertEquals(FULL_OUTPUT, Files.readString(stored));
        }
    }

    @Test
    void cachedEvictionPreservesHookRewrittenMetadataAndState() {
        LocalFilesystem filesystem =
                new LocalFilesystem(tempDir, LocalFsMode.ROOTED, PathPolicy.empty(), 10, null);
        ToolResultEvictionMiddleware middleware =
                new ToolResultEvictionMiddleware(filesystem, testConfig());
        AgentState state =
                AgentState.builder().addMessage(toolMessage(toolResult(FULL_OUTPUT))).build();
        RuntimeContext ctx = RuntimeContext.builder().agentState(state).build();
        ToolResultBlock hookRewrite =
                new ToolResultBlock(
                        "call:1",
                        "large_output",
                        List.of(TextBlock.builder().text(FULL_OUTPUT).build()),
                        Map.of("source", "hook", "hook_annotation", "preserve-me"),
                        ToolResultState.ERROR);
        ReasoningInput hookModifiedInput =
                new ReasoningInput(List.of(toolMessage(hookRewrite)), List.of(), null);
        AtomicReference<ReasoningInput> forwarded = new AtomicReference<>();

        middleware
                .onReasoning(
                        agent("Test Agent"),
                        ctx,
                        hookModifiedInput,
                        downstream -> {
                            forwarded.set(downstream);
                            return Flux.empty();
                        })
                .collectList()
                .block();

        ToolResultBlock compacted =
                forwarded.get().messages().get(0).getContentBlocks(ToolResultBlock.class).get(0);
        assertEquals("hook", compacted.getMetadata().get("source"));
        assertEquals("preserve-me", compacted.getMetadata().get("hook_annotation"));
        assertEquals(
                true,
                compacted.getMetadata().get(ToolResultEvictionMiddleware.EVICTED_METADATA_KEY));
        assertEquals(ToolResultState.ERROR, compacted.getState());
    }

    @Test
    void realLargeOutputUsingPlaceholderPrefixIsStillEvicted() throws IOException {
        String prefixedOutput = "Tool output was too large (but this is real): " + FULL_OUTPUT;
        LocalFilesystem filesystem =
                new LocalFilesystem(tempDir, LocalFsMode.ROOTED, PathPolicy.empty(), 10, null);
        ToolResultEvictionMiddleware middleware =
                new ToolResultEvictionMiddleware(filesystem, testConfig());
        AgentState state =
                AgentState.builder().addMessage(toolMessage(toolResult(prefixedOutput))).build();
        RuntimeContext ctx = RuntimeContext.builder().agentState(state).build();
        ReasoningInput input =
                new ReasoningInput(
                        List.of(toolMessage(toolResult(prefixedOutput))), List.of(), null);
        AtomicReference<ReasoningInput> forwarded = new AtomicReference<>();

        middleware
                .onReasoning(
                        agent("Test Agent"),
                        ctx,
                        input,
                        downstream -> {
                            forwarded.set(downstream);
                            return Flux.empty();
                        })
                .collectList()
                .block();

        ToolResultBlock modelResult =
                forwarded.get().messages().get(0).getContentBlocks(ToolResultBlock.class).get(0);
        assertFalse(prefixedOutput.equals(toolOutput(forwarded.get().messages().get(0))));
        assertEquals(
                true,
                modelResult.getMetadata().get(ToolResultEvictionMiddleware.EVICTED_METADATA_KEY));
        assertFalse(prefixedOutput.equals(toolOutput(state.getContext().get(0))));
        try (var files = Files.list(tempDir.resolve("large_tool_results/Test_Agent"))) {
            Path stored = files.findFirst().orElseThrow();
            assertEquals(prefixedOutput, Files.readString(stored));
        }
    }

    @Test
    void writeFailureLeavesReasoningInputAndAgentStateUntouched() {
        RecordingFilesystem filesystem =
                new RecordingFilesystem(tempDir, WriteResult.fail("write denied"));
        ToolResultEvictionMiddleware middleware =
                new ToolResultEvictionMiddleware(filesystem, testConfig());

        Msg stateToolMessage = toolMessage(toolResult(FULL_OUTPUT));
        AgentState state = AgentState.builder().addMessage(stateToolMessage).build();
        RuntimeContext ctx = RuntimeContext.builder().agentState(state).build();
        Msg inputToolMessage = toolMessage(toolResult(FULL_OUTPUT));
        ReasoningInput input = new ReasoningInput(List.of(inputToolMessage), List.of(), null);
        AtomicReference<ReasoningInput> forwarded = new AtomicReference<>();

        middleware
                .onReasoning(
                        agent("Test Agent"),
                        ctx,
                        input,
                        downstream -> {
                            forwarded.set(downstream);
                            return Flux.empty();
                        })
                .collectList()
                .block();

        assertSame(input, forwarded.get());
        assertSame(stateToolMessage, state.getContext().get(0));
        assertEquals(FULL_OUTPUT, toolOutput(forwarded.get().messages().get(0)));
        assertEquals(FULL_OUTPUT, toolOutput(state.getContext().get(0)));
        assertSame(ctx, filesystem.lastContext);
        assertTrue(filesystem.lastPath.startsWith("large_tool_results/Test_Agent/call_1-"));
        assertEquals(FULL_OUTPUT, filesystem.lastContent);
    }

    @Test
    void preservesConfiguredAbsolutePathWhileTrimmingTrailingSlashes() {
        RecordingFilesystem filesystem = new RecordingFilesystem(tempDir, WriteResult.ok("stored"));
        ToolResultEvictionConfig config =
                ToolResultEvictionConfig.builder()
                        .maxResultChars(20)
                        .previewChars(4)
                        .evictionPath("/workspace/results///")
                        .build();
        ToolResultEvictionMiddleware middleware =
                new ToolResultEvictionMiddleware(filesystem, config);
        RuntimeContext ctx = RuntimeContext.empty();
        ReasoningInput input =
                new ReasoningInput(List.of(toolMessage(toolResult(FULL_OUTPUT))), List.of(), null);

        middleware
                .onReasoning(agent("Test Agent"), ctx, input, ignored -> Flux.empty())
                .collectList()
                .block();

        assertSame(ctx, filesystem.lastContext);
        assertTrue(filesystem.lastPath.startsWith("/workspace/results/Test_Agent/call_1-"));
        assertEquals(FULL_OUTPUT, filesystem.lastContent);
    }

    @Test
    void reusedToolCallIdUpdatesOnlyLatestOccurrenceAndKeepsBothFiles() throws IOException {
        String oldOutput = FULL_OUTPUT + "-old";
        String newOutput = FULL_OUTPUT + "-new";
        LocalFilesystem filesystem =
                new LocalFilesystem(tempDir, LocalFsMode.ROOTED, PathPolicy.empty(), 10, null);
        ToolResultEvictionMiddleware middleware =
                new ToolResultEvictionMiddleware(filesystem, testConfig());
        AgentState state =
                AgentState.builder().addMessage(toolMessage(toolResult(oldOutput))).build();
        RuntimeContext ctx = RuntimeContext.builder().agentState(state).build();
        Agent testAgent = agent("Test Agent");

        middleware
                .onReasoning(
                        testAgent,
                        ctx,
                        new ReasoningInput(
                                List.of(toolMessage(toolResult(oldOutput))), List.of(), null),
                        ignored -> Flux.empty())
                .collectList()
                .block();
        String oldPlaceholder = toolOutput(state.getContext().get(0));

        state.contextMutable().add(toolMessage(toolResult(newOutput)));
        middleware
                .onReasoning(
                        testAgent,
                        ctx,
                        new ReasoningInput(
                                List.of(toolMessage(toolResult(newOutput))), List.of(), null),
                        ignored -> Flux.empty())
                .collectList()
                .block();

        assertEquals(
                oldPlaceholder,
                toolOutput(state.getContext().get(0)),
                "A reused id must not rewrite the earlier logical result");
        assertTrue(toolOutput(state.getContext().get(1)).contains("Tool output was too large"));

        List<Path> storedFiles;
        try (var files = Files.list(tempDir.resolve("large_tool_results/Test_Agent"))) {
            storedFiles = files.toList();
        }
        assertEquals(2, storedFiles.size(), "Different contents must receive different paths");
        List<String> storedContents =
                storedFiles.stream()
                        .map(
                                path -> {
                                    try {
                                        return Files.readString(path);
                                    } catch (IOException e) {
                                        throw new IllegalStateException(e);
                                    }
                                })
                        .toList();
        assertTrue(storedContents.contains(oldOutput));
        assertTrue(storedContents.contains(newOutput));
    }

    private static ToolResultEvictionConfig testConfig() {
        return ToolResultEvictionConfig.builder().maxResultChars(20).previewChars(4).build();
    }

    private static ToolResultBlock toolResult(String output) {
        return new ToolResultBlock(
                "call:1",
                "large_output",
                List.of(TextBlock.builder().text(output).build()),
                Map.of("source", "test"),
                ToolResultState.SUCCESS);
    }

    private static Msg toolMessage(ToolResultBlock result) {
        return Msg.builder().role(MsgRole.TOOL).content(result).build();
    }

    private static String toolOutput(Msg message) {
        ToolResultBlock result = message.getContentBlocks(ToolResultBlock.class).get(0);
        StringBuilder output = new StringBuilder();
        for (ContentBlock block : result.getOutput()) {
            if (block instanceof TextBlock text && text.getText() != null) {
                output.append(text.getText());
            }
        }
        return output.toString();
    }

    private static Agent agent(String name) {
        return new AgentBase(name) {
            @Override
            protected Mono<Msg> doCall(List<Msg> msgs) {
                return Mono.empty();
            }

            @Override
            protected Mono<Msg> handleInterrupt(InterruptContext context, Msg... originalArgs) {
                return Mono.empty();
            }
        };
    }

    private static final class RecordingFilesystem extends LocalFilesystem {

        private final WriteResult result;
        private RuntimeContext lastContext;
        private String lastPath;
        private String lastContent;

        private RecordingFilesystem(Path root, WriteResult result) {
            super(root);
            this.result = result;
        }

        @Override
        public WriteResult write(RuntimeContext context, String path, String content) {
            lastContext = context;
            lastPath = path;
            lastContent = content;
            return result;
        }
    }
}
