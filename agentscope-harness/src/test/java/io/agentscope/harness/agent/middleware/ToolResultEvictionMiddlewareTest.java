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
import io.agentscope.core.message.AudioBlock;
import io.agentscope.core.message.Base64Source;
import io.agentscope.core.message.ContentBlock;
import io.agentscope.core.message.ImageBlock;
import io.agentscope.core.message.Msg;
import io.agentscope.core.message.MsgRole;
import io.agentscope.core.message.TextBlock;
import io.agentscope.core.message.ToolExecutionDetails;
import io.agentscope.core.message.ToolResultBlock;
import io.agentscope.core.message.ToolResultState;
import io.agentscope.core.middleware.ReasoningInput;
import io.agentscope.core.model.ChatUsage;
import io.agentscope.core.state.AgentState;
import io.agentscope.harness.agent.filesystem.local.LocalFilesystem;
import io.agentscope.harness.agent.filesystem.model.ReadResult;
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
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
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
        assertFalse(config.getExcludedToolNames().contains("grep_files"));
        assertFalse(config.getExcludedToolNames().contains("glob_files"));

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

    @ParameterizedTest
    @EnumSource(
            value = ToolResultState.class,
            names = {"SUCCESS", "ERROR"})
    void evictsOnlyTextAndKeepsRichBlockOrder(ToolResultState resultState) throws IOException {
        LocalFilesystem filesystem =
                new LocalFilesystem(tempDir, LocalFsMode.ROOTED, PathPolicy.empty(), 10, null);
        ToolResultEvictionMiddleware middleware =
                new ToolResultEvictionMiddleware(filesystem, testConfig());
        ImageBlock leading = image("AA==");
        AudioBlock audio =
                AudioBlock.builder().source(new Base64Source("audio/wav", "AQ==")).build();
        ImageBlock middle = image("Ag==");
        ImageBlock trailing = image("Aw==");
        List<ContentBlock> output =
                List.of(
                        leading,
                        text("HH"),
                        text("A".repeat(48)),
                        audio,
                        text("M".repeat(100)),
                        middle,
                        text("T".repeat(48)),
                        text("ZZ"),
                        trailing);
        String fullText = "HH" + "A".repeat(48) + "M".repeat(100) + "T".repeat(48) + "ZZ";
        ToolExecutionDetails details =
                new ToolExecutionDetails("process", ToolExecutionDetails.Outcome.PARTIAL, 7, true);
        ToolResultBlock original =
                new ToolResultBlock(
                        "call:1",
                        "large_output",
                        output,
                        Map.of("source", "test"),
                        resultState,
                        details);
        Msg message =
                Msg.builder()
                        .id("rich-message")
                        .name("tool")
                        .role(MsgRole.TOOL)
                        .content(original)
                        .metadata(Map.of("message", "kept"))
                        .timestamp("2026-10-28T00:00:00Z")
                        .usage(ChatUsage.builder().inputTokens(10).outputTokens(5).build())
                        .build();
        AgentState state = AgentState.builder().addMessage(message).build();
        RuntimeContext ctx = RuntimeContext.builder().agentState(state).build();

        ReasoningInput compacted =
                middleware.prepare(
                        agent("Test Agent"),
                        ctx,
                        new ReasoningInput(List.of(message), List.of(), null));

        Msg rebuilt = compacted.messages().get(0);
        ToolResultBlock result = rebuilt.getContentBlocks(ToolResultBlock.class).get(0);
        List<ContentBlock> preview = result.getOutput();
        assertEquals(8, preview.size(), "Fully removed text must not leave an empty block");
        assertSame(leading, preview.get(0));
        assertTrue(((TextBlock) preview.get(1)).getText().endsWith("HH"));
        assertTrue(((TextBlock) preview.get(2)).getText().startsWith("AA"));
        assertFalse(((TextBlock) preview.get(2)).getText().contains("AAA"));
        assertSame(audio, preview.get(3));
        assertSame(middle, preview.get(4));
        assertEquals("TT", ((TextBlock) preview.get(5)).getText());
        assertEquals("ZZ", ((TextBlock) preview.get(6)).getText());
        assertSame(trailing, preview.get(7));
        assertEquals(original.getId(), result.getId());
        assertEquals(original.getName(), result.getName());
        assertEquals("test", result.getMetadata().get("source"));
        assertEquals(
                true, result.getMetadata().get(ToolResultEvictionMiddleware.EVICTED_METADATA_KEY));
        assertEquals(resultState, result.getState());
        assertEquals(details, result.getExecutionDetails());
        assertEquals(message.getId(), rebuilt.getId());
        assertEquals(message.getName(), rebuilt.getName());
        assertEquals(message.getRole(), rebuilt.getRole());
        assertEquals(message.getMetadata(), rebuilt.getMetadata());
        assertEquals(message.getTimestamp(), rebuilt.getTimestamp());
        assertSame(message.getUsage(), rebuilt.getUsage());
        assertEquals(output, original.getOutput());
        assertSame(original, message.getContent().get(0));
        ToolResultBlock persisted =
                state.getContext().get(0).getContentBlocks(ToolResultBlock.class).get(0);
        assertEquals(toolOutput(rebuilt), toolOutput(state.getContext().get(0)));
        assertEquals(
                List.of(leading, audio, middle, trailing),
                persisted.getOutput().stream()
                        .filter(block -> !(block instanceof TextBlock))
                        .toList());

        try (var files = Files.list(tempDir.resolve("large_tool_results/Test_Agent"))) {
            Path storedFile = files.findFirst().orElseThrow();
            String path = tempDir.relativize(storedFile).toString().replace('\\', '/');
            assertTrue(toolOutput(rebuilt).contains("`" + path + "`"));
            ReadResult recovered = filesystem.read(ctx, path, 0, 100);
            assertTrue(recovered.isSuccess());
            assertEquals(fullText, recovered.fileData().content());
        }
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
        RecordingFilesystem filesystem = new RecordingFilesystem(tempDir, WriteResult.ok("stored"));
        ToolResultEvictionMiddleware middleware =
                new ToolResultEvictionMiddleware(filesystem, testConfig());
        ImageBlock canonicalImage = image("AA==");
        ImageBlock hookImage = image("AQ==");
        AgentState state =
                AgentState.builder()
                        .addMessage(
                                toolMessage(toolResult(List.of(canonicalImage, text(FULL_OUTPUT)))))
                        .build();
        RuntimeContext ctx = RuntimeContext.builder().agentState(state).build();
        ToolResultBlock hookRewrite =
                new ToolResultBlock(
                        "call:1",
                        "large_output",
                        List.of(
                                text(FULL_OUTPUT.substring(0, 50)),
                                hookImage,
                                text(FULL_OUTPUT.substring(50))),
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
        assertSame(hookImage, compacted.getOutput().get(1));
        assertEquals("6789", ((TextBlock) compacted.getOutput().get(2)).getText());
        ToolResultBlock canonical =
                state.getContext().get(0).getContentBlocks(ToolResultBlock.class).get(0);
        assertSame(canonicalImage, canonical.getOutput().get(0));
        assertEquals(
                1,
                filesystem.writes,
                "Reuse the saved text without copying the other view's media");
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

        ImageBlock image = image("AA==");
        Msg stateToolMessage = toolMessage(toolResult(List.of(image, text(FULL_OUTPUT))));
        AgentState state = AgentState.builder().addMessage(stateToolMessage).build();
        RuntimeContext ctx = RuntimeContext.builder().agentState(state).build();
        Msg inputToolMessage = toolMessage(toolResult(List.of(image, text(FULL_OUTPUT))));
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
        assertSame(inputToolMessage, forwarded.get().messages().get(0));
        assertEquals(FULL_OUTPUT, toolOutput(forwarded.get().messages().get(0)));
        assertEquals(FULL_OUTPUT, toolOutput(state.getContext().get(0)));
        assertSame(ctx, filesystem.lastContext);
        assertTrue(filesystem.lastPath.startsWith("large_tool_results/Test_Agent/call_1-"));
        assertEquals(FULL_OUTPUT, filesystem.lastContent);
    }

    @Test
    void mediaOnlyAndWithinBudgetResultsAreNotEvicted() {
        RecordingFilesystem filesystem = new RecordingFilesystem(tempDir, WriteResult.ok("stored"));
        ToolResultEvictionMiddleware middleware =
                new ToolResultEvictionMiddleware(filesystem, testConfig());
        ImageBlock image = image("AA==");
        ReasoningInput input =
                new ReasoningInput(
                        List.of(
                                toolMessage(toolResult(List.of(image))),
                                toolMessage(
                                        toolResult(
                                                List.of(
                                                        text("a".repeat(10)),
                                                        image,
                                                        text("b".repeat(10)))))),
                        List.of(),
                        null);

        assertSame(input, middleware.prepare(agent("Test Agent"), RuntimeContext.empty(), input));
        assertEquals(0, filesystem.writes);
    }

    @Test
    void fullPreviewDoesNotMarkUnchangedTextAsOmitted() {
        RecordingFilesystem filesystem = new RecordingFilesystem(tempDir, WriteResult.ok("stored"));
        ToolResultEvictionMiddleware middleware =
                new ToolResultEvictionMiddleware(
                        filesystem,
                        ToolResultEvictionConfig.builder()
                                .maxResultChars(20)
                                .previewChars(100)
                                .build());
        ImageBlock image = image("AA==");
        ReasoningInput input =
                new ReasoningInput(
                        List.of(toolMessage(toolResult(List.of(image, text(FULL_OUTPUT))))),
                        List.of(),
                        null);

        Msg result =
                middleware
                        .prepare(agent("Test Agent"), RuntimeContext.empty(), input)
                        .messages()
                        .get(0);

        assertTrue(toolOutput(result).endsWith(FULL_OUTPUT));
        assertFalse(toolOutput(result).contains("\n\n...\n\n"));
        assertSame(image, result.getContentBlocks(ToolResultBlock.class).get(0).getOutput().get(0));
    }

    @Test
    void zeroPreviewKeepsMediaAndRecoveryPointer() {
        RecordingFilesystem filesystem = new RecordingFilesystem(tempDir, WriteResult.ok("stored"));
        ToolResultEvictionMiddleware middleware =
                new ToolResultEvictionMiddleware(
                        filesystem,
                        ToolResultEvictionConfig.builder()
                                .maxResultChars(20)
                                .previewChars(0)
                                .build());
        ImageBlock leading = image("AA==");
        ImageBlock trailing = image("AQ==");
        ReasoningInput input =
                new ReasoningInput(
                        List.of(
                                toolMessage(
                                        toolResult(List.of(leading, text(FULL_OUTPUT), trailing)))),
                        List.of(),
                        null);

        Msg result =
                middleware
                        .prepare(agent("Test Agent"), RuntimeContext.empty(), input)
                        .messages()
                        .get(0);

        List<ContentBlock> output =
                result.getContentBlocks(ToolResultBlock.class).get(0).getOutput();
        assertEquals(3, output.size());
        assertSame(leading, output.get(0));
        assertSame(trailing, output.get(2));
        assertTrue(toolOutput(result).contains("`" + filesystem.lastPath + "`"));
        assertFalse(toolOutput(result).contains("0123"));
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
        return toolResult(List.of(text(output)));
    }

    private static ToolResultBlock toolResult(List<ContentBlock> output) {
        return new ToolResultBlock(
                "call:1",
                "large_output",
                output,
                Map.of("source", "test"),
                ToolResultState.SUCCESS);
    }

    private static TextBlock text(String value) {
        return TextBlock.builder().text(value).build();
    }

    private static ImageBlock image(String data) {
        return ImageBlock.builder().source(new Base64Source("image/png", data)).build();
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
        private int writes;

        private RecordingFilesystem(Path root, WriteResult result) {
            super(root);
            this.result = result;
        }

        @Override
        public WriteResult write(RuntimeContext context, String path, String content) {
            writes++;
            lastContext = context;
            lastPath = path;
            lastContent = content;
            return result;
        }
    }
}
