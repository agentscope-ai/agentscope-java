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
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import io.agentscope.core.agent.RuntimeContext;
import io.agentscope.harness.agent.filesystem.model.FileUploadResponse;
import io.agentscope.harness.agent.filesystem.sandbox.SandboxBackedFilesystem;
import io.agentscope.harness.agent.sandbox.SandboxAcquireResult;
import io.agentscope.harness.agent.sandbox.SandboxBackgroundWrites;
import io.agentscope.harness.agent.sandbox.SandboxClient;
import io.agentscope.harness.agent.sandbox.SandboxClientOptions;
import io.agentscope.harness.agent.sandbox.SandboxContext;
import io.agentscope.harness.agent.sandbox.SandboxLease;
import io.agentscope.harness.agent.sandbox.SandboxManager;
import io.agentscope.harness.agent.sandbox.SessionSandboxStateStore;
import io.agentscope.harness.agent.sandbox.TrackingSandbox;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

/**
 * Regression tests for issue #3415: memory flush / maintenance and session mirrors write to the
 * sandbox after the agent call returns, so a self-managed sandbox must not be stopped and removed
 * until those writes are done.
 */
class SandboxLifecycleMiddlewareBackgroundWriteTest {

    private final List<String> events = TrackingSandbox.newEventLog();
    private final CountDownLatch leaseClosed = new CountDownLatch(1);

    @AfterEach
    void drain() {
        SandboxBackgroundWrites.awaitPendingReleases(5, TimeUnit.SECONDS);
    }

    @Test
    void writeAfterTheCallLandsBeforeTheSandboxIsStopped() throws Exception {
        TrackingSandbox sandbox = new TrackingSandbox(events);
        SandboxBackedFilesystem proxy = new SandboxBackedFilesystem();
        SandboxLifecycleMiddleware mw = new SandboxLifecycleMiddleware(manager(sandbox), proxy);
        RuntimeContext ctx = callContext();

        mw.acquireForCall(ctx);
        // MemoryFlushMiddleware pins in doOnComplete, before Mono.using releases the call.
        SandboxBackgroundWrites.Pin flush = SandboxBackgroundWrites.pinCallSandbox(ctx);
        mw.releaseForCall(ctx);

        assertNull(ctx.get(SandboxAcquireResult.class), "the call's binding is still cleared");
        assertTrue(sandbox.isRunning(), "release must wait for the in-flight memory write");
        assertEquals(1, leaseClosed.getCount(), "the scope lease stays held while deferred");

        // Before the fix this write failed with "No active sandbox" (binding cleared) or
        // "container is not running" (sandbox already stopped and removed).
        List<FileUploadResponse> written =
                proxy.uploadFiles(
                        flush.context(),
                        List.of(
                                Map.entry(
                                        "memory/2026-10-05.md",
                                        "note".getBytes(StandardCharsets.UTF_8))));
        flush.close();

        assertTrue(written.get(0).isSuccess(), String.valueOf(written.get(0).error()));
        assertTrue(leaseClosed.await(5, TimeUnit.SECONDS), "deferred release must run");
        assertEquals(List.of("load", "upload", "stop", "shutdown", "persist"), events);
    }

    @Test
    void nextCallForTheSameScopeWaitsForTheDeferredRelease() throws Exception {
        TrackingSandbox first = new TrackingSandbox(events);
        TrackingSandbox second = new TrackingSandbox(events);
        SandboxLifecycleMiddleware mw =
                new SandboxLifecycleMiddleware(
                        manager(first, second), new SandboxBackedFilesystem());
        RuntimeContext call1 = callContext();
        RuntimeContext call2 = callContext();

        mw.acquireForCall(call1);
        SandboxBackgroundWrites.Pin flush = SandboxBackgroundWrites.pinCallSandbox(call1);
        mw.releaseForCall(call1);
        events.clear();

        CompletableFuture<Void> acquired =
                CompletableFuture.runAsync(() -> mw.acquireForCall(call2));
        assertThrows(
                TimeoutException.class,
                () -> acquired.get(200, TimeUnit.MILLISECONDS),
                "the next call must not resume state the previous call has not persisted");

        flush.close();
        acquired.get(5, TimeUnit.SECONDS);
        assertEquals(List.of("stop", "shutdown", "persist", "load"), events);
        assertTrue(second.isRunning());
        mw.releaseForCall(call2);
    }

    @Test
    void releaseIsNotDeferredWithoutBackgroundWrites() throws Exception {
        TrackingSandbox sandbox = new TrackingSandbox(events);
        SandboxLifecycleMiddleware mw =
                new SandboxLifecycleMiddleware(manager(sandbox), new SandboxBackedFilesystem());
        RuntimeContext ctx = callContext();

        mw.acquireForCall(ctx);
        mw.releaseForCall(ctx);

        assertFalse(sandbox.isRunning());
        assertEquals(0, leaseClosed.getCount());
        assertEquals(List.of("load", "stop", "shutdown", "persist"), events);
    }

    @Test
    void userManagedSandboxIsReleasedAtOnceAndStillReceivesTheWrite() throws Exception {
        TrackingSandbox external = new TrackingSandbox(events);
        SandboxBackedFilesystem proxy = new SandboxBackedFilesystem();
        SandboxLifecycleMiddleware mw = new SandboxLifecycleMiddleware(manager(), proxy);
        RuntimeContext ctx =
                RuntimeContext.builder()
                        .userId("u1")
                        .sessionId("s1")
                        .put(
                                SandboxContext.class,
                                SandboxContext.builder().externalSandbox(external).build())
                        .build();

        mw.acquireForCall(ctx);
        SandboxBackgroundWrites.Pin flush = SandboxBackgroundWrites.pinCallSandbox(ctx);
        mw.releaseForCall(ctx);
        List<FileUploadResponse> written =
                proxy.uploadFiles(
                        flush.context(),
                        List.of(Map.entry("memory/x.md", "x".getBytes(StandardCharsets.UTF_8))));
        flush.close();

        assertTrue(written.get(0).isSuccess(), String.valueOf(written.get(0).error()));
        assertTrue(SandboxBackgroundWrites.awaitPendingReleases(0, TimeUnit.MILLISECONDS));
        assertEquals(List.of("upload"), events, "a user-managed sandbox is never stopped");
    }

    private static RuntimeContext callContext() {
        return RuntimeContext.builder()
                .userId("u1")
                .sessionId("s1")
                .put(SandboxContext.class, SandboxContext.builder().build())
                .build();
    }

    /** A real {@link SandboxManager} whose client creates the given sandboxes in order. */
    @SuppressWarnings("unchecked")
    private SandboxManager manager(TrackingSandbox... sandboxes) throws Exception {
        SandboxClient<SandboxClientOptions> client = mock(SandboxClient.class);
        if (sandboxes.length > 0) {
            TrackingSandbox[] rest = Arrays.copyOfRange(sandboxes, 1, sandboxes.length);
            when(client.create(any(), any(), any())).thenReturn(sandboxes[0], rest);
        }
        when(client.serializeState(any())).thenReturn("{}");
        SessionSandboxStateStore store = mock(SessionSandboxStateStore.class);
        doAnswer(
                        invocation -> {
                            events.add("load");
                            return Optional.empty();
                        })
                .when(store)
                .load(any());
        doAnswer(
                        invocation -> {
                            events.add("persist");
                            return null;
                        })
                .when(store)
                .save(any(), any());
        SandboxLease lease = leaseClosed::countDown;
        return new SandboxManager(client, store, "agent-3415", key -> lease);
    }
}
