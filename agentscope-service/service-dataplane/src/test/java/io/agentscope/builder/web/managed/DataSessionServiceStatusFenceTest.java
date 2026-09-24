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

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.when;

import io.agentscope.builder.control.ControlPlaneClient;
import io.agentscope.builder.control.ControlPlaneClient.ManagedExecutionScope;
import io.agentscope.builder.control.SessionResolveResult;
import io.agentscope.builder.web.managed.service.ManagedJsonHelper;
import io.agentscope.builder.web.managed.service.SessionEventLog;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class DataSessionServiceStatusFenceTest {

    @Test
    void managedStatusPatchAndEventUseOnlyCapturedTurnFence() {
        ControlPlaneClient controlPlane = mock(ControlPlaneClient.class);
        SessionEventLog eventLog = mock(SessionEventLog.class);
        DataSessionService service =
                new DataSessionService(
                        controlPlane,
                        eventLog,
                        mock(ManagedJsonHelper.class),
                        mock(SessionTurnRunner.class));
        ManagedSessionDto session =
                new ManagedSessionDto(
                        "session-a",
                        "owner-a",
                        "agent-a",
                        "owner-a",
                        1,
                        "managed",
                        null,
                        null,
                        null,
                        List.of(),
                        List.of(),
                        List.of(),
                        "running",
                        null,
                        1,
                        1,
                        null);
        SessionResolveResult resolved = mock(SessionResolveResult.class);
        when(resolved.session()).thenReturn(session);
        when(controlPlane.resolveSession("session-a")).thenReturn(resolved);
        SessionEventDto statusEvent =
                new SessionEventDto(
                        "event-a",
                        "session-a",
                        1,
                        "session.status_idle",
                        Map.of("status", "idle"),
                        null,
                        1);
        when(eventLog.appendLocal(eq("session-a"), eq("session.status_idle"), any(), eq(null)))
                .thenReturn(statusEvent);
        ManagedExecutionScope oldScope =
                new ManagedExecutionScope("tenant-a", "task-a", "attempt-a", 3, "turn-a");

        service.updateStatus("owner-a", "session-a", "idle", null, oldScope);

        verify(controlPlane).patchSessionRuntime("session-a", "idle", null, "owner-a", oldScope);
        verify(eventLog).appendLocal(eq("session-a"), eq("session.status_idle"), any(), eq(null));
        verify(controlPlane).appendSessionEvent(statusEvent, oldScope);
        verifyNoMoreInteractions(eventLog);
    }
}
