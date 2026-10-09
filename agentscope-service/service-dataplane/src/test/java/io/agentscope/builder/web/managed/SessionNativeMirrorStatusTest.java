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
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doNothing;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.when;

import io.agentscope.builder.control.ManagedEventOutbox;
import io.agentscope.builder.control.ManagedEventOutbox.DeliveryStatus;
import io.agentscope.builder.web.managed.service.DeletedSessionRegistry;
import io.agentscope.builder.web.managed.service.SessionEventLog;
import io.agentscope.builder.web.persistence.jpa.SessionExecutionScopeEntity;
import io.agentscope.builder.web.persistence.jpa.SessionExportSourceEntity;
import io.agentscope.builder.web.persistence.jpa.SessionExportSourceRepository;
import io.agentscope.core.session.SessionLog;
import io.agentscope.core.session.SessionLogStore;
import java.util.List;
import java.util.Optional;
import java.util.function.Predicate;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class SessionNativeMirrorStatusTest {
    private final ManagedEventOutbox outbox = mock(ManagedEventOutbox.class);
    private final SessionExportSourceRepository sources = mock(SessionExportSourceRepository.class);
    private final SessionLogStore store = mock(SessionLogStore.class);
    private final SessionNativeLogService service =
            spy(
                    new SessionNativeLogService(
                            store,
                            mock(SessionEventLog.class),
                            sources,
                            new DeletedSessionRegistry(),
                            outbox));

    private List<SessionExecutionScopeEntity> admissions = List.of();

    @BeforeEach
    void runNativeFenceCallback() {
        when(outbox.freezeAttemptIfFinished(anyString(), anyString(), any()))
                .thenAnswer(
                        call -> {
                            Predicate<List<SessionExecutionScopeEntity>> callback =
                                    call.getArgument(2);
                            return callback.test(admissions);
                        });
    }

    @Test
    void executionThatNeverStartedIsReadyButAnAdmittedBuildAndPendingDeliveryAreNot() {
        when(outbox.status("session", "attempt")).thenReturn(new DeliveryStatus(0, 0, 0));
        assertThat(service.mirrorStatus("session", "attempt")).containsEntry("ready", true);
        var admission = new SessionExecutionScopeEntity();
        admission.leaseUntil = Long.MAX_VALUE;
        admissions = List.of(admission);
        assertThat(service.mirrorStatus("session", "attempt")).containsEntry("ready", false);
        admission.finished = true;
        when(outbox.status("session", "attempt")).thenReturn(new DeliveryStatus(1, 3, 2));
        assertThat(service.mirrorStatus("session", "attempt")).containsEntry("ready", false);
        when(outbox.status("session", "attempt")).thenReturn(new DeliveryStatus(0, 3, 3));
        assertThat(service.mirrorStatus("session", "attempt")).containsEntry("ready", true);
    }

    @Test
    void expiredAdmissionStillWaitsForItsNativeWriterThenRefreshesCommittedPrefix() {
        var admission = new SessionExecutionScopeEntity();
        admission.runId = "run";
        admissions = List.of(admission);
        when(outbox.status("session", "attempt")).thenReturn(new DeliveryStatus(0, 0, 0));
        var source = new SessionExportSourceEntity();
        source.userId = "owner";
        source.agentId = "agent";
        source.sessionId = "session";
        when(sources.findById("session")).thenReturn(Optional.of(source));
        var log = mock(SessionLog.class);
        when(store.open(any(), any())).thenReturn(log);
        when(log.head()).thenReturn(new SessionLog.Head(8, 1, "run", Long.MAX_VALUE, "commit"));
        doNothing().when(service).refresh("session");
        assertThat(service.mirrorStatus("session", "attempt")).containsEntry("ready", false);
        when(log.head()).thenReturn(new SessionLog.Head(8, 1, "run", 0, "commit"));
        assertThat(service.mirrorStatus("session", "attempt")).containsEntry("ready", true);
    }
}
