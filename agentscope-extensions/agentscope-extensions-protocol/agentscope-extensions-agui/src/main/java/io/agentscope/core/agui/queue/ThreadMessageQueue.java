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
import java.util.UUID;

/**
 * Bounded FIFO queue for a single AG-UI thread.
 *
 * <p>All operations are serialized on the queue instance so that a claim is an atomic
 * check-and-remove. The queue never calls an agent and never performs I/O while holding the lock.
 *
 * <p>A small ledger remembers recently accepted message identifiers so that a client retry with the
 * same identifier and the same trailing message is idempotent. The ledger is bounded; identifiers
 * evicted from it are treated as new messages.
 *
 * <p>Claiming hands the message to a client but never returns it to the queue, so a queue decision
 * can never execute a message twice. To keep two simultaneous claims from taking two different
 * messages in the same idle window, a delivered message blocks further claims for a short grace
 * period. On a later claim the block is released when the run id the thread last started is the
 * very id this delivery handed out (the delivered message itself was replayed), or when the claim
 * observes the thread busy after the delivery; the grace period is the fallback. Keep a single
 * claimant per thread: the gap between a claim and its replay stays unprotected, so a run that
 * another client starts first rejects the replay and the claimed message is lost.
 */
public final class ThreadMessageQueue {

    /**
     * How long a delivered message keeps the queue closed for further claims while the thread is
     * still idle. Covers a client that claims twice, or a claim retried after a lost response.
     * A client that crashes after claiming loses that message (by design); this window only makes
     * sure no further message is handed out before the crashed client's reply would have started.
     */
    private static final long HAND_OFF_GUARD_MILLIS = 5_000L;

    private final int capacity;
    private final int retention;
    private final LinkedHashMap<String, QueuedMessage> pending = new LinkedHashMap<>();
    private final LinkedHashMap<String, LedgerEntry> ledger = new LinkedHashMap<>();
    private long handOffBlockedUntilMillis;
    private long lastHandOffAtMillis;
    private String lastHandedOffRunId;

    /**
     * Creates a queue.
     *
     * @param capacity maximum number of queued messages
     * @param retention maximum number of identifiers remembered for deduplication; must be at
     *     least {@code capacity} so that remembering a new identifier can never evict one that is
     *     still queued
     */
    public ThreadMessageQueue(int capacity, int retention) {
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
     * Offers a message to the queue.
     *
     * @param messageId stable identifier used for deduplication
     * @param input the run input to replay when the message is claimed
     * @return the acceptance outcome
     */
    public synchronized QueueAcceptance offer(String messageId, RunAgentInput input) {
        Objects.requireNonNull(messageId, "messageId cannot be null");
        Objects.requireNonNull(input, "input cannot be null");

        AguiMessage trailingMessage = trailingMessage(input.getMessages());
        LedgerEntry seen = ledger.get(messageId);
        if (seen != null) {
            if (!Objects.equals(seen.trailingMessage(), trailingMessage)) {
                return QueueAcceptance.conflict(messageId);
            }
            int position = positionOf(messageId);
            return QueueAcceptance.duplicate(messageId, position, seen.state().name());
        }
        if (pending.size() >= capacity) {
            return QueueAcceptance.full(messageId);
        }

        pending.put(messageId, QueuedMessage.create(messageId, input, System.currentTimeMillis()));
        remember(messageId, trailingMessage, QueuedMessage.State.QUEUED);
        return QueueAcceptance.accepted(messageId, positionOf(messageId));
    }

    /**
     * Claims the oldest queued message when the thread is idle.
     *
     * <p>Claiming removes the message from the queue. The caller replays the returned input on the
     * regular run endpoint. A claimed message is never re-queued, so a message can never be
     * executed twice because of a queue decision.
     *
     * <p>The returned input carries a fresh run id, so it can be replayed verbatim even when the
     * queued body reused the id of the run that was active when the message was accepted.
     *
     * <p>Delegates to {@link #claim(boolean, long, String)} with no run-start evidence, so the
     * hand-off window only opens through a busy observation or the grace period.
     *
     * @param idle whether the thread currently has no active run and no unresolved interrupt
     * @return the claimed message, or empty when nothing can be claimed
     */
    public synchronized Optional<QueuedMessage> claim(boolean idle) {
        return claim(idle, System.currentTimeMillis());
    }

    /**
     * Claims the oldest queued message when the thread is idle, with an explicit observation
     * time.
     *
     * <p>Delegates to {@link #claim(boolean, long, String)} with no run-start evidence, so the
     * hand-off window only opens through a busy observation or the grace period.
     *
     * @param idle whether the thread currently has no active run and no unresolved interrupt
     * @param observedAtMillis when {@code idle} was determined, in epoch milliseconds
     * @return the claimed message, or empty when nothing can be claimed
     */
    public synchronized Optional<QueuedMessage> claim(boolean idle, long observedAtMillis) {
        return claim(idle, observedAtMillis, null);
    }

    /**
     * Claims the oldest queued message when the thread is idle, with explicit observation time
     * and run-start evidence.
     *
     * <p>{@code observedAtMillis} is the moment the caller began observing the thread — read
     * before {@code idle} — and it also serves as the current time for the hand-off window, so
     * a delivery anchors its window to the observing request rather than to the hand-out
     * instant. Callers that read the idle state over HTTP should pass that reading's timestamp:
     * a "busy" observation taken <em>before</em> another client's claim delivered a message must
     * not open the next delivery window for the same idle period, while an observation taken
     * after the delivery reflects the replayed run and does open the next window. An observation
     * in the same millisecond as the delivery cannot prove it followed the delivery, so it counts
     * as earlier.
     *
     * <p>{@code lastStartedRunId} is the run id the thread most recently started. When it equals
     * the run id this queue handed out at the last delivery, the delivered message itself was
     * replayed, so the hand-off window opens as soon as the thread is idle again; a client that
     * never polls during the replay is therefore not delayed either. Any other run id — another
     * client's direct request, or a resume answer — does not open the window. The release takes
     * effect on this call; the queue has no callback into run starts. Read {@code
     * lastStartedRunId} before reading {@code idle}: a replay that starts between the two reads
     * then shows up as {@code idle == false} instead of as stale evidence.
     *
     * @param idle whether the thread currently has no active run and no unresolved interrupt
     * @param observedAtMillis when {@code idle} was determined, in epoch milliseconds
     * @param lastStartedRunId the run id the thread most recently started, or {@code null} when
     *     it never started one
     * @return the claimed message, or empty when nothing can be claimed
     */
    public synchronized Optional<QueuedMessage> claim(
            boolean idle, long observedAtMillis, String lastStartedRunId) {
        // Keep the window arithmetic away from overflow; an extreme caller timestamp must close
        // the window, not wrap it into the past and reopen it.
        long observedAt = Math.min(observedAtMillis, Long.MAX_VALUE - HAND_OFF_GUARD_MILLIS);
        if (lastStartedRunId != null && lastStartedRunId.equals(lastHandedOffRunId)) {
            // The run this delivery handed out is the one the thread last started, so the
            // delivered message itself was replayed and the window no longer protects it.
            handOffBlockedUntilMillis = 0L;
        }
        if (!idle) {
            if (observedAt > lastHandOffAtMillis) {
                // The caller's observation timestamp is strictly greater than the last
                // delivery's, so the busy observation is treated as newer than that delivery:
                // the next idle window may deliver again.
                handOffBlockedUntilMillis = 0L;
            }
            return Optional.empty();
        }
        if (pending.isEmpty() || observedAt < handOffBlockedUntilMillis) {
            return Optional.empty();
        }
        Iterator<Map.Entry<String, QueuedMessage>> iterator = pending.entrySet().iterator();
        Map.Entry<String, QueuedMessage> first = iterator.next();
        iterator.remove();
        remember(
                first.getKey(),
                trailingMessage(first.getValue().getInput().getMessages()),
                QueuedMessage.State.CLAIMED);
        QueuedMessage delivered = withFreshRunId(first.getValue());
        lastHandOffAtMillis = observedAt;
        handOffBlockedUntilMillis = observedAt + HAND_OFF_GUARD_MILLIS;
        lastHandedOffRunId = delivered.getInput().getRunId();
        return Optional.of(delivered);
    }

    /**
     * Withdraws a queued message.
     *
     * <p>Withdrawing a message that is still queued forgets it entirely, including its
     * deduplication entry, so the same identifier may be submitted again later. A message that was
     * already claimed is consumed and can no longer be withdrawn.
     *
     * @param messageId the message identifier
     * @return the withdraw outcome
     */
    public synchronized WithdrawStatus withdraw(String messageId) {
        QueuedMessage message = pending.remove(messageId);
        if (message != null) {
            ledger.remove(messageId);
            return WithdrawStatus.WITHDRAWN;
        }
        LedgerEntry entry = ledger.get(messageId);
        if (entry != null && entry.state() == QueuedMessage.State.CLAIMED) {
            return WithdrawStatus.ALREADY_CLAIMED;
        }
        return WithdrawStatus.NOT_FOUND;
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
            items.add(new QueueSnapshot.Item(message.getMessageId(), position));
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

    private void remember(
            String messageId, AguiMessage trailingMessage, QueuedMessage.State state) {
        ledger.put(messageId, new LedgerEntry(trailingMessage, state));
        while (ledger.size() > retention) {
            Iterator<String> iterator = ledger.keySet().iterator();
            iterator.next();
            iterator.remove();
        }
    }

    private record LedgerEntry(AguiMessage trailingMessage, QueuedMessage.State state) {}

    /**
     * Returns a copy of a claimed message whose input carries a fresh run id.
     *
     * <p>A client usually queues the body it also sent to the run endpoint, so the queued run id
     * can be the id of the run that was active when the message was accepted. Replaying that id
     * makes two runs share one identifier, which clients cannot tell apart and which gateways
     * that de-duplicate requests on the run id reject, so a claim hands out an input that is safe
     * to replay verbatim. Every other field of the body is preserved.
     *
     * @param message the accepted message
     * @return the message with a replayable input
     */
    private static QueuedMessage withFreshRunId(QueuedMessage message) {
        RunAgentInput input = message.getInput();
        RunAgentInput replayable =
                RunAgentInput.builder()
                        .threadId(input.getThreadId())
                        .runId(UUID.randomUUID().toString())
                        .messages(input.getMessages())
                        .tools(input.getTools())
                        .context(input.getContext())
                        .state(input.getState())
                        .forwardedProps(input.getForwardedProps())
                        .resume(input.getResume())
                        .build();
        return QueuedMessage.create(message.getMessageId(), replayable, message.getAcceptedAt());
    }

    /**
     * Returns the message that identifies a submission: the trailing message of the request body.
     *
     * <p>It carries both the text of a new prompt and the tool result a client appends when it
     * resumes an interrupted run, so comparing it separates a retry from a different submission
     * that reused the identifier.
     */
    private static AguiMessage trailingMessage(List<AguiMessage> messages) {
        if (messages == null || messages.isEmpty()) {
            return null;
        }
        return messages.get(messages.size() - 1);
    }
}
