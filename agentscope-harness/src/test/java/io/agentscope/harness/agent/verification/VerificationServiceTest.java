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
package io.agentscope.harness.agent.verification;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.when;

import io.agentscope.core.agent.RuntimeContext;
import io.agentscope.core.message.ToolExecutionDetails;
import io.agentscope.core.message.ToolResultBlock;
import io.agentscope.core.message.ToolResultState;
import io.agentscope.core.message.ToolUseBlock;
import io.agentscope.core.middleware.TaskContextProjection;
import io.agentscope.core.observation.ActionObservation;
import io.agentscope.core.observation.ActionObservations;
import io.agentscope.core.observation.ActionObserver;
import io.agentscope.core.state.AgentState;
import io.agentscope.core.state.JsonFileAgentStateStore;
import io.agentscope.core.state.TaskContextState;
import io.agentscope.core.state.TaskRequirement;
import io.agentscope.core.state.TaskVerification.Outcome;
import io.agentscope.core.tool.ToolCallParam;
import io.agentscope.harness.agent.observation.StateStoreActionObserver;
import io.agentscope.harness.agent.observation.StoredActionObservation;
import java.nio.file.Path;
import java.util.ConcurrentModificationException;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import reactor.core.publisher.Mono;

class VerificationServiceTest {
    @TempDir Path directory;
    private JsonFileAgentStateStore store;
    private TaskContextState task;
    private RuntimeContext context;
    private String criterion;
    private VerificationService service;

    @BeforeEach
    void setUp() {
        store = new JsonFileAgentStateStore(directory);
        service = new VerificationService(store);
        var state = AgentState.builder().build();
        task = state.getTasksContext();
        task.beginTask(new TaskContextState.Scope("task", "Build", "request:1"), 0);
        var requirement =
                task.propose(
                        TaskRequirement.Kind.ACCEPTANCE_CRITERION,
                        "Selected check exits zero",
                        "request:1");
        criterion = requirement.id();
        task.decide(
                criterion,
                TaskRequirement.Status.CONFIRMED,
                new TaskRequirement.Decision(TaskRequirement.Authority.CALLER, "policy:1"),
                task.getRevision());
        task.setSubjectVersion("workspace:v1", task.getRevision());
        context =
                RuntimeContext.builder()
                        .userId("user")
                        .sessionId("session")
                        .agentState(state)
                        .build();
    }

    private StoredActionObservation shell(Integer exit, boolean truncated) {
        var details =
                new ToolExecutionDetails(
                        "shell",
                        exit == null
                                ? ToolExecutionDetails.Outcome.UNKNOWN
                                : exit == 0
                                        ? ToolExecutionDetails.Outcome.SUCCEEDED
                                        : ToolExecutionDetails.Outcome.FAILED,
                        exit,
                        truncated);
        var action =
                new ActionObservation(
                        "action",
                        "call",
                        "execute",
                        "agent",
                        "user",
                        "session",
                        1,
                        2L,
                        exit != null && exit == 0
                                ? ActionObservation.Status.RETURNED
                                : ActionObservation.Status.FAILED,
                        null,
                        details,
                        task.getEvidenceBinding());
        var result =
                ToolResultBlock.text("raw log")
                        .withIdAndName("call", "execute")
                        .withExecutionDetails(details)
                        .withState(
                                exit != null && exit == 0
                                        ? ToolResultState.SUCCESS
                                        : ToolResultState.ERROR);
        new StateStoreActionObserver(store).record(action, result).block();
        return new StoredActionObservation(action, result);
    }

    @Test
    void persistsResultAndProjectsItWithoutClaimingTaskCompletion() {
        shell(0, false);
        var report =
                service.verify(
                                context,
                                criterion,
                                "action",
                                new ShellExitCodeVerifier("execute", 0))
                        .block();
        assertEquals(Outcome.PASSED, report.outcome());
        assertEquals(
                report,
                new VerificationService(new JsonFileAgentStateStore(directory))
                        .load("user", "session", report.id())
                        .orElseThrow()
                        .verification());
        String projection = TaskContextProjection.project(List.of(), task).get(0).getTextContent();
        assertTrue(projection.contains("| PASSED"));
        assertTrue(projection.contains("not an overall task completion verdict"));
        assertTrue(task.getTasks().isEmpty());
        store.save("user", "session", "state", context.getAgentState());
        var restored =
                new JsonFileAgentStateStore(directory)
                        .get("user", "session", "state", AgentState.class)
                        .orElseThrow();
        assertEquals(task, restored.getTasksContext());
    }

    @Test
    void versionChangeMakesOldEvidenceStaleAndCannotBeRebound() {
        shell(0, false);
        service.verify(context, criterion, "action", new ShellExitCodeVerifier("execute", 0))
                .block();
        task.invalidateSubject(task.getRevision());
        task.setSubjectVersion("workspace:v2", task.getRevision());
        assertTrue(
                TaskContextProjection.project(List.of(), task)
                        .get(0)
                        .getTextContent()
                        .contains("| STALE"));
        assertThrows(
                ConcurrentModificationException.class,
                () ->
                        service.verify(
                                        context,
                                        criterion,
                                        "action",
                                        new ShellExitCodeVerifier("execute", 0))
                                .block());
        task.setSubjectVersion("workspace:v1", task.getRevision());
        assertTrue(
                TaskContextProjection.project(List.of(), task)
                        .get(0)
                        .getTextContent()
                        .contains("| STALE"));
    }

    @Test
    void candidateAndOtherSessionCannotUseEvidence() {
        shell(0, false);
        var candidate =
                task.propose(
                        TaskRequirement.Kind.ACCEPTANCE_CRITERION, "Another check", "message:2");
        assertThrows(
                IllegalArgumentException.class,
                () ->
                        service.verify(
                                        context,
                                        candidate.id(),
                                        "action",
                                        new ShellExitCodeVerifier("execute", 0))
                                .block());
        var other = RuntimeContext.builder(context).sessionId("other").build();
        assertThrows(
                IllegalArgumentException.class,
                () ->
                        service.verify(
                                        other,
                                        criterion,
                                        "action",
                                        new ShellExitCodeVerifier("execute", 0))
                                .block());
        assertTrue(task.getVerifications().isEmpty());
    }

    @Test
    void executionBoundaryCapturesVersionBeforeToolRuns() {
        var initialBinding = task.getEvidenceBinding();
        var actionId = new AtomicReference<String>();
        var recorder = new StateStoreActionObserver(store);
        ActionObserver observer =
                (observation, result) -> {
                    actionId.set(observation.actionId());
                    return recorder.record(observation, result);
                };
        var rc = RuntimeContext.builder(context).put(ActionObserver.CONTEXT_KEY, observer).build();
        var param =
                ToolCallParam.builder()
                        .runtimeContext(rc)
                        .toolUseBlock(
                                ToolUseBlock.builder()
                                        .id("call")
                                        .name("execute")
                                        .input(Map.of())
                                        .content("{}")
                                        .build())
                        .build();
        ActionObservations.observe(
                        param,
                        () -> {
                            task.setSubjectVersion("workspace:v2", task.getRevision());
                            return Mono.just(
                                    ToolResultBlock.text("result")
                                            .withExecutionDetails(
                                                    new ToolExecutionDetails(
                                                            "shell",
                                                            ToolExecutionDetails.Outcome.SUCCEEDED,
                                                            0,
                                                            false)));
                        })
                .block();
        assertEquals(
                initialBinding,
                recorder.load("user", "session", actionId.get(), false)
                        .orElseThrow()
                        .observation()
                        .evidenceBinding());
        assertThrows(
                ConcurrentModificationException.class,
                () ->
                        service.verify(
                                        context,
                                        criterion,
                                        actionId.get(),
                                        new ShellExitCodeVerifier("execute", 0))
                                .block());
    }

    @Test
    void truncationIsUnknown() {
        shell(0, true);
        assertEquals(
                Outcome.UNKNOWN,
                service.verify(
                                context,
                                criterion,
                                "action",
                                new ShellExitCodeVerifier("execute", 0))
                        .block()
                        .outcome());
    }

    @Test
    void mismatchingExitIsFailure() {
        shell(2, false);
        assertEquals(
                Outcome.FAILED,
                service.verify(
                                context,
                                criterion,
                                "action",
                                new ShellExitCodeVerifier("execute", 0))
                        .block()
                        .outcome());
    }

    @Test
    void missingExitIsUnknown() {
        shell(null, false);
        assertEquals(
                Outcome.UNKNOWN,
                service.verify(
                                context,
                                criterion,
                                "action",
                                new ShellExitCodeVerifier("execute", 0))
                        .block()
                        .outcome());
    }

    @Test
    void verifierExceptionIsAnErrorNotAPass() {
        shell(0, false);
        var report =
                service.verify(
                                context,
                                criterion,
                                "action",
                                new DeterministicVerifier() {
                                    public String id() {
                                        return "broken";
                                    }

                                    public Verdict verify(StoredActionObservation evidence) {
                                        throw new IllegalStateException("secret");
                                    }
                                })
                        .block();
        assertEquals(Outcome.ERROR, report.outcome());
        assertTrue(!report.reason().contains("secret"));
    }

    @Test
    void stateChangeDuringCheckCannotAttachResult() {
        shell(0, false);
        assertThrows(
                ConcurrentModificationException.class,
                () ->
                        service.verify(
                                        context,
                                        criterion,
                                        "action",
                                        new DeterministicVerifier() {
                                            public String id() {
                                                return "changing";
                                            }

                                            public Verdict verify(
                                                    StoredActionObservation evidence) {
                                                task.invalidateSubject(task.getRevision());
                                                return new Verdict(Outcome.PASSED, "old version");
                                            }
                                        })
                                .block());
        assertTrue(task.getVerifications().isEmpty());
    }

    @Test
    void rejectedCasCommitCannotUpdateTaskState() {
        shell(0, false);
        var conflicting = spy(store);
        when(conflicting.supportsVersioning()).thenReturn(true);
        doReturn(-1L)
                .when(conflicting)
                .saveIfVersion(
                        anyString(),
                        anyString(),
                        anyString(),
                        any(StoredVerification.class),
                        eq(0L));
        assertThrows(
                IllegalStateException.class,
                () ->
                        new VerificationService(conflicting)
                                .verify(
                                        context,
                                        criterion,
                                        "action",
                                        new ShellExitCodeVerifier("execute", 0))
                                .block());
        assertTrue(task.getVerifications().isEmpty());
    }

    @Test
    void cancellationBeforeCommitCannotAttachPassingResult() throws Exception {
        shell(0, false);
        var entered = new CountDownLatch(1);
        var release = new CountDownLatch(1);
        var finished = new CountDownLatch(1);
        var subscription =
                service.verify(
                                context,
                                criterion,
                                "action",
                                new DeterministicVerifier() {
                                    public String id() {
                                        return "cancellable";
                                    }

                                    public Verdict verify(StoredActionObservation evidence) {
                                        entered.countDown();
                                        try {
                                            release.await(5, TimeUnit.SECONDS);
                                            return new Verdict(Outcome.PASSED, "result");
                                        } catch (InterruptedException error) {
                                            Thread.currentThread().interrupt();
                                            return new Verdict(Outcome.UNKNOWN, "interrupted");
                                        } finally {
                                            finished.countDown();
                                        }
                                    }
                                })
                        .subscribe();
        try {
            assertTrue(entered.await(5, TimeUnit.SECONDS));
            subscription.dispose();
            release.countDown();
            assertTrue(finished.await(5, TimeUnit.SECONDS));
            assertTrue(task.getVerifications().isEmpty());
        } finally {
            subscription.dispose();
            release.countDown();
        }
    }

    @Test
    void failedEvidenceCommitCannotUpdateTaskState() {
        shell(0, false);
        var failing = spy(store);
        doThrow(new IllegalStateException("store unavailable"))
                .when(failing)
                .save(anyString(), anyString(), anyString(), any(StoredVerification.class));
        assertThrows(
                IllegalStateException.class,
                () ->
                        new VerificationService(failing)
                                .verify(
                                        context,
                                        criterion,
                                        "action",
                                        new ShellExitCodeVerifier("execute", 0))
                                .block());
        assertTrue(task.getVerifications().isEmpty());
    }

    private StoredActionObservation json(String output) {
        var action =
                new ActionObservation(
                        "json-action",
                        "json-call",
                        "json-tool",
                        "agent",
                        "user",
                        "session",
                        1,
                        2L,
                        ActionObservation.Status.RETURNED,
                        null,
                        null,
                        task.getEvidenceBinding());
        return new StoredActionObservation(
                action, ToolResultBlock.text(output).withIdAndName("json-call", "json-tool"));
    }

    @Test
    void jsonVerifierUsesStrictOfflineSchemaWithoutNormalizingNulls() {
        var verifier =
                new JsonResultSchemaVerifier(
                        Map.of(
                                "type",
                                "object",
                                "properties",
                                Map.of("name", Map.of("type", "string")),
                                "additionalProperties",
                                false));
        assertEquals(Outcome.PASSED, verifier.verify(json("{\"name\":\"ok\"}")).outcome());
        assertEquals(Outcome.FAILED, verifier.verify(json("{\"name\":null}")).outcome());
        assertEquals(Outcome.FAILED, verifier.verify(json("{} {}")).outcome());
        assertThrows(
                IllegalArgumentException.class,
                () -> new JsonResultSchemaVerifier(Map.of("$ref", "https://example.com/schema")));
    }
}
