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
package io.agentscope.builder.web.managed.selfhosted;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import io.agentscope.builder.web.managed.SessionEventDto;
import io.agentscope.builder.web.managed.SessionEventTypes;
import io.agentscope.builder.web.managed.SessionTurnRunner;
import io.agentscope.builder.web.managed.service.SessionEventLog;
import io.agentscope.core.message.TextBlock;
import io.agentscope.core.message.ToolResultBlock;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * Data-plane slice of the self-hosted hands tests: pending tool tracking over the session event
 * log, tool-result payload mapping, and the built-in tool schemas. The worker-side executor and
 * input stager are covered by {@code SelfHostedHandsUnitTest} in {@code service-scheduler}.
 */
class SelfHostedHandsDataPlaneTest {

    @Test
    void pendingHandsToolServiceTracksPendingUntilResult() {
        SessionEventLog log = mock(SessionEventLog.class);
        List<SessionEventDto> events = new ArrayList<>();
        events.add(
                new SessionEventDto(
                        "evt_1",
                        "s1",
                        1L,
                        SessionEventTypes.AGENT_TOOL_USE,
                        Map.of(
                                "id",
                                "tu_1",
                                "name",
                                "execute",
                                "input",
                                Map.of("command", "echo hi"),
                                "state",
                                "pending"),
                        1L,
                        1L));
        when(log.list("s1")).thenAnswer(inv -> List.copyOf(events));

        PendingHandsToolService service = new PendingHandsToolService(log);
        assertThat(service.listPending("s1")).hasSize(1);
        assertThat(service.listPending("s1").get(0).get("id")).isEqualTo("tu_1");

        events.add(
                new SessionEventDto(
                        "evt_2",
                        "s1",
                        2L,
                        SessionEventTypes.USER_TOOL_RESULT,
                        Map.of("tool_use_id", "tu_1", "content", "hi", "is_error", false),
                        2L,
                        2L));
        assertThat(service.listPending("s1")).isEmpty();
    }

    @Test
    void toolResultFromPayloadBuildsErrorMetadata() {
        ToolResultBlock ok =
                SessionTurnRunner.toolResultFromPayload(
                        Map.of("tool_use_id", "tu_1", "name", "execute", "content", "ok"));
        assertThat(ok.getId()).isEqualTo("tu_1");
        assertThat(((TextBlock) ok.getOutput().get(0)).getText()).isEqualTo("ok");

        ToolResultBlock err =
                SessionTurnRunner.toolResultFromPayload(
                        Map.of(
                                "tool_use_id",
                                "tu_2",
                                "name",
                                "execute",
                                "content",
                                "boom",
                                "is_error",
                                true));
        assertThat(err.getMetadata()).containsEntry("error", true);
    }

    @Test
    void selfHostedToolSchemasCoverBuiltins() {
        assertThat(SelfHostedToolSchemas.all()).isNotEmpty();
        assertThat(SelfHostedToolSchemas.all().stream().map(s -> s.getName()).toList())
                .contains(
                        "execute",
                        "read_file",
                        "write_file",
                        "edit_file",
                        "grep_files",
                        "glob_files");
    }
}
