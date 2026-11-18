/*
 * Copyright 2024-2026 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package io.agentscope.core.agui.queue;

import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Registry of per-thread message queues.
 *
 * <p>Queues are created on the first accepted message and never for read-only calls, so probing
 * arbitrary thread identifiers cannot grow the registry. The registry lives in-process only: a
 * restart drops all queued messages, and two application instances do not share queues.
 */
public final class MessageQueueRegistry {

    private final int capacity;
    private final int retention;
    private final ConcurrentHashMap<String, ThreadMessageQueue> queues = new ConcurrentHashMap<>();

    /**
     * Creates a registry.
     *
     * @param capacity maximum number of queued messages per thread
     * @param retention maximum number of identifiers remembered per thread for deduplication;
     *     must be at least {@code capacity} so a queued message's identifier is never evicted
     *     before the message is claimed
     */
    public MessageQueueRegistry(int capacity, int retention) {
        if (capacity <= 0) {
            throw new IllegalArgumentException("capacity must be positive");
        }
        if (retention < capacity) {
            throw new IllegalArgumentException(
                    "retention must be greater than or equal to capacity");
        }
        this.capacity = capacity;
        this.retention = retention;
    }

    /**
     * Returns the queue for a thread, creating it on first use.
     *
     * @param threadId the AG-UI thread identifier
     * @return the thread queue
     */
    public ThreadMessageQueue forThread(String threadId) {
        if (threadId == null || threadId.isBlank()) {
            throw new IllegalArgumentException("threadId cannot be blank");
        }
        return queues.computeIfAbsent(threadId, id -> new ThreadMessageQueue(capacity, retention));
    }

    /**
     * Returns the queue of a thread without creating one.
     *
     * <p>Read paths use this so that a status request for an unknown thread leaves no state behind.
     *
     * @param threadId the AG-UI thread identifier
     * @return the existing queue, or empty when the thread never queued a message
     */
    public Optional<ThreadMessageQueue> find(String threadId) {
        if (threadId == null || threadId.isBlank()) {
            return Optional.empty();
        }
        return Optional.ofNullable(queues.get(threadId));
    }

    /**
     * Returns the number of known thread queues.
     *
     * @return the number of queues
     */
    public int size() {
        return queues.size();
    }
}
