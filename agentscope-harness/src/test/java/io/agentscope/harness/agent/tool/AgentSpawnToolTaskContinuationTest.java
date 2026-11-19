/*
 * Copyright 2024-2026 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package io.agentscope.harness.agent.tool;

import static io.agentscope.harness.agent.tool.ToolResultAssertions.assertText;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import io.agentscope.core.agent.Agent;
import io.agentscope.core.agent.ExecutionIdentity;
import io.agentscope.core.agent.RuntimeContext;
import io.agentscope.core.message.ToolResultState;
import io.agentscope.core.state.AgentState;
import io.agentscope.core.state.ToolContextState;
import io.agentscope.harness.agent.middleware.SubagentEntry;
import io.agentscope.harness.agent.subagent.DefaultAgentManager;
import io.agentscope.harness.agent.subagent.SubagentDeclaration;
import io.agentscope.harness.agent.subagent.protocol.RemoteAgentEvent;
import io.agentscope.harness.agent.subagent.protocol.RemoteConfirmDecision;
import io.agentscope.harness.agent.subagent.task.BackgroundTask;
import io.agentscope.harness.agent.subagent.task.RemoteSessionAwaitingConfirmException;
import io.agentscope.harness.agent.subagent.task.RemoteSessionBusyException;
import io.agentscope.harness.agent.subagent.task.RemoteSubagentTransport;
import io.agentscope.harness.agent.subagent.task.RemoteSubmitContext;
import io.agentscope.harness.agent.subagent.task.RemoteTarget;
import io.agentscope.harness.agent.subagent.task.RemoteTaskStatus;
import io.agentscope.harness.agent.subagent.task.TaskRepository;
import io.agentscope.harness.agent.subagent.task.TaskRunSpec;
import java.io.Closeable;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;
import org.junit.jupiter.api.Test;

/**
 * Continuing a remote child's conversation through the {@code agent_key}/{@code label} bound to the
 * task the child last ran: the new submission points the server at the earlier task's session, while
 * the task id itself stays fresh so results and events remain addressable per turn.
 *
 * <p>Also covers the two ways a submission can leave no remote task behind: the server rejecting it
 * (HTTP 400/409), which must not move the child's conversation anchor, and a parent that is still
 * running, which has to be reported as {@code still_running} rather than as a failed turn.
 */
class AgentSpawnToolTaskContinuationTest {

    private static final String CHILD_KEY = "agent:worker:key";
    private static final String PREVIOUS_TASK = "task_previous";

    private final Agent agent = mock(Agent.class);
    private final TaskRepository repository = mock(TaskRepository.class);
    private final RuntimeContext context = RuntimeContext.builder().sessionId("parent").build();

    @Test
    void remoteSubmissionCarriesCallerIdentityFromRuntimeContext() {
        AtomicReference<TaskRunSpec.RemoteTaskRunSpec> submitted = new AtomicReference<>();
        when(repository.putTask(
                        any(RuntimeContext.class),
                        anyString(),
                        anyString(),
                        anyString(),
                        any(TaskRunSpec.class)))
                .thenAnswer(
                        invocation -> {
                            submitted.set(
                                    (TaskRunSpec.RemoteTaskRunSpec) invocation.getArgument(4));
                            return new BackgroundTask(
                                    invocation.getArgument(1),
                                    invocation.getArgument(2),
                                    CompletableFuture.completedFuture("ok"));
                        });
        AgentSpawnTool tool = remoteTool();
        // Production callers get the identity from the running agent; here it is installed by hand
        // because no agent loop is involved.
        RuntimeContext parent =
                RuntimeContext.builder()
                        .userId("u-1")
                        .sessionId("sess-parent")
                        .put(
                                ExecutionIdentity.CONTEXT_KEY,
                                new ExecutionIdentity(
                                        "planner", "sess-parent", "main", null, "run-1"))
                        .build();

        assertText(
                tool.agentSpawn(
                                parent,
                                AgentState.builder().build(),
                                "worker",
                                "hello",
                                true,
                                "worker-label",
                                0,
                                null)
                        .block(),
                ToolResultState.SUCCESS);

        RemoteSubmitContext wire = submitted.get().context();
        assertEquals("u-1", wire.userId());
        assertEquals("sess-parent", wire.parentSessionId());
        assertEquals("planner", wire.parentAgentId());
    }

    @Test
    void sendByAgentKeyContinuesTheConversationTheSpawnOpened() {
        AtomicReference<TaskRunSpec.RemoteTaskRunSpec> submitted = new AtomicReference<>();
        stubSubmissions(submitted);
        AgentSpawnTool tool = remoteTool();
        AgentState parentState = AgentState.builder().build();

        String spawn =
                assertText(
                        tool.agentSpawn(
                                        context,
                                        parentState,
                                        "worker",
                                        "first turn",
                                        true,
                                        null,
                                        5,
                                        null)
                                .block(),
                        ToolResultState.SUCCESS);
        String agentKey = lineValue(spawn, "agent_key: ");
        String spawnSession = submitted.get().context().sessionId();
        assertNotNull(spawnSession, "a remote spawn must name the child session");

        String send =
                assertText(
                        tool.agentSend(context, parentState, agentKey, null, "second turn", 5)
                                .block(),
                        ToolResultState.SUCCESS);

        // The result names the handle the caller passed in. The session is how the subagent keeps
        // its conversation, not something the caller has to carry between turns.
        assertTrue(send.contains("agent_key: " + agentKey), send);
        assertFalse(send.contains("session:"), "send must not echo the child session: " + send);
        assertEquals(spawnSession, submitted.get().context().sessionId());
        assertEquals("second turn", submitted.get().input());
    }

    @Test
    void sendByLabelResumesTheRemoteTaskAnEarlierInstanceBound() {
        AtomicReference<TaskRunSpec.RemoteTaskRunSpec> submitted = new AtomicReference<>();
        stubSubmissions(submitted);
        AgentSpawnTool tool = remoteTool();
        // State restored on another replica: only the persisted entry knows which remote task holds
        // the child's conversation.
        AgentState parentState = AgentState.builder().build();
        parentState
                .getToolContext()
                .putSpawnEntry(
                        "agent:worker:key",
                        new ToolContextState.SpawnEntry(
                                "agent:worker:key", "worker", "sub-restored", "reviewer", 1));

        String send =
                assertText(
                        tool.agentSend(context, parentState, null, "reviewer", "next turn", 5)
                                .block(),
                        ToolResultState.SUCCESS);

        assertFalse(send.contains("session:"), "send must not echo the child session: " + send);
        assertEquals("sub-restored", submitted.get().context().sessionId());
    }

    /**
     * Registers both a remote {@code worker} and a local {@code local-worker}, and records the
     * remote submission for assertions.
     */
    private AgentSpawnTool remoteTool() {
        RemoteSubagentTransport transport =
                new RemoteSubagentTransport() {
                    @Override
                    public String transportType() {
                        return "agent-protocol";
                    }

                    @Override
                    public void submit(
                            RemoteTarget target,
                            String taskId,
                            String agentId,
                            String input,
                            RemoteSubmitContext context) {}

                    @Override
                    public RemoteTaskStatus getStatus(RemoteTarget target, String taskId) {
                        return new RemoteTaskStatus("success", null);
                    }

                    @Override
                    public String waitForResult(
                            RemoteTarget target, String taskId, long timeoutSeconds) {
                        return "ok";
                    }

                    @Override
                    public void cancel(RemoteTarget target, String taskId) {}

                    @Override
                    public void resume(
                            RemoteTarget target,
                            String taskId,
                            List<RemoteConfirmDecision> decisions) {}

                    @Override
                    public Closeable streamEvents(
                            RemoteTarget target,
                            String taskId,
                            long fromSeq,
                            Consumer<RemoteAgentEvent> consumer) {
                        return () -> {};
                    }
                };
        AgentSpawnTool tool =
                new AgentSpawnTool(
                        new DefaultAgentManager(
                                List.of(
                                        new SubagentEntry(
                                                "worker",
                                                "remote worker",
                                                rc -> agent,
                                                SubagentDeclaration.builder()
                                                        .name("worker")
                                                        .description("remote worker")
                                                        .url("http://unused.invalid")
                                                        .build()),
                                        new SubagentEntry(
                                                "local-worker",
                                                "local worker",
                                                rc -> agent,
                                                SubagentDeclaration.builder()
                                                        .name("local-worker")
                                                        .description("local worker")
                                                        .inlineAgentsBody("local worker")
                                                        .build())),
                                null),
                        repository,
                        0);
        tool.setRemoteTransport(transport);
        return tool;
    }

    @Test
    void aStillRunningSessionIsReportedAsStillRunning() {
        AgentSpawnTool tool = remoteTool();
        AgentState parentState = restoredChildState();
        stubFailedSubmission(
                new RemoteSessionBusyException(PREVIOUS_TASK, PREVIOUS_TASK, "still running"));

        String result =
                assertText(
                        tool.agentSend(context, parentState, CHILD_KEY, null, "next turn", 5)
                                .block(),
                        ToolResultState.SUCCESS);

        assertTrue(result.contains("status: still_running"), result);
        assertFalse(
                result.contains("session:"), "a busy report must not echo the session: " + result);
        assertTrue(result.contains("task_id: " + PREVIOUS_TASK), result);
        assertTrue(result.contains("task_output"), result);
        // The session, not a task, is the conversation pointer: the restored entry still names the
        // same one after a busy turn, so the next send lands in the same conversation.
        assertEquals(
                "sub-restored",
                parentState.getToolContext().getSpawnRegistry().get(CHILD_KEY).sessionId());
    }

    @Test
    void anAwaitingConfirmSessionIsReportedAsResumable() {
        AgentSpawnTool tool = remoteTool();
        AgentState parentState = restoredChildState();
        stubFailedSubmission(
                new RemoteSessionAwaitingConfirmException(
                        PREVIOUS_TASK,
                        "task_pending",
                        "2026-11-20T18:00:00Z",
                        "awaiting confirmation"));

        String result =
                assertText(
                        tool.agentSend(context, parentState, CHILD_KEY, null, "next turn", 5)
                                .block(),
                        ToolResultState.SUCCESS);

        assertTrue(result.contains("status: awaiting_confirm"), result);
        assertTrue(result.contains("task_id: task_pending"), result);
        assertTrue(result.contains("Resume"), result);
        assertFalse(
                result.contains("session:"), "a pause report must not echo the session: " + result);
    }

    /**
     * Parent state as restored on another replica: the persisted entry is the only record that this
     * child's conversation lives in {@link #PREVIOUS_TASK}.
     */
    private AgentState restoredChildState() {
        AgentState parentState = AgentState.builder().build();
        parentState
                .getToolContext()
                .putSpawnEntry(
                        CHILD_KEY,
                        new ToolContextState.SpawnEntry(
                                CHILD_KEY, "worker", "sub-restored", null, 1));
        return parentState;
    }

    private void stubFailedSubmission(Exception failure) {
        when(repository.putTask(
                        any(RuntimeContext.class),
                        anyString(),
                        anyString(),
                        eq("parent"),
                        any(TaskRunSpec.class)))
                .thenReturn(
                        new BackgroundTask(
                                "task_rejected",
                                "worker",
                                CompletableFuture.failedFuture(failure)));
    }

    /** Stubs {@code putTask} to capture the submitted remote spec for assertions. */
    private void stubSubmissions(AtomicReference<TaskRunSpec.RemoteTaskRunSpec> submitted) {
        when(repository.putTask(
                        eq(context),
                        anyString(),
                        anyString(),
                        eq("parent"),
                        any(TaskRunSpec.class)))
                .thenAnswer(
                        invocation -> {
                            TaskRunSpec spec = invocation.getArgument(4);
                            if (spec instanceof TaskRunSpec.RemoteTaskRunSpec remote) {
                                submitted.set(remote);
                            }
                            return new BackgroundTask(
                                    invocation.getArgument(1),
                                    invocation.getArgument(2),
                                    CompletableFuture.completedFuture("ok"));
                        });
    }

    private static String lineValue(String text, String prefix) {
        return text.lines()
                .filter(line -> line.startsWith(prefix))
                .map(line -> line.substring(prefix.length()).trim())
                .findFirst()
                .orElseThrow(() -> new AssertionError("missing '" + prefix + "' in: " + text));
    }
}
