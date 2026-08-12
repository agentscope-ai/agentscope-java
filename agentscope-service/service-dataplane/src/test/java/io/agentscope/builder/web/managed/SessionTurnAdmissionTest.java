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
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import io.agentscope.builder.web.catalog.HarnessAgentBuildService;
import io.agentscope.builder.web.coord.CoordinationStore;
import io.agentscope.builder.web.coord.TurnLeaseService;
import io.agentscope.builder.web.managed.service.DeletedSessionRegistry;
import io.agentscope.builder.web.managed.service.SessionEventLog;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

/**
 * A wake that cannot start a turn must leave no trace. The control plane retries a rejected wake
 * every couple of seconds for as long as the member stays busy, so anything recorded before the turn
 * lease is held would be written once per retry.
 */
class SessionTurnAdmissionTest {

    @Test
    void aRejectedWakeDoesNotRecordTheMessageItCouldNotDeliver() {
        AtomicInteger recorded = new AtomicInteger();
        SessionTurnRunner runner = runnerWithLease(busyLease());

        assertThatThrownBy(
                        () ->
                                runner.runTurnAsync(
                                        session(), "task-1 completed", recorded::incrementAndGet))
                .isInstanceOf(ResponseStatusException.class);
        assertThat(recorded.get()).isZero();
    }

    @Test
    void anAdmittedTurnRecordsTheMessageThatStartedIt() {
        AtomicInteger recorded = new AtomicInteger();
        SessionTurnRunner runner = runnerWithLease(freeLease());

        runner.runTurnAsync(session(), "task-1 completed", recorded::incrementAndGet);

        assertThat(recorded.get()).isEqualTo(1);
    }

    private static TurnLeaseService busyLease() {
        TurnLeaseService leases = mock(TurnLeaseService.class);
        when(leases.acquireOrConflict(anyString(), anyString(), any()))
                .thenThrow(
                        new ResponseStatusException(
                                HttpStatus.CONFLICT, "Session turn already in progress"));
        return leases;
    }

    private static TurnLeaseService freeLease() {
        TurnLeaseService leases = mock(TurnLeaseService.class);
        when(leases.acquireOrConflict(anyString(), anyString(), any()))
                .thenReturn(mock(TurnLeaseService.TurnLease.class));
        return leases;
    }

    private static SessionTurnRunner runnerWithLease(TurnLeaseService leases) {
        return new SessionTurnRunner(
                mock(HarnessAgentBuildService.class),
                mock(DataSessionService.class),
                mock(SessionEventLog.class),
                mock(SessionEventMapper.class),
                mock(SessionEventPreviewBus.class),
                mock(DataEnvironmentService.class),
                mock(HandsLeaseService.class),
                leases,
                mock(CoordinationStore.class),
                new DeletedSessionRegistry());
    }

    private static ManagedSessionDto session() {
        return new ManagedSessionDto(
                "sess_lead",
                "user_1",
                "agt_lead",
                "user_1",
                1,
                null,
                null,
                null,
                "team|default/research|lead",
                List.of(),
                List.of(),
                List.of(),
                "idle",
                Map.of(),
                0L,
                0L,
                null);
    }
}
