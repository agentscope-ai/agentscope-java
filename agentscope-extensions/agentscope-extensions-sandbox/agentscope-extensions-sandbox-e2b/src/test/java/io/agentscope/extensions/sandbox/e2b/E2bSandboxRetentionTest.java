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
package io.agentscope.extensions.sandbox.e2b;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import io.agentscope.core.agent.RuntimeContext;
import io.agentscope.core.state.InMemoryAgentStateStore;
import io.agentscope.harness.agent.IsolationScope;
import io.agentscope.harness.agent.sandbox.Sandbox;
import io.agentscope.harness.agent.sandbox.SandboxAcquireResult;
import io.agentscope.harness.agent.sandbox.SandboxContext;
import io.agentscope.harness.agent.sandbox.SandboxException;
import io.agentscope.harness.agent.sandbox.SandboxManager;
import io.agentscope.harness.agent.sandbox.SandboxReleasePolicy;
import io.agentscope.harness.agent.sandbox.SandboxState;
import io.agentscope.harness.agent.sandbox.SessionSandboxStateStore;
import io.agentscope.harness.agent.sandbox.WorkspaceSpec;
import io.agentscope.harness.agent.sandbox.snapshot.LocalSnapshotSpec;
import io.agentscope.harness.agent.sandbox.snapshot.RemoteSnapshotClient;
import io.agentscope.harness.agent.sandbox.snapshot.RemoteSnapshotSpec;
import io.agentscope.harness.agent.sandbox.snapshot.SandboxSnapshot;
import io.agentscope.harness.agent.sandbox.snapshot.SandboxSnapshotSpec;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import okhttp3.mockwebserver.MockResponse;
import okhttp3.mockwebserver.MockWebServer;
import okhttp3.mockwebserver.RecordedRequest;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class E2bSandboxRetentionTest {
    @TempDir Path temp;
    private MockWebServer server;
    private E2bSandboxClientOptions options;
    private E2bSandboxClient client;

    @BeforeEach
    void setUp() throws Exception {
        server = new MockWebServer();
        server.start();
        options = new E2bSandboxClientOptions();
        options.setApiKey("test-key");
        options.setApiBaseUrl(server.url("/").toString());
        options.setMaxRetries(1);
        options.setSnapshotRetention(1);
        client = new E2bSandboxClient(options, null);
    }

    @AfterEach
    void tearDown() throws Exception {
        server.shutdown();
    }

    private E2bSandboxState state() {
        E2bSandboxState state =
                (E2bSandboxState) client.create(new WorkspaceSpec(), null, null).getState();
        state.setSandboxId("live");
        state.setWorkspaceRootReady(true);
        state.setWorkspaceProjectionHash("projection");
        return state;
    }

    private void respond(int status) {
        server.enqueue(new MockResponse().setResponseCode(status));
    }

    private void sandboxResponse(String id) {
        server.enqueue(
                new MockResponse()
                        .setResponseCode(200)
                        .setHeader("Content-Type", "application/json")
                        .setBody("{\"sandboxID\":\"" + id + "\"}"));
    }

    private void request(String method, String path) throws Exception {
        RecordedRequest request = server.takeRequest(2, TimeUnit.SECONDS);
        assertNotNull(request);
        assertEquals(method, request.getMethod());
        assertEquals(path, request.getPath());
    }

    @Test
    void remoteConfigurationDoesNotReplacePersistedLocalSnapshot() throws Exception {
        E2bSandboxState state = state();
        state.setSnapshot(new LocalSnapshotSpec(temp).build("local-archive"));
        byte[] archive = "retained local workspace".getBytes(StandardCharsets.UTF_8);
        state.getSnapshot().persist(new ByteArrayInputStream(archive));
        SandboxState restored =
                client.deserializeState(
                        client.serializeState(state),
                        new RemoteSnapshotSpec(mock(RemoteSnapshotClient.class)));
        assertEquals(state.getSnapshot().getClass(), restored.getSnapshot().getClass());
        try (InputStream content = restored.getSnapshot().restore()) {
            assertEquals(
                    "retained local workspace",
                    new String(content.readAllBytes(), StandardCharsets.UTF_8));
        }
        assertEquals("live", ((E2bSandboxState) restored).getSandboxId());
        assertEquals(0, server.getRequestCount());
    }

    @Test
    void specDefaultsToDeleteAndPassesRetainThroughToContext() {
        E2bFilesystemSpec spec = new E2bFilesystemSpec();
        assertEquals(SandboxReleasePolicy.DELETE, spec.toSandboxContext().getReleasePolicy());
        assertSame(spec, spec.releasePolicy(SandboxReleasePolicy.RETAIN));
        assertEquals(SandboxReleasePolicy.RETAIN, spec.toSandboxContext().getReleasePolicy());
        assertTrue(spec.toSandboxContext().getClient().supportsRetention());
        assertThrows(NullPointerException.class, () -> spec.releasePolicy(null));
    }

    @Test
    void retainedSnapshotGcPreservesLockedIdsAcrossSerializationAndRetriesOnDelete()
            throws Exception {
        E2bSandboxState state = state();
        state.setSnapshotIds(new ArrayList<>(List.of("locked", "old", "latest")));
        respond(400);
        respond(200);
        E2bSandbox sandbox = new E2bSandbox(state, options);
        sandbox.onRetained();
        request("DELETE", "/templates/locked");
        request("DELETE", "/templates/old");
        assertEquals("live", state.getSandboxId());
        assertEquals(List.of("locked", "latest"), state.getSnapshotIds());
        E2bSandboxState restored =
                (E2bSandboxState) client.deserializeState(client.serializeState(state));
        respond(200);
        respond(200);
        client.delete(client.resume(restored));
        request("DELETE", "/sandboxes/live");
        request("DELETE", "/templates/locked");
        assertEquals(List.of("latest"), restored.getSnapshotIds());
        assertNull(restored.getSandboxId());
        assertFalse(restored.isWorkspaceRootReady());
        assertNull(restored.getWorkspaceProjectionHash());
    }

    @Test
    void explicitDeleteIsIdempotentIncludingAlreadyExpiredSandbox() throws Exception {
        E2bSandboxState state = state();
        respond(404);
        Sandbox sandbox = new E2bSandbox(state, options);
        client.delete(sandbox);
        client.delete(sandbox);
        request("DELETE", "/sandboxes/live");
        assertEquals(1, server.getRequestCount());
        assertNull(state.getSandboxId());
    }

    @Test
    void failedDeleteKeepsIdForRetryAndDoesNotPruneSnapshots() throws Exception {
        E2bSandboxState state = state();
        state.setSnapshotIds(List.of("old", "latest"));
        Sandbox sandbox = new E2bSandbox(state, options);
        respond(500);
        assertThrows(SandboxException.class, () -> client.delete(sandbox));
        assertEquals("live", state.getSandboxId());
        assertEquals(List.of("old", "latest"), state.getSnapshotIds());
        assertEquals(1, server.getRequestCount());
        respond(200);
        respond(200);
        client.delete(sandbox);
        assertNull(state.getSandboxId());
        assertEquals(List.of("latest"), state.getSnapshotIds());
    }

    @Test
    void unownedResourcesAndSnapshotsAreNotDeleted() {
        E2bSandboxState state = state();
        state.setSandboxOwned(false);
        state.setSnapshotIds(List.of("old", "latest"));
        E2bSandbox sandbox = new E2bSandbox(state, options);
        sandbox.onRetained();
        client.delete(sandbox);
        assertEquals(0, server.getRequestCount());
        assertEquals("live", state.getSandboxId());
        assertEquals(List.of("old", "latest"), state.getSnapshotIds());
    }

    @Test
    void explicitCloseStillDestroysRetainedSandbox() throws Exception {
        E2bSandboxState state = state();
        E2bSandbox sandbox = new E2bSandbox(state, options);
        sandbox.onRetained();
        respond(200);
        sandbox.close();
        request("DELETE", "/sandboxes/live");
        assertNull(state.getSandboxId());
        assertFalse(state.isWorkspaceRootReady());
    }

    @Test
    void zeroRetentionDoesNotPruneDuringRetainedRelease() {
        E2bSandboxState state = state();
        state.setSnapshotIds(List.of("old", "latest"));
        options.setSnapshotRetention(0);
        new E2bSandbox(state, options).onRetained();
        assertEquals(0, server.getRequestCount());
        assertEquals(List.of("old", "latest"), state.getSnapshotIds());
    }

    @Test
    void deleteRejectsOtherBackend() {
        assertThrows(IllegalArgumentException.class, () -> client.delete(mock(Sandbox.class)));
    }

    @Test
    void failedNativeSnapshotUploadDoesNotPrunePreviousArchiveSnapshot() throws Exception {
        E2bSandboxState state = state();
        state.setPersistenceMode(E2bPersistenceMode.NATIVE_SNAPSHOT);
        state.setSnapshotIds(new ArrayList<>(List.of("previous")));
        SandboxSnapshot snapshot = mock(SandboxSnapshot.class);
        when(snapshot.isPersistenceEnabled()).thenReturn(true);
        doThrow(new IOException("store unavailable")).when(snapshot).persist(any());
        state.setSnapshot(snapshot);
        server.enqueue(
                new MockResponse().setResponseCode(200).setBody("{\"snapshotID\":\"failed-new\"}"));
        E2bSandbox sandbox = new E2bSandbox(state, options);
        SandboxManager manager =
                new SandboxManager(client, mock(SessionSandboxStateStore.class), "agent");
        manager.release(
                SandboxAcquireResult.selfManaged(sandbox, null, SandboxReleasePolicy.RETAIN));
        request("POST", "/sandboxes/live/snapshots");
        assertEquals(1, server.getRequestCount());
        assertEquals(List.of("previous"), state.getSnapshotIds());
        assertEquals("live", state.getSandboxId());
    }

    @Test
    void successfulNativeSnapshotUploadPrunesOnlyAfterPersistence() throws Exception {
        E2bSandboxState state = state();
        state.setPersistenceMode(E2bPersistenceMode.NATIVE_SNAPSHOT);
        state.setSnapshotIds(new ArrayList<>(List.of("old")));
        SandboxSnapshot snapshot = mock(SandboxSnapshot.class);
        when(snapshot.isPersistenceEnabled()).thenReturn(true);
        doAnswer(
                        invocation -> {
                            assertEquals(
                                    1,
                                    server.getRequestCount(),
                                    "no cleanup before archive persisted");
                            return null;
                        })
                .when(snapshot)
                .persist(any());
        state.setSnapshot(snapshot);
        server.enqueue(
                new MockResponse().setResponseCode(200).setBody("{\"snapshotID\":\"latest\"}"));
        respond(200);
        E2bSandbox sandbox = new E2bSandbox(state, options);
        new SandboxManager(client, mock(SessionSandboxStateStore.class), "agent")
                .release(
                        SandboxAcquireResult.selfManaged(
                                sandbox, null, SandboxReleasePolicy.RETAIN));
        request("POST", "/sandboxes/live/snapshots");
        request("DELETE", "/templates/old");
        assertEquals(List.of("latest"), state.getSnapshotIds());
        assertEquals("live", state.getSandboxId());
        assertFalse(sandbox.isRunning());
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void twoCallsReuseSameRemoteSandboxThenExpiryRestoresLatestSnapshot(boolean remote)
            throws Exception {
        Map<String, byte[]> archives = new HashMap<>();
        RemoteSnapshotClient storage =
                new RemoteSnapshotClient() {
                    @Override
                    public void upload(String id, InputStream data) throws Exception {
                        archives.put(id, data.readAllBytes());
                    }

                    @Override
                    public InputStream download(String id) {
                        return new ByteArrayInputStream(archives.get(id));
                    }

                    @Override
                    public boolean exists(String id) {
                        return archives.containsKey(id);
                    }
                };
        SandboxSnapshotSpec snapshots =
                remote ? new RemoteSnapshotSpec(storage) : new LocalSnapshotSpec(temp);
        // Exercise manager acquire/release, JSON state storage, E2B platform HTTP and base
        // start/stop.
        // Only guest filesystem operations are replaced with an in-memory workspace.
        WorkspaceClient localClient = new WorkspaceClient();
        SandboxManager manager =
                new SandboxManager(
                        localClient,
                        new SessionSandboxStateStore(new InMemoryAgentStateStore(), "agent"),
                        "agent");
        SandboxContext context =
                SandboxContext.builder()
                        .isolationScope(IsolationScope.SESSION)
                        .snapshotSpec(snapshots)
                        .releasePolicy(SandboxReleasePolicy.RETAIN)
                        .build();
        RuntimeContext runtime = RuntimeContext.builder().sessionId("same-session").build();

        sandboxResponse("first");
        SandboxAcquireResult first = manager.acquire(context, runtime);
        first.getSandbox().start();
        localClient.workspace = "first-call-file";
        manager.release(first);
        manager.persistState(first, context, runtime);
        request("POST", "/sandboxes");
        assertEquals(1, server.getRequestCount());

        sandboxResponse("first");
        SandboxAcquireResult second = manager.acquire(context, runtime);
        assertNotSame(first.getSandbox(), second.getSandbox());
        second.getSandbox().start();
        assertEquals("first-call-file", localClient.workspace);
        assertEquals(0, localClient.restores);
        localClient.workspace = "second-call-file";
        manager.release(second);
        manager.persistState(second, context, runtime);
        request("POST", "/sandboxes/first/connect");
        assertEquals(2, server.getRequestCount());

        assertEquals(
                first.getSandbox().getState().getSnapshot().getId(),
                second.getSandbox().getState().getSnapshot().getId());
        if (remote) {
            assertEquals(1, archives.size());
            assertEquals(
                    "second-call-file",
                    new String(archives.values().iterator().next(), StandardCharsets.UTF_8));
        }
        // Provider expiry removes the guest, but the latest archive remains available.
        localClient.workspace = null;
        respond(404);
        sandboxResponse("replacement");
        SandboxAcquireResult third = manager.acquire(context, runtime);
        third.getSandbox().start();
        assertEquals("second-call-file", localClient.workspace);
        assertEquals(1, localClient.restores);
        assertEquals(
                "replacement", ((E2bSandboxState) third.getSandbox().getState()).getSandboxId());
        request("POST", "/sandboxes/first/connect");
        request("POST", "/sandboxes");
        respond(200);
        assertTrue(third.getSandbox().isRunning());
        localClient.delete(third.getSandbox());
        assertFalse(third.getSandbox().isRunning());
        request("DELETE", "/sandboxes/replacement");
    }

    private final class WorkspaceClient extends E2bSandboxClient {
        private String workspace;
        private int restores;

        WorkspaceClient() {
            super(options, null);
        }

        @Override
        public Sandbox create(
                WorkspaceSpec spec, SandboxSnapshotSpec snapshots, E2bSandboxClientOptions call) {
            return wrap((E2bSandboxState) super.create(spec, snapshots, call).getState());
        }

        @Override
        public Sandbox resume(SandboxState state) {
            return wrap((E2bSandboxState) state);
        }

        private Sandbox wrap(E2bSandboxState state) {
            return new E2bSandbox(state, options) {
                @Override
                protected boolean probeWorkspaceRootForPreservedResume() {
                    return workspace != null;
                }

                @Override
                protected void doSetupWorkspace() {
                    workspace = "";
                }

                @Override
                protected InputStream doPersistWorkspace() {
                    return new ByteArrayInputStream(workspace.getBytes(StandardCharsets.UTF_8));
                }

                @Override
                protected void doHydrateWorkspace(InputStream archive) throws Exception {
                    workspace = new String(archive.readAllBytes(), StandardCharsets.UTF_8);
                    restores++;
                }
            };
        }
    }
}
