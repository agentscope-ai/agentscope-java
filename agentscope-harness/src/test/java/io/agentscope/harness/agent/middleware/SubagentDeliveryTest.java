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
import static org.junit.jupiter.api.Assertions.assertNotNull;
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
import io.agentscope.harness.agent.subagent.SubagentFactory;
import io.agentscope.harness.agent.subagent.task.BackgroundTask;
import io.agentscope.harness.agent.subagent.task.TaskDelivery;
import io.agentscope.harness.agent.subagent.task.TaskRepository;
import io.agentscope.harness.agent.subagent.task.TaskRunSpec;
import io.agentscope.harness.agent.subagent.task.TaskStatus;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import reactor.core.publisher.Flux;

/**
 * Phase B-3 — push delivery: SubagentsMiddleware injects newly-terminal tasks as a single
 * {@code <system-reminder>} USER message, writes it into AgentState, marks delivered after
 * downstream completion, never re-delivers, falls back gracefully for non-ReActAgent.
 */
class SubagentDeliveryTest {

    // ---- helpers ----------------------------------------------------------------------------

    private static ReActAgent newReActAgent() {
        return ReActAgent.builder()
                .name("parent")
                .sysPrompt("Test agent")
                .model(new MockModel("noop"))
                .toolkit(new Toolkit())
                .build();
    }

    private static SubagentEntry dummyEntry() {
        SubagentFactory factory = (rc) -> (Agent) null;
        return new SubagentEntry("worker", "Test worker", factory);
    }

    private static TaskDelivery delivery(String id, TaskStatus status, String result, String err) {
        return new TaskDelivery(id, "worker", status, result, err, Instant.now());
    }

    /** Stub TaskRepository that exposes pending deliveries deterministically. */
    private static final class StubRepo implements TaskRepository {
        final List<TaskDelivery> queue = new ArrayList<>();
        final Set<String> delivered = new HashSet<>();
        final List<String> markCalls = new ArrayList<>();

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
        public void removeTask(RuntimeContext rc, String sessionId, String taskId) {}

        @Override
        public void clear() {}

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
            List<TaskDelivery> out = new ArrayList<>();
            for (TaskDelivery d : queue) {
                if (!delivered.contains(d.taskId())) out.add(d);
            }
            return Collections.unmodifiableList(out);
        }

        @Override
        public void markDelivered(RuntimeContext rc, String sessionId, String taskId) {
            markCalls.add(taskId);
            delivered.add(taskId);
        }

        @Override
        public boolean isDelivered(RuntimeContext rc, String sessionId, String taskId) {
            return delivered.contains(taskId);
        }
    }

    private static String textOf(Msg m) {
        return m.getTextContent() != null ? m.getTextContent() : "";
    }

    // ---- buildDeliveryReminder static formatting ---------------------------------------------

    @Test
    void reminder_singleCompleted_rendersTaskResultBlock() {
        Msg msg =
                SubagentsMiddleware.buildDeliveryReminder(
                        List.of(delivery("t1", TaskStatus.COMPLETED, "the-result", null)));
        String text = textOf(msg);
        assertTrue(text.startsWith("<system-reminder>"));
        assertTrue(text.endsWith("</system-reminder>"));
        assertTrue(text.contains("1 background subagent task has completed"));
        assertTrue(text.contains("<task id=\"t1\" state=\"completed\" agent=\"worker\">"));
        assertTrue(text.contains("<task_result>"));
        assertTrue(text.contains("the-result"));
    }

    @Test
    void reminder_failedRendersTaskErrorBlock() {
        Msg msg =
                SubagentsMiddleware.buildDeliveryReminder(
                        List.of(delivery("tx", TaskStatus.FAILED, null, "boom")));
        String text = textOf(msg);
        assertTrue(text.contains("state=\"error\""));
        assertTrue(text.contains("<task_error>"));
        assertTrue(text.contains("boom"));
    }

    @Test
    void reminder_cancelledRendersCancelledLine() {
        Msg msg =
                SubagentsMiddleware.buildDeliveryReminder(
                        List.of(delivery("tc", TaskStatus.CANCELLED, null, null)));
        String text = textOf(msg);
        assertTrue(text.contains("state=\"cancelled\""));
        assertTrue(text.contains("cancelled before producing a result"));
    }

    @Test
    void reminder_capsAndSummarisesOverflow() {
        List<TaskDelivery> ds = new ArrayList<>();
        for (int i = 0; i < SubagentsMiddleware.MAX_DELIVERIES_PER_REMINDER + 5; i++) {
            ds.add(delivery("t" + i, TaskStatus.COMPLETED, "r" + i, null));
        }
        Msg msg = SubagentsMiddleware.buildDeliveryReminder(ds);
        String text = textOf(msg);
        // First N rendered as <task ...> blocks, then 5 overflow noted in the tail line.
        int blocks = text.split("<task ", -1).length - 1;
        assertEquals(SubagentsMiddleware.MAX_DELIVERIES_PER_REMINDER, blocks);
        assertTrue(text.contains("... and 5 more"));
    }

    @Test
    void reminder_hasSyntheticMetadata() {
        Msg msg =
                SubagentsMiddleware.buildDeliveryReminder(
                        List.of(delivery("t1", TaskStatus.COMPLETED, "r", null)));
        assertEquals(MsgRole.USER, msg.getRole());
        assertEquals("system", msg.getName());
        assertNotNull(msg.getMetadata());
        assertEquals(Boolean.TRUE, msg.getMetadata().get(Msg.METADATA_SYNTHETIC));
        assertEquals("task_delivery", msg.getMetadata().get(Msg.METADATA_REMINDER_KIND));
    }

    // ---- onReasoning integration -------------------------------------------------------------

    @Test
    void onReasoning_writesDeliveryToAgentStateAndMarksAfterCompletion() {
        ReActAgent agent = newReActAgent();
        StubRepo repo = new StubRepo();
        repo.queue.add(delivery("t1", TaskStatus.COMPLETED, "hello", null));

        SubagentsMiddleware mw =
                new SubagentsMiddleware(
                        List.of(dummyEntry()),
                        repo,
                        (io.agentscope.harness.agent.workspace.WorkspaceManager) null);

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

        // AgentState was appended with one delivery reminder
        List<Msg> ctx = agent.getAgentState().contextMutable();
        assertEquals(1, ctx.size());
        Msg delivered = ctx.get(0);
        assertEquals(MsgRole.USER, delivered.getRole());
        assertTrue(textOf(delivered).contains("hello"));

        // The same reminder was also injected into the per-round messages forwarded downstream
        // (so the very next LLM call sees it without waiting for the next state-derived round).
        boolean inRound =
                forwarded.get().messages().stream().anyMatch(m -> textOf(m).contains("hello"));
        assertTrue(inRound);

        // markDelivered fired after downstream onComplete (single call, idempotent).
        assertEquals(List.of("t1"), repo.markCalls);
    }

    @Test
    void onReasoning_doesNotReDeliver() {
        ReActAgent agent = newReActAgent();
        StubRepo repo = new StubRepo();
        repo.queue.add(delivery("t1", TaskStatus.COMPLETED, "once", null));

        SubagentsMiddleware mw =
                new SubagentsMiddleware(
                        List.of(dummyEntry()),
                        repo,
                        (io.agentscope.harness.agent.workspace.WorkspaceManager) null);

        mw.onReasoning(
                        agent,
                        null,
                        new ReasoningInput(new ArrayList<>(), List.of(), null),
                        in -> Flux.<AgentEvent>empty())
                .blockLast();
        mw.onReasoning(
                        agent,
                        null,
                        new ReasoningInput(new ArrayList<>(), List.of(), null),
                        in -> Flux.<AgentEvent>empty())
                .blockLast();

        // Reminder added exactly once.
        long matching =
                agent.getAgentState().contextMutable().stream()
                        .filter(m -> textOf(m).contains("once"))
                        .count();
        assertEquals(1, matching);
        // markDelivered called exactly once even though onReasoning ran twice.
        assertEquals(1, repo.markCalls.size());
    }

    @Test
    void onReasoning_multiDeliveryAggregatedToSingleMessage() {
        ReActAgent agent = newReActAgent();
        StubRepo repo = new StubRepo();
        repo.queue.add(delivery("a", TaskStatus.COMPLETED, "A", null));
        repo.queue.add(delivery("b", TaskStatus.FAILED, null, "bad"));
        repo.queue.add(delivery("c", TaskStatus.CANCELLED, null, null));

        SubagentsMiddleware mw =
                new SubagentsMiddleware(
                        List.of(dummyEntry()),
                        repo,
                        (io.agentscope.harness.agent.workspace.WorkspaceManager) null);
        mw.onReasoning(
                        agent,
                        null,
                        new ReasoningInput(new ArrayList<>(), List.of(), null),
                        in -> Flux.<AgentEvent>empty())
                .blockLast();

        List<Msg> ctx = agent.getAgentState().contextMutable();
        assertEquals(1, ctx.size(), "expected a single aggregated reminder, not one per task");
        String text = textOf(ctx.get(0));
        assertTrue(text.contains("3 background subagent tasks have completed"));
        assertTrue(text.contains("\"a\""));
        assertTrue(text.contains("\"b\""));
        assertTrue(text.contains("\"c\""));
        assertEquals(List.of("a", "b", "c"), repo.markCalls);
    }
}
