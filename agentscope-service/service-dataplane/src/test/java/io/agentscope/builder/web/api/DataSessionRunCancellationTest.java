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

import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.agentscope.builder.web.api.error.ApiException;
import io.agentscope.builder.web.managed.DataSessionService;
import io.agentscope.builder.web.managed.SessionEventPreviewBus;
import io.agentscope.builder.web.managed.SessionEventTypes;
import io.agentscope.builder.web.managed.SessionTurnRunner;
import io.agentscope.builder.web.managed.service.HandsMetrics;
import io.agentscope.builder.web.managed.service.SessionEventLog;
import io.agentscope.builder.web.toolbus.ToolConfirmationCoordinator;
import java.time.Duration;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.web.server.ResponseStatusException;

class DataSessionRunCancellationTest {
    private final DataSessionService sessions = mock(DataSessionService.class);
    private final SessionTurnRunner runner = mock(SessionTurnRunner.class);
    private final DataSessionApiController controller =
            new DataSessionApiController(
                    sessions,
                    mock(SessionEventLog.class),
                    mock(SessionEventPreviewBus.class),
                    mock(ToolConfirmationCoordinator.class),
                    runner,
                    new ObjectMapper(),
                    mock(HandsMetrics.class));

    @Test
    void exactRunRequestDoesNotFallBackToSessionInterruptOrClearItsStatus() {
        post(Map.of("run_id", "run-b"));
        verify(sessions).get("alice", "session");
        verify(runner).interruptRun("alice", "session", "run-b");
        verify(runner, never()).interrupt(anyString());
        verify(sessions, never()).updateStatus(anyString(), anyString(), anyString(), any());
    }

    @Test
    void invalidRunIdNeverBecomesASessionWideInterrupt() {
        for (Object value : new Object[] {null, "", "  ", 42}) {
            Map<String, Object> payload = new HashMap<>();
            payload.put("run_id", value);
            assertThrows(ApiException.class, () -> post(payload));
        }
        verify(runner, never()).interrupt(anyString());
        verify(runner, never()).interruptRun(anyString(), anyString(), anyString());
    }

    @Test
    void sessionAuthorizationRunsBeforeCancellation() {
        when(sessions.get("alice", "session"))
                .thenThrow(new ResponseStatusException(HttpStatus.FORBIDDEN));
        assertThrows(ResponseStatusException.class, () -> post(Map.of("run_id", "run-b")));
        verify(runner, never()).interruptRun(anyString(), anyString(), anyString());
    }

    private void post(Map<String, Object> payload) {
        controller
                .postEvents(
                        "session",
                        new DataSessionApiController.PostEventsRequest(
                                List.of(
                                        new DataSessionApiController.InboundEvent(
                                                SessionEventTypes.USER_INTERRUPT, payload))),
                        new UsernamePasswordAuthenticationToken("alice", "unused"))
                .block(Duration.ofSeconds(5));
    }
}
