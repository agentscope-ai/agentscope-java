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
package io.agentscope.core.journal;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

import io.agentscope.core.agent.ThreadKey;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

@Timeout(15)
class InvocationRecorderAsyncCommitTest {
    @Test
    void slowCommitDoesNotLockAppendAndConcurrentBarriersCoalesceInOrder() throws Exception {
        var log = log();
        var entered = new CountDownLatch(1);
        var release = new CountDownLatch(1);
        var commits = new AtomicInteger();
        doAnswer(
                        call -> {
                            assertTrue(
                                    Thread.currentThread()
                                            .getName()
                                            .startsWith("agentscope-session-commit"));
                            if (commits.incrementAndGet() == 1) {
                                entered.countDown();
                                assertTrue(release.await(5, TimeUnit.SECONDS));
                            }
                            return call.callRealMethod();
                        })
                .when(log)
                .commit(any(), anyString(), anyLong(), anyList());
        var recorder = new InvocationRecorder(log, "turn", "run", null);
        try {
            recorder.append("step/start", Map.of("stepId", "first", "iteration", 0));
            var first = recorder.flush().toFuture();
            assertTrue(entered.await(5, TimeUnit.SECONDS));
            var samePrefix = recorder.flush().toFuture();
            CompletableFuture.runAsync(
                            () ->
                                    recorder.append(
                                            "step/start",
                                            Map.of("stepId", "second", "iteration", 1)))
                    .get(1, TimeUnit.SECONDS);
            var second = recorder.flush().toFuture();
            assertFalse(first.isDone());
            assertFalse(second.isDone());
            release.countDown();
            CompletableFuture.allOf(first, samePrefix, second).get(5, TimeUnit.SECONDS);
            assertEquals(2, commits.get());
            assertEquals(
                    List.of(1L, 2L), log.readAfter(0, 10).stream().map(JournalEvent::seq).toList());
        } finally {
            release.countDown();
            recorder.close();
        }
    }

    @Test
    void cancellingOneWaiterDoesNotCancelAnotherDurableBarrier() throws Exception {
        var log = log();
        var entered = new CountDownLatch(1);
        var release = new CountDownLatch(1);
        doAnswer(
                        call -> {
                            entered.countDown();
                            assertTrue(release.await(5, TimeUnit.SECONDS));
                            return call.callRealMethod();
                        })
                .when(log)
                .commit(any(), anyString(), anyLong(), anyList());
        var recorder = new InvocationRecorder(log, "turn", "run", null);
        try {
            recorder.append("step/start", Map.of("stepId", "first", "iteration", 0));
            var cancelled = recorder.flush().toFuture();
            assertTrue(entered.await(5, TimeUnit.SECONDS));
            var surviving = recorder.flush().toFuture();
            cancelled.cancel(true);
            release.countDown();
            surviving.get(5, TimeUnit.SECONDS);
            assertEquals(1, log.head().seq());
            verify(log, times(1)).commit(any(), anyString(), anyLong(), anyList());
        } finally {
            release.countDown();
            recorder.close();
        }
    }

    @Test
    void staleWriterFailsAllBarriersWithoutCommitting() {
        var log = log();
        var recorder = new InvocationRecorder(log, "turn", "run", null);
        try {
            recorder.append("step/start", Map.of("stepId", "first", "iteration", 0));
            doThrow(new JournalException("writer fenced")).when(log).renew(any(), any());
            assertThrows(
                    JournalException.class, () -> recorder.flush().block(Duration.ofSeconds(5)));
            assertThrows(
                    JournalException.class, () -> recorder.flush().block(Duration.ofSeconds(5)));
            verify(log, never()).commit(any(), anyString(), anyLong(), anyList());
            assertEquals(0, log.head().seq());
        } finally {
            recorder.close();
        }
    }

    @Test
    void idleRecorderDoesNotFlushAndDirtyNotificationsShareMaintenanceBatch() throws Exception {
        var log = log();
        var recorder = new InvocationRecorder(log, "turn", "run", null);
        var committed = new CountDownLatch(1);
        try {
            Thread.sleep(1200);
            verify(log, never()).renew(any(), any());
            verify(log, never()).commit(any(), anyString(), anyLong(), anyList());
            doAnswer(
                            call -> {
                                assertTrue(
                                        Thread.currentThread()
                                                .getName()
                                                .startsWith("agentscope-session-commit"));
                                var result = call.callRealMethod();
                                committed.countDown();
                                return result;
                            })
                    .when(log)
                    .commit(any(), anyString(), anyLong(), anyList());
            for (int i = 0; i < 20; i++)
                recorder.append("step/start", Map.of("stepId", "step-" + i, "iteration", i));
            assertTrue(committed.await(5, TimeUnit.SECONDS));
            assertEquals(20, log.head().seq());
            verify(log, times(1)).commit(any(), anyString(), anyLong(), anyList());
        } finally {
            recorder.close();
        }
    }

    @Test
    void appendedSuffixUsesReceiptSequenceWhenBackendFiltersDiagnostics() throws Exception {
        var log = log();
        var stored =
                new InMemoryThreadJournalStore()
                        .open(new ThreadKey("u", "a", "filtered", "main"), null);
        var writer = stored.acquire("run", Duration.ofMinutes(2));
        var entered = new CountDownLatch(1);
        var release = new CountDownLatch(1);
        var number = new AtomicInteger();
        doAnswer(
                        call -> {
                            List<JournalEvent> events = call.getArgument(3);
                            long expected = call.getArgument(2);
                            if (number.incrementAndGet() == 1) {
                                entered.countDown();
                                assertTrue(release.await(5, TimeUnit.SECONDS));
                                events = events.subList(0, 1);
                            }
                            return stored.commit(writer, call.getArgument(1), expected, events);
                        })
                .when(log)
                .commit(any(), anyString(), anyLong(), anyList());
        var recorder = new InvocationRecorder(log, "turn", "run", null);
        try {
            recorder.append("step/start", Map.of("stepId", "first", "iteration", 0));
            recorder.append("step/end", Map.of("stepId", "first", "status", "continued"));
            var first = recorder.flush().toFuture();
            assertTrue(entered.await(5, TimeUnit.SECONDS));
            recorder.append("step/start", Map.of("stepId", "next", "iteration", 1));
            var next = recorder.flush().toFuture();
            release.countDown();
            CompletableFuture.allOf(first, next).get(5, TimeUnit.SECONDS);
            assertEquals(
                    List.of(1L, 2L),
                    stored.readAfter(0, 10).stream().map(JournalEvent::seq).toList());
        } finally {
            release.countDown();
            recorder.close();
        }
    }

    @Test
    void failedCommitDoesNotAcknowledgeOrContinueTheModelBoundary() {
        var log = log();
        var recorder = new InvocationRecorder(log, "turn", "run", null);
        try {
            recorder.append("step/start", Map.of("stepId", "first", "iteration", 0));
            doThrow(new JournalException("storage failed"))
                    .when(log)
                    .commit(any(), anyString(), anyLong(), anyList());
            var proceeded = new AtomicInteger();
            assertThrows(
                    JournalException.class,
                    () ->
                            recorder.flush()
                                    .doOnSuccess(ignored -> proceeded.incrementAndGet())
                                    .block(Duration.ofSeconds(5)));
            assertEquals(0, proceeded.get());
            assertEquals(0, recorder.durableSeq());
            assertThrows(
                    JournalException.class,
                    () -> recorder.append("step/start", Map.of("stepId", "next", "iteration", 1)));
        } finally {
            recorder.close();
        }
    }

    @Test
    void closingAfterOneAcknowledgementDoesNotRevokeAnotherWaitersCommittedPrefix()
            throws Exception {
        var log = log();
        var entered = new CountDownLatch(1);
        var release = new CountDownLatch(1);
        doAnswer(
                        call -> {
                            entered.countDown();
                            assertTrue(release.await(5, TimeUnit.SECONDS));
                            return call.callRealMethod();
                        })
                .when(log)
                .commit(any(), anyString(), anyLong(), anyList());
        var recorder = new InvocationRecorder(log, "turn", "run", null);
        try {
            recorder.append("step/start", Map.of("stepId", "first", "iteration", 0));
            var closing = recorder.flush().doOnSuccess(ignored -> recorder.close()).toFuture();
            assertTrue(entered.await(5, TimeUnit.SECONDS));
            var alsoAcknowledged = recorder.flush().toFuture();
            release.countDown();
            CompletableFuture.allOf(closing, alsoAcknowledged).get(5, TimeUnit.SECONDS);
            assertEquals(1, log.head().seq());
        } finally {
            release.countDown();
            recorder.close();
        }
    }

    private ThreadJournal log() {
        return spy(
                new InMemoryThreadJournalStore().open(new ThreadKey("u", "a", "s", "main"), null));
    }
}
