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
package io.agentscope.core.session;

import io.agentscope.core.message.Msg;
import io.agentscope.core.util.JsonUtils;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.function.Function;

/** Durable command acceptance. Its short writer lease never takes the execution writer's lease. */
public final class SessionInbox {
    public record Command(
            String id, String kind, String turnId, List<Msg> messages, List<String> requestIds) {
        public Command {
            Objects.requireNonNull(id);
            Objects.requireNonNull(kind);
            messages = List.copyOf(messages);
            requestIds = List.copyOf(requestIds);
        }

        public boolean execution() {
            return kind.equals("submit") || kind.equals("resume") || kind.equals("respond");
        }
    }

    public record Snapshot(
            List<Command> commands,
            String turnId,
            String runId,
            Map<String, String> rejected,
            Set<String> handled) {}

    private final SessionLog journal;

    public SessionInbox(SessionLog log) {
        journal = log.inbox();
    }

    public Snapshot snapshot() {
        var commands = new ArrayList<Command>();
        var rejected = new LinkedHashMap<String, String>();
        var handled = new HashSet<String>();
        String turn = null, run = null;
        for (var event : journal.scan(0, journal.head().seq())) {
            switch (event.type()) {
                case "inbox/accepted" -> commands.add(event.payload(Command.class));
                case "inbox/opened" -> {
                    turn = event.turnId();
                    run = event.executionRunId();
                }
                case "inbox/closed" -> {
                    if (Objects.equals(run, event.executionRunId())) {
                        turn = null;
                        run = null;
                    }
                }
                case "inbox/rejected" ->
                        rejected.put(
                                (String) event.data().get("commandId"),
                                (String) event.data().get("reason"));
                case "inbox/handled" -> handled.add((String) event.data().get("commandId"));
                default -> throw new SessionLogException("Unknown inbox event: " + event.type());
            }
        }
        return new Snapshot(
                List.copyOf(commands), turn, run, Map.copyOf(rejected), Set.copyOf(handled));
    }

    public <T> T locked(Function<Transaction, T> operation) {
        SessionLog.Writer writer = acquire();
        try {
            return operation.apply(new Transaction(writer));
        } finally {
            journal.release(writer);
        }
    }

    private SessionLog.Writer acquire() {
        for (int attempt = 0; ; attempt++) {
            try {
                return journal.acquire(UUID.randomUUID().toString(), Duration.ofMinutes(2));
            } catch (SessionLogException error) {
                if (attempt >= 99 || !error.getMessage().contains("active writer")) throw error;
                try {
                    Thread.sleep(10);
                } catch (InterruptedException interrupted) {
                    Thread.currentThread().interrupt();
                    throw new SessionLogException(
                            "Interrupted while accepting session input", interrupted);
                }
            }
        }
    }

    public final class Transaction {
        private final SessionLog.Writer writer;

        private Transaction(SessionLog.Writer writer) {
            this.writer = writer;
        }

        public Snapshot snapshot() {
            return SessionInbox.this.snapshot();
        }

        public Command accept(Command command) {
            for (var existing : snapshot().commands()) {
                if (!existing.id().equals(command.id())) continue;
                if (!comparable(existing).equals(comparable(command)))
                    throw new IllegalArgumentException(
                            "Idempotency key already used for different input");
                return existing;
            }
            append("inbox/accepted", null, command.turnId(), command);
            return command;
        }

        private Map<?, ?> comparable(Command command) {
            var value = JsonUtils.getJsonCodec().convertValue(command, Map.class);
            for (Object message : (List<?>) value.get("messages"))
                ((Map<?, ?>) message).remove("timestamp");
            return value;
        }

        public void open(String turn, String run) {
            append("inbox/opened", run, turn, Map.of());
        }

        public void close(String turn, String run) {
            append("inbox/closed", run, turn, Map.of());
        }

        public void reject(Command command, String reason) {
            append(
                    "inbox/rejected",
                    null,
                    command.turnId(),
                    Map.of("commandId", command.id(), "reason", reason));
        }

        public void handled(Command command) {
            append("inbox/handled", null, command.turnId(), Map.of("commandId", command.id()));
        }

        private void append(String type, String run, String turn, Object data) {
            long seq = journal.head().seq();
            journal.commit(
                    writer,
                    UUID.randomUUID().toString(),
                    seq,
                    List.of(
                            new SessionEvent(
                                    1,
                                    UUID.randomUUID().toString(),
                                    seq + 1,
                                    System.currentTimeMillis(),
                                    type,
                                    run,
                                    turn,
                                    true,
                                    JsonUtils.getJsonCodec().toJson(data))));
        }
    }
}
