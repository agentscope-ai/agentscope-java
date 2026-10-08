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
import static org.mockito.Mockito.when;

import io.agentscope.builder.web.managed.service.SessionEventLog;
import io.agentscope.core.session.SessionEvent;
import io.agentscope.core.util.JsonUtils;
import io.agentscope.harness.agent.filesystem.remote.store.InMemoryStore;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.web.server.ResponseStatusException;

class AgentSessionViewTest {
    private SessionEventDto event(long seq, String type, Map<String, Object> data) {
        var payload = new LinkedHashMap<>(data);
        payload.put("source", Map.of());
        return new SessionEventDto("e" + seq, "s", seq, type, payload, null, 123);
    }

    private List<SessionEventDto> timeline() {
        return List.of(
                event(
                        1,
                        "item.started",
                        Map.of(
                                "turn_id",
                                "t",
                                "run_id",
                                "r",
                                "item_id",
                                "item_m",
                                "item",
                                Map.of(
                                        "id",
                                        "item_m",
                                        "status",
                                        "in_progress",
                                        "content",
                                        List.of()))),
                event(
                        2,
                        "item.delta",
                        Map.of(
                                "turn_id",
                                "t",
                                "run_id",
                                "r",
                                "item_id",
                                "item_m",
                                "content",
                                List.of(
                                        Map.of("type", "text", "text", "Before "),
                                        Map.of(
                                                "type",
                                                "tool_use",
                                                "id",
                                                "call",
                                                "name",
                                                "search",
                                                "state",
                                                "PENDING",
                                                "content",
                                                "{\"q\":")))),
                event(
                        3,
                        "item.delta",
                        Map.of(
                                "turn_id",
                                "t",
                                "run_id",
                                "r",
                                "item_id",
                                "item_m",
                                "content",
                                List.of(Map.of("type", "tool_use", "content", "\"test\"}")))),
                event(
                        4,
                        "tool.dispatched",
                        Map.of(
                                "turn_id",
                                "t",
                                "run_id",
                                "r",
                                "tool_call_id",
                                "call",
                                "input",
                                Map.of("q", "test"))),
                event(
                        5,
                        "tool.delta",
                        Map.of(
                                "turn_id",
                                "t",
                                "run_id",
                                "r",
                                "tool_call_id",
                                "call",
                                "output",
                                List.of(Map.of("type", "text", "text", "progress")))),
                event(
                        6,
                        "tool.completed",
                        Map.of(
                                "turn_id",
                                "t",
                                "run_id",
                                "r",
                                "tool_call_id",
                                "call",
                                "result",
                                Map.of(
                                        "type",
                                        "tool_result",
                                        "id",
                                        "call",
                                        "state",
                                        "SUCCESS",
                                        "output",
                                        List.of(Map.of("type", "text", "text", "result"))))),
                event(
                        7,
                        "item.completed",
                        Map.of(
                                "turn_id",
                                "t",
                                "run_id",
                                "r",
                                "item_id",
                                "item_m",
                                "item",
                                Map.of(
                                        "id",
                                        "item_m",
                                        "status",
                                        "completed",
                                        "content",
                                        List.of(
                                                Map.of("type", "text", "text", "Before "),
                                                Map.of(
                                                        "type",
                                                        "tool_use",
                                                        "id",
                                                        "call",
                                                        "input",
                                                        Map.of("q", "test")))))),
                event(
                        8,
                        "item.completed",
                        Map.of(
                                "turn_id",
                                "t",
                                "run_id",
                                "r",
                                "item_id",
                                "item_final",
                                "item",
                                Map.of(
                                        "id",
                                        "item_final",
                                        "status",
                                        "completed",
                                        "content",
                                        List.of(Map.of("type", "text", "text", "Done"))))));
    }

    @Test
    void failedModelKeepsPartialItemIncompleteAndUnreportedCostUnknown() {
        var view = new AgentSessionView(Map.of());
        timeline().subList(0, 2).forEach(view::apply);
        view.apply(
                event(
                        3,
                        "model.completed",
                        Map.of(
                                "item_id",
                                "item_m",
                                "model_call_id",
                                "failed",
                                "model",
                                "test",
                                "status",
                                "failed")));
        view.apply(
                event(
                        4,
                        "usage.recorded",
                        Map.of(
                                "model_call_id",
                                "ok",
                                "model",
                                "test",
                                "usage",
                                Map.of("inputTokens", 2, "time", 0.25))));
        view.apply(event(5, "run.ended", Map.of("run_id", "r", "status", "completed")));
        assertThat(view.resources("s").get("items").toString()).contains("status=incomplete");
        var usage = (Map<String, Object>) view.resources("s").get("usage");
        assertThat(usage.get("model_calls")).isEqualTo(2);
        assertThat(((Map<?, ?>) usage.get("totals")).get("time")).isEqualTo(0.25);
        var pricer =
                new SessionUsagePricingConfiguration()
                        .sessionUsagePricer(
                                "{\"test\":{\"input_per_million\":1,\"output_per_million\":2}}",
                                "USD");
        var budget =
                new SessionBudgetService(
                        new InMemoryStore(),
                        pricer,
                        mock(SessionEventLog.class),
                        mock(SessionNativeLogService.class));
        assertThat(budget.priceUsage(usage))
                .containsEntry("cost_complete", false)
                .containsEntry("unpriced_calls", 1L);
    }

    @Test
    void partialToolSnapshotAndEveryReconnectPrefixConvergeWithoutMutatingCache() {
        var events = timeline();
        var full = new AgentSessionView(Map.of());
        events.forEach(full::apply);
        for (int split = 0; split < events.size(); split++) {
            var prefix = new AgentSessionView(Map.of());
            events.subList(0, split).forEach(prefix::apply);
            String frozen = JsonUtils.getJsonCodec().toJson(prefix.state());
            var resumed = new AgentSessionView(prefix.state());
            events.subList(split, events.size()).forEach(resumed::apply);
            assertThat(JsonUtils.getJsonCodec().toJson(resumed.resources("s")))
                    .isEqualTo(JsonUtils.getJsonCodec().toJson(full.resources("s")));
            assertThat(JsonUtils.getJsonCodec().toJson(prefix.state())).isEqualTo(frozen);
        }
        assertThat(full.resources("s").get("tools").toString())
                .contains("status=success", "q=test", "result", "progress")
                .doesNotContain("null:");
        assertThat((List<?>) full.resources("s").get("items")).hasSize(2);
    }

    @Test
    void rejectionRestoresOnlyStillPendingActions() {
        var view = new AgentSessionView(Map.of());
        view.apply(
                event(
                        1,
                        "required_action.created",
                        Map.of("request_id", "a", "kind", "confirmation")));
        view.apply(
                event(2, "required_action.accepted", Map.of("request_id", "a", "command_id", "c")));
        view.apply(
                event(
                        3,
                        "required_action.rejected",
                        Map.of("request_id", "a", "command_id", "c", "pending", true)));
        assertThat(view.resources("s").get("required_actions").toString())
                .contains("kind=confirmation");
        view.apply(event(4, "required_action.resolved", Map.of("request_id", "a")));
        view.apply(
                event(
                        5,
                        "required_action.rejected",
                        Map.of("request_id", "a", "command_id", "c", "pending", false)));
        assertThat((List<?>) view.resources("s").get("required_actions")).isEmpty();
    }

    @Test
    void publicChunksExcludePrivateThinkingAndAuxiliaryCalls() {
        var data =
                Map.of(
                        "purpose",
                        "REASONING",
                        "modelCallId",
                        "m",
                        "chunk",
                        Map.of(
                                "id",
                                "msg",
                                "content",
                                List.of(
                                        Map.of("type", "text", "text", "visible"),
                                        Map.of("type", "thinking", "thinking", "secret"))));
        var nativeEvent =
                new SessionEvent(
                        1,
                        "e",
                        1,
                        123,
                        "model/chunk",
                        "r",
                        "t",
                        true,
                        JsonUtils.getJsonCodec().toJson(data));
        var projected = CommittedSessionEventProjector.project(nativeEvent).orElseThrow();
        assertThat(projected.payload()).containsEntry("item_id", "item_model_m");
        assertThat(projected.payload().toString()).contains("visible").doesNotContain("secret");
        assertThat(
                        CommittedSessionEventProjector.project(
                                new SessionEvent(
                                        1,
                                        "e",
                                        1,
                                        123,
                                        "model/chunk",
                                        "r",
                                        "t",
                                        true,
                                        "{\"purpose\":\"COMPACTION\"}")))
                .isEmpty();
    }

    @Test
    void resourcePagesKeepTheirOriginalWatermarkWhileNewEventsArrive() {
        var events = mock(SessionEventLog.class);
        var facts = timeline();
        when(events.highWatermark("s")).thenReturn(8L);
        when(events.page("s", 0, 8, 256)).thenReturn(facts);
        var store = new AgentSessionViewStore(new InMemoryStore(), events);
        var first = store.page("s", "items", null, 1);
        when(events.highWatermark("s")).thenReturn(9L);
        var second = store.page("s", "items", (String) first.get("next_cursor"), 1);
        assertThat(second.get("as_of")).isEqualTo(first.get("as_of"));
        assertThat(second.get("has_more")).isEqualTo(false);
        assertThatThrownBy(() -> store.page("other", "items", (String) first.get("next_cursor"), 1))
                .isInstanceOf(ResponseStatusException.class);
    }
}
