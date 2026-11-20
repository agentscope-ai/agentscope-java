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

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.agentscope.core.agui.model.AguiContext;
import io.agentscope.core.agui.model.AguiMessage;
import io.agentscope.core.agui.model.AguiResume;
import io.agentscope.core.agui.model.AguiTool;
import io.agentscope.core.agui.model.RunAgentInput;
import io.agentscope.core.util.JsonUtils;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

class ThreadMessageQueueTest {

    @Test
    void shouldExposeQueuedMessageFields() {
        RunAgentInput input = input("t1", "r1", "m1", "hi");
        QueuedMessage message = QueuedMessage.create("m1", input, 123L);

        assertEquals("m1", message.getMessageId());
        assertEquals("t1", message.getThreadId());
        assertEquals("r1", message.getRunId());
        assertSame(input, message.getInput());
        assertEquals(123L, message.getAcceptedAt());
    }

    @Test
    void shouldAcceptThenDeduplicateSameIdAndContent() {
        ThreadMessageQueue queue = new ThreadMessageQueue(16, 64);
        RunAgentInput input = input("t1", "r1", "m1", "hello");

        QueueAcceptance first = queue.offer("m1", input);
        // A separate but equal body must be recognised as a retry, not as a new message.
        QueueAcceptance second = queue.offer("m1", input("t1", "r1", "m1", "hello"));

        assertEquals(QueueAcceptance.Status.ACCEPTED, first.status());
        assertEquals(1, first.position());
        assertEquals(QueueAcceptance.Status.DUPLICATE, second.status());
        assertEquals(1, second.position());
        assertEquals("QUEUED", second.state());
        assertEquals(1, queue.snapshot(false, false).items().size());
    }

    @Test
    void shouldRejectSameIdWithDifferentContent() {
        ThreadMessageQueue queue = new ThreadMessageQueue(16, 64);
        queue.offer("m1", input("t1", "r1", "m1", "hello"));

        QueueAcceptance result = queue.offer("m1", input("t1", "r2", "m1", "changed"));

        assertEquals(QueueAcceptance.Status.CONFLICT, result.status());
        assertEquals(1, queue.snapshot(false, false).items().size());
    }

    @Test
    void shouldRejectWhenFullAndAcceptAfterWithdraw() {
        ThreadMessageQueue queue = new ThreadMessageQueue(2, 64);
        queue.offer("m1", input("t1", "r1", "m1", "a"));
        queue.offer("m2", input("t1", "r2", "m2", "b"));

        QueueAcceptance full = queue.offer("m3", input("t1", "r3", "m3", "c"));
        assertEquals(QueueAcceptance.Status.FULL, full.status());

        assertEquals(WithdrawStatus.WITHDRAWN, queue.withdraw("m1"));
        QueueAcceptance retried = queue.offer("m3", input("t1", "r3", "m3", "c"));
        assertEquals(QueueAcceptance.Status.ACCEPTED, retried.status());
    }

    @Test
    void shouldForgetOldLedgerEntriesBeyondRetention() {
        ThreadMessageQueue queue = new ThreadMessageQueue(2, 2);
        queue.offer("m0", input("t1", "r0", "m0", "x"));
        assertEquals("m0", queue.claim(true, 0L).orElseThrow().getMessageId());

        // Two new identifiers push the claimed one out of the ledger.
        queue.offer("m1", input("t1", "r1", "m1", "x"));
        queue.offer("m2", input("t1", "r2", "m2", "x"));
        assertEquals(WithdrawStatus.WITHDRAWN, queue.withdraw("m1"));

        QueueAcceptance reoffered = queue.offer("m0", input("t1", "r0", "m0", "different"));

        assertEquals(QueueAcceptance.Status.ACCEPTED, reoffered.status());
    }

    @Test
    void shouldRejectRetentionSmallerThanCapacity() {
        assertThrows(IllegalArgumentException.class, () -> new ThreadMessageQueue(4, 2));
    }

    @Test
    void shouldWithdrawOnlyQueuedMessages() {
        ThreadMessageQueue queue = new ThreadMessageQueue(16, 64);
        queue.offer("m1", input("t1", "r1", "m1", "a"));

        assertEquals(WithdrawStatus.WITHDRAWN, queue.withdraw("m1"));
        assertEquals(WithdrawStatus.NOT_FOUND, queue.withdraw("m1"));
        assertTrue(queue.claim(true).isEmpty());
    }

    @Test
    void shouldKeepTheIdentifierProtectedAfterWithdraw() {
        ThreadMessageQueue queue = new ThreadMessageQueue(16, 64);
        RunAgentInput input = input("t1", "r1", "m1", "a");
        queue.offer("m1", input);

        assertEquals(WithdrawStatus.WITHDRAWN, queue.withdraw("m1"));

        // A retry that arrives after a lost withdrawal response must not enqueue the message
        // again, or the withdrawal the user already sent would be undone. Only the trailing
        // message is compared, so the retry may carry a different run id.
        QueueAcceptance retry = queue.offer("m1", input("t1", "r-retry", "m1", "a"));
        assertEquals(QueueAcceptance.Status.DUPLICATE, retry.status());
        assertEquals("WITHDRAWN", retry.state());
        assertEquals(-1, retry.position());
        assertTrue(queue.claim(true).isEmpty());

        // The retained identifier stays bound to the body that was withdrawn.
        QueueAcceptance reused = queue.offer("m1", input("t1", "r2", "m1", "different"));
        assertEquals(QueueAcceptance.Status.CONFLICT, reused.status());
    }

    @Test
    void shouldNotEvictAQueuedIdentifierWhenWithdrawalsFillTheLedger() {
        // A withdrawal keeps its identifier in the ledger. When the ledger is full, the oldest
        // entry is evicted, but never one that is still queued: that would let the retry in
        // under a new body instead of rejecting it as a conflict.
        ThreadMessageQueue queue = new ThreadMessageQueue(2, 2);
        RunAgentInput waiting = input("t1", "r1", "m1", "a");
        queue.offer("m1", waiting);
        queue.offer("m2", input("t1", "r2", "m2", "b"));
        assertEquals(WithdrawStatus.WITHDRAWN, queue.withdraw("m2"));

        queue.offer("m3", input("t1", "r3", "m3", "c"));

        // The withdrawn id was the only entry that could be evicted, so the ledger no longer
        // protects it: reusing the id is not a conflict, and the queue is full anyway.
        assertEquals(
                QueueAcceptance.Status.FULL,
                queue.offer("m2", input("t1", "r5", "m2", "changed")).status());
        assertEquals(QueueAcceptance.Status.DUPLICATE, queue.offer("m1", waiting).status());
        assertEquals(
                QueueAcceptance.Status.CONFLICT,
                queue.offer("m1", input("t1", "r4", "m1", "different")).status());
    }

    @Test
    void shouldCompareOnlyTheTrailingMessageForDuplicates() {
        ThreadMessageQueue queue = new ThreadMessageQueue(16, 64);
        queue.offer(
                "m1",
                RunAgentInput.builder()
                        .threadId("t1")
                        .runId("r1")
                        .messages(
                                List.of(
                                        AguiMessage.userMessage("m0", "earlier"),
                                        AguiMessage.userMessage("m1", "a")))
                        .build());

        // Everything but the trailing message may differ and still count as a retry.
        QueueAcceptance retry =
                queue.offer(
                        "m1",
                        RunAgentInput.builder()
                                .threadId("t1")
                                .runId("r2")
                                .messages(
                                        List.of(
                                                AguiMessage.userMessage("m0b", "changed"),
                                                AguiMessage.userMessage("m1", "a")))
                                .build());

        assertEquals(QueueAcceptance.Status.DUPLICATE, retry.status());
        assertEquals(1, retry.position());
    }

    @Test
    void shouldReportAlreadyClaimedOnWithdraw() {
        ThreadMessageQueue queue = new ThreadMessageQueue(16, 64);
        queue.offer("m1", input("t1", "r1", "m1", "a"));
        assertEquals("m1", queue.claim(true, 0L).orElseThrow().getMessageId());

        assertEquals(WithdrawStatus.ALREADY_CLAIMED, queue.withdraw("m1"));
    }

    @Test
    void shouldReportClaimedStateForRetryAfterClaim() {
        ThreadMessageQueue queue = new ThreadMessageQueue(16, 64);
        RunAgentInput input = input("t1", "r1", "m1", "a");
        queue.offer("m1", input);
        queue.claim(true, 0L);

        QueueAcceptance retry = queue.offer("m1", input);

        assertEquals(QueueAcceptance.Status.DUPLICATE, retry.status());
        assertEquals("CLAIMED", retry.state());
        assertEquals(-1, retry.position());
    }

    @Test
    void shouldNotClaimWhenNotIdle() {
        ThreadMessageQueue queue = new ThreadMessageQueue(16, 64);
        queue.offer("m1", input("t1", "r1", "m1", "a"));

        assertTrue(queue.claim(false).isEmpty());
        assertEquals(1, queue.snapshot(true, false).items().size());
    }

    @Test
    void shouldGiveTheClaimedInputAFreshRunId() {
        ThreadMessageQueue queue = new ThreadMessageQueue(16, 64);
        RunAgentInput input =
                RunAgentInput.builder()
                        .threadId("t1")
                        .runId("r-original")
                        .messages(List.of(AguiMessage.userMessage("m1", "a")))
                        .tools(List.of(new AguiTool("search", "find things", Map.of("q", "text"))))
                        .context(List.of(new AguiContext("docs", "selected pages")))
                        .state(Map.of("step", 2))
                        .forwardedProps(Map.of("agent", "helper"))
                        .resume(List.of(new AguiResume("i1", "resolved", "ok")))
                        .build();
        queue.offer("m1", input);

        QueuedMessage claimed = queue.claim(true, 0L).orElseThrow();
        String replayRunId = claimed.getInput().getRunId();

        // A queued body usually reuses the id of the run that was active when it was accepted,
        // and replaying that id makes two runs share one identifier. A claim mints a fresh one
        // and must keep every other field of the body intact, so compare the full encoded bodies
        // after folding the fresh id back onto the original one.
        assertNotEquals("r-original", replayRunId);
        assertFalse(replayRunId == null || replayRunId.isBlank());
        String original = JsonUtils.getJsonCodec().toJson(input);
        String replayable =
                JsonUtils.getJsonCodec()
                        .toJson(claimed.getInput())
                        .replace(replayRunId, "r-original");
        assertEquals(original, replayable);
    }

    @Test
    void shouldNotReleaseHandOffForABusyObservationOlderThanTheDelivery() {
        ThreadMessageQueue queue = new ThreadMessageQueue(16, 64);
        queue.offer("m1", input("t1", "r1", "m1", "a"));
        queue.offer("m2", input("t1", "r2", "m2", "b"));

        assertEquals("m1", queue.claim(true, 1_000L).orElseThrow().getMessageId());
        // A poll that read "busy" before the delivery above must not open the next window; the
        // same millisecond cannot prove the observation followed the delivery either.
        assertTrue(queue.claim(false, 999L).isEmpty());
        assertTrue(queue.claim(false, 1_000L).isEmpty());
        assertTrue(queue.claim(true, 2_000L).isEmpty());
        // A busy observation made strictly after the delivery reflects the replayed run.
        assertTrue(queue.claim(false, 1_500L).isEmpty());
        assertEquals("m2", queue.claim(true, 2_500L).orElseThrow().getMessageId());
    }

    @Test
    void shouldKeepTheWindowClosedForAnExtremeObservationTime() {
        ThreadMessageQueue queue = new ThreadMessageQueue(16, 64);
        queue.offer("m1", input("t1", "r1", "m1", "a"));
        queue.offer("m2", input("t1", "r2", "m2", "b"));

        assertEquals("m1", queue.claim(true, Long.MAX_VALUE).orElseThrow().getMessageId());
        // An extreme observation time must not overflow the window into the past and hand out
        // the next message in the same idle period.
        assertTrue(queue.claim(true, Long.MAX_VALUE).isEmpty());
    }

    @Test
    void shouldDeliverAMessageOfferedAfterTheReleasedWindow() {
        ThreadMessageQueue queue = new ThreadMessageQueue(16, 64);
        queue.offer("m1", input("t1", "r1", "m1", "a"));
        String replayRunId = queue.claim(true, 1_000L).orElseThrow().getInput().getRunId();

        // The identity evidence released the window while nothing was queued; a message
        // offered afterwards is claimable immediately instead of waiting out a stale window.
        assertTrue(queue.claim(true, 2_000L, replayRunId).isEmpty());
        queue.offer("m2", input("t1", "r2", "m2", "b"));
        assertEquals("m2", queue.claim(true, 3_000L).orElseThrow().getMessageId());
    }

    @Test
    void shouldOpenTheWindowWhenTheDeliveredRunsOwnReplayStarted() {
        ThreadMessageQueue queue = new ThreadMessageQueue(16, 64);
        queue.offer("m1", input("t1", "r1", "m1", "a"));
        queue.offer("m2", input("t1", "r2", "m2", "b"));

        String replayRunId = queue.claim(true, 1_000L).orElseThrow().getInput().getRunId();
        // The delivered message was replayed verbatim, so the thread's last started run is the
        // very id this delivery handed out; the next claim delivers immediately instead of
        // waiting out the five-second window.
        assertEquals("m2", queue.claim(true, 2_000L, replayRunId).orElseThrow().getMessageId());
    }

    @Test
    void shouldKeepTheWindowForAnyOtherRun() {
        ThreadMessageQueue queue = new ThreadMessageQueue(16, 64);
        queue.offer("m1", input("t1", "r1", "m1", "a"));
        queue.offer("m2", input("t1", "r2", "m2", "b"));

        queue.claim(true, 1_000L);
        // Another client's direct run, a resume answer, or no run at all never matches the id
        // this delivery handed out, so only the grace period opens the next window.
        assertTrue(queue.claim(true, 2_000L, "another-run").isEmpty());
        assertTrue(queue.claim(true, 2_000L, null).isEmpty());
        assertTrue(queue.claim(true, 5_999L, "another-run").isEmpty());
        assertEquals("m2", queue.claim(true, 6_000L, "another-run").orElseThrow().getMessageId());
    }

    @Test
    void shouldClaimInAcceptanceOrderAndHoldTheHandOff() {
        ThreadMessageQueue queue = new ThreadMessageQueue(16, 64);
        queue.offer("m1", input("t1", "r1", "m1", "a"));
        queue.offer("m2", input("t1", "r2", "m2", "b"));

        assertEquals("m1", queue.claim(true, 0L).orElseThrow().getMessageId());
        // The same idle window must not hand out a second message.
        assertTrue(queue.claim(true, 1_000L).isEmpty());
        assertEquals("m2", queue.claim(true, 6_000L).orElseThrow().getMessageId());
        assertTrue(queue.claim(true, 12_000L).isEmpty());
        assertEquals(0, queue.snapshot(true, false).items().size());
    }

    @Test
    void shouldReleaseHandOffOnceTheThreadIsObservedBusy() {
        ThreadMessageQueue queue = new ThreadMessageQueue(16, 64);
        queue.offer("m1", input("t1", "r1", "m1", "a"));
        queue.offer("m2", input("t1", "r2", "m2", "b"));

        assertEquals("m1", queue.claim(true, 0L).orElseThrow().getMessageId());
        assertTrue(queue.claim(false, 1L).isEmpty());

        // The claimed message started a run, so the next idle window is free again.
        assertEquals("m2", queue.claim(true, 2L).orElseThrow().getMessageId());
    }

    @Test
    void shouldLetExactlyOneConcurrentClaimWinWithTwoMessagesQueued() throws Exception {
        ThreadMessageQueue queue = new ThreadMessageQueue(16, 64);
        queue.offer("m1", input("t1", "r1", "m1", "a"));
        queue.offer("m2", input("t1", "r2", "m2", "b"));

        ExecutorService pool = Executors.newFixedThreadPool(8);
        CountDownLatch start = new CountDownLatch(1);
        AtomicInteger wins = new AtomicInteger();
        List<Future<?>> futures = new ArrayList<>();
        try {
            for (int i = 0; i < 8; i++) {
                futures.add(
                        pool.submit(
                                () -> {
                                    start.await();
                                    if (queue.claim(true, 0L).isPresent()) {
                                        wins.incrementAndGet();
                                    }
                                    return null;
                                }));
            }
            start.countDown();
            for (Future<?> future : futures) {
                future.get(5, TimeUnit.SECONDS);
            }
        } finally {
            pool.shutdownNow();
        }

        // Only one delivery per idle window, even though a second message is waiting.
        assertEquals(1, wins.get());
        assertEquals(1, queue.snapshot(false, false).items().size());
    }

    private static RunAgentInput input(
            String threadId, String runId, String messageId, String text) {
        return RunAgentInput.builder()
                .threadId(threadId)
                .runId(runId)
                .messages(List.of(AguiMessage.userMessage(messageId, text)))
                .build();
    }
}
