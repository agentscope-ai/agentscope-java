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

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import io.agentscope.core.agent.RuntimeContext;
import io.agentscope.harness.agent.IsolationScope;
import io.agentscope.harness.agent.filesystem.sandbox.SandboxBackedFilesystem;
import io.agentscope.harness.agent.middleware.SandboxLifecycleMiddleware;
import java.io.IOException;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.mockito.InOrder;

class SandboxRetentionTest {
    @SuppressWarnings("unchecked")
    private final SandboxClient<SandboxClientOptions> client = mock(SandboxClient.class);

    private final SessionSandboxStateStore store = mock(SessionSandboxStateStore.class);
    private final Sandbox sandbox = mock(Sandbox.class);
    private final SandboxManager manager = new SandboxManager(client, store, "agent");

    private SandboxContext context(SandboxReleasePolicy policy) {
        return SandboxContext.builder()
                .isolationScope(IsolationScope.SESSION)
                .releasePolicy(policy)
                .build();
    }

    private RuntimeContext runtime() {
        return RuntimeContext.builder().sessionId("session").build();
    }

    @Test
    void defaultReleaseStillStopsThenShutsDown() throws Exception {
        assertEquals(
                SandboxReleasePolicy.DELETE, SandboxContext.builder().build().getReleasePolicy());
        manager.release(SandboxAcquireResult.selfManaged(sandbox));
        InOrder order = inOrder(sandbox);
        order.verify(sandbox).stop();
        order.verify(sandbox).shutdown();
        verify(sandbox, never()).onRetained();
    }

    @Test
    void retainFlowsThroughFreshAcquireAndPersistBeforeUnlock() throws Exception {
        when(client.supportsRetention()).thenReturn(true);
        when(store.load(any())).thenReturn(Optional.empty());
        when(client.create(any(), any(), any())).thenReturn(sandbox);
        SandboxState state = mock(SandboxState.class);
        when(sandbox.getState()).thenReturn(state);
        when(client.serializeState(state)).thenReturn("saved");
        SandboxLease lease = mock(SandboxLease.class);
        SandboxManager guarded = new SandboxManager(client, store, "agent", key -> lease);
        SandboxContext config = context(SandboxReleasePolicy.RETAIN);
        RuntimeContext ctx = runtime();
        SandboxAcquireResult result = guarded.acquire(config, ctx);
        assertEquals(SandboxReleasePolicy.RETAIN, result.getReleasePolicy());
        ctx.put(SandboxContext.class, config);
        ctx.put(SandboxAcquireResult.class, result);
        new SandboxLifecycleMiddleware(guarded, new SandboxBackedFilesystem()).releaseForCall(ctx);
        InOrder order = inOrder(sandbox, store, lease);
        order.verify(sandbox).stop();
        order.verify(sandbox).onRetained();
        order.verify(store).save(any(), eq("saved"));
        order.verify(lease).close();
        verify(sandbox, never()).shutdown();
    }

    @Test
    void currentPolicyAppliesToResumedState() throws Exception {
        when(client.supportsRetention()).thenReturn(true);
        when(store.load(any())).thenReturn(Optional.of("saved"));
        SandboxState state = mock(SandboxState.class);
        when(client.deserializeState("saved", null)).thenReturn(state);
        when(client.resume(state)).thenReturn(sandbox);
        SandboxAcquireResult result =
                manager.acquire(context(SandboxReleasePolicy.RETAIN), runtime());
        manager.release(result);
        verify(sandbox).onRetained();
        verify(sandbox, never()).shutdown();
        // A subsequent call can turn retention off without changing serialized sandbox state.
        manager.release(manager.acquire(context(SandboxReleasePolicy.DELETE), runtime()));
        verify(sandbox).shutdown();
    }

    @Test
    void unsupportedBackendRejectedBeforeAcquire() throws Exception {
        assertThrows(
                SandboxException.SandboxConfigurationException.class,
                () -> manager.acquire(context(SandboxReleasePolicy.RETAIN), runtime()));
        verify(client, never()).create(any(), any(), any());
        verifyNoInteractions(store);
    }

    @Test
    void missingIsolationKeyRejectedBeforeAcquire() throws Exception {
        when(client.supportsRetention()).thenReturn(true);
        assertThrows(
                SandboxException.SandboxConfigurationException.class,
                () ->
                        manager.acquire(
                                context(SandboxReleasePolicy.RETAIN), RuntimeContext.empty()));
        verify(client, never()).create(any(), any(), any());
        verifyNoInteractions(store);
    }

    @Test
    void externalSandboxRemainsCallerOwnedEvenWithRetain() throws Exception {
        SandboxContext config =
                SandboxContext.builder()
                        .externalSandbox(sandbox)
                        .releasePolicy(SandboxReleasePolicy.RETAIN)
                        .build();
        SandboxAcquireResult result = manager.acquire(config, null);
        manager.release(result);
        manager.discard(result);
        verify(sandbox, never()).stop();
        verify(sandbox, never()).shutdown();
        verify(sandbox, never()).onRetained();
        verifyNoInteractions(client, store);
    }

    @Test
    void explicitStateCanBeRetainedWithoutHarnessIsolationKey() throws Exception {
        when(client.supportsRetention()).thenReturn(true);
        SandboxState state = mock(SandboxState.class);
        when(client.resume(state)).thenReturn(sandbox);
        SandboxContext config =
                SandboxContext.builder()
                        .externalSandboxState(state)
                        .releasePolicy(SandboxReleasePolicy.RETAIN)
                        .build();
        manager.release(manager.acquire(config, null));
        verify(sandbox).stop();
        verify(sandbox).onRetained();
        verify(sandbox, never()).shutdown();
        verifyNoInteractions(store);
    }

    @Test
    void failedSnapshotRetainsLiveWorkspaceWithoutPruning() throws Exception {
        doThrow(new IOException("snapshot unavailable")).when(sandbox).stop();
        manager.release(
                SandboxAcquireResult.selfManaged(sandbox, null, SandboxReleasePolicy.RETAIN));
        verify(sandbox, never()).onRetained();
        verify(sandbox, never()).shutdown();
    }

    @Test
    void failedRetainedMaintenanceDoesNotDestroyWorkspace() throws Exception {
        doThrow(new IOException("maintenance unavailable")).when(sandbox).onRetained();
        assertDoesNotThrow(
                () ->
                        manager.release(
                                SandboxAcquireResult.selfManaged(
                                        sandbox, null, SandboxReleasePolicy.RETAIN)));
        verify(sandbox, never()).shutdown();
    }

    @Test
    void failedStartDelegatesCleanupWithoutOverwritingSnapshotAndReleasesLease() throws Exception {
        when(client.supportsRetention()).thenReturn(true);
        when(store.load(any())).thenReturn(Optional.empty());
        when(client.create(any(), any(), any())).thenReturn(sandbox);
        doThrow(new IOException("start failed")).when(sandbox).start();
        SandboxLease lease = mock(SandboxLease.class);
        SandboxManager guarded = new SandboxManager(client, store, "agent", key -> lease);
        RuntimeContext ctx = runtime();
        ctx.put(SandboxContext.class, context(SandboxReleasePolicy.RETAIN));
        SandboxLifecycleMiddleware middleware =
                new SandboxLifecycleMiddleware(guarded, new SandboxBackedFilesystem());
        assertThrows(RuntimeException.class, () -> middleware.acquireForCall(ctx));
        verify(sandbox).cleanupAfterStartFailure();
        verify(sandbox, never()).shutdown();
        verify(sandbox, never()).stop();
        verify(store, never()).save(any(), any());
        verify(lease).close();
        assertNull(ctx.get(SandboxAcquireResult.class));
    }

    @Test
    void unavailableStateStoreDoesNotCreateAnotherRetainedSandboxAndReleasesLease()
            throws Exception {
        when(client.supportsRetention()).thenReturn(true);
        when(store.load(any())).thenThrow(new IOException("store unavailable"));
        SandboxLease lease = mock(SandboxLease.class);
        SandboxManager guarded = new SandboxManager(client, store, "agent", key -> lease);
        assertThrows(
                IOException.class,
                () -> guarded.acquire(context(SandboxReleasePolicy.RETAIN), runtime()));
        verify(client, never()).create(any(), any(), any());
        verify(lease).close();
    }

    @Test
    void nullPolicyRejected() {
        assertThrows(
                NullPointerException.class, () -> SandboxContext.builder().releasePolicy(null));
    }
}
