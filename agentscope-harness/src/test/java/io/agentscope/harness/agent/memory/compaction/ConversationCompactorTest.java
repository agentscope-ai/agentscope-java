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
import io.agentscope.harness.agent.memory.compaction.CompactionConfig.PruneConfig;
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
     * Verifies that media nested in a tool result pulls its whole ASSISTANT/TOOL group into the kept
     * tail instead of being summarized away.
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

    /** Verifies media survives two consecutive compaction rounds. */
    @Test
    void compactIfNeeded_retainsMediaAcrossConsecutiveCompactions() {
        Fixture fixture = new Fixture();
        Msg media = messageWithImage("older image", "AAAA");

        List<Msg> first =
                fixture.compact(
                        List.of(message("M1"), media, message("TAIL_1"), message("TAIL_2")));
        assertEquals(4, first.size());

        List<Msg> secondInput = new ArrayList<>(first);
        secondInput.add(message("M3"));
        secondInput.add(message("TAIL_3"));
        secondInput.add(message("TAIL_4"));

        List<Msg> second = fixture.compact(secondInput);

        String prompt = fixture.model.prompt(1);
        assertTrue(prompt.contains("ROUND_1"));
        assertFalse(prompt.contains("older image"));
        assertEquals(7, second.size());
        assertSame(media, second.get(1));
        assertTrue(second.get(1).getContent().get(1) instanceof ImageBlock);
        assertEquals(
                1,
                second.stream()
                        .filter(msg -> ConversationCompactor.SUMMARY_MSG_NAME.equals(msg.getName()))
                        .count());
        assertTrue(fixture.flushInputs.get(1).isEmpty());
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

    /** Verifies top-level audio, video and data messages are never summarized away. */
    @Test
    void compactIfNeeded_keepsTopLevelAudioVideoDataMessagesOutOfSummaryPrefix() {
        Fixture fixture = new Fixture(false);
        String audioData = "YXVkaW8tdG9wLXBheWxvYWQ=";
        String videoData = "dmlkZW8tdG9wLXBheWxvYWQ=";
        String dataPayload = "cGRmLXRvcC1wYXlsb2Fk";
        Msg audio = messageWithAudio("listen to this", audioData);
        Msg video = messageWithVideo("watch this", videoData);
        Msg data = messageWithData("read this", dataPayload);

        List<Msg> compacted =
                fixture.compact(
                        List.of(
                                message("M1"),
                                audio,
                                video,
                                data,
                                message("TAIL_1"),
                                message("TAIL_2")));

        String prompt = fixture.model.prompt(0);
        assertTrue(prompt.contains("M1"));
        assertFalse(prompt.contains("listen to this"));
        assertFalse(prompt.contains("watch this"));
        assertFalse(prompt.contains("read this"));
        assertFalse(prompt.contains(audioData));
        assertFalse(prompt.contains(videoData));
        assertFalse(prompt.contains(dataPayload));
        assertEquals(6, compacted.size());
        assertSame(audio, compacted.get(1));
        assertSame(video, compacted.get(2));
        assertSame(data, compacted.get(3));
        assertTrue(compacted.get(1).getContent().get(1) instanceof AudioBlock);
        assertTrue(compacted.get(2).getContent().get(1) instanceof VideoBlock);
        assertTrue(compacted.get(3).getContent().get(1) instanceof DataBlock);
        assertEquals(List.of("TAIL_1", "TAIL_2"), texts(compacted.subList(4, compacted.size())));
    }

    /**
     * Verifies that audio, video and data nested in a tool result pull the whole ASSISTANT/TOOL
     * group into the kept tail instead of being summarized away.
     */
    @Test
    void compactIfNeeded_keepsNestedToolAudioVideoDataGroupOutOfSummaryPrefix() {
        Fixture fixture = new Fixture(false);
        String audioData = "YXVkaW8tbmVzdGVkLXRvb2w=";
        String videoData = "dmlkZW8tbmVzdGVkLXRvb2w=";
        String dataPayload = "cGRmLW5lc3RlZC10b29s";
        Msg assistant = assistantToolCall("call:media", "read_media");
        Msg tool =
                toolMessage(
                        toolResult(
                                "call:media",
                                "read_media",
                                List.of(
                                        TextBlock.builder().text("captured").build(),
                                        audioBlock(audioData),
                                        videoBlock(videoData),
                                        dataBlock("doc-1", "report.pdf", dataPayload)),
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
        assertFalse(prompt.contains(audioData));
        assertFalse(prompt.contains(videoData));
        assertFalse(prompt.contains(dataPayload));
        assertEquals(5, compacted.size());
        assertSame(assistant, compacted.get(1));
        assertSame(tool, compacted.get(2));
        ToolResultBlock kept = compacted.get(2).getContentBlocks(ToolResultBlock.class).get(0);
        assertTrue(kept.getOutput().stream().anyMatch(block -> block instanceof AudioBlock));
        assertTrue(kept.getOutput().stream().anyMatch(block -> block instanceof VideoBlock));
        assertTrue(kept.getOutput().stream().anyMatch(block -> block instanceof DataBlock));
    }

    /**
     * Verifies that an empty keep window cannot summarize the current input away when it carries
     * raw audio, video and data blocks.
     */
    @Test
    void compactIfNeeded_protectsCurrentTurnRawMediaWhenKeepWindowIsEmpty() {
        Fixture fixture = new Fixture(false);
        String audioData = "YXVkaW8tY3VycmVudA=";
        Msg current =
                messageWithMedia(
                        "current media",
                        audioBlock(audioData),
                        videoBlock("dmlkZW8tY3VycmVudA=="),
                        dataBlock("doc-2", "current.pdf", "cGRmLWN1cnJlbnQ="));
        CompactionConfig zeroKeep =
                CompactionConfig.builder()
                        .triggerMessages(3)
                        .triggerTokens(0)
                        .keepMessages(0)
                        .keepTokens(0)
                        .summaryPrompt("SUMMARY_INPUT:\n{messages}")
                        .flushBeforeCompact(false)
                        .truncateArgs(null)
                        .prune(null)
                        .build();

        Optional<List<Msg>> result =
                fixture.compactor
                        .compactIfNeeded(
                                fixture.runtimeContext,
                                List.of(message("M1"), message("M2"), current),
                                zeroKeep,
                                "agent-id",
                                "session-id")
                        .block();

        List<Msg> compacted = result.orElseThrow();
        assertEquals(2, compacted.size());
        assertSame(current, compacted.get(1));
        assertTrue(compacted.get(1).getContent().get(1) instanceof AudioBlock);
        assertTrue(compacted.get(1).getContent().get(2) instanceof VideoBlock);
        assertTrue(compacted.get(1).getContent().get(3) instanceof DataBlock);
        String prompt = fixture.model.prompt(0);
        assertFalse(prompt.contains("current media"));
        assertFalse(prompt.contains(audioData));
    }

    /** Verifies audio, video and data survive two consecutive compaction rounds. */
    @Test
    void compactIfNeeded_retainsAudioVideoDataAcrossConsecutiveCompactions() {
        Fixture fixture = new Fixture();
        String audioData = "YXVkaW8tY2hhaW4=";
        Msg audio = messageWithAudio("older audio", audioData);
        Msg video = messageWithVideo("older video", "dmlkZW8tY2hhaW4=");
        Msg data = messageWithData("older data", "cGRmLWNoYWlu");

        List<Msg> first =
                fixture.compact(
                        List.of(
                                message("M1"),
                                audio,
                                video,
                                data,
                                message("TAIL_1"),
                                message("TAIL_2")));
        assertEquals(6, first.size());

        List<Msg> secondInput = new ArrayList<>(first);
        secondInput.add(message("M3"));
        secondInput.add(message("TAIL_3"));
        secondInput.add(message("TAIL_4"));

        List<Msg> second = fixture.compact(secondInput);

        String prompt = fixture.model.prompt(1);
        assertTrue(prompt.contains("ROUND_1"));
        assertFalse(prompt.contains("older audio"));
        assertFalse(prompt.contains("older video"));
        assertFalse(prompt.contains("older data"));
        assertFalse(prompt.contains(audioData));
        assertEquals(9, second.size());
        assertSame(audio, second.get(1));
        assertSame(video, second.get(2));
        assertSame(data, second.get(3));
        assertTrue(second.get(1).getContent().get(1) instanceof AudioBlock);
        assertTrue(second.get(2).getContent().get(1) instanceof VideoBlock);
        assertTrue(second.get(3).getContent().get(1) instanceof DataBlock);
        assertEquals(
                1,
                second.stream()
                        .filter(msg -> ConversationCompactor.SUMMARY_MSG_NAME.equals(msg.getName()))
                        .count());
        assertTrue(fixture.flushInputs.get(1).isEmpty());
    }

    /**
     * Verifies the summarization prompt formatter renders every non-text media kind as a
     * placeholder, so raw base64 payloads and signed URLs can never reach the summary model.
     */
    @Test
    void formatMessagesForSummaryRendersAllMediaKindsAsPlaceholders() {
        String audioData = "YXVkaW8tcHJvbXB0";
        String videoUrl = "https://example.com/prompt-video.mp4?sig=prompt-secret";
        String dataPayload = "cGRmLXByb21wdA==";
        Msg audio = messageWithAudio("audio note", audioData);
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
        Msg data = messageWithData("data note", dataPayload);

        String formatted =
                ConversationCompactor.formatMessagesForSummary(List.of(audio, video, data));

        assertTrue(formatted.contains("kind=audio"));
        assertTrue(formatted.contains("kind=video"));
        assertTrue(formatted.contains("kind=data"));
        assertTrue(formatted.contains("protocol=https"));
        assertFalse(formatted.contains(audioData));
        assertFalse(formatted.contains(videoUrl));
        assertFalse(formatted.contains("prompt-secret"));
        assertFalse(formatted.contains(dataPayload));
    }

    /**
     * Verifies that tool-result pruning preserves metadata, state and execution details, and skips
     * blocks that contain unregistered media instead of replacing them with a text-only preview.
     */
    @Test
    void pruneToolResults_preservesMetadataStateAndExecutionDetailsAndSkipsMediaBlocks() {
        ConversationCompactor compactor =
                new ConversationCompactor(new RecordingModel(), mock(MemoryFlushManager.class));
        PruneConfig pruneConfig =
                PruneConfig.builder()
                        .protectTokens(0)
                        .minimumTokens(0)
                        .maxOutputChars(10)
                        .excludedTools(Set.of())
                        .build();
        ToolExecutionDetails details =
                new ToolExecutionDetails("bash", ToolExecutionDetails.Outcome.FAILED, 1, false);
        Msg textOnly =
                toolMessage(
                        toolResult(
                                "call:1",
                                "tool_a",
                                List.of(TextBlock.builder().text("0123456789abcdefghij").build()),
                                Map.of("source", "test", "key", "value"),
                                ToolResultState.ERROR,
                                details));
        Msg withMedia =
                toolMessage(
                        toolResult(
                                "call:2",
                                "tool_b",
                                List.of(
                                        TextBlock.builder().text("0123456789abcdefghij").build(),
                                        ImageBlock.builder()
                                                .source(
                                                        Base64Source.builder()
                                                                .mediaType("image/png")
                                                                .data("iVBORw0KGgo=")
                                                                .build())
                                                .build()),
                                Map.of("source", "media-test"),
                                ToolResultState.SUCCESS,
                                null));
        List<Msg> input = List.of(textOnly, withMedia);

        List<Msg> pruned = compactor.pruneToolResults(input, pruneConfig);

        assertEquals(2, pruned.size());
        assertEquals("msg-call:1", pruned.get(0).getId());

        ToolResultBlock prunedBlock = pruned.get(0).getContentBlocks(ToolResultBlock.class).get(0);
        assertTrue(toolOutput(pruned.get(0)).contains("chars pruned"));
        assertEquals("test", prunedBlock.getMetadata().get("source"));
        assertEquals("value", prunedBlock.getMetadata().get("key"));
        assertEquals(ToolResultState.ERROR, prunedBlock.getState());
        assertSame(details, prunedBlock.getExecutionDetails());

        ToolResultBlock unchangedBlock =
                pruned.get(1).getContentBlocks(ToolResultBlock.class).get(0);
        assertEquals(2, unchangedBlock.getOutput().size());
        assertEquals("0123456789abcdefghij", toolOutput(pruned.get(1)));
        assertEquals(ToolResultState.SUCCESS, unchangedBlock.getState());
        assertTrue(
                unchangedBlock.getOutput().stream().anyMatch(block -> block instanceof ImageBlock));
    }

    /** Verifies tool-result pruning never drops audio, video or data blocks. */
    @Test
    void pruneToolResults_skipsBlocksWithAudioVideoDataMedia() {
        ConversationCompactor compactor =
                new ConversationCompactor(new RecordingModel(), mock(MemoryFlushManager.class));
        PruneConfig pruneConfig =
                PruneConfig.builder()
                        .protectTokens(0)
                        .minimumTokens(0)
                        .maxOutputChars(10)
                        .excludedTools(Set.of())
                        .build();
        Msg withMedia =
                toolMessage(
                        toolResult(
                                "call:avd",
                                "tool_avd",
                                List.of(
                                        TextBlock.builder().text("0123456789abcdefghij").build(),
                                        audioBlock("YXVkaW8="),
                                        videoBlock("dmlkZW8="),
                                        dataBlock("doc-3", "r.bin", "cGRm")),
                                Map.of(),
                                ToolResultState.SUCCESS,
                                null));

        List<Msg> pruned = compactor.pruneToolResults(List.of(withMedia), pruneConfig);

        assertSame(withMedia, pruned.get(0));
        assertEquals("0123456789abcdefghij", toolOutput(pruned.get(0)));
        ToolResultBlock unchanged = pruned.get(0).getContentBlocks(ToolResultBlock.class).get(0);
        assertEquals(4, unchanged.getOutput().size());
        assertTrue(unchanged.getOutput().stream().anyMatch(block -> block instanceof AudioBlock));
        assertTrue(unchanged.getOutput().stream().anyMatch(block -> block instanceof VideoBlock));
        assertTrue(unchanged.getOutput().stream().anyMatch(block -> block instanceof DataBlock));
    }

    /** Creates a regular user message. */
    private static Msg message(String text) {
        return Msg.builder()
                .role(MsgRole.USER)
                .content(TextBlock.builder().text(text).build())
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

    /** Creates a user message containing text and a base64 image. */
    private static Msg messageWithImage(String text, String imageData) {
        return Msg.builder()
                .id("msg-with-image")
                .role(MsgRole.USER)
                .content(
                        List.of(
                                TextBlock.builder().text(text).build(),
                                ImageBlock.builder()
                                        .source(
                                                Base64Source.builder()
                                                        .mediaType("image/png")
                                                        .data(imageData)
                                                        .build())
                                        .build()))
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

    /** Concatenates text output from a tool result message. */
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

    /** Creates an ASSISTANT message issuing a single tool call. */
    private static Msg assistantToolCall(String callId, String name) {
        return Msg.builderForRole(MsgRole.ASSISTANT)
                .content(ToolUseBlock.builder().id(callId).name(name).input(Map.of()).build())
                .build();
    }

    /** Creates a user message containing text and a base64 audio block. */
    private static Msg messageWithAudio(String text, String audioData) {
        return messageWithMedia(text, audioBlock(audioData));
    }

    /** Creates a user message containing text and a base64 video block. */
    private static Msg messageWithVideo(String text, String videoData) {
        return messageWithMedia(text, videoBlock(videoData));
    }

    /** Creates a user message containing text and a base64 data block. */
    private static Msg messageWithData(String text, String dataPayload) {
        return messageWithMedia(text, dataBlock("doc-data", "payload.bin", dataPayload));
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

    private static VideoBlock videoBlock(String data) {
        return VideoBlock.builder()
                .source(Base64Source.builder().mediaType("video/mp4").data(data).build())
                .build();
    }

    private static DataBlock dataBlock(String id, String name, String data) {
        return DataBlock.builder()
                .source(Base64Source.builder().mediaType("application/pdf").data(data).build())
                .id(id)
                .name(name)
                .build();
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
