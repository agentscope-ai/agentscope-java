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

import io.agentscope.core.session.JournalSessionLog;
import io.agentscope.core.session.SessionInbox;
import io.agentscope.core.session.SessionTurns;
import java.nio.charset.StandardCharsets;
import java.util.List;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

/** Cross-replica steering admission shares the execution inbox's terminal-admission fence. */
@Service
public final class SessionInputService {
    private final DataSessionService sessions;
    private final SessionNativeLogService logs;
    private final SessionFileService files;

    public SessionInputService(
            DataSessionService sessions, SessionNativeLogService logs, SessionFileService files) {
        this.sessions = sessions;
        this.logs = logs;
        this.files = files;
    }

    public record Receipt(String input_id, String turn_id, String kind, String status) {}

    public Receipt accept(
            String user,
            String session,
            String turn,
            String kind,
            String key,
            AgentSessionInput input) {
        if (key == null || key.isBlank() || key.length() > 256)
            throw new ResponseStatusException(
                    HttpStatus.BAD_REQUEST, "Idempotency-Key must contain 1..256 characters");
        var managed = sessions.get(user, session);
        logs.register(managed);
        var log = logs.open(managed);
        String id =
                JournalSessionLog.hash(
                        (user + "\n" + session + "\ninput\n" + key)
                                .getBytes(StandardCharsets.UTF_8));
        var request = files.resolveInput(session, input.normalized());
        var command =
                new SessionInbox.Command(
                        id, kind, turn, AgentSessionInput.messages(request, id), List.of());
        var inbox = new SessionInbox(log);
        inbox.locked(
                tx -> {
                    var snapshot = tx.snapshot();
                    if (snapshot.commands().stream()
                            .anyMatch(existing -> existing.id().equals(id))) {
                        try {
                            return tx.accept(command);
                        } catch (IllegalArgumentException mismatch) {
                            throw new ResponseStatusException(
                                    HttpStatus.CONFLICT, mismatch.getMessage());
                        }
                    }
                    if (kind.equals("steer")) {
                        var head = log.head();
                        if (turn == null
                                || !turn.equals(snapshot.turnId())
                                || snapshot.runId() == null
                                || !snapshot.runId().equals(head.owner())
                                || head.leaseUntil() <= System.currentTimeMillis())
                            throw new ResponseStatusException(
                                    HttpStatus.CONFLICT,
                                    "The requested turn no longer accepts steering");
                    }
                    return tx.accept(command);
                });
        logs.refresh(session);
        var snapshot = inbox.snapshot();
        String status =
                snapshot.rejected().containsKey(id)
                        ? "rejected"
                        : SessionTurns.read(log).applied().contains(id) ? "applied" : "accepted";
        return new Receipt(id, turn, kind, status);
    }
}
