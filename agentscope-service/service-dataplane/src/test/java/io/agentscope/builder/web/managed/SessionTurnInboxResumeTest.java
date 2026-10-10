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
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import io.agentscope.builder.web.managed.service.SessionEventLog;
import io.agentscope.builder.web.persistence.jpa.SessionActionCommandRepository;
import io.agentscope.builder.web.persistence.jpa.SessionTurnCommandEntity;
import io.agentscope.builder.web.persistence.jpa.SessionTurnCommandRepository;
import io.agentscope.builder.web.toolbus.ToolConfirmationCoordinator;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.springframework.transaction.support.TransactionCallback;
import org.springframework.transaction.support.TransactionTemplate;

class SessionTurnInboxResumeTest {
    @Test
    void retryAfterAnotherInterruptionDoesNotResumeTwice() {
        var repository = mock(SessionTurnCommandRepository.class);
        var events = mock(SessionEventLog.class);
        var tx = mock(TransactionTemplate.class);
        when(tx.execute(any()))
                .thenAnswer(
                        call ->
                                ((TransactionCallback<?>) call.getArgument(0))
                                        .doInTransaction(null));
        var row = new SessionTurnCommandEntity();
        row.id = "turn";
        row.sessionId = "session";
        row.userId = "user";
        row.status = "interrupted";
        when(repository.lockById("turn")).thenReturn(Optional.of(row));
        Map<String, SessionEventDto> receipts = new HashMap<>();
        when(events.findByEventId(anyString()))
                .thenAnswer(call -> Optional.ofNullable(receipts.get(call.getArgument(0))));
        when(events.appendCommand(eq("session"), eq("turn.queued"), anyMap(), anyString()))
                .thenAnswer(
                        call -> {
                            String id = call.getArgument(3);
                            assertThat(id.length()).isLessThanOrEqualTo(64);
                            var event =
                                    new SessionEventDto(
                                            id,
                                            "session",
                                            1,
                                            "turn.queued",
                                            call.getArgument(2),
                                            null,
                                            0);
                            receipts.put(id, event);
                            return event;
                        });
        var inbox =
                new SessionTurnInbox(
                        repository,
                        tx,
                        mock(DataSessionService.class),
                        mock(SessionTurnRunner.class),
                        events,
                        mock(SessionNativeLogService.class),
                        mock(SessionActionCommandRepository.class),
                        mock(ToolConfirmationCoordinator.class));
        assertThat(inbox.resume("user", "session", "turn", "key").status()).isEqualTo("queued");
        row.status = "interrupted";
        assertThat(inbox.resume("user", "session", "turn", "key").status())
                .isEqualTo("interrupted");
        verify(repository, times(1)).save(row);
    }
}
