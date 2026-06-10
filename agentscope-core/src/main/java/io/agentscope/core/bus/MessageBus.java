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
package io.agentscope.core.bus;

import java.util.List;
import java.util.Map;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

/**
 * Live message transport for coordinating work across sessions and processes.
 *
 * <p>Two consumption modes are exposed:
 * <ul>
 *   <li><b>Drain queue</b> (Mode A) — single-consumer, ack-on-read. Each entry is returned at
 *       most once; storage drops it the moment it is read.</li>
 *   <li><b>Transient broadcast</b> (Mode D) — fire-and-forget pub/sub. Only currently-subscribed
 *       listeners receive a payload; no history is retained.</li>
 * </ul>
 *
 * <p>Domain helpers ({@link #inboxPush}, {@link #inboxDrain}, {@link #enqueueWakeup}) map
 * higher-level concepts onto these primitives with a fixed key-naming convention.
 *
 * <p>Implementations:
 * <ul>
 *   <li>{@link InMemoryMessageBus} — single-process, no external dependencies</li>
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
     * @param sessionId the session to wake
     * @param agentId   the agent that owns the session
     */
    default Mono<Void> enqueueWakeup(String sessionId, String agentId) {
        return queuePush("agentscope:wakeups", Map.of("sessionId", sessionId, "agentId", agentId))
                .then(publish("agentscope:wakeup_signal", Map.of()));
    }

    /**
     * Subscribe to the shared wakeup signal channel.
     *
     * @return stream of signal payloads (each indicates "drain the wakeup queue now")
     */
    default Flux<Map<String, Object>> subscribeWakeup() {
        return subscribe("agentscope:wakeup_signal");
    }

    /** Release underlying transport resources. Default is a no-op. */
    @Override
    default void close() {}
}
