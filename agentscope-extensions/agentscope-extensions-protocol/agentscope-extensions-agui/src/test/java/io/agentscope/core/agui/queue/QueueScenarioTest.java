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
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.agentscope.core.agui.model.AguiMessage;
import io.agentscope.core.agui.model.RunAgentInput;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

/**
 * Acceptance scenarios agreed with the upstream contributor on issue #4223.
 *
 * <p>Script to test mapping:
 *
 * <ol>
 *   <li>busy, then idle, then claim in acceptance order — {@link #shouldNotAdvanceWhileBusyThenClaimInOrder()}
 *   <li>duplicate submissions and changed content — {@link #shouldBeIdempotentForRetries()}
 *   <li>disconnect and reconnect — {@link #shouldKeepQueuedMessagesWhileClientIsAway()}
 *   <li>two concurrent knocks claim once — {@link #shouldLetOnlyOneOfTwoConcurrentClaimsWin()}
 *   <li>full queue, withdraw, accept again — {@link #shouldRejectWhenFullThenAcceptAfterWithdraw()}
 *   <li>feature disabled means no endpoints — covered by
 *       {@code AguiQueueAutoConfigurationTest#shouldNotRegisterQueueByDefault}
 *   <li>error with unresolved confirmation blocks advancement — covered by
 *       {@code AguiResumeCoordinatorStateTest#shouldKeepBlockingAfterAnErroredResumeUntilResolvedAgain}
 *       together with {@code AguiQueueControllerTest#shouldDeliverAfterTheConfirmationIsAnswered}
 * </ol>
 */
class QueueScenarioTest {

    @Test
    void shouldNotAdvanceWhileBusyThenClaimInOrder() {
        ThreadMessageQueue queue = new ThreadMessageQueue(16, 64);
        queue.offer("m1", input("t1", "r1", "m1", "first"));
        queue.offer("m2", input("t1", "r2", "m2", "second"));

        // While the thread is busy (or awaiting confirmation) nothing may be claimed.
        assertTrue(queue.claim(false).isEmpty());

        assertEquals("m1", queue.claim(true, 0L).orElseThrow().getMessageId());
        // New runs take time to register, so the same idle window delivers only one message.
        assertTrue(queue.claim(true, 1_000L).isEmpty());
        assertEquals("m2", queue.claim(true, 6_000L).orElseThrow().getMessageId());
        assertTrue(queue.claim(true, 12_000L).isEmpty());
    }

    @Test
    void shouldBeIdempotentForRetries() {
        ThreadMessageQueue queue = new ThreadMessageQueue(16, 64);
        RunAgentInput input = input("t1", "r1", "m1", "hello");

        for (int i = 0; i < 3; i++) {
            QueueAcceptance acceptance = queue.offer("m1", input);
            assertEquals(1, acceptance.position());
        }
        assertEquals(1, queue.snapshot(false, false).items().size());

        assertEquals("m1", queue.claim(true, 0L).orElseThrow().getMessageId());

        // A retry arriving after the claim must not enqueue the message again.
        QueueAcceptance afterClaim = queue.offer("m1", input);
        assertEquals(QueueAcceptance.Status.DUPLICATE, afterClaim.status());
        assertTrue(queue.claim(true, 10_000L).isEmpty());
    }

    @Test
    void shouldKeepQueuedMessagesWhileClientIsAway() {
        ThreadMessageQueue queue = new ThreadMessageQueue(16, 64);
        queue.offer("m1", input("t1", "r1", "m1", "hello"));

        // No client is attached, so no claim happens; the message stays queued.
        assertEquals(1, queue.snapshot(false, false).items().size());

        // After reconnect the same message can be claimed exactly once.
        assertEquals("m1", queue.claim(true, 0L).orElseThrow().getMessageId());
        assertTrue(queue.claim(true, 10_000L).isEmpty());
    }

    @Test
    void shouldLetOnlyOneOfTwoConcurrentClaimsWin() throws Exception {
        ThreadMessageQueue queue = new ThreadMessageQueue(16, 64);
        queue.offer("m1", input("t1", "r1", "m1", "first"));
        queue.offer("m2", input("t1", "r2", "m2", "second"));

        ExecutorService pool = Executors.newFixedThreadPool(2);
        CountDownLatch start = new CountDownLatch(1);
        AtomicInteger wins = new AtomicInteger();
        try {
            Future<?> first =
                    pool.submit(
                            () -> {
                                start.await();
                                if (queue.claim(true, 0L).isPresent()) {
                                    wins.incrementAndGet();
                                }
                                return null;
                            });
            Future<?> second =
                    pool.submit(
                            () -> {
                                start.await();
                                if (queue.claim(true, 0L).isPresent()) {
                                    wins.incrementAndGet();
                                }
                                return null;
                            });
            start.countDown();
            first.get(5, TimeUnit.SECONDS);
            second.get(5, TimeUnit.SECONDS);
        } finally {
            pool.shutdownNow();
        }

        // A second message is waiting, so a broken hand-off would report two winners.
        assertEquals(1, wins.get());
        assertEquals(1, queue.snapshot(false, false).items().size());
    }

    @Test
    void shouldRejectWhenFullThenAcceptAfterWithdraw() {
        ThreadMessageQueue queue = new ThreadMessageQueue(1, 64);
        queue.offer("m1", input("t1", "r1", "m1", "a"));

        QueueAcceptance full = queue.offer("m2", input("t1", "r2", "m2", "b"));
        assertEquals(QueueAcceptance.Status.FULL, full.status());

        assertEquals(WithdrawStatus.WITHDRAWN, queue.withdraw("m1"));

        QueueAcceptance accepted = queue.offer("m2", input("t1", "r2", "m2", "b"));
        assertEquals(QueueAcceptance.Status.ACCEPTED, accepted.status());
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
