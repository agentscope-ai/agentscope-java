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
package io.agentscope.harness.agent.memory.compaction;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import io.agentscope.core.agent.RuntimeContext;
import io.agentscope.core.message.AudioBlock;
import io.agentscope.core.message.Base64Source;
import io.agentscope.core.message.ContentBlock;
import io.agentscope.core.message.DataBlock;
import io.agentscope.core.message.ImageBlock;
import io.agentscope.core.message.Msg;
import io.agentscope.core.message.MsgRole;
import io.agentscope.core.message.TextBlock;
import io.agentscope.core.message.ToolExecutionDetails;
import io.agentscope.core.message.ToolResultBlock;
import io.agentscope.core.message.ToolResultState;
import io.agentscope.core.message.ToolUseBlock;
import io.agentscope.core.message.URLSource;
import io.agentscope.core.message.VideoBlock;
import io.agentscope.core.model.ChatResponse;
import io.agentscope.core.model.GenerateOptions;
import io.agentscope.core.model.Model;
import io.agentscope.core.model.ToolSchema;
import io.agentscope.core.session.InMemorySessionLogStore;
import io.agentscope.core.session.SessionKey;
import io.agentscope.core.session.SessionRecorder;
import io.agentscope.harness.agent.memory.MemoryFlushManager;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import org.junit.jupiter.api.Test;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;

/** Tests message routing between summarization, memory flushing, and the preserved tail. */
class ConversationCompactorTest {

    /** Verifies that a prior summary is summarized again but is neither flushed nor retained. */
    @Test
    void compactIfNeeded_routesPriorSummaryToSummaryInputButNotFlushOrTail() {
        Fixture fixture = new Fixture();

        List<Msg> compacted =
                fixture.compact(
                        List.of(
                                summary("S1"),
                                message("M2"),
                                message("TAIL_1"),
                                message("TAIL_2")));

        assertTrue(fixture.model.prompt(0).contains("S1"));
        assertTrue(fixture.model.prompt(0).contains("M2"));
        assertEquals(List.of("M2"), texts(fixture.flushInputs.get(0)));
        assertEquals(3, compacted.size());
        assertTrue(compacted.get(0).getId().startsWith(ConversationCompactor.SUMMARY_MSG_NAME));
        assertEquals(ConversationCompactor.SUMMARY_MSG_NAME, compacted.get(0).getName());
        assertEquals(List.of("TAIL_1", "TAIL_2"), texts(compacted.subList(1, compacted.size())));
    }

    /** Verifies that multiple prior summaries in the prefix are replaced by one new summary. */
    @Test
    void compactIfNeeded_mergesMultiplePriorSummariesIntoOneReplacement() {
        Fixture fixture = new Fixture();

        List<Msg> compacted =
                fixture.compact(
                        List.of(
                                summary("S0"),
                                message("M1"),
                                summary("S1"),
                                message("M2"),
                                message("TAIL_1"),
                                message("TAIL_2")));

        String prompt = fixture.model.prompt(0);
        assertTrue(prompt.contains("S0"));
        assertTrue(prompt.contains("S1"));
        assertEquals(List.of("M1", "M2"), texts(fixture.flushInputs.get(0)));
        assertEquals(
                1,
                compacted.stream()
                        .filter(msg -> ConversationCompactor.SUMMARY_MSG_NAME.equals(msg.getName()))
                        .count());
    }

    /** Verifies that a summary-only prefix is not treated as empty conversation history. */
    @Test
    void compactIfNeeded_summaryOnlyPrefixDoesNotBecomeEmptyHistory() {
        Fixture fixture = new Fixture();

        List<Msg> compacted =
                fixture.compact(
                        List.of(summary("ONLY_OLD_SUMMARY"), message("TAIL_1"), message("TAIL_2")));

        assertTrue(fixture.model.prompt(0).contains("ONLY_OLD_SUMMARY"));
        assertTrue(fixture.flushInputs.get(0).isEmpty());
        assertTrue(text(compacted.get(0)).contains("ROUND_1"));
        assertFalse(text(compacted.get(0)).contains("No previous conversation history."));
    }

    /** Verifies that chained compaction summarizes the prior summary with the new prefix. */
    @Test
    void compactIfNeeded_chainedCompactionUsesPreviousSummaryAndNewPrefix() {
        Fixture fixture = new Fixture();
        List<Msg> first =
                fixture.compact(
                        List.of(
                                summary("S1"),
                                message("M2"),
                                message("TAIL_1"),
                                message("TAIL_2")));
        List<Msg> secondInput = new ArrayList<>(first);
        secondInput.add(message("M3"));
        secondInput.add(message("TAIL_3"));
        secondInput.add(message("TAIL_4"));

        List<Msg> second = fixture.compact(secondInput);

        assertTrue(fixture.model.prompt(1).contains("ROUND_1"));
        assertTrue(fixture.model.prompt(1).contains("M3"));
        assertFalse(text(second.get(0)).contains("ROUND_1"));
        assertTrue(text(second.get(0)).contains("ROUND_2"));
        assertEquals(List.of("TAIL_1", "TAIL_2", "M3"), texts(fixture.flushInputs.get(1)));
    }

    /** Verifies that disabling memory flush does not remove the prior summary from summarization. */
    @Test
    void compactIfNeeded_skipsFlushWhenDisabledAndStillUsesPriorSummary() {
        Fixture fixture = new Fixture(false);

        fixture.compact(
                List.of(summary("S1"), message("M2"), message("TAIL_1"), message("TAIL_2")));

        assertTrue(fixture.model.prompt(0).contains("S1"));
        assertTrue(fixture.model.prompt(0).contains("M2"));
        assertTrue(fixture.flushInputs.isEmpty());
    }

    /**
     * Verifies that when the safe cutoff is zero (nothing may be summarized) the lightweight
     * prune/truncate result is still returned instead of being discarded.
     */
    @Test
    void compactIfNeeded_returnsLightweightResultWhenCutoffIsZero() {
        RecordingModel model = new RecordingModel();
        ConversationCompactor compactor =
                new ConversationCompactor(model, new MemoryFlushManager(null, null));
        List<Msg> input = List.of(message("hello"), toolResult("bash", "x".repeat(100_000)));
        CompactionConfig config =
                CompactionConfig.builder()
                        .triggerTokens(1)
                        .keepTokens(1_000_000)
                        .keepMessages(0)
                        .flushBeforeCompact(false)
                        .truncateArgs(null)
                        .prune(
                                CompactionConfig.PruneConfig.builder()
                                        .protectTokens(0)
                                        .minimumTokens(1)
                                        .maxOutputChars(10)
                                        .excludedTools(Set.of())
                                        .build())
                        .build();

        Optional<List<Msg>> result =
                compactor
                        .compactIfNeeded(mock(RuntimeContext.class), input, config, "agent", "s")
                        .block();

        assertTrue(result.isPresent());
        assertTrue(
                TokenCounterUtil.calculateToken(result.orElseThrow())
                        < TokenCounterUtil.calculateToken(input));
    }

    /** Verifies the empty result is preserved when the cutoff is zero and nothing changed. */
    @Test
    void compactIfNeeded_keepsEmptyWhenCutoffZeroAndNothingChanged() {
        RecordingModel model = new RecordingModel();
        ConversationCompactor compactor =
                new ConversationCompactor(model, new MemoryFlushManager(null, null));
        List<Msg> input = List.of(message("M1"), message("M2"));
        CompactionConfig config =
                CompactionConfig.builder()
                        .triggerTokens(1)
                        .keepTokens(1_000_000)
                        .keepMessages(0)
                        .flushBeforeCompact(false)
                        .truncateArgs(null)
                        .prune(null)
                        .build();

        Optional<List<Msg>> result =
                compactor
                        .compactIfNeeded(mock(RuntimeContext.class), input, config, "agent", "s")
                        .block();

        assertTrue(result.isEmpty());
    }

    /** Verifies that memory-flush fallback does not swallow a user interruption. */
    @Test
    void compactIfNeeded_propagatesInterruptedMemoryFlush() {
        RecordingModel model = new RecordingModel();
        MemoryFlushManager flushManager = mock(MemoryFlushManager.class);
        when(flushManager.flushMemories(any(RuntimeContext.class), anyList()))
                .thenReturn(Mono.error(new InterruptedException("interrupted while flushing")));
        ConversationCompactor compactor = new ConversationCompactor(model, flushManager);

        StepVerifier.create(
                        compactor.compactIfNeeded(
                                mock(RuntimeContext.class),
                                compactableMessages(),
                                config(true),
                                "agent-id",
                                "session-id"))
                .expectError(InterruptedException.class)
                .verify();
    }

    /** Verifies that the durable compaction end event contains before and after metrics. */
    @Test
    void compactIfNeeded_recordsCompactionMetrics() {
        InMemorySessionLogStore store = new InMemorySessionLogStore();
        RuntimeContext runtimeContext =
                RuntimeContext.builder().userId("user").sessionId("session").build();
        var log = store.open(new SessionKey("user", "agent-id", "session"), runtimeContext);
        SessionRecorder recorder = new SessionRecorder(log, "turn");
        runtimeContext.put(SessionRecorder.CONTEXT_KEY, recorder);

        List<Msg> input = compactableMessages();
        MemoryFlushManager flushManager = new MemoryFlushManager(null, null);
        try {
            List<Msg> compacted =
                    new ConversationCompactor(new RecordingModel(), flushManager)
                            .compactIfNeeded(
                                    runtimeContext, input, config(false), "agent-id", "session")
                            .block()
                            .orElseThrow();

            var end =
                    log.readAfter(0, 100).stream()
                            .filter(event -> event.type().equals("compaction/end"))
                            .findFirst()
                            .orElseThrow();
            assertEquals("completed", end.data().get("status"));
            assertEquals(input.size(), end.data().get("beforeMsgCount"));
            assertEquals(
                    TokenCounterUtil.calculateToken(input), end.data().get("beforeTokenCount"));
            assertEquals(compacted.size(), end.data().get("afterMsgCount"));
            assertEquals(
                    TokenCounterUtil.calculateToken(compacted), end.data().get("afterTokenCount"));
        } finally {
            recorder.close();
        }
    }

    /** Verifies that failed compaction records only metrics known before execution. */
    @Test
    void compactIfNeeded_failedEventOmitsAfterMetrics() {
        InMemorySessionLogStore store = new InMemorySessionLogStore();
        RuntimeContext runtimeContext =
                RuntimeContext.builder().userId("user").sessionId("session").build();
        var log = store.open(new SessionKey("user", "agent-id", "failed-session"), runtimeContext);
        SessionRecorder recorder = new SessionRecorder(log, "turn");
        runtimeContext.put(SessionRecorder.CONTEXT_KEY, recorder);
        Model failingModel =
                new Model() {
                    @Override
                    public Flux<ChatResponse> stream(
                            List<Msg> messages, List<ToolSchema> tools, GenerateOptions options) {
                        return Flux.error(new IllegalStateException("summary failed"));
                    }

                    @Override
                    public String getModelName() {
                        return "failing-model";
                    }
                };

        List<Msg> input = compactableMessages();
        try {
            StepVerifier.create(
                            new ConversationCompactor(
                                            failingModel, new MemoryFlushManager(null, null))
                                    .compactIfNeeded(
                                            runtimeContext,
                                            input,
                                            config(false),
                                            "agent-id",
                                            "failed-session"))
                    .expectErrorMessage("summary failed")
                    .verify();

            var end =
                    log.readAfter(0, 100).stream()
                            .filter(event -> event.type().equals("compaction/end"))
                            .findFirst()
                            .orElseThrow();
            assertEquals("failed", end.data().get("status"));
            assertEquals(input.size(), end.data().get("beforeMsgCount"));
            assertEquals(
                    TokenCounterUtil.calculateToken(input), end.data().get("beforeTokenCount"));
            assertFalse(end.data().containsKey("afterMsgCount"));
            assertFalse(end.data().containsKey("afterTokenCount"));
        } finally {
            recorder.close();
        }
    }

    /**
     * Verifies that a message carrying top-level media is kept out of the summarization prefix and
     * survives as the original block in the working history, while its data never reaches the
     * summary prompt.
     */
    @Test
    void compactIfNeeded_keepsTopLevelMediaMessageOutOfSummaryPrefix() {
        Fixture fixture = new Fixture(false);
        String imageData =
                "iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAQAAAC1HAwCAAAAC0lEQVR42mNk+A8AAQUBAScY42YAAAAASUVORK5CYII=";
        Msg media = messageWithImage("look at this", imageData);

        List<Msg> compacted =
                fixture.compact(
                        List.of(message("M1"), media, message("TAIL_1"), message("TAIL_2")));

        String prompt = fixture.model.prompt(0);
        assertTrue(prompt.contains("M1"));
        assertFalse(prompt.contains("look at this"));
        assertFalse(prompt.contains(imageData));
        assertEquals(4, compacted.size());
        assertSame(media, compacted.get(1));
        assertTrue(compacted.get(1).getContent().get(1) instanceof ImageBlock);
        assertEquals(List.of("TAIL_1", "TAIL_2"), texts(compacted.subList(2, compacted.size())));
    }

    /**
     * Verifies that media nested in a tool result pulls its whole ASSISTANT/TOOL group into the
     * kept tail instead of being summarized away.
     */
    @Test
    void compactIfNeeded_keepsNestedToolMediaGroupOutOfSummaryPrefix() {
        Fixture fixture = new Fixture(false);
        Msg assistant = assistantToolCall("call:img", "screenshot");
        Msg tool =
                toolMessage(
                        toolResult(
                                "call:img",
                                "screenshot",
                                List.of(
                                        TextBlock.builder().text("captured").build(),
                                        ImageBlock.builder()
                                                .source(
                                                        Base64Source.builder()
                                                                .mediaType("image/png")
                                                                .data("iVBORw0KGgo=")
                                                                .build())
                                                .build()),
                                Map.of(),
                                ToolResultState.SUCCESS,
                                null));

        List<Msg> compacted =
                fixture.compact(
                        List.of(
                                message("M1"),
                                assistant,
                                tool,
                                message("TAIL_1"),
                                message("TAIL_2")));

        String prompt = fixture.model.prompt(0);
        assertTrue(prompt.contains("M1"));
        assertFalse(prompt.contains("captured"));
        assertEquals(5, compacted.size());
        assertSame(assistant, compacted.get(1));
        assertSame(tool, compacted.get(2));
        ToolResultBlock kept = compacted.get(2).getContentBlocks(ToolResultBlock.class).get(0);
        assertTrue(kept.getOutput().stream().anyMatch(block -> block instanceof ImageBlock));
    }

    /**
     * Verifies that when a protected message sits at the history head, summarization is refused
     * entirely — no LLM call happens and the history stays untouched.
     */
    @Test
    void compactIfNeeded_refusesSummarizationWhenProtectedMessageCannotBeExcluded() {
        Fixture fixture = new Fixture(false);
        Msg media = messageWithImage("head image", "AAAA");

        Optional<List<Msg>> result =
                fixture.compactor
                        .compactIfNeeded(
                                fixture.runtimeContext,
                                List.of(media, message("M2"), message("TAIL_1"), message("TAIL_2")),
                                fixture.config,
                                "agent-id",
                                "session-id")
                        .block();

        assertTrue(result.isEmpty());
        assertTrue(fixture.model.inputs.isEmpty());
        assertTrue(fixture.flushInputs.isEmpty());
    }

    /**
     * Verifies every media kind renders as a safe placeholder in the summary input, including
     * media nested in a tool result, without leaking payloads, URLs or tokens.
     */
    @Test
    void formatMessagesForSummaryRendersAllMediaKindsAsPlaceholders() {
        String audioData = "YXVkaW8tcHJvbXB0";
        String videoUrl = "https://example.com/prompt-video.mp4?sig=prompt-secret";
        String dataPayload = "cGRmLXByb21wdA==";
        Msg audio = messageWithMedia("audio note", audioBlock(audioData));
        Msg video =
                messageWithMedia(
                        "video note",
                        VideoBlock.builder()
                                .source(
                                        URLSource.builder()
                                                .url(videoUrl)
                                                .mimeType("video/mp4")
                                                .build())
                                .build());
        Msg data =
                messageWithMedia("data note", dataBlock("doc-prompt", "prompt.pdf", dataPayload));
        Msg tool =
                toolMessage(
                        toolResult(
                                "call:media",
                                "fetch",
                                List.of(
                                        TextBlock.builder().text("tool text").build(),
                                        ImageBlock.builder()
                                                .source(
                                                        Base64Source.builder()
                                                                .mediaType("image/png")
                                                                .data("iVBORw0KGgo=")
                                                                .build())
                                                .build()),
                                Map.of(),
                                ToolResultState.SUCCESS,
                                null));

        String formatted =
                ConversationCompactor.formatMessagesForSummary(List.of(audio, video, data, tool));

        assertTrue(formatted.contains("kind=audio"));
        assertTrue(formatted.contains("kind=video"));
        assertTrue(formatted.contains("kind=data"));
        assertTrue(formatted.contains("kind=image"));
        assertTrue(formatted.contains("protocol=https"));
        assertTrue(formatted.contains("tool text"));
        assertFalse(formatted.contains(audioData));
        assertFalse(formatted.contains(videoUrl));
        assertFalse(formatted.contains("prompt-secret"));
        assertFalse(formatted.contains(dataPayload));
        assertFalse(formatted.contains("iVBORw0KGgo="));
    }

    /** Creates a regular user message. */
    private static Msg message(String text) {
        return Msg.builder()
                .role(MsgRole.USER)
                .content(TextBlock.builder().text(text).build())
                .build();
    }

    /** Creates a TOOL message carrying a single tool result. */
    private static Msg toolResult(String name, String output) {
        return Msg.builder()
                .role(MsgRole.TOOL)
                .content(
                        ToolResultBlock.builder()
                                .id("call-1")
                                .name(name)
                                .output(TextBlock.builder().text(output).build())
                                .build())
                .build();
    }

    /** Creates a user message containing text and a base64 image. */
    private static Msg messageWithImage(String text, String imageData) {
        return messageWithMedia(
                text,
                ImageBlock.builder()
                        .source(
                                Base64Source.builder()
                                        .mediaType("image/png")
                                        .data(imageData)
                                        .build())
                        .build());
    }

    /** Creates a user message containing text and the given media blocks. */
    private static Msg messageWithMedia(String text, ContentBlock... media) {
        List<ContentBlock> content = new ArrayList<>();
        content.add(TextBlock.builder().text(text).build());
        content.addAll(List.of(media));
        return Msg.builder().id("msg-with-media").role(MsgRole.USER).content(content).build();
    }

    private static AudioBlock audioBlock(String data) {
        return AudioBlock.builder()
                .source(Base64Source.builder().mediaType("audio/mp3").data(data).build())
                .build();
    }

    private static DataBlock dataBlock(String id, String name, String data) {
        return DataBlock.builder()
                .source(Base64Source.builder().mediaType("application/pdf").data(data).build())
                .id(id)
                .name(name)
                .build();
    }

    /** Creates a TOOL message wrapping the given result. */
    private static Msg toolMessage(ToolResultBlock result) {
        return Msg.builderForRole(MsgRole.TOOL).id("msg-" + result.getId()).content(result).build();
    }

    /** Builds a {@link ToolResultBlock} with full metadata for preservation checks. */
    private static ToolResultBlock toolResult(
            String id,
            String name,
            List<ContentBlock> output,
            Map<String, Object> metadata,
            ToolResultState state,
            ToolExecutionDetails details) {
        return ToolResultBlock.builder()
                .id(id)
                .name(name)
                .output(output)
                .metadata(metadata)
                .state(state)
                .executionDetails(details)
                .build();
    }

    /** Creates an ASSISTANT message issuing a single tool call. */
    private static Msg assistantToolCall(String callId, String name) {
        return Msg.builderForRole(MsgRole.ASSISTANT)
                .content(ToolUseBlock.builder().id(callId).name(name).input(Map.of()).build())
                .build();
    }

    /** Creates a summary message injected by the compactor. */
    private static Msg summary(String text) {
        return Msg.builder()
                .role(MsgRole.USER)
                .name(ConversationCompactor.SUMMARY_MSG_NAME)
                .content(TextBlock.builder().text(text).build())
                .build();
    }

    /** Creates an input that always triggers compaction and retains a two-message tail. */
    private static List<Msg> compactableMessages() {
        return List.of(message("M1"), message("M2"), message("TAIL_1"), message("TAIL_2"));
    }

    /** Creates focused compaction configuration for fallback-boundary tests. */
    private static CompactionConfig config(boolean flushBeforeCompact) {
        return CompactionConfig.builder()
                .triggerMessages(3)
                .triggerTokens(0)
                .keepMessages(2)
                .keepTokens(0)
                .summaryPrompt("SUMMARY_INPUT:\n{messages}")
                .flushBeforeCompact(flushBeforeCompact)
                .truncateArgs(null)
                .prune(null)
                .build();
    }

    /** Returns the text from the first content block of a message. */
    private static String text(Msg message) {
        return ((TextBlock) message.getContent().get(0)).getText();
    }

    /** Returns message texts for concise routing assertions. */
    private static List<String> texts(List<Msg> messages) {
        return messages.stream().map(ConversationCompactorTest::text).toList();
    }

    /** Holds isolated compactor dependencies and invocation records for each test. */
    private static final class Fixture {

        private final RecordingModel model = new RecordingModel();
        private final MemoryFlushManager flushManager = mock(MemoryFlushManager.class);
        private final RuntimeContext runtimeContext = mock(RuntimeContext.class);
        private final List<List<Msg>> flushInputs = new ArrayList<>();
        private final ConversationCompactor compactor;
        private final CompactionConfig config;

        /** Creates a fixture with memory flushing enabled. */
        private Fixture() {
            this(true);
        }

        /** Creates a fixture with the requested memory-flush setting. */
        private Fixture(boolean flushBeforeCompact) {
            when(flushManager.flushMemories(any(RuntimeContext.class), anyList()))
                    .thenAnswer(
                            invocation -> {
                                List<Msg> input = invocation.getArgument(1);
                                flushInputs.add(List.copyOf(input));
                                return Mono.empty();
                            });
            compactor = new ConversationCompactor(model, flushManager);
            config = config(flushBeforeCompact);
        }

        /** Runs compaction and requires the supplied input to trigger it. */
        private List<Msg> compact(List<Msg> messages) {
            Optional<List<Msg>> result =
                    compactor
                            .compactIfNeeded(
                                    runtimeContext, messages, config, "agent-id", "session-id")
                            .block();
            assertTrue(result.isPresent());
            return result.orElseThrow();
        }
    }

    /** Records summarization prompts and returns a distinct fixed summary for each invocation. */
    private static final class RecordingModel implements Model {

        private final List<List<Msg>> inputs = new ArrayList<>();

        /** Records the model input and emits a fixed text response for the current invocation. */
        @Override
        public Flux<ChatResponse> stream(
                List<Msg> messages, List<ToolSchema> tools, GenerateOptions options) {
            inputs.add(List.copyOf(messages));
            String summary = "ROUND_" + inputs.size();
            return Flux.just(
                    ChatResponse.builder()
                            .id("summary-" + inputs.size())
                            .content(List.of(TextBlock.builder().text(summary).build()))
                            .build());
        }

        /** Returns the test model name. */
        @Override
        public String getModelName() {
            return "recording-model";
        }

        /** Returns the summarization prompt for the specified invocation. */
        private String prompt(int index) {
            return text(inputs.get(index).get(0));
        }
    }
}
