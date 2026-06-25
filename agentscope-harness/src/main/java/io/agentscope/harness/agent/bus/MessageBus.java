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
package io.agentscope.harness.agent.bus;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

/**
 * Live message transport for coordinating work across sessions and processes.
 *
 * <p>Three consumption modes are exposed:
 * <ul>
 *   <li><b>Drain queue</b> (Mode A) — single-consumer, ack-on-read. Each entry is returned at
 *       most once; storage drops it the moment it is read.</li>
 *   <li><b>Replay log</b> (Mode C) — multi-consumer, externally bounded. Each reader tracks its
 *       own cursor; entries persist until trimmed or capped by maxLen.</li>
 *   <li><b>Transient broadcast</b> (Mode D) — fire-and-forget pub/sub. Only currently-subscribed
 *       listeners receive a payload; no history is retained.</li>
 * </ul>
 *
 * <p>Domain helpers ({@link #inboxPush}, {@link #inboxDrain}, {@link #enqueueWakeup}) map
 * higher-level concepts onto these primitives with a fixed key-naming convention.
 *
 * <p>Implementations:
 * <ul>
 *   <li>{@code WorkspaceMessageBus} — single-process, no external dependencies</li>
 * </ul>
 */
public interface MessageBus extends AutoCloseable {

    // ---- Mode A: drain queue (single consumer, ack-on-read) ----

    /**
     * Append a payload to the drain queue at the given key.
     *
     * @param key     queue identifier (caller-defined naming convention)
     * @param payload JSON-serializable dict to enqueue
     * @return the transport-level entry id
     */
    Mono<String> queuePush(String key, Map<String, Object> payload);

    /**
     * Drain up to {@code maxCount} entries from the queue at the given key. Returned entries are
     * removed from the queue atomically. A subsequent call returns only entries that arrived after
     * this one.
     *
     * @param key      queue identifier
     * @param maxCount maximum number of entries to return
     * @return entries in arrival order; empty list when the queue is empty
     */
    Mono<List<BusEntry>> queueDrain(String key, int maxCount);

    /**
     * Delete the drain queue at the given key and all of its entries. Idempotent.
     *
     * @param key queue identifier
     */
    Mono<Void> queueDelete(String key);

    /**
     * Check whether the drain queue at the given key has any entries, without consuming them.
     *
     * @param key queue identifier
     * @return true if the queue has at least one entry
     */
    Mono<Boolean> queuePeek(String key);

    // ---- Domain helpers: Inbox ----

    /**
     * Check whether a session's inbox has pending messages without consuming them.
     *
     * @param sessionId the session to check
     * @return true if the inbox has at least one message
     */
    default Mono<Boolean> inboxHasMessages(String sessionId) {
        return queuePeek("agentscope:inbox:" + sessionId);
    }

    // ---- Mode C: replay log (multi-consumer, externally bounded) ----

    /**
     * Append a payload to the replay log at the given key. Readers track their own cursor; entries
     * persist until trimmed or capped by {@code maxLen}.
     *
     * @param key     log identifier
     * @param payload JSON-serializable dict to append
     * @param maxLen  cap the log at approximately this many entries; older entries are trimmed when
     *                exceeded. Use {@code 0} or negative for no cap.
     * @return the transport-level entry id (usable as a cursor for subsequent reads)
     */
    Mono<String> logAppend(String key, Map<String, Object> payload, int maxLen);

    /**
     * Read up to {@code maxCount} entries from the replay log, starting after {@code since}.
     * Non-destructive: the same entries can be returned to any number of readers.
     *
     * @param key      log identifier
     * @param since    cursor — return entries strictly newer than this id. {@code null} reads from
     *                 the beginning.
     * @param maxCount maximum number of entries to return
     * @return entries in append order; empty list when no entries are newer than {@code since}
     */
    Mono<List<BusEntry>> logRead(String key, String since, int maxCount);

    /**
     * Delete the replay log at the given key and all of its entries. Idempotent.
     *
     * @param key log identifier
     */
    Mono<Void> logTrim(String key);

    // ---- Mode D: transient broadcast (fire-and-forget) ----

    /**
     * Publish a payload on the broadcast channel. Only currently-subscribed listeners receive it.
     *
     * @param key     channel identifier
     * @param payload JSON-serializable dict
     */
    Mono<Void> publish(String key, Map<String, Object> payload);

    /**
     * Subscribe to a broadcast channel. Yields payloads published after subscription is
     * established. The caller owns the subscription's lifetime — cancelling the Flux ends it.
     *
     * @param key channel identifier
     * @return stream of payloads
     */
    Flux<Map<String, Object>> subscribe(String key);

    // ---- Domain helpers: Inbox ----

    /**
     * Push a message into a session's inbox queue.
     *
     * @param sessionId the recipient session
     * @param msg       JSON-serializable payload (typically a serialized HintBlock)
     */
    default Mono<Void> inboxPush(String sessionId, Map<String, Object> msg) {
        return queuePush("agentscope:inbox:" + sessionId, msg).then();
    }

    /**
     * Drain pending inbox messages for a session.
     *
     * @param sessionId the session whose inbox to drain
     * @param maxCount  maximum entries to drain
     * @return entries in arrival order
     */
    default Mono<List<BusEntry>> inboxDrain(String sessionId, int maxCount) {
        return queueDrain("agentscope:inbox:" + sessionId, maxCount);
    }

    // ---- Domain helpers: Wakeup ----

    /**
     * Enqueue a wakeup request and signal dispatchers.
     *
     * @param userId    the owning user id (for multi-user isolation)
     * @param sessionId the session to wake
     * @param agentId   the agent that owns the session
     */
    default Mono<Void> enqueueWakeup(String userId, String sessionId, String agentId) {
        return queuePush(
                        "agentscope:wakeups",
                        Map.of(
                                "userId", userId != null ? userId : "",
                                "sessionId", sessionId,
                                "agentId", agentId))
                .then(publish("agentscope:wakeup_signal", Map.of()));
    }

    /**
     * Convenience overload without userId. Delegates to the three-parameter form with an empty
     * userId.
     */
    default Mono<Void> enqueueWakeup(String sessionId, String agentId) {
        return enqueueWakeup("", sessionId, agentId);
    }

    /**
     * Subscribe to the shared wakeup signal channel.
     *
     * @return stream of signal payloads (each indicates "drain the wakeup queue now")
     */
    default Flux<Map<String, Object>> subscribeWakeup() {
        return subscribe("agentscope:wakeup_signal");
    }

    // ---- Domain helpers: Session events ----

    String SESSION_EVENTS_KEY_PREFIX = "agentscope:session:events:";
    int SESSION_REPLAY_MAX_LEN = 1000;

    /**
     * Append a session event to the replay log and fan it out live. Late-joining subscribers can
     * replay from the log; already-connected subscribers receive it immediately via pub/sub.
     *
     * @param sessionId the session this event belongs to
     * @param event     JSON-serializable event payload
     * @return the replay-log entry id
     */
    default Mono<String> sessionPublishEvent(String sessionId, Map<String, Object> event) {
        String key = SESSION_EVENTS_KEY_PREFIX + sessionId;
        return logAppend(key, event, SESSION_REPLAY_MAX_LEN)
                .flatMap(
                        entryId -> {
                            Map<String, Object> live = new HashMap<>(event);
                            live.put("_entry_id", entryId);
                            return publish(key, live).thenReturn(entryId);
                        });
    }

    /**
     * Read events from a session's replay log for catch-up / reconnection.
     *
     * @param sessionId the session whose events to read
     * @param since     cursor — return entries strictly newer than this id; {@code null} reads all
     * @param maxCount  maximum events to return
     * @return entries in append order
     */
    default Mono<List<BusEntry>> sessionReadEvents(String sessionId, String since, int maxCount) {
        return logRead(SESSION_EVENTS_KEY_PREFIX + sessionId, since, maxCount);
    }

    /**
     * Live-subscribe to a session's published events. Yields only payloads delivered after the
     * subscription is established.
     *
     * @param sessionId the session to subscribe to
     * @return stream of event payloads
     */
    default Flux<Map<String, Object>> sessionSubscribeEvents(String sessionId) {
        return subscribe(SESSION_EVENTS_KEY_PREFIX + sessionId);
    }

    /**
     * Trim the session's event replay log. Called after a session run completes so late subscribers
     * don't replay stale events from a previous run.
     *
     * @param sessionId the session whose event log to clear
     */
    default Mono<Void> sessionTrimEvents(String sessionId) {
        return logTrim(SESSION_EVENTS_KEY_PREFIX + sessionId);
    }

    /** Release underlying transport resources. Default is a no-op. */
    @Override
    default void close() {}
}
