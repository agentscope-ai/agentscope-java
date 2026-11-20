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
package io.agentscope.harness.agent.memory;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.clearInvocations;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

import io.agentscope.core.agent.RuntimeContext;
import io.agentscope.core.agent.ThreadKey;
import io.agentscope.core.journal.InMemoryThreadJournalStore;
import io.agentscope.core.journal.InvocationRecorder;
import io.agentscope.core.journal.ThreadJournal;
import io.agentscope.core.message.Msg;
import io.agentscope.core.message.MsgRole;
import io.agentscope.core.message.TextBlock;
import io.agentscope.core.message.ThinkingBlock;
import io.agentscope.core.message.ToolUseBlock;
import io.agentscope.core.model.ChatResponse;
import io.agentscope.core.model.GenerateOptions;
import io.agentscope.core.model.Model;
import io.agentscope.core.model.ToolSchema;
import io.agentscope.harness.agent.HarnessAgent;
import io.agentscope.harness.agent.filesystem.AtomicTextFile;
import io.agentscope.harness.agent.filesystem.local.LocalFilesystem;
import io.agentscope.harness.agent.memory.compaction.CompactionConfig;
import io.agentscope.harness.agent.memory.compaction.ConversationCompactor;
import io.agentscope.harness.agent.middleware.MemoryFlushMiddleware;
import io.agentscope.harness.agent.workspace.WorkspaceManager;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;

class JournalMemoryFlushTest {
    @TempDir Path directory;
    final RuntimeContext rc = RuntimeContext.builder().userId("user").sessionId("session").build();
    final ThreadKey key = new ThreadKey("user", "agent", "session", "main");
    final InMemoryThreadJournalStore store = new InMemoryThreadJournalStore();
    final RecordingModel model = new RecordingModel();

    @ParameterizedTest
    @ValueSource(booleans = {true, false})
    void harnessConfigurationAppliesAdmissionToNativeAndLegacyExtraction(boolean nativeJournal) {
        var judgments = new java.util.concurrent.atomic.AtomicInteger();
        Model routing =
                new Model() {
                    public String getModelName() {
                        return "harness-admission";
                    }

                    public Flux<ChatResponse> stream(
                            List<Msg> messages, List<ToolSchema> tools, GenerateOptions options) {
                        return Flux.just(
                                ChatResponse.builder()
                                        .content(
                                                List.of(
                                                        TextBlock.builder()
                                                                .text(
                                                                        tools == null
                                                                                        || tools
                                                                                                .isEmpty()
                                                                                ? "- durable fact"
                                                                                : "done")
                                                                .build()))
                                        .build());
                    }
                };
        try (var agent =
                HarnessAgent.builder()
                        .name("agent")
                        .model(routing)
                        .workspace(directory)
                        .threadJournalStore(store)
                        .journalEnabled(nativeJournal)
                        .memory(
                                MemoryConfig.builder()
                                        .admission(
                                                input -> {
                                                    judgments.incrementAndGet();
                                                    return Mono.just(
                                                            new MemoryAdmission.Decision(
                                                                    false, "test", Map.of()));
                                                })
                                        .build())
                        .build()) {
            agent.call(List.of(message("Remember this project rule")), rc).block();
        }
        assertEquals(1, judgments.get());
        try (var workspace = new WorkspaceManager(directory)) {
            assertEquals(
                    "",
                    workspace.readMemoryFileUtf8(
                            rc, "memory/" + java.time.LocalDate.now() + ".md"));
        }
    }

    @Test
    void recreatedHarnessResumesJournalAndIncludesFinalReply() {
        Model routing =
                new Model() {
                    public String getModelName() {
                        return "harness-memory";
                    }

                    public Flux<ChatResponse> stream(
                            List<Msg> messages, List<ToolSchema> tools, GenerateOptions options) {
                        if (messages.stream()
                                .anyMatch(
                                        m ->
                                                m.getTextContent() != null
                                                        && m.getTextContent()
                                                                .contains(
                                                                        "Extract NEW memories from"
                                                                                + " these original"
                                                                                + " messages:")))
                            return model.stream(messages, tools, options);
                        return Flux.just(
                                ChatResponse.builder()
                                        .content(
                                                List.of(
                                                        TextBlock.builder()
                                                                .text("final reply")
                                                                .build()))
                                        .build());
                    }
                };
        for (String text : List.of("first request", "second request")) {
            try (HarnessAgent agent =
                    HarnessAgent.builder()
                            .name("agent")
                            .agentId("agent")
                            .model(routing)
                            .workspace(directory)
                            .threadJournalStore(store)
                            .build()) {
                agent.call(List.of(message(text)), rc).block();
            }
        }
        assertEquals(2, model.inputs.size());
        assertTrue(model.inputs.get(0).contains("first request"));
        assertTrue(model.inputs.get(0).contains("final reply"));
        assertTrue(model.inputs.get(1).contains("second request"));
        assertFalse(model.inputs.get(1).contains("first request"));
    }

    @Test
    void recreatedManagerOnlyExtractsNewMessagesAndThreadsHaveIndependentProgress() {
        ThreadJournal journal = store.open(key, rc);
        append(journal, "first original");
        flush(journal, key);
        append(journal, "second original");
        flush(journal, key);
        flush(journal, key);
        ThreadKey other = new ThreadKey("user", "agent", "session", "branch");
        ThreadJournal branch = store.open(other, rc);
        append(branch, "branch original");
        flush(branch, other);
        assertEquals(3, model.inputs.size());
        assertTrue(model.inputs.get(1).contains("second original"));
        assertFalse(model.inputs.get(1).contains("first original"));
        assertTrue(model.inputs.get(2).contains("branch original"));
    }

    @Test
    void modelFailureLeavesPositionForNextTrigger() {
        ThreadJournal journal = store.open(key, rc);
        append(journal, "retry original");
        model.fail.set(true);
        assertThrows(IllegalStateException.class, () -> flush(journal, key));
        model.fail.set(false);
        flush(journal, key);
        flush(journal, key);
        assertEquals(2, model.inputs.size());
        assertEquals(model.inputs.get(0), model.inputs.get(1));
    }

    @Test
    void proseWithoutBulletsIsJudgedAndConsumedForBothDecisions() {
        model.output = "The project uses Java 17.\nThe user prefers Chinese.";
        for (boolean accepted : List.of(true, false)) {
            ThreadKey thread = new ThreadKey("user", "agent", "session", "prose-" + accepted);
            ThreadJournal journal = store.open(thread, rc);
            append(journal, "project and preference source");
            List<MemoryAdmission.Input> inputs = new ArrayList<>();
            try (WorkspaceManager workspace =
                    new WorkspaceManager(directory.resolve("prose-" + accepted))) {
                var manager =
                        new MemoryFlushManager(
                                workspace,
                                model,
                                null,
                                input -> {
                                    inputs.add(input);
                                    return Mono.just(
                                            new MemoryAdmission.Decision(
                                                    accepted, "test", Map.of()));
                                });
                manager.flushJournal(rc, thread, journal).block();
                manager.flushJournal(rc, thread, journal).block();
                assertEquals(1, inputs.size());
                assertEquals("- " + model.output, inputs.get(0).candidate());
                String daily =
                        workspace.readMemoryFileUtf8(
                                rc, "memory/" + java.time.LocalDate.now() + ".md");
                assertEquals(accepted, daily.contains(model.output));
            }
        }
        assertEquals(2, model.inputs.size());
    }

    @Test
    void unsupportedBackendReportsItsCapabilityBeforeCallingTheModel() {
        ThreadJournal journal = store.open(key, rc);
        append(journal, "retained original");
        var filesystem =
                new LocalFilesystem(directory) {
                    @Override
                    public AtomicTextFile atomicTextFile(
                            RuntimeContext context, String path, boolean inherit) {
                        throw new UnsupportedOperationException("conditional writes unavailable");
                    }
                };
        try (WorkspaceManager workspace = new WorkspaceManager(directory, filesystem, null)) {
            var manager = new MemoryFlushManager(workspace, model);
            var failure =
                    assertThrows(
                            UnsupportedOperationException.class,
                            () -> manager.flushJournal(rc, key, journal).block());
            assertTrue(failure.getMessage().contains(filesystem.getClass().getName()));
            assertTrue(failure.getMessage().contains("atomicTextFile"));
            assertTrue(model.inputs.isEmpty());
        }
        flush(journal, key);
        assertTrue(model.inputs.get(0).contains("retained original"));
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void hangingExtractionOrAdmissionTimesOutAndRetriesItsSource(boolean duringAdmission) {
        ThreadJournal journal = store.open(key, rc);
        append(journal, "retry after timeout");
        model.output = "- durable fact";
        if (!duringAdmission) model.hangOnCall = 1;
        AtomicBoolean stalled = new AtomicBoolean(true);
        MemoryAdmission admission =
                duringAdmission
                        ? input ->
                                stalled.get()
                                        ? Mono.never()
                                        : Mono.just(
                                                new MemoryAdmission.Decision(
                                                        true, "test", Map.of()))
                        : null;
        try (WorkspaceManager workspace = new WorkspaceManager(directory)) {
            var manager =
                    new MemoryFlushManager(
                            workspace, model, null, admission, Duration.ofSeconds(1));
            StepVerifier.create(manager.flushJournal(rc, key, journal))
                    .expectErrorSatisfies(
                            error -> {
                                assertTrue(error instanceof TimeoutException);
                                assertTrue(
                                        error.getMessage().contains("timed out without progress"));
                                assertTrue(error.getMessage().contains("startAfter=0"));
                                assertTrue(error.getMessage().contains("offset=0"));
                                assertTrue(error.getMessage().contains("through="));
                                assertTrue(
                                        error.getMessage()
                                                .contains(
                                                        "increase flushTimeout or reduce admission"
                                                                + " budget"));
                            })
                    .verify(Duration.ofSeconds(10));
            stalled.set(false);
            manager.flushJournal(rc, key, journal).block(Duration.ofSeconds(10));
            manager.flushJournal(rc, key, journal).block(Duration.ofSeconds(10));
        }
        assertEquals(2, model.inputs.size());
        assertEquals(model.inputs.get(0), model.inputs.get(1));
    }

    @Test
    void timeoutKeepsEarlierBatchProgress() {
        ThreadJournal journal = store.open(key, rc);
        append(journal, "abc😀中".repeat(3000));
        model.hangOnCall = 2;
        try (WorkspaceManager workspace = new WorkspaceManager(directory)) {
            var manager =
                    new MemoryFlushManager(workspace, model, null, null, Duration.ofSeconds(1));
            StepVerifier.create(manager.flushJournal(rc, key, journal))
                    .expectErrorSatisfies(
                            error -> {
                                assertTrue(error instanceof TimeoutException);
                                assertTrue(error.getMessage().contains("after partial progress"));
                                assertTrue(error.getMessage().contains("after=0"));
                                assertFalse(error.getMessage().contains(", offset=0,"));
                            })
                    .verify(Duration.ofSeconds(10));
            assertEquals(2, model.inputs.size());
            new MemoryFlushManager(workspace, model).flushJournal(rc, key, journal).block();
            assertEquals(model.inputs.get(1), model.inputs.get(2));
            assertFalse(model.inputs.get(0).equals(model.inputs.get(2)));
        }
    }

    @Test
    void inlineAdmissionReadsCompletedDecisionsOncePerBatch() {
        ThreadJournal journal = spy(store.open(key, rc));
        append(journal, "source facts");
        model.output =
                java.util.stream.IntStream.range(0, 20)
                        .mapToObj(i -> "- fact " + i)
                        .collect(java.util.stream.Collectors.joining("\n"));
        InvocationRecorder invocation = recorder(journal);
        rc.put(InvocationRecorder.CONTEXT_KEY, invocation);
        rc.put(MemoryFlushMiddleware.INLINE_CONTEXT_KEY, true);
        clearInvocations(journal);
        try (WorkspaceManager workspace = new WorkspaceManager(directory)) {
            new MemoryFlushManager(
                            workspace,
                            model,
                            null,
                            input ->
                                    Mono.just(new MemoryAdmission.Decision(true, "test", Map.of())))
                    .flushJournal(rc, key, journal)
                    .block();
            verify(journal, times(4)).scanMetadata(anyLong(), anyLong());
        } finally {
            invocation.close();
        }
    }

    @Test
    void admissionFiltersPreambleAndRetainsUserIdentityAcrossSessions() {
        ThreadJournal journal = store.open(key, rc);
        append(journal, "user source");
        model.output = "Here are the memories:\n- persistent\n- ephemeral\nThat is all.";
        List<MemoryAdmission.Input> inputs = new ArrayList<>();
        MemoryAdmission admission =
                input -> {
                    inputs.add(input);
                    return Mono.just(
                            new MemoryAdmission.Decision(
                                    input.candidate().equals("- persistent"), "test", Map.of()));
                };
        try (WorkspaceManager workspace = new WorkspaceManager(directory)) {
            for (String session : List.of("first-session", "second-session")) {
                RuntimeContext context =
                        RuntimeContext.builder().userId("user").sessionId(session).build();
                new MemoryFlushManager(workspace, model, null, admission)
                        .flushJournal(
                                context, new ThreadKey("user", "agent", session, "main"), journal)
                        .block();
            }
            String daily =
                    workspace.readMemoryFileUtf8(rc, "memory/" + java.time.LocalDate.now() + ".md");
            assertTrue(daily.contains("- persistent"));
            assertFalse(daily.contains("- ephemeral"));
        }
        assertEquals(4, inputs.size());
        assertTrue(
                inputs.stream()
                        .allMatch(
                                input ->
                                        input.owner().equals("user")
                                                && input.source().contains("user source")));
        assertTrue(inputs.get(1).existing().contains("- persistent"));
    }

    @Test
    void admissionFailureLeavesWholeBatchRetryableAndRejectionIsConsumed() {
        ThreadJournal journal = store.open(key, rc);
        append(journal, "original fact");
        model.output = "- one\n- two";
        AtomicBoolean unavailable = new AtomicBoolean(true);
        MemoryAdmission admission =
                input -> {
                    if (unavailable.get() && input.candidate().equals("- two"))
                        return Mono.error(new IllegalStateException("judge unavailable"));
                    return Mono.just(
                            new MemoryAdmission.Decision(!unavailable.get(), "test", Map.of()));
                };
        try (WorkspaceManager workspace = new WorkspaceManager(directory)) {
            var manager = new MemoryFlushManager(workspace, model, null, admission);
            assertThrows(
                    IllegalStateException.class,
                    () -> manager.flushJournal(rc, key, journal).block());
            assertEquals(
                    "",
                    workspace.readMemoryFileUtf8(
                            rc, "memory/" + java.time.LocalDate.now() + ".md"));
            unavailable.set(false);
            new MemoryFlushManager(workspace, model, null, admission)
                    .flushJournal(rc, key, journal)
                    .block();
            assertEquals(2, model.inputs.size());
            append(journal, "rejected fact");
            var rejecting =
                    new MemoryFlushManager(
                            workspace,
                            model,
                            null,
                            input ->
                                    Mono.just(
                                            new MemoryAdmission.Decision(false, "test", Map.of())));
            rejecting.flushJournal(rc, key, journal).block();
            rejecting.flushJournal(rc, key, journal).block();
            assertEquals(3, model.inputs.size());
        }
    }

    @Test
    void resumedExtractionValidatesTheConsumedEventWithoutReadingTheJournalStart() {
        ThreadJournal journal = store.open(key, rc);
        for (int i = 0; i < 10; i++) append(journal, "original " + i);
        flush(journal, key);
        long consumed = journal.head().seq();
        append(journal, "new original");
        ThreadJournal observed = spy(journal);
        flush(observed, key);
        verify(observed).readMetadataAfter(consumed - 1, 1);
        verify(observed, never()).readMetadataAfter(0, 1);
        assertTrue(model.inputs.get(1).contains("new original"));
        assertFalse(model.inputs.get(1).contains("original 0"));
    }

    @Test
    void failureAfterASplitMessageResumesWithoutReextractingTheSuccessfulPrefix() {
        ThreadJournal journal = store.open(key, rc);
        String original = "abc😀中".repeat(3000);
        append(journal, original);
        model.failOnCall = 2;
        assertThrows(IllegalStateException.class, () -> flush(journal, key));
        String failedInput = model.inputs.get(1);
        model.failOnCall = 0;
        flush(journal, key);
        assertEquals(failedInput, model.inputs.get(2));
        model.inputs.remove(1);
        String reconstructed =
                model.inputs.stream()
                        .map(
                                text ->
                                        text.substring(
                                                text.indexOf("original messages:\n")
                                                        + "original messages:\n".length()))
                        .reduce("", String::concat)
                        .replace("\n", "");
        assertEquals("[USER]: " + original, reconstructed);
    }

    @Test
    void outputLimitRemainsRetryableButEmptySuccessIsConsumed() {
        ThreadJournal journal = store.open(key, rc);
        append(journal, "original");
        model.finishReason = "length";
        assertThrows(IllegalStateException.class, () -> flush(journal, key));
        model.finishReason = "stop";
        model.output = "";
        flush(journal, key);
        flush(journal, key);
        assertEquals(2, model.inputs.size());
    }

    @Test
    void reasoningBlocksAreIgnoredWhileFinalTextIsDelivered() throws Exception {
        rc.put(Clock.class, Clock.fixed(Instant.parse("2026-11-13T00:00:00Z"), ZoneOffset.UTC));
        ThreadJournal journal = store.open(key, rc);
        append(journal, "one fact");
        model.thinking = true;
        model.output = "- final fact";
        flush(journal, key);
        flush(journal, key);
        assertEquals(1, model.inputs.size());
        String ledger =
                java.nio.file.Files.readString(
                        directory.resolve("memory/2026-11-13.md"),
                        java.nio.charset.StandardCharsets.UTF_8);
        assertTrue(ledger.contains("- final fact"));
        assertFalse(ledger.contains("private reasoning"));
    }

    @Test
    void longUnicodeMessageIsSplitWithoutLosingOrBreakingCharacters() {
        ThreadJournal journal = store.open(key, rc);
        String original = "abc😀中".repeat(3000);
        append(journal, original);
        flush(journal, key);
        assertTrue(model.inputs.size() > 1);
        String reconstructed =
                model.inputs.stream()
                        .map(
                                text ->
                                        text.substring(
                                                text.indexOf("original messages:\n")
                                                        + "original messages:\n".length()))
                        .reduce("", String::concat)
                        .replace("\n", "");
        assertEquals("[USER]: " + original, reconstructed);
    }

    @Test
    void telemetryDoesNotConsumeTheSourceMessageLimit() {
        ThreadJournal journal = store.open(key, rc);
        InvocationRecorder recorder = recorder(journal);
        try {
            for (int i = 0; i < 300; i++)
                recorder.append("test/metadata", java.util.Map.of(), false);
            recorder.captureMessages(List.of(message("after first page")));
            recorder.flush().block();
        } finally {
            recorder.close();
        }
        flush(journal, key);
        assertEquals(1, model.inputs.size());
        assertTrue(model.inputs.get(0).contains("after first page"));
        flush(journal, key);
        assertEquals(1, model.inputs.size());
    }

    @Test
    void sourceMessageLimitRetainsRemainderForNextTrigger() {
        ThreadJournal journal = store.open(key, rc);
        InvocationRecorder recorder = recorder(journal);
        try {
            List<Msg> messages = new ArrayList<>();
            for (int i = 0; i < 300; i++) messages.add(message("source-" + i));
            recorder.captureMessages(messages);
            recorder.flush().block();
        } finally {
            recorder.close();
        }
        flush(journal, key);
        assertEquals(1, model.inputs.size());
        assertTrue(model.inputs.get(0).contains("source-255"));
        assertFalse(model.inputs.get(0).contains("source-256"));
        flush(journal, key);
        assertEquals(2, model.inputs.size());
        assertTrue(model.inputs.get(1).contains("source-299"));
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void deliveredSplitBatchResumesItsRecordedEndWhenPositionWriteFails(boolean retryNoReply) {
        rc.put(Clock.class, Clock.fixed(Instant.parse("2026-11-13T00:00:00Z"), ZoneOffset.UTC));
        ThreadJournal journal = store.open(key, rc);
        append(journal, "abc😀中".repeat(3000));
        model.output =
                java.util.stream.IntStream.range(0, 40)
                        .mapToObj(i -> "- fact-" + i + " " + "y".repeat(20))
                        .collect(java.util.stream.Collectors.joining("\n"));
        String controlLedger;
        try (WorkspaceManager control = new WorkspaceManager(directory.resolve("control"))) {
            new MemoryFlushManager(control, model).flushJournal(rc, key, journal).block();
            controlLedger = control.readMemoryFileUtf8(rc, "memory/2026-11-13.md");
        }
        List<String> controlInputs = List.copyOf(model.inputs);
        assertTrue(controlInputs.size() > 1);
        model.inputs.clear();
        AtomicBoolean fail = new AtomicBoolean(true);
        try (WorkspaceManager workspace =
                new WorkspaceManager(directory) {
                    @Override
                    public AtomicTextFile atomicMemoryFile(RuntimeContext context, String path) {
                        AtomicTextFile delegate = super.atomicMemoryFile(context, path);
                        if (!path.contains(".flush-state")) return delegate;
                        return new AtomicTextFile() {
                            public String read() {
                                return delegate.read();
                            }

                            public boolean compareAndSet(String expected, String updated) {
                                if (fail.getAndSet(false))
                                    throw new IllegalStateException("write unavailable");
                                return delegate.compareAndSet(expected, updated);
                            }
                        };
                    }
                }) {
            MemoryFlushManager manager = new MemoryFlushManager(workspace, model);
            assertThrows(
                    IllegalStateException.class,
                    () -> manager.flushJournal(rc, key, journal).block());
            if (retryNoReply) model.output = "NO_REPLY";
            manager.flushJournal(rc, key, journal).block();
            String ledger = workspace.readMemoryFileUtf8(rc, "memory/2026-11-13.md");
            assertEquals(controlInputs.get(1), model.inputs.get(1));
            if (retryNoReply) assertEquals(1, ledger.split("## Memory Flush", -1).length - 1);
            else {
                assertEquals(controlInputs, model.inputs);
                assertEquals(controlLedger, ledger);
            }
            int calls = model.inputs.size();
            manager.flushJournal(rc, key, journal).block();
            assertEquals(calls, model.inputs.size());
        }
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void firstSplitMessageSurvivesAnInitiallyEmptyAnchorButRejectsReplacement(boolean replace) {
        ThreadJournal journal = spy(store.open(key, rc));
        append(journal, "abc😀中".repeat(3000));
        if (!replace) doReturn(List.of()).doCallRealMethod().when(journal).readMetadataAfter(0, 1);
        model.failOnCall = 2;
        assertThrows(IllegalStateException.class, () -> flush(journal, key));
        assertEquals(2, model.inputs.size());
        model.failOnCall = 0;
        if (replace) {
            ThreadJournal replacement = new InMemoryThreadJournalStore().open(key, rc);
            append(replacement, "abc😀中".repeat(3000));
            assertThrows(IllegalStateException.class, () -> flush(replacement, key));
            assertEquals(2, model.inputs.size());
        } else {
            flush(journal, key);
            int calls = model.inputs.size();
            assertTrue(calls > 2);
            flush(journal, key);
            assertEquals(calls, model.inputs.size());
        }
    }

    @Test
    void concurrentManagersKeepTheWinningPositionAndAppendTheBatchOnce() throws Exception {
        rc.put(Clock.class, Clock.fixed(Instant.parse("2026-11-13T00:00:00Z"), ZoneOffset.UTC));
        ThreadJournal journal = store.open(key, rc);
        append(journal, "one fact");
        CountDownLatch extracting = new CountDownLatch(2);
        Model concurrent =
                new Model() {
                    public String getModelName() {
                        return "concurrent-memory";
                    }

                    public Flux<ChatResponse> stream(
                            List<Msg> messages, List<ToolSchema> tools, GenerateOptions options) {
                        extracting.countDown();
                        try {
                            if (!extracting.await(5, TimeUnit.SECONDS))
                                return Flux.error(
                                        new IllegalStateException(
                                                "Concurrent extraction did not start"));
                        } catch (InterruptedException interrupted) {
                            Thread.currentThread().interrupt();
                            return Flux.error(interrupted);
                        }
                        return Flux.just(
                                ChatResponse.builder()
                                        .content(
                                                List.of(
                                                        TextBlock.builder()
                                                                .text("- one fact")
                                                                .build()))
                                        .build());
                    }
                };
        try (WorkspaceManager workspace = new WorkspaceManager(directory)) {
            var first =
                    new MemoryFlushManager(workspace, concurrent).flushJournal(rc, key, journal);
            var second =
                    new MemoryFlushManager(workspace, concurrent).flushJournal(rc, key, journal);
            var outcomes =
                    Flux.merge(first.materialize(), second.materialize()).collectList().block();
            assertEquals(1, outcomes.stream().filter(signal -> signal.isOnComplete()).count());
            Throwable failure =
                    outcomes.stream()
                            .filter(signal -> signal.isOnError())
                            .findFirst()
                            .orElseThrow()
                            .getThrowable();
            assertEquals(
                    "Concurrent memory extraction; retry on the next trigger",
                    failure.getMessage());
            String ledger = workspace.readMemoryFileUtf8(rc, "memory/2026-11-13.md");
            assertEquals(1, ledger.split("- one fact", -1).length - 1);
            new MemoryFlushManager(workspace, model).flushJournal(rc, key, journal).block();
            assertTrue(
                    model.inputs.isEmpty(),
                    "The winning position must survive the conflicting writer");
        }
    }

    @Test
    void sourceReplacementIsRejected() {
        ThreadJournal journal = store.open(key, rc);
        append(journal, "original");
        flush(journal, key);
        ThreadJournal replacement = new InMemoryThreadJournalStore().open(key, rc);
        append(replacement, "replacement");
        assertThrows(IllegalStateException.class, () -> flush(replacement, key));
    }

    @Test
    void compactionRetainsOriginalsWithoutASecondExtraction() {
        ThreadJournal journal = store.open(key, rc);
        InvocationRecorder recorder = recorder(journal);
        rc.put(InvocationRecorder.CONTEXT_KEY, recorder);
        List<Msg> originals =
                List.of(
                        message("original prefix " + "x".repeat(2000)),
                        message("original middle " + "y".repeat(2000)),
                        message("original tail"));
        try (WorkspaceManager workspace = new WorkspaceManager(directory)) {
            MemoryFlushManager manager = new MemoryFlushManager(workspace, model);
            var compacted =
                    new ConversationCompactor(model, manager)
                            .compactIfNeeded(
                                    rc,
                                    originals,
                                    CompactionConfig.builder()
                                            .triggerMessages(2)
                                            .keepTokens(0)
                                            .keepMessages(1)
                                            .build(),
                                    "agent",
                                    "session")
                            .block()
                            .orElseThrow();
            assertEquals(1, model.inputs.size(), "Compaction only calls its summary model");
            model.inputs.clear();
            manager.flushJournal(rc, key, journal).block();
            manager.flushJournal(rc, key, journal).block();
            assertEquals(1, model.inputs.size());
            assertTrue(model.inputs.get(0).contains("original prefix"));
            assertTrue(model.inputs.get(0).contains("original middle"));
            assertTrue(model.inputs.get(0).contains("original tail"));
            assertTrue(compacted.size() < originals.size());
        } finally {
            recorder.close();
        }
    }

    @Test
    void lightweightTruncationRetainsOriginalArgumentsForExtraction() {
        ThreadJournal journal = store.open(key, rc);
        InvocationRecorder recorder = recorder(journal);
        rc.put(InvocationRecorder.CONTEXT_KEY, recorder);
        Msg toolCall =
                Msg.builder()
                        .role(MsgRole.ASSISTANT)
                        .content(
                                ToolUseBlock.builder()
                                        .id("call")
                                        .name("write_file")
                                        .input(
                                                Map.of(
                                                        "content",
                                                        "x".repeat(80) + "retained original fact"))
                                        .build())
                        .build();
        try (WorkspaceManager workspace = new WorkspaceManager(directory)) {
            MemoryFlushManager manager = new MemoryFlushManager(workspace, model);
            List<Msg> reduced =
                    new ConversationCompactor(model, manager)
                            .compactIfNeeded(
                                    rc,
                                    List.of(toolCall, message("tail")),
                                    CompactionConfig.builder()
                                            .triggerMessages(1000)
                                            .triggerTokens(0)
                                            .truncateArgs(
                                                    CompactionConfig.TruncateArgsConfig.builder()
                                                            .triggerMessages(1)
                                                            .keepTokens(0)
                                                            .keepMessages(1)
                                                            .maxArgLength(20)
                                                            .build())
                                            .build(),
                                    "agent",
                                    "session")
                            .block()
                            .orElseThrow();
            assertFalse(
                    reduced.get(0)
                            .getContentBlocks(ToolUseBlock.class)
                            .get(0)
                            .getInput()
                            .get("content")
                            .toString()
                            .contains("retained original fact"));
            assertTrue(model.inputs.isEmpty(), "Lightweight truncation needs no model call");
            recorder.flush().block();
            manager.flushJournal(rc, key, journal).block();
            assertTrue(model.inputs.get(0).contains("retained original fact"));
        } finally {
            recorder.close();
        }
    }

    private void flush(ThreadJournal journal, ThreadKey thread) {
        try (WorkspaceManager workspace = new WorkspaceManager(directory)) {
            new MemoryFlushManager(workspace, model).flushJournal(rc, thread, journal).block();
        }
    }

    private static void append(ThreadJournal journal, String text) {
        InvocationRecorder recorder = recorder(journal);
        try {
            recorder.captureMessages(List.of(message(text)));
            recorder.flush().block();
        } finally {
            recorder.close();
        }
    }

    private static InvocationRecorder recorder(ThreadJournal journal) {
        return new InvocationRecorder(journal, "turn", UUID.randomUUID().toString(), null);
    }

    private static Msg message(String text) {
        return Msg.builder().role(MsgRole.USER).textContent(text).build();
    }

    private static final class RecordingModel implements Model {
        final List<String> inputs = new ArrayList<>();
        final AtomicBoolean fail = new AtomicBoolean();
        String output = "NO_REPLY";
        String finishReason;
        boolean thinking;
        int failOnCall;
        int hangOnCall;

        public int getContextWindowSize() {
            return 4096;
        }

        public String getModelName() {
            return "memory-test";
        }

        public Flux<ChatResponse> stream(
                List<Msg> messages, List<ToolSchema> tools, GenerateOptions options) {
            inputs.add(messages.get(messages.size() - 1).getTextContent());
            if (inputs.size() == hangOnCall) return Flux.never();
            if (fail.get() || inputs.size() == failOnCall)
                return Flux.error(new IllegalStateException("model unavailable"));
            return Flux.just(
                    ChatResponse.builder()
                            .finishReason(finishReason)
                            .content(
                                    thinking
                                            ? List.of(
                                                    ThinkingBlock.builder()
                                                            .thinking("private reasoning")
                                                            .build(),
                                                    TextBlock.builder().text(output).build())
                                            : List.of(TextBlock.builder().text(output).build()))
                            .build());
        }
    }
}
