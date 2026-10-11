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
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.agentscope.core.ReActAgent;
import io.agentscope.core.agent.Agent;
import io.agentscope.core.agent.RuntimeContext;
import io.agentscope.core.agent.test.MockModel;
import io.agentscope.core.event.AgentEvent;
import io.agentscope.core.message.Msg;
import io.agentscope.core.message.MsgRole;
import io.agentscope.core.middleware.ReasoningInput;
import io.agentscope.core.tool.Toolkit;
import io.agentscope.harness.agent.HarnessAgent;
import io.agentscope.harness.agent.filesystem.local.LocalFilesystem;
import io.agentscope.harness.agent.subagent.SubagentFactory;
import io.agentscope.harness.agent.subagent.task.BackgroundTask;
import io.agentscope.harness.agent.subagent.task.TaskDelivery;
import io.agentscope.harness.agent.subagent.task.TaskRepository;
import io.agentscope.harness.agent.subagent.task.TaskRunSpec;
import io.agentscope.harness.agent.subagent.task.TaskStatus;
import io.agentscope.harness.agent.testing.HarnessQuiescence;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import reactor.core.publisher.Flux;

/**
 * Phase B-3 — push delivery on the <em>dynamic</em> subagent path: {@link
 * DynamicSubagentsMiddleware} must drain newly-terminal tasks from the {@link TaskRepository},
 * inject a single aggregated {@code <system-reminder>} message into the current reasoning round,
 * persist it to AgentState, and mark deliveries only after the round completes successfully —
 * identical semantics to {@link SubagentsMiddleware} (covered by {@code SubagentDeliveryTest}).
 *
 * <p>Also guards that the dynamic declaration reload (per-reasoning-round {@code subagents/*.md}
 * scan) keeps working alongside delivery, and that the default {@code HarnessAgent} builder path
 * (which registers {@link DynamicSubagentsMiddleware} whenever a workspace filesystem exists)
 * delivers through the builder-provided repository.
 */
@HarnessQuiescence
class DynamicSubagentDeliveryTest {

    // ---- helpers ----------------------------------------------------------------------------

    private static ReActAgent newReActAgent() {
        return ReActAgent.builder()
                .name("parent")
                .sysPrompt("Test agent")
                .model(new MockModel("noop"))
                .toolkit(new Toolkit())
                .build();
    }

    private static TaskDelivery delivery(String id, TaskStatus status, String result, String err) {
        return new TaskDelivery(id, "worker", status, result, err, Instant.now());
    }

    private static DynamicSubagentsMiddleware newMiddleware(TaskRepository repo) {
        return new DynamicSubagentsMiddleware(
                List.of(),
                null,
                null,
                null,
                new io.agentscope.harness.agent.subagent.DefaultAgentManager(List.of(), null),
                null,
                repo);
    }

    private static DynamicSubagentsMiddleware newMiddleware(
            TaskRepository repo, Path mainWorkspace) {
        SubagentFactory factory = (rc) -> (Agent) null;
        return new DynamicSubagentsMiddleware(
                List.of(),
                null,
                mainWorkspace,
                decl -> factory,
                new io.agentscope.harness.agent.subagent.DefaultAgentManager(List.of(), null),
                null,
                repo);
    }

    /** Session-aware stub repository exposing pending deliveries deterministically. */
    private static final class StubRepo implements TaskRepository {
        /** sessionId -> queued deliveries. */
        final Map<String, List<TaskDelivery>> queue = new LinkedHashMap<>();

        final Set<String> delivered = new HashSet<>();

        /** Recorded as {@code sessionId + "/" + taskId}. */
        final List<String> markCalls = new ArrayList<>();

        final List<String> findSessions = new ArrayList<>();

        private static String key(String sessionId, String taskId) {
            return sessionId + "/" + taskId;
        }

        @Override
        public BackgroundTask getTask(RuntimeContext rc, String sessionId, String taskId) {
            return null;
        }

        @Override
        public BackgroundTask putTask(
                RuntimeContext rc,
                String taskId,
                String subAgentId,
                String sessionId,
                TaskRunSpec spec) {
            throw new UnsupportedOperationException();
        }

        @Override
        public Collection<BackgroundTask> listTasks(
                RuntimeContext rc, String sessionId, TaskStatus filter) {
            return List.of();
        }

        @Override
        public boolean cancelTask(RuntimeContext rc, String sessionId, String taskId) {
            return false;
        }

        @Override
        public List<TaskDelivery> findPendingDeliveries(RuntimeContext rc, String sessionId) {
            findSessions.add(sessionId);
            List<TaskDelivery> out = new ArrayList<>();
            for (TaskDelivery d : queue.getOrDefault(sessionId, List.of())) {
                if (!delivered.contains(key(sessionId, d.taskId()))) out.add(d);
            }
            return Collections.unmodifiableList(out);
        }

        @Override
        public void markDelivered(RuntimeContext rc, String sessionId, String taskId) {
            markCalls.add(key(sessionId, taskId));
            delivered.add(key(sessionId, taskId));
        }

        @Override
        public boolean isDelivered(RuntimeContext rc, String sessionId, String taskId) {
            return delivered.contains(key(sessionId, taskId));
        }
    }

    private static String textOf(Msg m) {
        return m.getTextContent() != null ? m.getTextContent() : "";
    }

    // ---- delivery behaviour ------------------------------------------------------------------

    @Test
    void onReasoning_writesDeliveryToAgentStateInjectsRoundAndMarksAfterCompletion() {
        ReActAgent agent = newReActAgent();
        StubRepo repo = new StubRepo();
        repo.queue
                .computeIfAbsent(null, k -> new ArrayList<>())
                .add(delivery("t1", TaskStatus.COMPLETED, "hello", null));

        DynamicSubagentsMiddleware mw = newMiddleware(repo);
        AtomicReference<ReasoningInput> forwarded = new AtomicReference<>();
        mw.onReasoning(
                        agent,
                        null,
                        new ReasoningInput(new ArrayList<>(), List.of(), null),
                        in -> {
                            forwarded.set(in);
                            return Flux.<AgentEvent>empty();
                        })
                .blockLast();

        // AgentState was appended with one delivery reminder.
        List<Msg> ctx = agent.getAgentState().contextMutable();
        assertEquals(1, ctx.size());
        Msg delivered = ctx.get(0);
        assertEquals(MsgRole.USER, delivered.getRole());
        assertTrue(textOf(delivered).contains("hello"));
        assertTrue(textOf(delivered).startsWith("<system-reminder>"));

        // The same reminder was injected into the per-round messages forwarded downstream.
        assertTrue(forwarded.get().messages().stream().anyMatch(m -> textOf(m).contains("hello")));

        // markDelivered fired after downstream onComplete (single call).
        assertEquals(List.of("null/t1"), repo.markCalls);
    }

    @Test
    void onReasoning_aggregatesCompletedFailedCancelledIntoSingleReminder() {
        ReActAgent agent = newReActAgent();
        StubRepo repo = new StubRepo();
        List<TaskDelivery> q = repo.queue.computeIfAbsent(null, k -> new ArrayList<>());
        q.add(delivery("a", TaskStatus.COMPLETED, "A", null));
        q.add(delivery("b", TaskStatus.FAILED, null, "bad"));
        q.add(delivery("c", TaskStatus.CANCELLED, null, null));

        newMiddleware(repo)
                .onReasoning(
                        agent,
                        null,
                        new ReasoningInput(new ArrayList<>(), List.of(), null),
                        in -> Flux.<AgentEvent>empty())
                .blockLast();

        List<Msg> ctx = agent.getAgentState().contextMutable();
        assertEquals(1, ctx.size(), "expected a single aggregated reminder, not one per task");
        String text = textOf(ctx.get(0));
        assertTrue(text.contains("3 background subagent tasks have completed"));
        assertTrue(text.contains("state=\"completed\""));
        assertTrue(text.contains("state=\"error\""));
        assertTrue(text.contains("bad"));
        assertTrue(text.contains("state=\"cancelled\""));
        assertEquals(List.of("null/a", "null/b", "null/c"), repo.markCalls);
    }

    @Test
    void onReasoning_reasoningFailureDoesNotMarkDelivered() {
        ReActAgent agent = newReActAgent();
        StubRepo repo = new StubRepo();
        repo.queue
                .computeIfAbsent(null, k -> new ArrayList<>())
                .add(delivery("t1", TaskStatus.COMPLETED, "hello", null));

        DynamicSubagentsMiddleware mw = newMiddleware(repo);
        assertThrows(
                IllegalStateException.class,
                () ->
                        mw.onReasoning(
                                        agent,
                                        null,
                                        new ReasoningInput(new ArrayList<>(), List.of(), null),
                                        in ->
                                                Flux.<AgentEvent>error(
                                                        new IllegalStateException("boom")))
                                .blockLast());

        // Reasoning errored → nothing acknowledged → the next round re-delivers.
        assertTrue(repo.markCalls.isEmpty(), "failed reasoning must not markDelivered");

        // Retry with a healthy downstream: delivery is re-pushed and then acknowledged.
        mw.onReasoning(
                        agent,
                        null,
                        new ReasoningInput(new ArrayList<>(), List.of(), null),
                        in -> Flux.<AgentEvent>empty())
                .blockLast();
        assertEquals(List.of("null/t1"), repo.markCalls);
    }

    @Test
    void deliveryCap_acknowledgesOnlyCapPerRound_remainderNextRound() {
        ReActAgent agent = newReActAgent();
        StubRepo repo = new StubRepo();
        List<TaskDelivery> q = repo.queue.computeIfAbsent(null, k -> new ArrayList<>());
        for (int i = 0; i < SubagentsMiddleware.MAX_DELIVERIES_PER_REMINDER + 2; i++) {
            q.add(delivery("t" + i, TaskStatus.COMPLETED, "result " + i, null));
        }

        DynamicSubagentsMiddleware mw = newMiddleware(repo);
        mw.onReasoning(
                        agent,
                        null,
                        new ReasoningInput(new ArrayList<>(), List.of(), null),
                        in -> Flux.<AgentEvent>empty())
                .blockLast();
        assertEquals(SubagentsMiddleware.MAX_DELIVERIES_PER_REMINDER, repo.markCalls.size());
        String text1 = textOf(agent.getAgentState().contextMutable().get(0));
        assertTrue(text1.contains("... and 2 more"));

        mw.onReasoning(
                        agent,
                        null,
                        new ReasoningInput(new ArrayList<>(), List.of(), null),
                        in -> Flux.<AgentEvent>empty())
                .blockLast();
        assertEquals(
                SubagentsMiddleware.MAX_DELIVERIES_PER_REMINDER + 2,
                repo.markCalls.size(),
                "remainder must be delivered on the next round");
    }

    @Test
    void deliveriesAreScopedBySessionId() {
        ReActAgent agent = newReActAgent();
        StubRepo repo = new StubRepo();
        repo.queue
                .computeIfAbsent("s1", k -> new ArrayList<>())
                .add(delivery("t1", TaskStatus.COMPLETED, "for-s1", null));
        repo.queue
                .computeIfAbsent("s2", k -> new ArrayList<>())
                .add(delivery("t2", TaskStatus.COMPLETED, "for-s2", null));

        DynamicSubagentsMiddleware mw = newMiddleware(repo);
        AtomicReference<ReasoningInput> forwarded = new AtomicReference<>();
        mw.onReasoning(
                        agent,
                        RuntimeContext.builder().sessionId("s1").build(),
                        new ReasoningInput(new ArrayList<>(), List.of(), null),
                        in -> {
                            forwarded.set(in);
                            return Flux.<AgentEvent>empty();
                        })
                .blockLast();

        assertEquals(List.of("s1"), repo.findSessions);
        assertEquals(List.of("s1/t1"), repo.markCalls);
        assertTrue(forwarded.get().messages().stream().anyMatch(m -> textOf(m).contains("for-s1")));
        assertTrue(
                forwarded.get().messages().stream().noneMatch(m -> textOf(m).contains("for-s2")),
                "another session's task result must never leak into this session's reminder");
    }

    @Test
    void onReasoning_noSubagentsNoDeliveries_forwardsOriginalInputUnchanged() {
        // Nothing to add (no declared subagents, empty task summary) and nothing pending →
        // the middleware must forward the original ReasoningInput instance untouched, taking
        // the rebuilt == input.messages() pass-through branch.
        ReActAgent agent = newReActAgent();
        StubRepo repo = new StubRepo();
        DynamicSubagentsMiddleware mw = newMiddleware(repo);

        ReasoningInput original = new ReasoningInput(new ArrayList<>(), List.of(), null);
        AtomicReference<ReasoningInput> forwarded = new AtomicReference<>();
        mw.onReasoning(
                        agent,
                        null,
                        original,
                        in -> {
                            forwarded.set(in);
                            return Flux.<AgentEvent>empty();
                        })
                .blockLast();

        assertSame(
                original,
                forwarded.get(),
                "empty addition + no delivery must forward the original ReasoningInput as-is");
        assertTrue(repo.markCalls.isEmpty());
    }

    // ---- dynamic declaration semantics preserved ----------------------------------------------

    @Test
    void dynamicDeclarationsStillReloadPerRoundAlongsideDelivery(@TempDir Path workspace)
            throws Exception {
        Path subagentsDir = workspace.resolve("subagents");
        Files.createDirectories(subagentsDir);
        Files.writeString(
                subagentsDir.resolve("helper1.md"),
                "---\nname: helper1\ndescription: First helper\n---\nBody one.\n");

        ReActAgent agent = newReActAgent();
        StubRepo repo = new StubRepo();
        DynamicSubagentsMiddleware mw = newMiddleware(repo, workspace);

        AtomicReference<ReasoningInput> r1 = new AtomicReference<>();
        mw.onReasoning(
                        agent,
                        null,
                        new ReasoningInput(new ArrayList<>(), List.of(), null),
                        in -> {
                            r1.set(in);
                            return Flux.<AgentEvent>empty();
                        })
                .blockLast();
        String sys1 = textOf(r1.get().messages().get(0));
        assertEquals(MsgRole.SYSTEM, r1.get().messages().get(0).getRole());
        assertTrue(sys1.contains("helper1"), "round 1 must advertise helper1");

        // A new declaration appears between rounds — the dynamic reload must pick it up.
        Files.writeString(
                subagentsDir.resolve("helper2.md"),
                "---\nname: helper2\ndescription: Second helper\n---\nBody two.\n");

        AtomicReference<ReasoningInput> r2 = new AtomicReference<>();
        mw.onReasoning(
                        agent,
                        null,
                        new ReasoningInput(new ArrayList<>(), List.of(), null),
                        in -> {
                            r2.set(in);
                            return Flux.<AgentEvent>empty();
                        })
                .blockLast();
        String sys2 = textOf(r2.get().messages().get(0));
        assertTrue(
                sys2.contains("helper1") && sys2.contains("helper2"),
                "round 2 must advertise both helpers");
    }

    // ---- default builder registration ----------------------------------------------------------

    @Test
    void defaultBuilderRegistersDynamicMiddlewareThatDeliversThroughBuilderRepo(
            @TempDir Path workspace) throws Exception {
        Files.createDirectories(workspace);
        StubRepo repo = new StubRepo();
        repo.queue
                .computeIfAbsent("builder-s", k -> new ArrayList<>())
                .add(delivery("bt1", TaskStatus.COMPLETED, "builder-result", null));

        HarnessAgent agent =
                HarnessAgent.builder()
                        .name("t")
                        .model(new MockModel("ok"))
                        .workspace(workspace)
                        .abstractFilesystem(new LocalFilesystem(workspace))
                        .taskRepository(repo)
                        .build();
        try {
            DynamicSubagentsMiddleware dynMw =
                    agent.getDelegate().getMiddlewares().stream()
                            .filter(DynamicSubagentsMiddleware.class::isInstance)
                            .map(DynamicSubagentsMiddleware.class::cast)
                            .findFirst()
                            .orElse(null);
            assertTrue(dynMw != null, "default build must register DynamicSubagentsMiddleware");
            assertEquals(repo, dynMw.getTaskRepository());

            ReActAgent delegate = agent.getDelegate();
            AtomicReference<ReasoningInput> forwarded = new AtomicReference<>();
            dynMw.onReasoning(
                            delegate,
                            RuntimeContext.builder().sessionId("builder-s").build(),
                            new ReasoningInput(new ArrayList<>(), List.of(), null),
                            in -> {
                                forwarded.set(in);
                                return Flux.<AgentEvent>empty();
                            })
                    .blockLast();

            assertTrue(
                    forwarded.get().messages().stream()
                            .anyMatch(m -> textOf(m).contains("builder-result")),
                    "builder-registered dynamic middleware must inject the delivery reminder");
            assertEquals(List.of("builder-s/bt1"), repo.markCalls);
        } finally {
            agent.close();
        }
    }
}
