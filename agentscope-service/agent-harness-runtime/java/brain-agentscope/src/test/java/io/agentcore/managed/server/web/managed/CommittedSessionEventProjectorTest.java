/*
 * Copyright 2024-2026 the original author or authors.
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *     http://www.apache.org/licenses/LICENSE-2.0
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package io.agentcore.managed.server.web.managed;

import static org.assertj.core.api.Assertions.assertThat;

import io.agentscope.core.session.SessionEvent;
import io.agentscope.core.util.JsonUtils;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class CommittedSessionEventProjectorTest {
    @Test
    void nativeToolMessageCompletesEachRealToolOnceAndWaitingRemainsAnItemOnly() {
        var result =
                Map.of(
                        "type",
                        "tool_result",
                        "id",
                        "call",
                        "name",
                        "execute",
                        "state",
                        "success",
                        "output",
                        List.of(Map.of("type", "text", "text", "actual output")));
        var waiting =
                Map.of(
                        "type",
                        "tool_result",
                        "id",
                        "pending",
                        "state",
                        "suspended",
                        "output",
                        List.of());
        var message =
                Map.of(
                        "message",
                        Map.of(
                                "id",
                                "tool-message",
                                "role",
                                "tool",
                                "content",
                                List.of(result, waiting)));
        var fact = event("tool/result", message);
        var projected = CommittedSessionEventProjector.projectAll(fact);
        assertThat(projected)
                .extracting(CommittedSessionEventProjector.Event::type)
                .containsExactly("tool.completed", "item.completed");
        assertThat(projected.get(0).payload())
                .containsEntry("tool_call_id", "call")
                .containsEntry("status", "completed")
                .doesNotContainKey("item");
        assertThat(projected.get(0).payload().get("result").toString()).contains("actual output");
        assertThat(projected.get(1).payload().toString()).contains("actual output");
        var replay =
                new SessionEvent(
                        1,
                        "another-event",
                        8,
                        2,
                        "tool/result",
                        "new-run",
                        "turn",
                        true,
                        JsonUtils.getJsonCodec().toJson(message));
        assertThat(CommittedSessionEventProjector.projectionId(fact, projected.get(0)))
                .isEqualTo(
                        CommittedSessionEventProjector.projectionId(
                                replay, CommittedSessionEventProjector.projectAll(replay).get(0)));
        assertThat(
                        CommittedSessionEventProjector.projectAll(
                                event(
                                        "tool/result",
                                        Map.of(
                                                "message",
                                                Map.of(
                                                        "id",
                                                        "waiting",
                                                        "content",
                                                        List.of(waiting))))))
                .extracting(CommittedSessionEventProjector.Event::type)
                .containsExactly("item.completed");
    }

    @Test
    void suspendedActionIsWaitingAndCarriesNoInventedResult() {
        var fact =
                event(
                        "action/end",
                        Map.of(
                                "toolCallId",
                                "call",
                                "observation",
                                Map.of("status", "SUSPENDED"),
                                "result",
                                Map.of(
                                        "type",
                                        "tool_result",
                                        "state",
                                        "suspended",
                                        "output",
                                        List.of())));
        var projected = CommittedSessionEventProjector.project(fact).orElseThrow();
        assertThat(projected.type()).isEqualTo("tool.waiting");
        assertThat(projected.payload())
                .containsEntry("status", "waiting")
                .doesNotContainKey("result");
    }

    @Test
    void actualExternalResultCompletesBeforeActionResolutionWithStableDistinctIds() {
        for (boolean error : List.of(false, true)) {
            var fact =
                    event(
                            "interaction/resolved",
                            Map.of(
                                    "requestId",
                                    "call",
                                    "kind",
                                    "external_execution",
                                    "result",
                                    Map.of(
                                            "type",
                                            "tool_result",
                                            "id",
                                            "call",
                                            "name",
                                            "execute",
                                            "state",
                                            "running",
                                            "metadata",
                                            Map.of("error", error, "secret", "hidden"),
                                            "output",
                                            List.of(
                                                    Map.of(
                                                            "type",
                                                            "text",
                                                            "text",
                                                            "real output")))));
            var projected = CommittedSessionEventProjector.projectAll(fact);
            assertThat(projected)
                    .extracting(CommittedSessionEventProjector.Event::type)
                    .containsExactly("tool.completed", "required_action.resolved");
            assertThat(projected.get(0).payload())
                    .containsEntry("tool_call_id", "call")
                    .containsEntry("status", error ? "failed" : "completed");
            assertThat(projected.toString()).contains("real output").doesNotContain("hidden");
            assertThat(CommittedSessionEventProjector.projectionId(fact, projected.get(0)))
                    .isNotEqualTo(
                            CommittedSessionEventProjector.projectionId(fact, projected.get(1)));
            assertThat(CommittedSessionEventProjector.projectAll(fact)).isEqualTo(projected);
        }
    }

    @Test
    void hostSuspensionRecordedAsFailedAttemptDoesNotCompleteTheModelTool() {
        var waiting =
                event(
                        "action/end",
                        Map.of(
                                "toolCallId",
                                "call",
                                "observation",
                                Map.of("status", "FAILED", "errorType", "ManagedToolWaiting")));
        var projection = CommittedSessionEventProjector.projectAll(waiting);
        assertThat(projection)
                .extracting(CommittedSessionEventProjector.Event::type)
                .containsExactly("tool.waiting");
        assertThat(projection.get(0).payload())
                .containsEntry("status", "waiting")
                .doesNotContainKey("result");
        for (String status : List.of("RETURNED", "FAILED", "CANCELLED")) {
            var ended =
                    event(
                            "action/end",
                            Map.of("toolCallId", "call", "observation", Map.of("status", status)));
            assertThat(CommittedSessionEventProjector.projectAll(ended)).isEmpty();
        }
    }

    @Test
    void confirmationAnswerDoesNotInventToolCompletion() {
        var fact =
                event(
                        "interaction/resolved",
                        Map.of(
                                "kind",
                                "confirmation",
                                "requestId",
                                "call",
                                "result",
                                Map.of("allow", true)));
        assertThat(CommittedSessionEventProjector.projectAll(fact))
                .extracting(CommittedSessionEventProjector.Event::type)
                .containsExactly("required_action.resolved");
    }

    @Test
    void onlyRealCompactionCanReportCompactedAndPrivateContextIsExcluded() {
        var replacement =
                event("context/replaced", Map.of("reason", "before_model", "messages", "private"));
        var update = CommittedSessionEventProjector.project(replacement).orElseThrow();
        assertThat(update.type()).isEqualTo("context.updated");
        assertThat(update.payload().toString()).doesNotContain("private");
        var compacted =
                event(
                        "compaction/end",
                        Map.of(
                                "status",
                                "completed",
                                "beforeMsgCount",
                                10,
                                "afterMsgCount",
                                3,
                                "beforeTokenCount",
                                1000,
                                "afterTokenCount",
                                300));
        assertThat(CommittedSessionEventProjector.project(compacted).orElseThrow().type())
                .isEqualTo("context.compacted");
        var failed = event("compaction/end", Map.of("status", "failed", "beforeMsgCount", 10));
        assertThat(CommittedSessionEventProjector.project(failed).orElseThrow().type())
                .isEqualTo("context.compaction_ended");
    }

    @Test
    void finalOutputConfirmsTheSameItemWithoutRepeatingItsCompletedEvent() {
        var message =
                Map.of(
                        "message",
                        Map.of("id", "message", "role", "assistant", "content", List.of()),
                        "modelCallId",
                        "model");
        var complete =
                CommittedSessionEventProjector.project(event("message/assistant", message))
                        .orElseThrow();
        var finalOutput =
                CommittedSessionEventProjector.project(event("turn/output", message)).orElseThrow();
        assertThat(complete.type()).isEqualTo("item.completed");
        assertThat(finalOutput.type()).isEqualTo("item.finalized");
        assertThat(finalOutput.payload())
                .containsEntry("item_id", complete.payload().get("item_id"))
                .containsEntry("final_output", true);
    }

    private static SessionEvent event(String type, Map<String, ?> data) {
        return new SessionEvent(
                1, "event", 1, 1, type, "run", "turn", true, JsonUtils.getJsonCodec().toJson(data));
    }
}
