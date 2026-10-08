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
package io.agentscope.builder.web.managed;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import io.agentscope.builder.web.catalog.HarnessAgentBuildService;
import io.agentscope.builder.web.persistence.jpa.SessionTurnCommandRepository;
import io.agentscope.core.agent.RuntimeContext;
import io.agentscope.core.message.UserMessage;
import io.agentscope.core.session.InMemorySessionLogStore;
import io.agentscope.core.session.SessionEvent;
import io.agentscope.core.session.SessionExecution;
import io.agentscope.core.session.SessionInbox;
import io.agentscope.core.session.SessionKey;
import io.agentscope.core.session.SessionLog;
import io.agentscope.core.session.SessionProjection;
import io.agentscope.core.session.SessionRecorder;
import io.agentscope.core.session.SessionRecovery;
import io.agentscope.core.session.SessionTurns;
import io.agentscope.core.state.AgentState;
import io.agentscope.core.util.JsonUtils;
import io.agentscope.harness.agent.filesystem.remote.store.InMemoryStore;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.web.server.ResponseStatusException;

class AgentSessionRecoveryTest {
    private SessionLog log(String id) {
        return new InMemorySessionLogStore()
                .open(
                        new SessionKey("u", "a", id),
                        RuntimeContext.builder().userId("u").sessionId(id).build());
    }

    private ManagedSessionDto session(String id) {
        var session = mock(ManagedSessionDto.class);
        when(session.id()).thenReturn(id);
        when(session.ownerId()).thenReturn("u");
        when(session.agentId()).thenReturn("a");
        return session;
    }

    @Test
    void steeringIsConsumedOnceAndTerminalAdmissionRejectsNewInput() {
        var sessions = mock(DataSessionService.class);
        var logs = mock(SessionNativeLogService.class);
        var session = session("s");
        var log = log("s");
        when(sessions.get("u", "s")).thenReturn(session);
        when(logs.open(session)).thenReturn(log);
        var service =
                new SessionInputService(
                        sessions, logs, new SessionFileService(new InMemoryStore(), 1000));
        var state = AgentState.builder().sessionId("s").userId("u").build();
        var recorder = new SessionRecorder(log, "t", "r", null);
        try {
            var execution =
                    new SessionExecution(
                            log,
                            new SessionInbox.Command(
                                    "submit",
                                    "submit",
                                    "t",
                                    List.of(new UserMessage("first")),
                                    List.of()));
            execution.prepare(recorder);
            recorder.start(execution.inputs(), state).block();
            var input = new AgentSessionInput("correction", null);
            var receipt = service.accept("u", "s", "t", "steer", "key", input);
            assertThat(receipt.status()).isEqualTo("accepted");
            execution.beforeStep(recorder, state);
            execution.beforeStep(recorder, state);
            assertThat(
                            state.getContext().stream()
                                    .filter(m -> m.getTextContent().equals("correction")))
                    .hasSize(1);
            assertThat(service.accept("u", "s", "t", "steer", "key", input).status())
                    .isEqualTo("applied");
            execution.close(recorder);
            assertThatThrownBy(() -> service.accept("u", "s", "t", "steer", "new", input))
                    .isInstanceOf(ResponseStatusException.class);
            assertThat(service.accept("u", "s", "t", "steer", "key", input).input_id())
                    .isEqualTo(receipt.input_id());
            assertThatThrownBy(
                            () ->
                                    service.accept(
                                            "u",
                                            "s",
                                            "t",
                                            "steer",
                                            "key",
                                            new AgentSessionInput("changed", null)))
                    .isInstanceOf(ResponseStatusException.class);
            recorder.finish(state, "completed").block();
        } finally {
            recorder.close();
        }
    }

    @Test
    void cancellationClosesInterruptedTaskAndItsUnconsumedSteering() {
        var log = log("s");
        var state = AgentState.builder().sessionId("s").userId("u").build();
        var recorder = new SessionRecorder(log, "t", "r", null);
        try {
            recorder.start(List.of(new UserMessage("first")), state).block();
            new SessionInbox(log)
                    .locked(
                            tx ->
                                    tx.accept(
                                            new SessionInbox.Command(
                                                    "late",
                                                    "steer",
                                                    "t",
                                                    List.of(new UserMessage("late")),
                                                    List.of())));
            recorder.finish(state, "interrupted").block();
        } finally {
            recorder.close();
        }
        SessionRecovery.cancelPending(log, "t");
        long seq = log.head().seq();
        SessionRecovery.cancelPending(log, "t");
        assertThat(log.head().seq()).isEqualTo(seq);
        assertThat(SessionTurns.read(log).latest().ended()).isTrue();
        assertThat(new SessionInbox(log).snapshot().rejected()).containsKey("late");
    }

    @Test
    void forkAndRestoreCommitStateWithoutExposingItOrRewritingHistory() {
        var sessions = mock(DataSessionService.class);
        var logs = mock(SessionNativeLogService.class);
        var turns = mock(SessionTurnCommandRepository.class);
        var agents = mock(HarnessAgentBuildService.class);
        var source = session("s");
        var target = session("target");
        var sourceLog = log("s");
        var targetLog = log("target");
        when(sessions.get("u", "s")).thenReturn(source);
        when(sessions.get("u", "target")).thenReturn(target);
        when(logs.open(source)).thenReturn(sourceLog);
        when(logs.open(target)).thenReturn(targetLog);
        var writer = sourceLog.acquire("seed", Duration.ofMinutes(1));
        var state =
                AgentState.builder()
                        .userId("u")
                        .sessionId("s")
                        .context(List.of(new UserMessage("baseline")))
                        .build();
        sourceLog.commit(
                writer,
                "seed",
                0,
                List.of(
                        new SessionEvent(
                                1,
                                "checkpoint",
                                1,
                                123,
                                "state/checkpoint",
                                null,
                                null,
                                true,
                                JsonUtils.getJsonCodec()
                                        .toJson(
                                                Map.of(
                                                        "stateJson",
                                                        state.toJson(),
                                                        "reason",
                                                        "test")))));
        sourceLog.release(writer);
        var service = new SessionCheckpointService(sessions, logs, turns, agents);
        assertThat(service.list("u", "s", null, 100).toString())
                .contains("cp_checkpoint")
                .doesNotContain("baseline", "stateJson");
        var fork = service.restore("u", "s", "target", "cp_checkpoint", "fork", "branch");
        assertThat(service.restore("u", "s", "target", "cp_checkpoint", "fork", "branch"))
                .isEqualTo(fork);
        assertThat(SessionProjection.read(targetLog).restore().getSessionId()).isEqualTo("target");
        assertThat(sourceLog.head().seq()).isEqualTo(1);
        assertThat(targetLog.head().seq()).isEqualTo(1);
        assertThatThrownBy(
                        () ->
                                service.restore(
                                        "u", "s", "target", "cp_checkpoint", "new-key", "branch"))
                .isInstanceOf(ResponseStatusException.class);
        service.restore("u", "s", "s", "cp_checkpoint", "restore", "rollback");
        assertThat(sourceLog.head().seq()).isEqualTo(2);
        assertThat(SessionProjection.read(sourceLog).restore().getContext()).hasSize(1);
        verify(agents).discardSession("u", "s");
    }
}
