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

import io.agentscope.builder.control.ControlPlaneClient.ManagedExecutionScope;
import io.agentscope.core.session.SessionEvent;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class LegacySessionEventAdapterTest {
    @Test
    void coordinationFenceHasExplicitNamesSeparateFromLogicalTurnAndExecution() {
        var fence = new ManagedExecutionScope("tenant", "task", "attempt", 4, "cp-turn");
        var data = SessionTurnRunner.executionCorrelation("run", "logical-turn", fence);
        assertThat(data).containsEntry("run_id", "run").containsEntry("turn_id", "logical-turn");
        assertThat(data.get("coordination"))
                .isEqualTo(
                        Map.of(
                                "control_plane_turn_id",
                                "cp-turn",
                                "orchestration_attempt_id",
                                "attempt",
                                "agent_task_id",
                                "task",
                                "dispatch_generation",
                                4L));
        assertThat(fence.turnId()).isEqualTo("cp-turn");
    }

    @Test
    void publicExecutionBoundariesExposeRunIdentityWithoutInventingTurnOutcomes() {
        for (String type : List.of("run/start", "run/end")) {
            var fact =
                    new SessionEvent(
                            1,
                            "event",
                            1,
                            123,
                            type,
                            "execution",
                            "logical",
                            true,
                            "{\"status\":\"suspended\",\"reason\":\"TOOL_SUSPENDED\"}");
            var projected = CommittedSessionEventProjector.project(fact).orElseThrow();
            assertThat(projected.type())
                    .isEqualTo(type.equals("run/start") ? "run.started" : "run.ended");
            assertThat(projected.payload())
                    .containsEntry("turn_id", "logical")
                    .containsEntry("run_id", "execution");
        }
        for (String type :
                List.of(
                        "turn/start",
                        "turn/end",
                        "turn/resumed",
                        "turn/completed",
                        "turn/suspended"))
            assertThat(
                            CommittedSessionEventProjector.project(
                                    new SessionEvent(
                                            1,
                                            "event",
                                            1,
                                            123,
                                            type,
                                            "execution",
                                            "logical",
                                            true,
                                            "{}")))
                    .isEmpty();
    }

    @Test
    void nativeOutputRevisionKeepsMessageIdentityWithoutReusingTheEventCursor() {
        var first = project("message/assistant", "one", 1, "first");
        var revision = project("turn/output", "two", 2, "final");
        assertThat(first.type()).isEqualTo(SessionEventTypes.AGENT_MESSAGE);
        assertThat(revision.payload())
                .containsEntry("message_id", first.payload().get("message_id"));
        assertThat(revision.payload())
                .containsEntry("text", "final")
                .containsEntry("final_output", true);
        assertThat(revision.id()).isEqualTo("two");
        assertThat(revision.seq()).isEqualTo(2);
        assertThat(revision.payload()).doesNotContainKey("source");
        var emptyFinal = project("turn/output", "empty", 3, "");
        assertThat(emptyFinal.type()).isEqualTo(SessionEventTypes.AGENT_MESSAGE);
        assertThat(emptyFinal.payload())
                .containsEntry("text", "")
                .containsEntry("final_output", true);
    }

    @Test
    void administrativeFactsWithoutExecutionIdentityStillProject() {
        var replacement =
                new SessionEvent(
                        1,
                        "admin-context",
                        1,
                        123,
                        "context/replaced",
                        null,
                        null,
                        true,
                        "{\"reason\":\"admin_reset\"}");
        var context = CommittedSessionEventProjector.project(replacement).orElseThrow();
        assertThat(context.type()).isEqualTo("context.compacted");
        assertThat(context.payload()).doesNotContainKey("turn_id");
        assertThat(context.payload().get("source"))
                .isEqualTo(Map.of("native_event_id", "admin-context", "native_seq", 1L));
        var message =
                new SessionEvent(
                        1,
                        "admin-message",
                        2,
                        123,
                        "message/user",
                        null,
                        null,
                        true,
                        "{\"message\":{\"id\":\"m\",\"role\":\"user\",\"content\":[]}}");
        var item = CommittedSessionEventProjector.project(message).orElseThrow();
        assertThat(item.type()).isEqualTo("item.completed");
        assertThat(item.payload()).doesNotContainKeys("turn_id", "replaces_preview_id");
    }

    @Test
    void unknownRowsKeepTheirSequenceSoOldClientsCanRepairContiguousHistory() {
        var event =
                new SessionEventDto(
                        "e",
                        "s",
                        17,
                        "turn.accepted",
                        Map.of("turn_id", "t", "source", Map.of("native_seq", 32)),
                        null,
                        123);
        var view = LegacySessionEventAdapter.adapt(event);
        assertThat(view.id()).isEqualTo(event.id());
        assertThat(view.seq()).isEqualTo(17);
        assertThat(view.type()).isEqualTo("turn.accepted");
        assertThat(view.payload()).doesNotContainKey("source");
    }

    @Test
    void toolResultBatchesCarryStatusesWithoutInventingPrivateOutput() {
        var event =
                new SessionEventDto(
                        "e",
                        "s",
                        4,
                        "item.completed",
                        Map.of(
                                "item",
                                Map.of(
                                        "id",
                                        "item_m",
                                        "type",
                                        "tool_result",
                                        "role",
                                        "tool",
                                        "content",
                                        List.of(
                                                Map.of(
                                                        "type",
                                                        "tool_result",
                                                        "id",
                                                        "a",
                                                        "state",
                                                        "SUCCESS"),
                                                Map.of(
                                                        "type",
                                                        "tool_result",
                                                        "id",
                                                        "b",
                                                        "state",
                                                        "ERROR")))),
                        null,
                        123);
        var view = LegacySessionEventAdapter.adapt(event);
        assertThat(view.type()).isEqualTo(SessionEventTypes.AGENT_TOOL_RESULT);
        assertThat((List<?>) view.payload().get("tool_results")).hasSize(2);
        assertThat(view.payload()).containsEntry("output", "Tool execution SUCCESS");
    }

    @Test
    void onlyPublicTextPreviewIsAdaptedAndRemainsUnnumbered() {
        var event =
                new SessionEventDto(
                        null,
                        "s",
                        -1,
                        "item.delta",
                        Map.of("preview_id", "turn-output-t", "delta", "hello"),
                        null,
                        123);
        var view = LegacySessionEventAdapter.adapt(event);
        assertThat(view.type()).isEqualTo(SessionEventTypes.EVENT_DELTA);
        assertThat(view.seq()).isEqualTo(-1);
        assertThat(view.payload())
                .containsEntry("type", SessionEventTypes.AGENT_MESSAGE)
                .containsEntry("event_id", "turn-output-t");
    }

    private SessionEventDto project(String type, String eventId, long seq, String text) {
        var source =
                new SessionEvent(
                        1,
                        eventId,
                        seq,
                        123,
                        type,
                        "run",
                        "turn",
                        true,
                        "{\"message\":{\"id\":\"same\",\"role\":\"assistant\",\"content\":[{\"type\":\"text\",\"text\":\""
                                + text
                                + "\"},{\"type\":\"thinking\",\"thinking\":\"private\"}]}}");
        var projected = CommittedSessionEventProjector.project(source).orElseThrow();
        var dto =
                new SessionEventDto(
                        eventId, "s", seq, projected.type(), projected.payload(), null, 123);
        var view = LegacySessionEventAdapter.adapt(dto);
        assertThat(view.payload().toString()).doesNotContain("private");
        return view;
    }
}
