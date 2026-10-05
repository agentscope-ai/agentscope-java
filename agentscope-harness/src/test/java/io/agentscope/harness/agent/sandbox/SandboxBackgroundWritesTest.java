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
package io.agentscope.harness.agent.sandbox;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.agentscope.core.agent.RuntimeContext;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

/** Unit tests for {@link SandboxBackgroundWrites} (issue #3415). */
class SandboxBackgroundWritesTest {

    @AfterEach
    void clearBudget() {
        System.clearProperty(SandboxBackgroundWrites.MAX_DEFER_MILLIS_PROPERTY);
    }

    @Test
    void releaseRunsInlineWhenNothingIsHeld() {
        Sandbox sandbox = new TrackingSandbox(TrackingSandbox.newEventLog());
        AtomicReference<Thread> ranOn = new AtomicReference<>();

        CompletableFuture<Void> released =
                SandboxBackgroundWrites.releaseWhenIdle(
                        sandbox, () -> ranOn.set(Thread.currentThread()));

        assertTrue(released.isDone(), "an idle sandbox is released before the call returns");
        assertSame(Thread.currentThread(), ranOn.get(), "an idle release runs on the caller");
        try (SandboxBackgroundWrites.Hold hold = SandboxBackgroundWrites.tryHold(sandbox)) {
            assertNotNull(hold, "a finished release must not leave its sandbox registered");
        }
    }

    @Test
    void releaseWaitsForTheLastHoldAndRunsOffTheWriterThread() throws Exception {
        Sandbox sandbox = new TrackingSandbox(TrackingSandbox.newEventLog());
        SandboxBackgroundWrites.Hold first = SandboxBackgroundWrites.tryHold(sandbox);
        SandboxBackgroundWrites.Hold second = SandboxBackgroundWrites.tryHold(sandbox);
        AtomicInteger runs = new AtomicInteger();
        AtomicReference<Thread> ranOn = new AtomicReference<>();

        CompletableFuture<Void> released =
                SandboxBackgroundWrites.releaseWhenIdle(
                        sandbox,
                        () -> {
                            runs.incrementAndGet();
                            ranOn.set(Thread.currentThread());
                        });
        assertFalse(released.isDone(), "release must wait while writes hold the sandbox");

        first.close();
        first.close(); // idempotent: must not also count for the second hold
        assertFalse(released.isDone(), "one hold is still outstanding");
        assertFalse(first.isReleased());

        second.close();
        released.get(5, TimeUnit.SECONDS);
        assertEquals(1, runs.get(), "the teardown runs exactly once");
        assertNotSame(
                Thread.currentThread(),
                ranOn.get(),
                "a deferred teardown must not run on the writer's thread");
    }

    @Test
    void deferredReleaseIsForcedOnceTheBudgetRunsOut() throws Exception {
        System.setProperty(SandboxBackgroundWrites.MAX_DEFER_MILLIS_PROPERTY, "100");
        Sandbox sandbox = new TrackingSandbox(TrackingSandbox.newEventLog());
        SandboxBackgroundWrites.Hold stuck = SandboxBackgroundWrites.tryHold(sandbox);
        try {
            CompletableFuture<Void> released =
                    SandboxBackgroundWrites.releaseWhenIdle(sandbox, () -> {});

            released.get(5, TimeUnit.SECONDS);
            assertTrue(stuck.isReleased(), "a stuck writer must learn the sandbox is gone");
            assertNull(
                    SandboxBackgroundWrites.tryHold(sandbox),
                    "no new write may start on a sandbox that is released");
        } finally {
            stuck.close();
        }
    }

    @Test
    void zeroBudgetDisablesDeferral() {
        System.setProperty(SandboxBackgroundWrites.MAX_DEFER_MILLIS_PROPERTY, "0");
        Sandbox sandbox = new TrackingSandbox(TrackingSandbox.newEventLog());
        SandboxBackgroundWrites.Hold hold = SandboxBackgroundWrites.tryHold(sandbox);
        AtomicInteger runs = new AtomicInteger();
        try {
            CompletableFuture<Void> released =
                    SandboxBackgroundWrites.releaseWhenIdle(sandbox, runs::incrementAndGet);

            assertTrue(released.isDone());
            assertEquals(1, runs.get());
            assertTrue(hold.isReleased());
        } finally {
            hold.close();
        }
        assertEquals(1, runs.get(), "closing a hold after release must not release again");
    }

    @Test
    void secondReleaseWhileDeferredRunsOnceAfterTheFirst() throws Exception {
        Sandbox sandbox = new TrackingSandbox(TrackingSandbox.newEventLog());
        SandboxBackgroundWrites.Hold hold = SandboxBackgroundWrites.tryHold(sandbox);
        List<String> order = Collections.synchronizedList(new ArrayList<>());

        CompletableFuture<Void> first =
                SandboxBackgroundWrites.releaseWhenIdle(sandbox, () -> order.add("first"));
        CompletableFuture<Void> second =
                SandboxBackgroundWrites.releaseWhenIdle(sandbox, () -> order.add("second"));

        assertFalse(second.isDone(), "a second release must not race the in-flight write");
        assertEquals(List.of(), order);
        hold.close();
        first.get(5, TimeUnit.SECONDS);
        second.get(5, TimeUnit.SECONDS);
        assertEquals(List.of("first", "second"), order, "each teardown runs exactly once");
    }

    @Test
    void secondReleaseDuringATeardownWaitsForIt() throws Exception {
        Sandbox sandbox = new TrackingSandbox(TrackingSandbox.newEventLog());
        CountDownLatch firstStarted = new CountDownLatch(1);
        CountDownLatch finishFirst = new CountDownLatch(1);
        List<String> order = Collections.synchronizedList(new ArrayList<>());
        CompletableFuture<Void> first =
                CompletableFuture.runAsync(
                        () ->
                                SandboxBackgroundWrites.releaseWhenIdle(
                                        sandbox,
                                        () -> {
                                            firstStarted.countDown();
                                            awaitQuietly(finishFirst);
                                            order.add("first");
                                        }));
        try {
            assertTrue(firstStarted.await(5, TimeUnit.SECONDS));

            CompletableFuture<Void> second =
                    SandboxBackgroundWrites.releaseWhenIdle(sandbox, () -> order.add("second"));

            assertFalse(second.isDone(), "teardowns of one sandbox must never overlap");
            finishFirst.countDown();
            second.get(5, TimeUnit.SECONDS);
            first.get(5, TimeUnit.SECONDS);
            assertEquals(List.of("first", "second"), order);
        } finally {
            finishFirst.countDown();
        }
    }

    @Test
    void secondReleaseAfterAForcedReleaseRunsOnce() throws Exception {
        System.setProperty(SandboxBackgroundWrites.MAX_DEFER_MILLIS_PROPERTY, "100");
        Sandbox sandbox = new TrackingSandbox(TrackingSandbox.newEventLog());
        SandboxBackgroundWrites.Hold stuck = SandboxBackgroundWrites.tryHold(sandbox);
        AtomicInteger runs = new AtomicInteger();
        try {
            SandboxBackgroundWrites.releaseWhenIdle(sandbox, runs::incrementAndGet)
                    .get(5, TimeUnit.SECONDS);

            SandboxBackgroundWrites.releaseWhenIdle(sandbox, runs::incrementAndGet)
                    .get(5, TimeUnit.SECONDS);
        } finally {
            stuck.close();
        }
        assertEquals(2, runs.get(), "closing the stuck hold must not run either teardown again");
    }

    @Test
    void pinKeepsTheCallBindingAfterTheCallClearsIt() throws Exception {
        Sandbox sandbox = new TrackingSandbox(TrackingSandbox.newEventLog());
        SandboxAcquireResult bound = SandboxAcquireResult.selfManaged(sandbox);
        RuntimeContext rc =
                RuntimeContext.builder()
                        .userId("u1")
                        .sessionId("s1")
                        .put(SandboxAcquireResult.class, bound)
                        .build();

        SandboxBackgroundWrites.Pin pin = SandboxBackgroundWrites.pinCallSandbox(rc);
        rc.put(SandboxAcquireResult.class, null); // what releaseForCall does

        assertSame(bound, pin.context().get(SandboxAcquireResult.class));
        assertEquals("u1", pin.context().getUserId());
        assertEquals("s1", pin.context().getSessionId());
        CompletableFuture<Void> released =
                SandboxBackgroundWrites.releaseWhenIdle(sandbox, () -> {});
        assertFalse(released.isDone(), "the pin holds the sandbox");
        pin.close();
        pin.close();
        released.get(5, TimeUnit.SECONDS);
    }

    @Test
    void pinWithoutABoundSandboxIsANoOp() {
        RuntimeContext rc = RuntimeContext.builder().sessionId("s1").build();

        SandboxBackgroundWrites.Pin pin = SandboxBackgroundWrites.pinCallSandbox(rc);

        assertSame(rc, pin.context());
        pin.close();
    }

    @Test
    void pinOnASandboxBeingReleasedDoesNotHoldIt() throws Exception {
        System.setProperty(SandboxBackgroundWrites.MAX_DEFER_MILLIS_PROPERTY, "100");
        Sandbox sandbox = new TrackingSandbox(TrackingSandbox.newEventLog());
        SandboxBackgroundWrites.Hold stuck = SandboxBackgroundWrites.tryHold(sandbox);
        try {
            SandboxBackgroundWrites.releaseWhenIdle(sandbox, () -> {}).get(5, TimeUnit.SECONDS);
            RuntimeContext rc =
                    RuntimeContext.builder()
                            .put(
                                    SandboxAcquireResult.class,
                                    SandboxAcquireResult.selfManaged(sandbox))
                            .build();

            SandboxBackgroundWrites.Pin pin = SandboxBackgroundWrites.pinCallSandbox(rc);

            assertSame(rc, pin.context());
            pin.close();
        } finally {
            stuck.close();
        }
    }

    @Test
    void awaitPendingReleasesWaitsForDeferredTeardowns() {
        Sandbox sandbox = new TrackingSandbox(TrackingSandbox.newEventLog());
        SandboxBackgroundWrites.Hold hold = SandboxBackgroundWrites.tryHold(sandbox);
        SandboxBackgroundWrites.releaseWhenIdle(sandbox, () -> {});

        assertFalse(SandboxBackgroundWrites.awaitPendingReleases(50, TimeUnit.MILLISECONDS));
        hold.close();
        assertTrue(SandboxBackgroundWrites.awaitPendingReleases(5, TimeUnit.SECONDS));
    }

    @Test
    void failingTeardownStillCompletesTheRelease() throws Exception {
        Sandbox sandbox = new TrackingSandbox(TrackingSandbox.newEventLog());
        SandboxBackgroundWrites.Hold hold = SandboxBackgroundWrites.tryHold(sandbox);
        CompletableFuture<Void> released =
                SandboxBackgroundWrites.releaseWhenIdle(
                        sandbox,
                        () -> {
                            throw new IllegalStateException("stop failed");
                        });

        hold.close();

        released.get(5, TimeUnit.SECONDS);
        try (SandboxBackgroundWrites.Hold next = SandboxBackgroundWrites.tryHold(sandbox)) {
            assertNotNull(next, "a failed release must not leave its sandbox registered");
        }
    }

    @Test
    void budgetFallsBackToTheDefaultAndClampsNegativeValues() {
        assertEquals(
                SandboxBackgroundWrites.DEFAULT_MAX_DEFER_MILLIS,
                SandboxBackgroundWrites.maxDeferMillis());
        System.setProperty(SandboxBackgroundWrites.MAX_DEFER_MILLIS_PROPERTY, "-5");
        assertEquals(0L, SandboxBackgroundWrites.maxDeferMillis());
    }

    private static void awaitQuietly(CountDownLatch latch) {
        try {
            latch.await(5, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
