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

import io.agentscope.core.agui.model.AguiMessage;
import io.agentscope.core.agui.model.RunAgentInput;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

/**
 * Bounded FIFO queue for a single AG-UI thread.
 *
 * <p>All operations are serialized on the queue instance so that a claim is an atomic
 * check-and-remove. The queue never calls an agent and never performs I/O while holding the lock.
 *
 * <p>A small ledger remembers recently accepted message identifiers so that a client retry with the
 * same identifier and identical content is idempotent. The ledger is bounded; identifiers evicted
 * from it are treated as new messages.
 */
public final class ThreadMessageQueue {

    private final int capacity;
    private final int retention;
    private final LinkedHashMap<String, QueuedMessage> pending = new LinkedHashMap<>();
    private final LinkedHashMap<String, LedgerEntry> ledger = new LinkedHashMap<>();

    /**
     * Creates a queue.
     *
     * @param capacity maximum number of queued messages
     * @param retention maximum number of identifiers remembered for deduplication
     */
    public ThreadMessageQueue(int capacity, int retention) {
        if (capacity <= 0) {
            throw new IllegalArgumentException("capacity must be positive");
        }
        if (retention <= 0) {
            throw new IllegalArgumentException("retention must be positive");
        }
        this.capacity = capacity;
        this.retention = retention;
    }

    /**
     * Offers a message to the queue.
     *
     * @param messageId stable identifier used for deduplication
     * @param input the original run input
     * @param lastUserMessage the trailing user message, used as the content fingerprint
     * @return the acceptance outcome
     */
    public synchronized QueueAcceptance offer(
            String messageId, RunAgentInput input, AguiMessage lastUserMessage) {
        Objects.requireNonNull(messageId, "messageId cannot be null");
        Objects.requireNonNull(input, "input cannot be null");

        LedgerEntry seen = ledger.get(messageId);
        if (seen != null) {
            if (!Objects.equals(seen.lastUserMessage(), lastUserMessage)) {
                return QueueAcceptance.conflict(messageId);
            }
            int position = positionOf(messageId);
            return QueueAcceptance.duplicate(messageId, position, position > 0 ? "QUEUED" : "DONE");
        }
        if (pending.size() >= capacity) {
            return QueueAcceptance.full(messageId);
        }

        QueuedMessage message =
                QueuedMessage.create(messageId, input, lastUserMessage, System.currentTimeMillis());
        pending.put(messageId, message);
        remember(messageId, lastUserMessage, "QUEUED");
        return QueueAcceptance.accepted(messageId, positionOf(messageId));
    }

    /**
     * Claims the oldest queued message when the thread is idle.
     *
     * <p>Claiming removes the message from the queue. The caller replays the returned input on the
     * regular run endpoint. A claimed message is never re-queued, so a message can never be
     * executed twice because of a queue decision.
     *
     * @param idle whether the thread currently has no active run and no unresolved interrupt
     * @return the claimed message, or empty when nothing can be claimed
     */
    public synchronized Optional<QueuedMessage> claim(boolean idle) {
        if (!idle || pending.isEmpty()) {
            return Optional.empty();
        }
        Iterator<Map.Entry<String, QueuedMessage>> iterator = pending.entrySet().iterator();
        Map.Entry<String, QueuedMessage> first = iterator.next();
        iterator.remove();
        first.getValue().markClaimed();
        remember(first.getKey(), first.getValue().getLastUserMessage(), "DONE");
        return Optional.of(first.getValue());
    }

    /**
     * Withdraws a queued message.
     *
     * <p>Withdrawing forgets the message entirely, including its deduplication entry, so the same
     * identifier may be submitted again later.
     *
     * @param messageId the message identifier
     * @return the withdraw outcome
     */
    public synchronized WithdrawStatus withdraw(String messageId) {
        QueuedMessage message = pending.remove(messageId);
        if (message == null) {
            return WithdrawStatus.NOT_FOUND;
        }
        ledger.remove(messageId);
        return WithdrawStatus.WITHDRAWN;
    }

    /**
     * Returns a snapshot of the queue.
     *
     * @param active whether the thread currently has an active run
     * @param awaitingConfirmation whether the thread has an unresolved interrupt
     * @return the snapshot
     */
    public synchronized QueueSnapshot snapshot(boolean active, boolean awaitingConfirmation) {
        List<QueueSnapshot.Item> items = new ArrayList<>(pending.size());
        int position = 0;
        for (QueuedMessage message : pending.values()) {
            position++;
            items.add(
                    new QueueSnapshot.Item(
                            message.getMessageId(), position, message.getState().name()));
        }
        return new QueueSnapshot(List.copyOf(items), active, awaitingConfirmation);
    }

    private int positionOf(String messageId) {
        int position = 0;
        for (String id : pending.keySet()) {
            position++;
            if (id.equals(messageId)) {
                return position;
            }
        }
        return -1;
    }

    private void remember(String messageId, AguiMessage lastUserMessage, String state) {
        ledger.put(messageId, new LedgerEntry(lastUserMessage, state));
        while (ledger.size() > retention) {
            Iterator<String> iterator = ledger.keySet().iterator();
            iterator.next();
            iterator.remove();
        }
    }

    private record LedgerEntry(AguiMessage lastUserMessage, String state) {}
}
