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
package io.agentscope.core.model;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.agentscope.core.message.TextBlock;
import java.io.IOException;
import java.io.InterruptedIOException;
import java.net.SocketTimeoutException;
import java.time.Duration;
import java.util.List;
import java.util.Queue;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import reactor.core.Disposable;
import reactor.core.Exceptions;
import reactor.core.publisher.Hooks;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;
import reactor.test.StepVerifier;

/** Provider-independent contracts for blocking model operations and cancellation. */
class BlockingModelCallTest {
    private static final String SCHEDULE_HOOK = "blocking-model-call-test";
    private final Queue<Throwable> droppedErrors = new ConcurrentLinkedQueue<>();
    private final AtomicReference<Thread> callThread = new AtomicReference<>();
    private final CountDownLatch entered = new CountDownLatch(1);
    private final CountDownLatch finished = new CountDownLatch(1);

    @BeforeEach
    void captureWorkerCompletionAndDroppedErrors() {
        Hooks.onErrorDropped(droppedErrors::add);
        Schedulers.onScheduleHook(
                SCHEDULE_HOOK,
                task ->
                        () -> {
                            try {
                                task.run();
                            } finally {
                                if (Thread.currentThread() == callThread.get()) {
                                    finished.countDown();
                                }
                            }
                        });
    }

    @AfterEach
    void resetHooks() {
        Schedulers.resetOnScheduleHook(SCHEDULE_HOOK);
        Hooks.resetOnErrorDropped();
    }

    @Test
    void eachSubscriptionDefersTheCallAndRunsOffTheCallerThread() {
        AtomicInteger calls = new AtomicInteger();
        Thread caller = Thread.currentThread();
        Mono<Integer> result =
                ModelUtils.fromBlockingCallable(
                        () -> {
                            assertFalse(Thread.currentThread() == caller);
                            return calls.incrementAndGet();
                        });
        assertEquals(0, calls.get());
        assertEquals(1, result.block(Duration.ofSeconds(5)));
        assertEquals(2, result.block(Duration.ofSeconds(5)));
    }

    @Test
    void callerCancellationInterruptsTheCallWithoutDroppingWrappedInterruption() throws Exception {
        AtomicReference<Throwable> delivered = new AtomicReference<>();
        Disposable subscription =
                ModelUtils.fromBlockingCallable(
                                () -> {
                                    try {
                                        waitForCancellation();
                                        return "unexpected";
                                    } catch (InterruptedException e) {
                                        Thread.currentThread().interrupt();
                                        throw new ModelException(
                                                "transport interrupted", new IOException(e));
                                    }
                                })
                        .subscribe(ignored -> {}, delivered::set);
        try {
            assertTrue(entered.await(5, TimeUnit.SECONDS));
        } finally {
            subscription.dispose();
        }
        assertTrue(finished.await(5, TimeUnit.SECONDS));
        assertTrue(droppedErrors.isEmpty(), () -> "Unexpected dropped errors: " + droppedErrors);
        assertEquals(null, delivered.get());
    }

    @Test
    void interruptionOfAnActiveCallStillPropagates() {
        ModelException failure =
                new ModelException("independent interruption", new InterruptedException());
        StepVerifier.create(
                        ModelUtils.fromBlockingCallable(
                                () -> {
                                    throw failure;
                                }))
                .expectErrorSatisfies(error -> assertSame(failure, error))
                .verify(Duration.ofSeconds(5));
        assertTrue(droppedErrors.isEmpty());
    }

    @Test
    void cancellationInducedIoInterruptionDoesNotDropAnError() throws Exception {
        Disposable subscription =
                ModelUtils.fromBlockingCallable(
                                () -> {
                                    try {
                                        waitForCancellation();
                                    } catch (InterruptedException ignored) {
                                        Thread.currentThread().interrupt();
                                        throw new IOException(
                                                new InterruptedIOException("interrupted"));
                                    }
                                    return "unexpected";
                                })
                        .subscribe();
        try {
            assertTrue(entered.await(5, TimeUnit.SECONDS));
        } finally {
            subscription.dispose();
        }
        assertTrue(finished.await(5, TimeUnit.SECONDS));
        assertTrue(droppedErrors.isEmpty());
    }

    @Test
    void ioFailureWithoutThreadInterruptionIsNotSuppressed() throws Exception {
        InterruptedIOException failure = new InterruptedIOException("read timeout");
        Disposable subscription =
                ModelUtils.fromBlockingCallable(
                                () -> {
                                    try {
                                        waitForCancellation();
                                    } catch (InterruptedException ignored) {
                                        throw failure;
                                    }
                                    return "unexpected";
                                })
                        .subscribe();
        try {
            assertTrue(entered.await(5, TimeUnit.SECONDS));
        } finally {
            subscription.dispose();
        }
        assertTrue(finished.await(5, TimeUnit.SECONDS));
        assertSame(failure, droppedErrors.peek());
    }

    @Test
    void socketTimeoutIsNotSuppressedEvenOnAnInterruptedWorker() throws Exception {
        SocketTimeoutException failure = new SocketTimeoutException("read timeout");
        Disposable subscription =
                ModelUtils.fromBlockingCallable(
                                () -> {
                                    try {
                                        waitForCancellation();
                                    } catch (InterruptedException ignored) {
                                        Thread.currentThread().interrupt();
                                        throw failure;
                                    }
                                    return "unexpected";
                                })
                        .subscribe();
        try {
            assertTrue(entered.await(5, TimeUnit.SECONDS));
        } finally {
            subscription.dispose();
        }
        assertTrue(finished.await(5, TimeUnit.SECONDS));
        assertSame(failure, droppedErrors.peek());
    }

    @Test
    void unrelatedLateFailureStillReachesDroppedErrorHandler() throws Exception {
        IOException failure = new IOException("unrelated failure");
        Disposable subscription =
                ModelUtils.fromBlockingCallable(
                                () -> {
                                    try {
                                        waitForCancellation();
                                    } catch (InterruptedException ignored) {
                                        throw failure;
                                    }
                                    return "unexpected";
                                })
                        .subscribe();
        try {
            assertTrue(entered.await(5, TimeUnit.SECONDS));
        } finally {
            subscription.dispose();
        }
        assertTrue(finished.await(5, TimeUnit.SECONDS));
        assertEquals(1, droppedErrors.size());
        assertSame(failure, droppedErrors.peek());
    }

    @Test
    void lateAcquiredResourceIsDiscardedAfterCancellation() throws Exception {
        Object resource = new Object();
        AtomicReference<Object> discarded = new AtomicReference<>();
        Disposable subscription =
                ModelUtils.fromBlockingCallable(
                                () -> {
                                    try {
                                        waitForCancellation();
                                    } catch (InterruptedException ignored) {
                                        // Simulate a provider that finishes acquiring its resource
                                        // despite cancellation.
                                    }
                                    return resource;
                                })
                        .doOnDiscard(Object.class, discarded::set)
                        .subscribe();
        try {
            assertTrue(entered.await(5, TimeUnit.SECONDS));
        } finally {
            subscription.dispose();
        }
        assertTrue(finished.await(5, TimeUnit.SECONDS));
        assertSame(resource, discarded.get());
        assertTrue(droppedErrors.isEmpty());
    }

    @Test
    void subscriberContextIsPreserved() {
        StepVerifier.create(
                        ModelUtils.fromBlockingCallable(() -> "value")
                                .flatMap(
                                        value ->
                                                Mono.deferContextual(
                                                        context ->
                                                                Mono.just(
                                                                        value
                                                                                + context.get(
                                                                                        "key"))))
                                .contextWrite(context -> context.put("key", "-context")))
                .expectNext("value-context")
                .verifyComplete();
    }

    @Test
    void timeoutCanRetryWithoutDroppingTheCancelledAttemptsInterruption() throws Exception {
        AtomicInteger attempts = new AtomicInteger();
        ChatResponse success =
                ChatResponse.builder()
                        .content(List.of(TextBlock.builder().text("ok").build()))
                        .build();
        var source =
                ModelUtils.fromBlockingCallable(
                                () -> {
                                    if (attempts.incrementAndGet() == 1) {
                                        waitForCancellation();
                                    }
                                    return success;
                                })
                        .flux();
        StepVerifier.create(
                        ModelUtils.applyTimeoutAndRetry(
                                source, retryOptions(2), null, "test", "test"))
                .expectNext(success)
                .verifyComplete();
        assertEquals(2, attempts.get());
        assertTrue(finished.await(5, TimeUnit.SECONDS));
        assertTrue(droppedErrors.isEmpty());
    }

    @Test
    void exhaustedTimeoutRetriesStillFail() throws Exception {
        AtomicInteger attempts = new AtomicInteger();
        var source =
                ModelUtils.<ChatResponse>fromBlockingCallable(
                                () -> {
                                    attempts.incrementAndGet();
                                    waitForCancellation();
                                    return null;
                                })
                        .flux();
        StepVerifier.create(
                        ModelUtils.applyTimeoutAndRetry(
                                source, retryOptions(2), null, "test", "test"))
                .expectErrorSatisfies(
                        error -> {
                            assertTrue(Exceptions.isRetryExhausted(error));
                            assertTrue(error.getCause() instanceof ModelException);
                            assertTrue(error.getCause().getCause() instanceof TimeoutException);
                        })
                .verify(Duration.ofSeconds(5));
        assertEquals(2, attempts.get());
    }

    @Test
    void nullResultCompletesEmptyAndDemandIsRespected() {
        StepVerifier.create(ModelUtils.fromBlockingCallable(() -> null)).verifyComplete();
        StepVerifier.create(ModelUtils.fromBlockingCallable(() -> "value"), 0)
                .thenRequest(1)
                .expectNext("value")
                .verifyComplete();
    }

    private GenerateOptions retryOptions(int attempts) {
        return GenerateOptions.builder()
                .executionConfig(
                        ExecutionConfig.builder()
                                .timeout(Duration.ofMillis(200))
                                .maxAttempts(attempts)
                                .initialBackoff(Duration.ofMillis(10))
                                .maxBackoff(Duration.ofMillis(10))
                                .build())
                .build();
    }

    private void waitForCancellation() throws InterruptedException {
        callThread.set(Thread.currentThread());
        entered.countDown();
        if (!new CountDownLatch(1).await(10, TimeUnit.SECONDS)) {
            throw new AssertionError("Blocking call was not interrupted by cancellation");
        }
    }
}
