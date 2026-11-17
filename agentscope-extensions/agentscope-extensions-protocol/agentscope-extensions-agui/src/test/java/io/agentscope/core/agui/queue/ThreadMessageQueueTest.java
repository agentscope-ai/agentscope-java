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
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.agentscope.core.agui.model.AguiMessage;
import io.agentscope.core.agui.model.RunAgentInput;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

class ThreadMessageQueueTest {

    @Test
    void shouldExposeQueuedMessageFieldsAndState() {
        RunAgentInput input = input("t1", "r1", "m1", "hi");
        QueuedMessage message = QueuedMessage.create("m1", input, last(input), 123L);

        assertEquals("m1", message.getMessageId());
        assertEquals("t1", message.getThreadId());
        assertEquals("r1", message.getRunId());
        assertSame(input, message.getInput());
        assertEquals(123L, message.getAcceptedAt());
        assertEquals(QueuedMessage.State.QUEUED, message.getState());

        message.markClaimed();

        assertEquals(QueuedMessage.State.CLAIMED, message.getState());
    }

    @Test
    void shouldAcceptThenDeduplicateSameIdAndContent() {
        ThreadMessageQueue queue = new ThreadMessageQueue(16, 64);
        RunAgentInput input = input("t1", "r1", "m1", "hello");

        QueueAcceptance first = queue.offer("m1", input, last(input));
        QueueAcceptance second = queue.offer("m1", input, last(input));

        assertEquals(QueueAcceptance.Status.ACCEPTED, first.status());
        assertEquals(1, first.position());
        assertEquals(QueueAcceptance.Status.DUPLICATE, second.status());
        assertEquals(1, second.position());
        assertEquals(1, queue.snapshot(false, false).items().size());
    }

    @Test
    void shouldRejectSameIdWithDifferentContent() {
        ThreadMessageQueue queue = new ThreadMessageQueue(16, 64);
        RunAgentInput original = input("t1", "r1", "m1", "hello");
        queue.offer("m1", original, last(original));

        RunAgentInput changed = input("t1", "r2", "m1", "changed");
        QueueAcceptance result = queue.offer("m1", changed, last(changed));

        assertEquals(QueueAcceptance.Status.CONFLICT, result.status());
        assertEquals(1, queue.snapshot(false, false).items().size());
    }

    @Test
    void shouldRejectWhenFullAndAcceptAfterWithdraw() {
        ThreadMessageQueue queue = new ThreadMessageQueue(2, 64);
        queue.offer("m1", input("t1", "r1", "m1", "a"), last(input("t1", "r1", "m1", "a")));
        queue.offer("m2", input("t1", "r2", "m2", "b"), last(input("t1", "r2", "m2", "b")));

        QueueAcceptance full =
                queue.offer("m3", input("t1", "r3", "m3", "c"), last(input("t1", "r3", "m3", "c")));
        assertEquals(QueueAcceptance.Status.FULL, full.status());

        assertEquals(WithdrawStatus.WITHDRAWN, queue.withdraw("m1"));
        QueueAcceptance retried =
                queue.offer("m3", input("t1", "r3", "m3", "c"), last(input("t1", "r3", "m3", "c")));
        assertEquals(QueueAcceptance.Status.ACCEPTED, retried.status());
    }

    @Test
    void shouldForgetOldLedgerEntriesBeyondRetention() {
        ThreadMessageQueue queue = new ThreadMessageQueue(16, 2);
        queue.offer("m0", input("t1", "r0", "m0", "x"), last(input("t1", "r0", "m0", "x")));
        queue.offer("m1", input("t1", "r1", "m1", "x"), last(input("t1", "r1", "m1", "x")));
        queue.offer("m2", input("t1", "r2", "m2", "x"), last(input("t1", "r2", "m2", "x")));
        assertEquals(WithdrawStatus.WITHDRAWN, queue.withdraw("m0"));

        QueueAcceptance reoffered =
                queue.offer("m0", input("t1", "r0", "m0", "x"), last(input("t1", "r0", "m0", "x")));

        assertEquals(QueueAcceptance.Status.ACCEPTED, reoffered.status());
    }

    @Test
    void shouldWithdrawOnlyQueuedMessages() {
        ThreadMessageQueue queue = new ThreadMessageQueue(16, 64);
        RunAgentInput input = input("t1", "r1", "m1", "a");
        queue.offer("m1", input, last(input));

        assertEquals(WithdrawStatus.WITHDRAWN, queue.withdraw("m1"));
        assertEquals(WithdrawStatus.NOT_FOUND, queue.withdraw("m1"));
        assertTrue(queue.claim(true).isEmpty());
    }

    @Test
    void shouldNotClaimWhenNotIdle() {
        ThreadMessageQueue queue = new ThreadMessageQueue(16, 64);
        RunAgentInput input = input("t1", "r1", "m1", "a");
        queue.offer("m1", input, last(input));

        assertTrue(queue.claim(false).isEmpty());
        assertEquals(1, queue.snapshot(true, false).items().size());
    }

    @Test
    void shouldClaimInAcceptanceOrder() {
        ThreadMessageQueue queue = new ThreadMessageQueue(16, 64);
        queue.offer("m1", input("t1", "r1", "m1", "a"), last(input("t1", "r1", "m1", "a")));
        queue.offer("m2", input("t1", "r2", "m2", "b"), last(input("t1", "r2", "m2", "b")));

        assertEquals("m1", queue.claim(true).orElseThrow().getMessageId());
        assertEquals(QueuedMessage.State.CLAIMED, queue.claim(true).orElseThrow().getState());
        assertTrue(queue.claim(true).isEmpty());
        assertEquals(0, queue.snapshot(true, false).items().size());
    }

    @Test
    void shouldLetExactlyOneConcurrentClaimWin() throws Exception {
        ThreadMessageQueue queue = new ThreadMessageQueue(16, 64);
        queue.offer("m1", input("t1", "r1", "m1", "a"), last(input("t1", "r1", "m1", "a")));

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
                                    if (queue.claim(true).isPresent()) {
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

        assertEquals(1, wins.get());
    }

    private static RunAgentInput input(
            String threadId, String runId, String messageId, String text) {
        return RunAgentInput.builder()
                .threadId(threadId)
                .runId(runId)
                .messages(List.of(AguiMessage.userMessage(messageId, text)))
                .build();
    }

    private static AguiMessage last(RunAgentInput input) {
        return input.getMessages().get(input.getMessages().size() - 1);
    }
}
