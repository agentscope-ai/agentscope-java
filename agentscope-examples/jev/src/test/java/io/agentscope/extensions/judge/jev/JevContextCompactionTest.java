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

package io.agentscope.extensions.judge.jev;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import io.agentscope.core.agent.Agent;
import io.agentscope.core.agent.RuntimeContext;
import io.agentscope.core.message.Msg;
import io.agentscope.core.message.MsgRole;
import io.agentscope.core.message.TextBlock;
import io.agentscope.core.message.ToolResultBlock;
import io.agentscope.core.message.ToolResultState;
import io.agentscope.core.message.ToolUseBlock;
import io.agentscope.core.middleware.ModelCallInput;
import io.agentscope.core.middleware.ModelRequestPreparer.Purpose;
import io.agentscope.core.model.Model;
import io.agentscope.core.state.AgentState;
import io.agentscope.core.util.JsonUtils;
import io.agentscope.extensions.judge.jev.context.FileJevContextArchive;
import io.agentscope.extensions.judge.jev.context.JevContextArchive;
import io.agentscope.extensions.judge.jev.context.JevContextCompactor;
import io.agentscope.harness.agent.context.ContextBudgetExceededException;
import io.agentscope.harness.agent.context.ContextPolicy;
import io.agentscope.harness.agent.context.ContextTokenEstimator;
import io.agentscope.harness.agent.context.HarnessContextBuilder;
import io.agentscope.harness.agent.memory.compaction.CompactionConfig;
import io.agentscope.harness.agent.memory.compaction.ConversationCompactionStrategy;
import io.agentscope.harness.agent.middleware.CompactionMiddleware;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Function;
import java.util.function.ToDoubleFunction;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;

class JevContextCompactionTest {
    @TempDir Path temp;

    static Msg text(String id, MsgRole role, String value) {
        return Msg.builder().id(id).role(role).textContent(value).build();
    }

    static Msg call(String id, String name) {
        return Msg.builder()
                .id("c-" + id)
                .role(MsgRole.ASSISTANT)
                .content(
                        ToolUseBlock.builder()
                                .id(id)
                                .name(name)
                                .input(Map.of("path", id + ".txt"))
                                .build())
                .build();
    }

    static Msg result(String id, String name) {
        return Msg.builder()
                .id("r-" + id)
                .role(MsgRole.TOOL)
                .content(
                        ToolResultBlock.builder()
                                .id(id)
                                .name(name)
                                .state(ToolResultState.SUCCESS)
                                .output(
                                        TextBlock.builder()
                                                .text(("evidence-" + id + " ").repeat(500))
                                                .build())
                                .build())
                .build();
    }

    static List<Msg> history() {
        return List.of(
                text("first", MsgRole.USER, "Never edit generated files. Investigate failure."),
                call("old", "read"),
                result("old", "read"),
                call("preview", "read"),
                result("preview", "read"),
                call("needed", "read"),
                result("needed", "read"),
                text("last", MsgRole.USER, "Continue fixing the test, preserving constraints."));
    }

    static JevContextCompactor.Config config(int recent, int questions, int state) {
        return new JevContextCompactor.Config(
                .2,
                .8,
                recent,
                state,
                state + 5000,
                questions,
                30,
                .01,
                1_000_000,
                Set.of("read"),
                Set.of());
    }

    static RuntimeContext ctx(String session) {
        return RuntimeContext.builder().userId("owner").sessionId(session).build();
    }

    static ConversationCompactionStrategy.Request request(List<Msg> messages, String session) {
        return new ConversationCompactionStrategy.Request(
                ctx(session), messages, "agent", session, 10000);
    }

    static Mono<SystemOneResult> answer(SystemOneRequest request, ToDoubleFunction<String> p) {
        Map<String, Answer> answers = new HashMap<>();
        request.questions()
                .keySet()
                .forEach(k -> answers.put(k, new NoulAnswer(p.applyAsDouble(k))));
        return Mono.just(new SystemOneResult("fake", answers, new Usage(10, 2)));
    }

    JevContextCompactor compactor(
            JevExecution.Mode mode,
            Function<SystemOneRequest, Mono<SystemOneResult>> caller,
            JevContextCompactor.Config config,
            JevContextArchive archive) {
        return new JevContextCompactor(
                caller,
                config,
                new JevExecution.Options(mode, Duration.ofSeconds(2), "test", (c, r) -> {}),
                archive);
    }

    FileJevContextArchive archive() {
        return new FileJevContextArchive(temp, 2_000_000);
    }

    static String json(Object value) {
        return JsonUtils.getJsonCodec().toJson(value);
    }

    @Test
    void dualDecisionsArchiveAndRestoreVerbatimWithoutExecutingTools() {
        var archive = archive();
        var original = history();
        String before = json(original);
        var c =
                compactor(
                        JevExecution.Mode.ENFORCE,
                        r ->
                                answer(
                                        r,
                                        k ->
                                                k.endsWith("t1")
                                                        ? .01
                                                        : k.equals("result_t2") ? .01 : .99),
                        config(1, 128, 25000),
                        archive);
        var d = c.plan(request(original, "s1")).block();
        assertEquals(JevExecution.Status.DECIDED, d.status());
        assertEquals(JevContextCompactor.Action.DROP_PAIR, d.value().decisions().get("t1"));
        assertEquals(JevContextCompactor.Action.TRUNCATE_RESULT, d.value().decisions().get("t2"));
        assertEquals(JevContextCompactor.Action.KEEP, d.value().decisions().get("t3"));
        assertEquals(
                List.of("first", "c-preview", "r-preview", "c-needed", "r-needed", "last"),
                d.value().messages().stream().map(Msg::getId).toList());
        assertTrue(json(d.value().messages()).contains("do not re-execute"));
        assertEquals(
                tree(before),
                tree(
                        json(
                                archive.restore(
                                                new JevContextArchive.Scope("owner", "agent", "s1"),
                                                d.value().archiveReference())
                                        .block())));
        assertEquals(before, json(original));
    }

    @Test
    void recentPairsAndIneligibleToolsStayPinned() {
        var history = new ArrayList<>(history());
        history.set(1, call("old", "write"));
        history.set(2, result("old", "write"));
        List<SystemOneRequest> seen = new ArrayList<>();
        var c =
                compactor(
                        JevExecution.Mode.ENFORCE,
                        r -> {
                            seen.add(r);
                            return answer(r, k -> 0);
                        },
                        config(2, 128, 25000),
                        archive());
        var d = c.plan(request(history, "pinned")).block();
        assertEquals(Set.of("call_t2", "result_t2"), seen.get(0).questions().keySet());
        assertEquals(JevContextCompactor.Action.KEEP, d.value().decisions().get("t1"));
        assertEquals(JevContextCompactor.Action.KEEP, d.value().decisions().get("t3"));
        assertEquals(history.get(0).getTextContent(), d.value().messages().get(0).getTextContent());
    }

    @Test
    void textInsideMixedMessagesAndPendingCallsSurvive() {
        var h = new ArrayList<>(history());
        h.set(
                1,
                Msg.builder()
                        .id("c-old")
                        .role(MsgRole.ASSISTANT)
                        .content(
                                TextBlock.builder().text("Important existing constraint").build(),
                                h.get(1).getContent().get(0))
                        .build());
        h.add(call("pending", "read"));
        var c =
                compactor(
                        JevExecution.Mode.ENFORCE,
                        r -> answer(r, k -> 0),
                        config(1, 128, 25000),
                        archive());
        var d = c.plan(request(h, "mixed")).block();
        assertTrue(json(d.value().messages()).contains("Important existing constraint"));
        assertTrue(json(d.value().messages()).contains("pending"));
    }

    @Test
    void uncertainAnswersKeepEverythingAndDoNotArchive() {
        var archive = mock(JevContextArchive.class);
        var c =
                compactor(
                        JevExecution.Mode.ENFORCE,
                        r -> answer(r, k -> .5),
                        config(1, 128, 25000),
                        archive);
        var h = history();
        assertEquals(h, c.compact(request(h, "uncertain")).block().orElseThrow());
        verifyNoInteractions(archive);
    }

    @Test
    void offAndShadowNeverReplaceInputsOrWriteArchive() {
        var archive = mock(JevContextArchive.class);
        AtomicInteger calls = new AtomicInteger();
        for (var mode : List.of(JevExecution.Mode.OFF, JevExecution.Mode.SHADOW)) {
            var c =
                    compactor(
                            mode,
                            r -> {
                                calls.incrementAndGet();
                                return answer(r, k -> 0);
                            },
                            config(1, 128, 25000),
                            archive);
            assertTrue(c.compact(request(history(), "mode")).block().isEmpty());
        }
        assertEquals(1, calls.get());
        verifyNoInteractions(archive);
    }

    @Test
    void batchesReuseCompleteStateAndNeverPartiallyApplyOnFailure() {
        List<SystemOneRequest> seen = new ArrayList<>();
        var archive = mock(JevContextArchive.class);
        var c =
                compactor(
                        JevExecution.Mode.ENFORCE,
                        r -> {
                            seen.add(r);
                            return seen.size() == 2
                                    ? Mono.error(new IllegalStateException())
                                    : answer(r, k -> 0);
                        },
                        config(1, 2, 25000),
                        archive);
        var h = history();
        assertEquals(h, c.compact(request(h, "failure")).block().orElseThrow());
        assertEquals(2, seen.size());
        assertEquals(seen.get(0).state(), seen.get(1).state());
        verifyNoInteractions(archive);
    }

    @Test
    void malformedRepliesAndArchiveFailuresKeepOriginal() {
        var broken = mock(JevContextArchive.class);
        when(broken.save(any(), any()))
                .thenReturn(Mono.error(new IllegalStateException("disk full")));
        for (Function<SystemOneRequest, Mono<SystemOneResult>> caller :
                List.<Function<SystemOneRequest, Mono<SystemOneResult>>>of(
                        r -> Mono.just(new SystemOneResult("fake", Map.of(), new Usage(1, 1))),
                        r -> answer(r, k -> 0))) {
            var c = compactor(JevExecution.Mode.ENFORCE, caller, config(1, 128, 25000), broken);
            var h = history();
            assertEquals(h, c.compact(request(h, "malformed")).block().orElseThrow());
        }
    }

    @Test
    void stateLimitDoesNotRemoveConstraintsToFit() {
        AtomicInteger calls = new AtomicInteger();
        var c =
                compactor(
                        JevExecution.Mode.ENFORCE,
                        r -> {
                            calls.incrementAndGet();
                            return answer(r, k -> 0);
                        },
                        config(1, 128, 1),
                        archive());
        var h = history();
        assertEquals(h, c.compact(request(h, "limit")).block().orElseThrow());
        assertEquals(0, calls.get());
    }

    @Test
    void timeoutAndCancellationStopPendingWork() {
        AtomicInteger cancelled = new AtomicInteger();
        List<JevExecution.Record> records = new ArrayList<>();
        var c =
                new JevContextCompactor(
                        r -> Mono.<SystemOneResult>never().doOnCancel(cancelled::incrementAndGet),
                        config(1, 128, 25000),
                        new JevExecution.Options(
                                JevExecution.Mode.ENFORCE,
                                Duration.ofSeconds(1),
                                "test",
                                (ctx, record) -> records.add(record)),
                        archive());
        var h = history();
        StepVerifier.withVirtualTime(() -> c.compact(request(h, "timeout")))
                .thenAwait(Duration.ofSeconds(2))
                .assertNext(result -> assertEquals(h, result.orElseThrow()))
                .verifyComplete();
        StepVerifier.create(c.compact(request(h, "cancel")))
                .thenAwait(Duration.ofMillis(1))
                .thenCancel()
                .verify();
        assertEquals(2, cancelled.get());
        assertTrue(records.stream().anyMatch(r -> r.reason().equals("TIMEOUT")));
        assertTrue(records.stream().anyMatch(r -> r.status() == JevExecution.Status.CANCELLED));
    }

    @Test
    void archivesRejectCrossSessionAndCorruption() throws Exception {
        var a = archive();
        var scope = new JevContextArchive.Scope("owner", "agent", "first");
        String ref = a.save(scope, history()).block();
        assertThrows(
                RuntimeException.class,
                () ->
                        a.restore(new JevContextArchive.Scope("owner", "agent", "second"), ref)
                                .block());
        assertThrows(
                RuntimeException.class,
                () ->
                        a.restore(new JevContextArchive.Scope("other", "agent", "first"), ref)
                                .block());
        try (var paths = Files.walk(temp)) {
            Path file =
                    paths.filter(p -> p.getFileName().toString().equals(ref + ".json"))
                            .findFirst()
                            .orElseThrow();
            Files.writeString(file, "corrupt");
        }
        assertThrows(RuntimeException.class, () -> a.restore(scope, ref).block());
        assertThrows(RuntimeException.class, () -> a.restore(scope, "../escape").block());
    }

    @Test
    void finalHarnessBoundaryCommitsOnlyAfterBudgetValidation() {
        var h = history();
        var state = AgentState.builder().context(h).build();
        var rc =
                RuntimeContext.builder()
                        .userId("owner")
                        .sessionId("boundary")
                        .agentState(state)
                        .build();
        var agent = mock(Agent.class);
        when(agent.getName()).thenReturn("agent");
        var model = mock(Model.class);
        when(model.getContextWindowSize()).thenReturn(100000);
        when(model.getModelName()).thenReturn("offline");
        var c =
                compactor(
                        JevExecution.Mode.ENFORCE,
                        r -> answer(r, k -> 0),
                        config(1, 128, 25000),
                        archive());
        var config =
                CompactionConfig.builder()
                        .triggerMessages(2)
                        .keepTokens(0)
                        .prune(null)
                        .flushBeforeCompact(false)
                        .strategy(c)
                        .build();
        var middleware = new CompactionMiddleware(null, model, config);
        var builder = new HarnessContextBuilder(ContextPolicy.defaults(), middleware, null);
        var prepared =
                builder.prepare(
                                agent,
                                rc,
                                new ModelCallInput(h, List.of(), null, model),
                                "success",
                                Purpose.REASONING)
                        .block();
        assertEquals(2, state.contextMutable().size());
        assertTrue(
                prepared.messages().stream()
                        .noneMatch(m -> !m.getContentBlocks(ToolUseBlock.class).isEmpty()));
        assertEquals(
                "Never edit generated files. Investigate failure.",
                state.contextMutable().get(0).getTextContent());
        verify(model, never()).stream(any(), any(), any());
    }

    static Object tree(String value) {
        try {
            return new com.fasterxml.jackson.databind.ObjectMapper().readTree(value);
        } catch (Exception e) {
            throw new IllegalArgumentException(e);
        }
    }

    @Test
    void rejectedBudgetAndConcurrentChangesNeverCommitCompactedHistory() {
        for (boolean conflict : List.of(false, true)) {
            var h = history();
            var state = AgentState.builder().context(h).build();
            var rc =
                    RuntimeContext.builder()
                            .userId("owner")
                            .sessionId("reject")
                            .agentState(state)
                            .build();
            var agent = mock(Agent.class);
            when(agent.getName()).thenReturn("agent");
            var model = mock(Model.class);
            when(model.getContextWindowSize()).thenReturn(100000);
            when(model.getModelName()).thenReturn("offline");
            var newer = text("newer", MsgRole.USER, "new instruction");
            var c =
                    compactor(
                            JevExecution.Mode.ENFORCE,
                            r -> {
                                if (conflict) state.contextMutable().add(newer);
                                return answer(r, k -> 0);
                            },
                            config(1, 128, 25000),
                            archive());
            var cfg =
                    CompactionConfig.builder()
                            .triggerMessages(2)
                            .prune(null)
                            .flushBeforeCompact(false)
                            .strategy(c)
                            .build();
            var policy =
                    conflict
                            ? ContextPolicy.defaults()
                            : new ContextPolicy(
                                    1, 1, 1, ContextTokenEstimator.approximate(), ignored -> {});
            var builder =
                    new HarnessContextBuilder(
                            policy, new CompactionMiddleware(null, model, cfg), null);
            Class<? extends RuntimeException> expectedError =
                    conflict
                            ? java.util.ConcurrentModificationException.class
                            : ContextBudgetExceededException.class;
            assertThrows(
                    expectedError,
                    () ->
                            builder.prepare(
                                            agent,
                                            rc,
                                            new ModelCallInput(h, List.of(), null, model),
                                            "reject",
                                            Purpose.REASONING)
                                    .block());
            assertEquals(h, state.contextMutable().subList(0, h.size()));
            assertEquals(conflict ? h.size() + 1 : h.size(), state.contextMutable().size());
        }
    }

    @Test
    void concurrentPlansHaveIndependentScopesAndResults() {
        var a = archive();
        var c =
                compactor(
                        JevExecution.Mode.ENFORCE,
                        r -> answer(r, k -> 0).delayElement(Duration.ofMillis(2)),
                        config(1, 2, 25000),
                        a);
        var both =
                Mono.zip(c.plan(request(history(), "one")), c.plan(request(history(), "two")))
                        .block();
        var first = both.getT1().value();
        var second = both.getT2().value();
        assertEquals(3, first.requests());
        assertEquals(3, second.requests());
        assertFalse(first.archiveReference().equals(second.archiveReference()));
        assertEquals(
                8,
                a.restore(
                                new JevContextArchive.Scope("owner", "agent", "one"),
                                first.archiveReference())
                        .block()
                        .size());
        assertThrows(
                RuntimeException.class,
                () ->
                        a.restore(
                                        new JevContextArchive.Scope("owner", "agent", "one"),
                                        second.archiveReference())
                                .block());
    }

    @Test
    void harnessStoreAdapterUsesCreateIfAbsentAndScopeIsolation() {
        var store = new io.agentscope.harness.agent.filesystem.remote.store.InMemoryStore();
        var archive =
                new io.agentscope.extensions.judge.jev.context.StoreJevContextArchive(
                        store, 1_000_000);
        var scope = new JevContextArchive.Scope("owner", "agent", "store");
        var h = history();
        var ref = archive.save(scope, h).block();
        assertEquals(ref, archive.save(scope, h).block());
        assertEquals(tree(json(h)), tree(json(archive.restore(scope, ref).block())));
        assertThrows(
                RuntimeException.class,
                () ->
                        archive.restore(new JevContextArchive.Scope("other", "agent", "store"), ref)
                                .block());
        var unsupported = mock(io.agentscope.harness.agent.filesystem.remote.store.BaseStore.class);
        assertThrows(
                RuntimeException.class,
                () ->
                        new io.agentscope.extensions.judge.jev.context.StoreJevContextArchive(
                                        unsupported, 1_000_000)
                                .save(scope, h)
                                .block());
    }

    @Test
    void actualHarnessAgentUsesCompactorAndDoesNotReplayHistoricalTools() {
        var run = io.agentscope.examples.jev.JevContextCompactionExample.runOffline(temp);
        assertEquals(1, run.modelCalls());
        assertEquals(1, run.decisions());
        assertEquals(6, run.restoredMessages());
        assertTrue(run.answer().contains("count 3"));
    }

    @Test
    void nestedInputMutationDuringJudgmentCannotBeCommittedOrArchived() {
        List<String> paths = new ArrayList<>(List.of("before.txt"));
        var h = new ArrayList<>(history());
        h.set(
                1,
                Msg.builder()
                        .id("c-old")
                        .role(MsgRole.ASSISTANT)
                        .content(
                                ToolUseBlock.builder()
                                        .id("old")
                                        .name("read")
                                        .input(Map.of("paths", paths))
                                        .build())
                        .build());
        var archive = mock(JevContextArchive.class);
        var c =
                compactor(
                        JevExecution.Mode.ENFORCE,
                        r -> {
                            paths.set(0, "changed.txt");
                            return answer(r, k -> 0);
                        },
                        config(1, 128, 25000),
                        archive);
        var decision = c.plan(request(h, "mutated")).block();
        assertEquals("TRANSCRIPT_CHANGED", decision.reason());
        verifyNoInteractions(archive);
        assertTrue(json(h).contains("changed.txt"));
    }
}
