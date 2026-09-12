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
package io.agentscope.harness.agent;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.agentscope.core.agent.RuntimeContext;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import reactor.core.publisher.Mono;

/**
 * Verifies that the sandbox acquire/release the harness wraps every call in run off the subscriber's
 * thread, never on it.
 *
 * <p>Now that the built-in guard actually blocks, the guard's {@code semaphore.acquire()} can block
 * for the entire duration of a same-slot peer call (issue #2800). If that block ran on the
 * subscriber's thread — a shared Netty event loop in a typical WebFlux deployment — it would stall
 * every unrelated session multiplexed on that thread. {@link HarnessAgent#acquireOffThread} pins the
 * blocking acquire to a dedicated scheduler and {@link HarnessAgent#releaseOffThread} pins release to
 * boundedElastic, so the harness is safe regardless of where the embedder subscribes — and release
 * can never be starved by the acquire waiters it must wake.
 */
class HarnessAgentSandboxOffThreadTest {

    @Test
    @Timeout(10)
    void acquireBlocksOnBoundedElasticAndSparesTheSubscriberThread() throws Exception {
        // A single-thread pool stands in for a shared event loop: if the acquire ran here and
        // blocked, the whole "event loop" would be wedged and unrelated work could not proceed.
        ExecutorService eventLoop =
                Executors.newSingleThreadExecutor(r -> new Thread(r, "fake-event-loop"));
        try {
            RuntimeContext ctx = RuntimeContext.empty();
            AtomicReference<String> acquireThread = new AtomicReference<>();
            CountDownLatch acquireStarted = new CountDownLatch(1);
            CountDownLatch letAcquireFinish = new CountDownLatch(1);
            CountDownLatch onNext = new CountDownLatch(1);

            Mono<RuntimeContext> mono =
                    HarnessAgent.acquireOffThread(
                            ctx,
                            () -> {
                                acquireThread.set(Thread.currentThread().getName());
                                acquireStarted.countDown();
                                try {
                                    // Emulate a busy guard holding the slot for a long peer call.
                                    letAcquireFinish.await();
                                } catch (InterruptedException e) {
                                    Thread.currentThread().interrupt();
                                }
                            });

            // Subscribe from the "event loop" thread. subscribeOn(boundedElastic) must hand the
            // blocking work off, so submitting the subscribe returns promptly even while it blocks.
            eventLoop
                    .submit(() -> mono.subscribe(r -> onNext.countDown()))
                    .get(2, TimeUnit.SECONDS);

            // The acquire has begun and is now parked inside letAcquireFinish.await().
            assertTrue(acquireStarted.await(2, TimeUnit.SECONDS), "acquire must have started");

            // Crux: the event loop must stay free while the acquire blocks. A marker task submitted
            // to the single event-loop thread runs promptly only if that thread was not captured by
            // the acquire. (With the acquire on the subscriber thread, this would time out.)
            CountDownLatch marker = new CountDownLatch(1);
            eventLoop.submit(marker::countDown);
            assertTrue(
                    marker.await(2, TimeUnit.SECONDS),
                    "event-loop thread must not be blocked by the sandbox acquire");

            // And the blocking actually happened on the dedicated acquire scheduler, not on the
            // event loop. Acquire runs on its own pool (not boundedElastic) so its long-parked
            // waiters can never starve the release that must wake them (issue #2800).
            assertTrue(
                    acquireThread.get().contains("as-sandbox-acquire"),
                    "acquire must run on the dedicated sandbox-acquire scheduler, but ran on "
                            + acquireThread.get());

            // Releasing the block lets the resource flow downstream as usual.
            letAcquireFinish.countDown();
            assertTrue(
                    onNext.await(2, TimeUnit.SECONDS),
                    "resource must be emitted once the acquire completes");
        } finally {
            eventLoop.shutdownNow();
        }
    }

    @Test
    @Timeout(10)
    void cancelAfterAcquireButBeforeEmitReleasesTheOrphanedResource() throws Exception {
        // usingWhen only registers its cleanup once the resource is emitted. If the subscription is
        // cancelled after the guard permit was taken but before the value reaches usingWhen, that
        // cleanup never runs and the permit leaks forever (issue #2800). The acquire's
        // cancel/acquire
        // reconciliation must release in exactly that window.
        RuntimeContext ctx = RuntimeContext.empty();
        CountDownLatch acquireStarted = new CountDownLatch(1);
        CountDownLatch letAcquireFinish = new CountDownLatch(1);
        AtomicReference<RuntimeContext> compensated = new AtomicReference<>();
        CountDownLatch compensationRan = new CountDownLatch(1);

        Mono<RuntimeContext> mono =
                HarnessAgent.acquireOffThread(
                        ctx,
                        () -> {
                            acquireStarted.countDown();
                            try {
                                // Hold until the subscriber has cancelled, so the acquired value is
                                // produced only after cancellation and is therefore discarded.
                                letAcquireFinish.await();
                            } catch (InterruptedException e) {
                                Thread.currentThread().interrupt();
                            }
                        },
                        orphaned -> {
                            compensated.set(orphaned);
                            compensationRan.countDown();
                        });

        reactor.core.Disposable subscription = mono.subscribe();
        assertTrue(acquireStarted.await(2, TimeUnit.SECONDS), "acquire must have started");
        // Cancel while the acquire is still parked, then let it produce its (now orphaned) value.
        subscription.dispose();
        letAcquireFinish.countDown();

        assertTrue(
                compensationRan.await(2, TimeUnit.SECONDS),
                "discard compensation must release the orphaned resource on cancel-before-emit");
        assertTrue(
                compensated.get() == ctx,
                "compensation must receive the exact acquired context so it can release its lease");
    }

    @Test
    @Timeout(10)
    void usingWhenCancelBeforeResourceEmitsReleasesViaCompensationNotCleanup() throws Exception {
        // End-to-end reproduction of the leak the compensation guards against, wired exactly like
        // the harness wraps every call: Mono.usingWhen(acquire, use, cleanup). When the subscriber
        // cancels after the permit was taken but before the acquire emits, usingWhen never receives
        // the resource, so it never registers its cleanup — the acquire's own compensation is the
        // only thing that can release it (issue #2800).
        RuntimeContext ctx = RuntimeContext.empty();
        CountDownLatch acquireStarted = new CountDownLatch(1);
        CountDownLatch letAcquireFinish = new CountDownLatch(1);
        CountDownLatch compensationDone = new CountDownLatch(1);
        AtomicBoolean usingWhenCleanupRan = new AtomicBoolean(false);
        AtomicBoolean resourceWasUsed = new AtomicBoolean(false);

        Mono<RuntimeContext> acquire =
                HarnessAgent.acquireOffThread(
                        ctx,
                        () -> {
                            acquireStarted.countDown();
                            try {
                                letAcquireFinish.await();
                            } catch (InterruptedException e) {
                                Thread.currentThread().interrupt();
                            }
                        },
                        orphaned -> compensationDone.countDown());

        Mono<String> wrapped =
                Mono.usingWhen(
                        acquire,
                        resource -> {
                            resourceWasUsed.set(true);
                            return Mono.just("used");
                        },
                        resource -> Mono.fromRunnable(() -> usingWhenCleanupRan.set(true)));

        reactor.core.Disposable subscription = wrapped.subscribe();
        assertTrue(acquireStarted.await(2, TimeUnit.SECONDS), "acquire must have started");
        // Cancel before the acquire emits, then let it finish and produce the orphaned resource.
        subscription.dispose();
        letAcquireFinish.countDown();

        assertTrue(
                compensationDone.await(2, TimeUnit.SECONDS),
                "compensation must release the resource usingWhen never received");
        // usingWhen never saw the resource, so it must neither use it nor run its own cleanup —
        // proving the compensation, not usingWhen, is what frees the permit in this window.
        assertFalse(
                resourceWasUsed.get(), "usingWhen must not use a resource it was cancelled before");
        assertFalse(
                usingWhenCleanupRan.get(),
                "usingWhen cleanup must not run for a resource it never received");
    }

    @Test
    @Timeout(10)
    void releaseRunsOnBoundedElastic() throws Exception {
        AtomicReference<String> releaseThread = new AtomicReference<>();
        CountDownLatch done = new CountDownLatch(1);

        HarnessAgent.releaseOffThread(
                        () -> {
                            releaseThread.set(Thread.currentThread().getName());
                            done.countDown();
                        })
                .subscribe();

        assertTrue(done.await(2, TimeUnit.SECONDS), "release must run");
        assertTrue(
                releaseThread.get().contains("boundedElastic"),
                "release must run on boundedElastic, but ran on " + releaseThread.get());
    }
}
