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
