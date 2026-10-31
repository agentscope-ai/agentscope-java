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

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.spy;

import java.time.Duration;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.BooleanSupplier;
import org.junit.jupiter.api.Test;
import reactor.core.Disposable;

/**
 * Disposing a {@link SessionRecorder} flush must cancel the signal, never the storage work: an
 * interrupted {@code renew}/{@code commit} leaves the durable outcome unknown and cannot be
 * retried safely.
 */
class SessionRecorderCancellationTest {

    private static final SessionKey KEY = new SessionKey("user-a", "agent-a", "cancel-flush");
    private static final Duration TIMEOUT = Duration.ofSeconds(10);

    @Test
    void disposingFlushDoesNotInterruptJournalIo() throws Exception {
        SessionLog log = spy(new InMemorySessionLogStore().open(KEY, null));

        CountDownLatch insideRenew = new CountDownLatch(1);
        CountDownLatch resume = new CountDownLatch(1);
        CountDownLatch renewReturned = new CountDownLatch(1);
        AtomicBoolean interrupted = new AtomicBoolean();
        AtomicBoolean first = new AtomicBoolean(true);
        doAnswer(
                        invocation -> {
                            if (first.compareAndSet(true, false)) {
                                insideRenew.countDown();
                                try {
                                    resume.await(TIMEOUT.toSeconds(), TimeUnit.SECONDS);
                                } catch (InterruptedException e) {
                                    interrupted.set(true);
                                    Thread.currentThread().interrupt();
                                }
                            }
                            try {
                                return invocation.callRealMethod();
                            } finally {
                                renewReturned.countDown();
                            }
                        })
                .when(log)
                .renew(any(), any());

        SessionRecorder recorder = new SessionRecorder(log, "turn-cancel");
        try {
            recorder.append(
                    "message/user",
                    Map.of(
                            "message",
                            io.agentscope.core.message.Msg.builder()
                                    .role(io.agentscope.core.message.MsgRole.USER)
                                    .textContent("queued")
                                    .build()));

            Disposable flush = recorder.flush().subscribe();
            assertTrue(
                    insideRenew.await(TIMEOUT.toSeconds(), TimeUnit.SECONDS),
                    "flush never reached the journal");

            // The cancellation under test: a caller (or Mono.using cleanup) disposing the flush.
            flush.dispose();
            resume.countDown();

            assertTrue(
                    renewReturned.await(TIMEOUT.toSeconds(), TimeUnit.SECONDS),
                    "the storage work never finished");
            assertFalse(
                    interrupted.get(),
                    "disposing flush interrupted the thread executing journal I/O");

            // The queued event must still reach the journal.
            awaitTrue(() -> recorder.durableSeq() > 0, "the queued event was never committed");
        } finally {
            recorder.close();
        }
    }

    private static void awaitTrue(BooleanSupplier condition, String message) {
        long deadline = System.nanoTime() + TIMEOUT.toNanos();
        while (!condition.getAsBoolean()) {
            if (System.nanoTime() >= deadline) throw new AssertionError(message);
            try {
                Thread.sleep(10);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new AssertionError(message, e);
            }
        }
    }
}
