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
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import io.agentscope.core.agent.RuntimeContext;
import io.agentscope.core.message.Msg;
import io.agentscope.core.message.MsgRole;
import io.agentscope.core.message.TextBlock;
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
import java.util.Optional;
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
