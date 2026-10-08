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
package io.agentscope.builder.web.api;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import io.agentscope.builder.web.managed.AgentSessionView;
import io.agentscope.builder.web.managed.AgentSessionViewStore;
import io.agentscope.builder.web.managed.DataSessionService;
import io.agentscope.builder.web.managed.ManagedSessionDto;
import io.agentscope.builder.web.managed.SessionBudgetService;
import io.agentscope.builder.web.managed.SessionEventDto;
import io.agentscope.builder.web.managed.SessionEventPreviewBus;
import io.agentscope.builder.web.managed.SessionNativeLogService;
import io.agentscope.builder.web.managed.SessionUsageService;
import io.agentscope.builder.web.managed.service.SessionEventLog;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.security.core.Authentication;

class AgentSessionEventsControllerTest {
    @Test
    void snapshotSeparatesTurnOutcomeFromRunsAndReadsHistoricalUsage() {
        var sessions = mock(DataSessionService.class);
        var logs = mock(SessionEventLog.class);
        var auth = mock(Authentication.class);
        when(auth.getPrincipal()).thenReturn("u");
        when(sessions.get("u", "s")).thenReturn(mock(ManagedSessionDto.class));
        when(logs.list("s"))
                .thenReturn(
                        List.of(
                                event(1, "turn.requires_action", Map.of("turn_id", "t")),
                                event(
                                        2,
                                        "turn.ended",
                                        Map.of("turn_id", "t", "status", "completed")),
                                event(
                                        3,
                                        "run.ended",
                                        Map.of(
                                                "turn_id",
                                                "t",
                                                "run_id",
                                                "r",
                                                "status",
                                                "suspended",
                                                "source",
                                                Map.of())),
                                event(
                                        4,
                                        "usage.recorded",
                                        Map.of(
                                                "attempt_id",
                                                "m",
                                                "usage",
                                                Map.of("inputTokens", 5),
                                                "source",
                                                Map.of())),
                                event(
                                        5,
                                        "usage.recorded",
                                        Map.of(
                                                "model_call_id",
                                                "m",
                                                "usage",
                                                Map.of("inputTokens", 5),
                                                "source",
                                                Map.of()))));
        var views = mock(AgentSessionViewStore.class);
        var budgets = mock(SessionBudgetService.class);
        var view = new AgentSessionView(Map.of());
        logs.list("s").forEach(view::apply);
        when(views.read("s")).thenReturn(view.resources("s"));
        when(budgets.priceUsage(anyMap())).thenAnswer(call -> call.getArgument(0));
        var api =
                new AgentSessionEventsController(
                        sessions,
                        logs,
                        mock(SessionEventPreviewBus.class),
                        mock(SessionNativeLogService.class),
                        views,
                        budgets,
                        mock(SessionUsageService.class));
        var snapshot = api.snapshot("s", auth).block(Duration.ofSeconds(5));
        assertThat(snapshot.get("turns").toString())
                .contains("turn.requires_action")
                .doesNotContain("turn.ended");
        assertThat(snapshot.get("runs").toString())
                .contains("run_id=r", "status=suspended")
                .doesNotContain("source");
        var usage = (Map<?, ?>) snapshot.get("usage");
        assertThat(usage.get("model_calls")).isEqualTo(1);
        assertThat(usage.get("totals")).isEqualTo(Map.of("inputTokens", 5L));
    }

    private SessionEventDto event(long seq, String type, Map<String, Object> data) {
        return new SessionEventDto("e" + seq, "s", seq, type, data, null, 123);
    }
}
