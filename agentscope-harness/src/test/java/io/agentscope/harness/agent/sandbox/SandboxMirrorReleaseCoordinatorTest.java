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
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

import io.agentscope.core.agent.RuntimeContext;
import io.agentscope.harness.agent.IsolationScope;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class SandboxMirrorReleaseCoordinatorTest {

    private SandboxManager manager;
    private Sandbox sandbox;
    private AtomicInteger releaseCalls;
    private AtomicBoolean leaseClosed;

    @BeforeEach
    void setUp() {
        SandboxMirrorReleaseCoordinator.resetForTests();
        releaseCalls = new AtomicInteger();
        leaseClosed = new AtomicBoolean(false);
        sandbox = mock(Sandbox.class);
        manager = mock(SandboxManager.class);
        org.mockito.Mockito.doAnswer(
                        invocation -> {
                            releaseCalls.incrementAndGet();
                            return null;
                        })
                .when(manager)
                .release(org.mockito.ArgumentMatchers.any());
        org.mockito.Mockito.when(manager.getAgentId()).thenReturn("assistant");
    }

    @AfterEach
    void tearDown() {
        SandboxMirrorReleaseCoordinator.resetForTests();
    }

    @Test
    void requestRelease_withoutPendingMirrors_releasesImmediately() {
        SandboxAcquireResult result = selfManagedWithLease(sandbox);

        SandboxMirrorReleaseCoordinator.requestRelease(manager, result);

        assertEquals(1, releaseCalls.get());
        assertTrue(leaseClosed.get());
        verify(manager, times(1)).release(result);
    }

    @Test
    void requestRelease_withPendingMirror_defersUntilReleaseMirror() {
        SandboxAcquireResult result = selfManagedWithLease(sandbox);

        SandboxMirrorReleaseCoordinator.retain(sandbox);
        SandboxMirrorReleaseCoordinator.requestRelease(manager, result);

        assertEquals(0, releaseCalls.get());
        assertFalse(leaseClosed.get());
        verify(manager, never()).release(result);

        SandboxMirrorReleaseCoordinator.releaseMirror(sandbox);
        assertTrue(awaitDeferredRelease());

        assertEquals(1, releaseCalls.get());
        assertTrue(leaseClosed.get());
        verify(manager, times(1)).release(result);
    }

    @Test
    void twoRetains_requireTwoReleaseMirrors_beforeDeferredRelease() {
        SandboxAcquireResult result = selfManagedWithLease(sandbox);

        SandboxMirrorReleaseCoordinator.retain(sandbox);
        SandboxMirrorReleaseCoordinator.retain(sandbox);
        SandboxMirrorReleaseCoordinator.requestRelease(manager, result);

        SandboxMirrorReleaseCoordinator.releaseMirror(sandbox);
        assertTrue(awaitDeferredRelease());
        assertEquals(0, releaseCalls.get());
        assertFalse(leaseClosed.get());

        SandboxMirrorReleaseCoordinator.releaseMirror(sandbox);
        assertTrue(awaitDeferredRelease());
        assertEquals(1, releaseCalls.get());
        assertTrue(leaseClosed.get());
    }

    @Test
    void mirrorFinishesBeforeRequestRelease_thenRequestReleasesImmediately() {
        SandboxAcquireResult result = selfManagedWithLease(sandbox);

        SandboxMirrorReleaseCoordinator.retain(sandbox);
        SandboxMirrorReleaseCoordinator.releaseMirror(sandbox);

        SandboxMirrorReleaseCoordinator.requestRelease(manager, result);

        assertEquals(1, releaseCalls.get());
        assertTrue(leaseClosed.get());
    }

    @Test
    void userManaged_releasesImmediatelyEvenWithPendingMirrors() {
        SandboxAcquireResult result = SandboxAcquireResult.userManaged(sandbox);
        SandboxMirrorReleaseCoordinator.retain(sandbox);
        SandboxMirrorReleaseCoordinator.requestRelease(manager, result);

        assertEquals(1, releaseCalls.get());
        verify(manager, times(1)).release(result);

        // drain retain so state does not leak across tests
        SandboxMirrorReleaseCoordinator.releaseMirror(sandbox);
    }

    @Test
    void releaseMirror_afterFailurePath_stillReleasesDeferred() {
        SandboxAcquireResult result = selfManagedWithLease(sandbox);

        SandboxMirrorReleaseCoordinator.retain(sandbox);
        SandboxMirrorReleaseCoordinator.requestRelease(manager, result);

        SandboxMirrorReleaseCoordinator.releaseMirror(sandbox);
        assertTrue(awaitDeferredRelease());

        assertEquals(1, releaseCalls.get());
        assertTrue(leaseClosed.get());
    }

    @Test
    void releaseMirror_underflow_takesOverDeferredRelease() {
        SandboxAcquireResult result = selfManagedWithLease(sandbox);

        SandboxMirrorReleaseCoordinator.retain(sandbox);
        SandboxMirrorReleaseCoordinator.requestRelease(manager, result);
        SandboxMirrorReleaseCoordinator.forcePendingZeroForUnderflowTests(sandbox);

        SandboxMirrorReleaseCoordinator.releaseMirror(sandbox);
        assertTrue(awaitDeferredRelease());

        assertEquals(
                1,
                releaseCalls.get(),
                "underflow with deferred must take over stop/shutdown so the lease is not"
                        + " orphaned");
        assertTrue(leaseClosed.get());
        verify(manager, times(1)).release(result);
    }

    @Test
    void releaseMirror_underflow_withoutDeferred_doesNotRelease() {
        SandboxMirrorReleaseCoordinator.retain(sandbox);
        SandboxMirrorReleaseCoordinator.forcePendingZeroForUnderflowTests(sandbox);

        SandboxMirrorReleaseCoordinator.releaseMirror(sandbox);

        assertEquals(0, releaseCalls.get());
        assertFalse(leaseClosed.get());
        verify(manager, never()).release(org.mockito.ArgumentMatchers.any());
    }

    @Test
    void deferredRelease_doesNotRunOnCallerThread() {
        SandboxAcquireResult result = selfManagedWithLease(sandbox);
        Thread caller = Thread.currentThread();
        AtomicBoolean ranOnCaller = new AtomicBoolean(false);

        org.mockito.Mockito.doAnswer(
                        invocation -> {
                            ranOnCaller.set(Thread.currentThread() == caller);
                            releaseCalls.incrementAndGet();
                            return null;
                        })
                .when(manager)
                .release(org.mockito.ArgumentMatchers.any());

        SandboxMirrorReleaseCoordinator.retain(sandbox);
        SandboxMirrorReleaseCoordinator.requestRelease(manager, result);
        SandboxMirrorReleaseCoordinator.releaseMirror(sandbox);
        assertTrue(awaitDeferredRelease());

        assertEquals(1, releaseCalls.get());
        assertFalse(ranOnCaller.get(), "deferred stop/shutdown must not run on the mirror caller");
    }

    @Test
    void twoCallsSharingSandbox_bothDeferredReleasesRun() {
        AtomicInteger leaseCloses = new AtomicInteger();
        SandboxAcquireResult resultA =
                SandboxAcquireResult.selfManaged(sandbox, leaseCloses::incrementAndGet);
        SandboxAcquireResult resultB =
                SandboxAcquireResult.selfManaged(sandbox, leaseCloses::incrementAndGet);

        SandboxMirrorReleaseCoordinator.retain(sandbox);
        SandboxMirrorReleaseCoordinator.requestRelease(manager, resultA);
        SandboxMirrorReleaseCoordinator.requestRelease(manager, resultB);

        assertEquals(0, releaseCalls.get());
        assertEquals(0, leaseCloses.get());

        SandboxMirrorReleaseCoordinator.releaseMirror(sandbox);
        assertTrue(awaitDeferredRelease());

        assertEquals(2, releaseCalls.get(), "each call's manager.release must run");
        assertEquals(2, leaseCloses.get(), "each call's lease must close");
        verify(manager, times(1)).release(resultA);
        verify(manager, times(1)).release(resultB);
    }

    @Test
    void lateRetainAfterReleaseNow_pairsWithoutSecondRelease() {
        SandboxAcquireResult result = selfManagedWithLease(sandbox);

        SandboxMirrorReleaseCoordinator.requestRelease(manager, result);
        assertEquals(1, releaseCalls.get());
        assertTrue(leaseClosed.get());

        // Race: retain lands while the released tombstone is still visible (or after). Either way
        // pairing must not schedule another destructive release.
        SandboxMirrorReleaseCoordinator.retain(sandbox);
        SandboxMirrorReleaseCoordinator.releaseMirror(sandbox);
        assertTrue(awaitDeferredRelease());

        assertEquals(1, releaseCalls.get(), "must not release twice after late retain pairing");
    }

    @Test
    void requestRelease_withPersistContext_runsReleaseBeforePersist() {
        SandboxAcquireResult result = selfManagedWithLease(sandbox);
        SandboxContext sandboxContext = SandboxContext.builder().build();
        RuntimeContext runtimeContext = RuntimeContext.builder().sessionId("s1").build();
        List<String> order = new ArrayList<>();

        org.mockito.Mockito.doAnswer(
                        invocation -> {
                            order.add("release");
                            releaseCalls.incrementAndGet();
                            return null;
                        })
                .when(manager)
                .release(org.mockito.ArgumentMatchers.any());
        org.mockito.Mockito.doAnswer(
                        invocation -> {
                            order.add("persist");
                            return null;
                        })
                .when(manager)
                .persistState(
                        org.mockito.ArgumentMatchers.any(),
                        org.mockito.ArgumentMatchers.any(),
                        org.mockito.ArgumentMatchers.any());

        SandboxMirrorReleaseCoordinator.retain(sandbox);
        SandboxMirrorReleaseCoordinator.requestRelease(
                manager, result, sandboxContext, runtimeContext);
        assertEquals(List.of(), order);

        SandboxMirrorReleaseCoordinator.releaseMirror(sandbox);
        assertTrue(awaitDeferredRelease());

        assertEquals(List.of("release", "persist"), order);
        assertTrue(leaseClosed.get());
        verify(manager, times(1)).persistState(result, sandboxContext, runtimeContext);
    }

    @Test
    void awaitPendingScopeRelease_blocksUntilDeferredPersistCompletes() throws Exception {
        SandboxAcquireResult result = selfManagedWithLease(sandbox);
        SandboxContext sandboxContext =
                SandboxContext.builder().isolationScope(IsolationScope.SESSION).build();
        RuntimeContext runtimeContext = RuntimeContext.builder().sessionId("gate-s1").build();

        SandboxIsolationKey key =
                SandboxIsolationKey.resolve(IsolationScope.SESSION, runtimeContext, "assistant")
                        .orElseThrow();

        CountDownLatch enteredWait = new CountDownLatch(1);
        AtomicBoolean sawPending = new AtomicBoolean(false);
        AtomicBoolean waitFinished = new AtomicBoolean(false);

        SandboxMirrorReleaseCoordinator.retain(sandbox);
        SandboxMirrorReleaseCoordinator.requestRelease(
                manager, result, sandboxContext, runtimeContext);

        Thread waiter =
                new Thread(
                        () -> {
                            try {
                                enteredWait.countDown();
                                // Short poll: should stay pending until releaseMirror drains.
                                boolean ok =
                                        SandboxMirrorReleaseCoordinator.awaitPendingScopeRelease(
                                                key, 5, TimeUnit.SECONDS);
                                sawPending.set(ok);
                                waitFinished.set(true);
                            } catch (InterruptedException e) {
                                Thread.currentThread().interrupt();
                            }
                        },
                        "scope-gate-waiter");
        waiter.start();
        assertTrue(enteredWait.await(2, TimeUnit.SECONDS));
        // Give the waiter a moment to block on the gate.
        Thread.sleep(50);
        assertFalse(waitFinished.get(), "waiter must block while stop+persist is deferred");

        SandboxMirrorReleaseCoordinator.releaseMirror(sandbox);
        waiter.join(5_000);
        assertTrue(waitFinished.get());
        assertTrue(sawPending.get());
        assertEquals(1, releaseCalls.get());
        verify(manager, times(1)).persistState(result, sandboxContext, runtimeContext);
    }

    private static boolean awaitDeferredRelease() {
        return SandboxMirrorReleaseCoordinator.awaitReleaseQuiescence(5, TimeUnit.SECONDS);
    }

    private SandboxAcquireResult selfManagedWithLease(Sandbox sb) {
        SandboxLease lease =
                () -> {
                    leaseClosed.set(true);
                };
        return SandboxAcquireResult.selfManaged(sb, lease);
    }
}
