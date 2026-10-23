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
package io.agentscope.harness.agent.middleware;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.same;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import io.agentscope.core.agent.RuntimeContext;
import io.agentscope.harness.agent.filesystem.sandbox.SandboxBackedFilesystem;
import io.agentscope.harness.agent.sandbox.ExecResult;
import io.agentscope.harness.agent.sandbox.Sandbox;
import io.agentscope.harness.agent.sandbox.SandboxAcquireResult;
import io.agentscope.harness.agent.sandbox.SandboxContext;
import io.agentscope.harness.agent.sandbox.SandboxException;
import io.agentscope.harness.agent.sandbox.SandboxLease;
import io.agentscope.harness.agent.sandbox.SandboxManager;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;
import org.junit.jupiter.api.Test;

class SandboxLazyInitializationTest {
    private final SandboxBackedFilesystem filesystem = new SandboxBackedFilesystem();
    private final SandboxManager manager = mock(SandboxManager.class);
    private final SandboxLifecycleMiddleware lifecycle =
            new SandboxLifecycleMiddleware(manager, filesystem);

    @Test
    void unusedCallDoesNotAcquireOrReleaseAndCannotStartAfterCompletion() {
        RuntimeContext context = context("a");
        lifecycle.acquireForCall(context);
        RuntimeContext copy = RuntimeContext.builder().from(context).build();
        lifecycle.releaseForCall(context);
        lifecycle.releaseForCall(copy);
        assertThrows(
                SandboxException.SandboxConfigurationException.class,
                () -> filesystem.execute(copy, "echo hello", null));
        verifyNoInteractions(manager);
    }

    @Test
    void firstAccessFromContextCopyStartsOnceAndReleasesInOrder() throws Exception {
        RuntimeContext context = context("a");
        Sandbox sandbox = sandbox("a");
        SandboxLease lease = mock(SandboxLease.class);
        SandboxAcquireResult result = SandboxAcquireResult.selfManaged(sandbox, lease);
        when(manager.acquire(any(), same(context))).thenReturn(result);
        @SuppressWarnings("unchecked")
        Consumer<RuntimeContext> callback = mock(Consumer.class);
        lifecycle.setBeforeStartCallback(callback);
        lifecycle.acquireForCall(context);
        verifyNoInteractions(manager, sandbox, callback);
        RuntimeContext copy = RuntimeContext.builder().from(context).build();
        assertEquals("a", filesystem.execute(copy, "first", null).output());
        assertEquals("a", filesystem.execute(context, "second", null).output());
        lifecycle.releaseForCall(context);
        lifecycle.releaseForCall(copy);
        var order = inOrder(callback, manager, sandbox, lease);
        order.verify(callback).accept(context);
        order.verify(manager).acquire(any(), same(context));
        order.verify(sandbox).start();
        order.verify(manager).release(result);
        order.verify(manager).persistState(result, context.get(SandboxContext.class), context);
        order.verify(lease).close();
        verify(manager, times(1)).acquire(any(), any());
        verify(manager, times(1)).release(result);
        assertThrows(
                SandboxException.SandboxConfigurationException.class,
                () -> filesystem.execute(copy, "late", null));
    }

    @Test
    void concurrentFirstAccessUsesOneAcquisition() throws Exception {
        RuntimeContext context = context("a");
        Sandbox sandbox = sandbox("a");
        when(manager.acquire(any(), any())).thenReturn(SandboxAcquireResult.selfManaged(sandbox));
        lifecycle.acquireForCall(context);
        ExecutorService executor = Executors.newFixedThreadPool(8);
        CyclicBarrier barrier = new CyclicBarrier(8);
        try {
            List<Future<String>> results = new ArrayList<>();
            for (int i = 0; i < 8; i++) {
                RuntimeContext copy = RuntimeContext.builder().from(context).build();
                results.add(
                        executor.submit(
                                () -> {
                                    barrier.await(5, TimeUnit.SECONDS);
                                    return filesystem.execute(copy, "run", null).output();
                                }));
            }
            for (Future<String> result : results)
                assertEquals("a", result.get(10, TimeUnit.SECONDS));
            verify(manager).acquire(any(), any());
            verify(sandbox).start();
        } finally {
            executor.shutdownNow();
            lifecycle.releaseForCall(context);
        }
    }

    @Test
    void failedStartClosesLeaseAndDoesNotRetryWithinTheCall() throws Exception {
        RuntimeContext context = context("a");
        Sandbox sandbox = sandbox("a");
        SandboxLease lease = mock(SandboxLease.class);
        SandboxAcquireResult result = SandboxAcquireResult.selfManaged(sandbox, lease);
        when(manager.acquire(any(), any())).thenReturn(result);
        doThrow(new IllegalStateException("startup failed")).when(sandbox).start();
        lifecycle.acquireForCall(context);
        RuntimeContext copy = RuntimeContext.builder().from(context).build();
        assertThrows(RuntimeException.class, () -> filesystem.execute(copy, "first", null));
        assertThrows(RuntimeException.class, () -> filesystem.execute(context, "second", null));
        lifecycle.releaseForCall(context);
        verify(manager).acquire(any(), any());
        verify(manager).release(result);
        verify(lease).close();
        verify(manager, never()).persistState(any(), any(), any());
    }

    @Test
    void releaseDuringInitializationWaitsForCleanupAndPreventsLateAcquisition() throws Exception {
        RuntimeContext context = context("a");
        Sandbox sandbox = sandbox("a");
        SandboxLease lease = mock(SandboxLease.class);
        SandboxAcquireResult result = SandboxAcquireResult.selfManaged(sandbox, lease);
        when(manager.acquire(any(), any())).thenReturn(result);
        CountDownLatch starting = new CountDownLatch(1);
        CountDownLatch finishStart = new CountDownLatch(1);
        doAnswer(
                        invocation -> {
                            starting.countDown();
                            assertTrue(finishStart.await(5, TimeUnit.SECONDS));
                            return null;
                        })
                .when(sandbox)
                .start();
        lifecycle.acquireForCall(context);
        ExecutorService executor = Executors.newFixedThreadPool(2);
        try {
            Future<?> use = executor.submit(() -> filesystem.execute(context, "run", null));
            assertTrue(starting.await(5, TimeUnit.SECONDS));
            CountDownLatch releaseRequested = new CountDownLatch(1);
            Future<?> release =
                    executor.submit(
                            () -> {
                                releaseRequested.countDown();
                                lifecycle.releaseForCall(context);
                            });
            assertTrue(releaseRequested.await(5, TimeUnit.SECONDS));
            finishStart.countDown();
            try {
                use.get(10, TimeUnit.SECONDS);
            } catch (ExecutionException error) {
                // Cleanup can win between acquiring the sandbox and checking its released flag.
                assertInstanceOf(
                        SandboxException.SandboxConfigurationException.class, error.getCause());
            }
            release.get(10, TimeUnit.SECONDS);
            verify(manager).release(result);
            verify(lease).close();
            assertThrows(
                    SandboxException.SandboxConfigurationException.class,
                    () -> filesystem.execute(context, "late", null));
        } finally {
            finishStart.countDown();
            executor.shutdownNow();
        }
    }

    @Test
    void unusedAndReleasedCallsCannotBorrowAnotherSessionsSandbox() throws Exception {
        RuntimeContext a = context("a");
        RuntimeContext b = context("b");
        Sandbox sandboxA = sandbox("a");
        Sandbox sandboxB = sandbox("b");
        when(manager.acquire(any(), same(a)))
                .thenReturn(SandboxAcquireResult.selfManaged(sandboxA));
        when(manager.acquire(any(), same(b)))
                .thenReturn(SandboxAcquireResult.selfManaged(sandboxB));
        lifecycle.acquireForCall(a);
        lifecycle.acquireForCall(b);
        assertEquals("b", filesystem.execute(b, "run", null).output());
        assertEquals("a", filesystem.execute(a, "run", null).output());
        lifecycle.releaseForCall(a);
        assertThrows(
                SandboxException.SandboxConfigurationException.class,
                () -> filesystem.execute(a, "late", null));
        assertEquals("b", filesystem.execute(b, "run", null).output());
        lifecycle.releaseForCall(b);
    }

    private RuntimeContext context(String session) {
        return RuntimeContext.builder()
                .sessionId(session)
                .put(
                        SandboxContext.class,
                        SandboxContext.builder().lazyInitialization(true).build())
                .build();
    }

    private Sandbox sandbox(String name) throws Exception {
        Sandbox sandbox = mock(Sandbox.class);
        when(sandbox.exec(any(), anyString(), any()))
                .thenReturn(new ExecResult(0, name, "", false));
        return sandbox;
    }
}
