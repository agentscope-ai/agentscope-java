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
import io.agentscope.core.message.MsgRole;
import io.agentscope.core.message.ToolResultBlock;
import io.agentscope.core.state.AgentState;
import io.agentscope.core.util.JsonUtils;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.function.Consumer;
import java.util.function.Supplier;

/** Read-only snapshots and fenced administrative state changes against native history. */
public final class SessionStateAccess {
    private static final Duration LEASE = Duration.ofMinutes(2);

    private SessionStateAccess() {}

    /** A detached state and the committed prefix from which it was recovered. */
    public record Snapshot(long asOfSeq, AgentState state) {}

    /** Reads one committed prefix without acquiring a writer or changing session history. */
    public static Snapshot read(SessionLog log, Supplier<AgentState> initialState) {
        SessionProjection projection = SessionProjection.read(log);
        AgentState state = projection.restore();
        return new Snapshot(projection.asOfSeq(), state == null ? initialState.get() : state);
    }

    /**
     * Reloads and mutates state while holding the native writer lease, then commits a checkpoint.
     * An active execution or an unresolved tool dispatch prevents administrative mutation.
     */
    public static Snapshot update(
            SessionLog log,
            Supplier<AgentState> initialState,
            String reason,
            Consumer<AgentState> mutation) {
        Objects.requireNonNull(mutation, "mutation");
        return change(log, initialState, reason, null, mutation);
    }

    /**
     * Saves an explicitly edited snapshot only if its committed prefix is still current.
     * A stale snapshot is rejected; it never overwrites an execution completed elsewhere.
     */
    public static Snapshot replace(
            SessionLog log, Supplier<AgentState> initialState, String reason, Snapshot snapshot) {
        Objects.requireNonNull(snapshot, "snapshot");
        AgentState frozen = AgentState.fromJsonString(snapshot.state().toJson());
        return change(log, initialState, reason, new Snapshot(snapshot.asOfSeq(), frozen), null);
    }

    private static Snapshot change(
            SessionLog log,
            Supplier<AgentState> initialState,
            String reason,
            Snapshot replacement,
            Consumer<AgentState> mutation) {
        if (reason == null || reason.isBlank())
            throw new IllegalArgumentException("A state change reason is required");
        SessionLog.Writer writer = log.acquire(UUID.randomUUID().toString(), LEASE);
        try {
            SessionProjection projection = SessionProjection.read(log);
            if (!projection.uncertainToolCalls().isEmpty())
                throw new SessionLogException(
                        "Reconcile unresolved tool outcomes before changing session state");
            if (replacement != null && replacement.asOfSeq() != projection.asOfSeq())
                throw new SessionLogException(
                        "Session state changed since inspection; reload or use an atomic update");
            AgentState current = projection.restore();
            if (current == null) current = initialState.get();
            String userId = current.getUserId();
            String sessionId = current.getSessionId();
            String beforeJson = current.toJson();
            var previousMessageIds = new HashSet<String>();
            for (Msg message : current.getContext()) previousMessageIds.add(message.getId());
            AgentState changed = replacement == null ? current : replacement.state();
            if (mutation != null) mutation.accept(changed);
            if (!Objects.equals(userId, changed.getUserId())
                    || !Objects.equals(sessionId, changed.getSessionId()))
                throw new IllegalArgumentException("State changes must preserve session identity");
            String stateJson = changed.toJson();
            List<SessionEvent> facts = new ArrayList<>();
            var before = JsonUtils.getJsonCodec().fromJson(beforeJson, Map.class);
            var after = JsonUtils.getJsonCodec().fromJson(stateJson, Map.class);
            boolean contextChanged = !Objects.equals(before.get("context"), after.get("context"));
            if (contextChanged && !SessionInteractions.pending(log).isEmpty())
                throw new SessionLogException(
                        "Resolve pending interactions before replacing context");
            if (contextChanged)
                append(
                        facts,
                        projection.asOfSeq(),
                        "context/replaced",
                        Map.of(
                                "reason",
                                reason,
                                "messages",
                                changed.getContext(),
                                "previousAsOfSeq",
                                projection.asOfSeq()));
            for (Msg message : changed.getContext()) {
                if (previousMessageIds.contains(message.getId())) continue;
                String type =
                        message.hasContentBlocks(ToolResultBlock.class)
                                ? "tool/result"
                                : message.getRole() == MsgRole.ASSISTANT
                                        ? "message/assistant"
                                        : message.getRole() == MsgRole.SYSTEM
                                                ? "message/system"
                                                : "message/user";
                append(facts, projection.asOfSeq(), type, Map.of("message", message));
            }
            for (String field :
                    List.of("tasks_context", "plan_mode_context", "permission_context")) {
                Object value = after.get(field);
                if (value != null && !Objects.equals(value, before.get(field)))
                    append(
                            facts,
                            projection.asOfSeq(),
                            field.equals("tasks_context")
                                    ? "task/changed"
                                    : field.equals("plan_mode_context")
                                            ? "plan/changed"
                                            : "permission/changed",
                            Map.of("state", value));
            }
            append(
                    facts,
                    projection.asOfSeq(),
                    "state/checkpoint",
                    Map.of("stateJson", stateJson, "projectorVersion", 1, "reason", reason));
            SessionLog.Head head =
                    log.commit(writer, UUID.randomUUID().toString(), projection.asOfSeq(), facts);
            return new Snapshot(head.seq(), AgentState.fromJsonString(stateJson));
        } finally {
            log.release(writer);
        }
    }

    private static void append(
            List<SessionEvent> facts, long previousSeq, String type, Object payload) {
        facts.add(
                new SessionEvent(
                        1,
                        UUID.randomUUID().toString(),
                        previousSeq + facts.size() + 1,
                        System.currentTimeMillis(),
                        type,
                        null,
                        null,
                        true,
                        JsonUtils.getJsonCodec().toJson(payload)));
    }
}
