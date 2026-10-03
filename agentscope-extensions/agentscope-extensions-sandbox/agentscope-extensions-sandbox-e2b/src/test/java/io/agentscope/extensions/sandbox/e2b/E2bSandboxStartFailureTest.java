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
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import io.agentscope.core.agent.RuntimeContext;
import io.agentscope.harness.agent.IsolationScope;
import io.agentscope.harness.agent.filesystem.sandbox.SandboxBackedFilesystem;
import io.agentscope.harness.agent.middleware.SandboxLifecycleMiddleware;
import io.agentscope.harness.agent.sandbox.ExecResult;
import io.agentscope.harness.agent.sandbox.Sandbox;
import io.agentscope.harness.agent.sandbox.SandboxAcquireResult;
import io.agentscope.harness.agent.sandbox.SandboxContext;
import io.agentscope.harness.agent.sandbox.SandboxLease;
import io.agentscope.harness.agent.sandbox.SandboxManager;
import io.agentscope.harness.agent.sandbox.SandboxReleasePolicy;
import io.agentscope.harness.agent.sandbox.SandboxState;
import io.agentscope.harness.agent.sandbox.SessionSandboxStateStore;
import io.agentscope.harness.agent.sandbox.WorkspaceSpec;
import io.agentscope.harness.agent.sandbox.snapshot.LocalSnapshotSpec;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.TimeUnit;
import okhttp3.OkHttpClient;
import okhttp3.mockwebserver.MockResponse;
import okhttp3.mockwebserver.MockWebServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class E2bSandboxStartFailureTest {
    @TempDir Path temp;
    private MockWebServer server;
    private E2bSandboxClientOptions options;
    private E2bSandboxState state;
    private boolean failProbe;
    private boolean missingWorkspace;
    private boolean failSetup;
    private boolean failRestore;
    private int restores;
    private E2bSandboxClient client;
    private SessionSandboxStateStore store;
    private SandboxLease lease;
    private SandboxLifecycleMiddleware middleware;
    private RuntimeContext context;

    @BeforeEach
    void setUp() throws Exception {
        server = new MockWebServer();
        server.start();
        options = new E2bSandboxClientOptions();
        options.setApiKey("test-key");
        options.setApiBaseUrl(server.url("/").toString());
        options.setMaxRetries(1);
        options.setSnapshotRetention(1);
        client =
                new E2bSandboxClient(options, null) {
                    @Override
                    public Sandbox resume(SandboxState restored) {
                        state = (E2bSandboxState) restored;
                        return sandbox();
                    }
                };
        state = (E2bSandboxState) client.create(new WorkspaceSpec(), null, null).getState();
        state.setSandboxId("retained-live");
        state.setWorkspaceRootReady(true);
        state.setSnapshotIds(List.of("old", "latest"));
        store = mock(SessionSandboxStateStore.class);
        lease = mock(SandboxLease.class);
        middleware =
                new SandboxLifecycleMiddleware(
                        new SandboxManager(client, store, "agent", key -> lease),
                        new SandboxBackedFilesystem());
        context = RuntimeContext.builder().sessionId("session").build();
        context.put(
                SandboxContext.class,
                SandboxContext.builder()
                        .isolationScope(IsolationScope.SESSION)
                        .releasePolicy(SandboxReleasePolicy.RETAIN)
                        .build());
    }

    @AfterEach
    void tearDown() throws Exception {
        server.shutdown();
    }

    private Sandbox sandbox() {
        return new E2bSandbox(state, options) {
            @Override
            protected ExecResult doExec(RuntimeContext ctx, String command, int timeout)
                    throws Exception {
                if (failProbe) {
                    throw new IOException("temporary guest connection failure");
                }
                return new ExecResult(missingWorkspace ? 1 : 0, "", "", false);
            }

            @Override
            protected void doSetupWorkspace() throws Exception {
                if (failSetup) {
                    throw new IOException("temporary initialization failure");
                }
            }

            @Override
            protected void doHydrateWorkspace(InputStream archive) throws Exception {
                restores++;
                if (failRestore) {
                    throw new IOException("temporary restore failure");
                }
            }
        };
    }

    private void saved() throws Exception {
        when(store.load(any())).thenReturn(Optional.of(client.serializeState(state)));
    }

    private void connected(String id) {
        server.enqueue(new MockResponse().setBody("{\"sandboxID\":\"" + id + "\"}"));
    }

    private void request(String method, String path) throws Exception {
        var request = server.takeRequest(2, TimeUnit.SECONDS);
        assertNotNull(request);
        assertEquals(method, request.getMethod());
        assertEquals(path, request.getPath());
    }

    private void failedCallPreservesStoredState() throws Exception {
        assertThrows(RuntimeException.class, () -> middleware.acquireForCall(context));
        verify(lease).close();
        verify(store, never()).save(any(), any());
        assertNull(context.get(SandboxAcquireResult.class));
    }

    @ParameterizedTest
    @ValueSource(ints = {401, 403, 429, 500, 503})
    void failedConnectPreservesLiveSandboxAndCanRetry(int status) throws Exception {
        saved();
        server.enqueue(new MockResponse().setResponseCode(status));
        failedCallPreservesStoredState();
        assertEquals("retained-live", state.getSandboxId());
        assertEquals(1, server.getRequestCount());
        request("POST", "/sandboxes/retained-live/connect");
        connected("retained-live");
        middleware.acquireForCall(context);
        assertEquals("retained-live", state.getSandboxId());
        assertEquals(2, server.getRequestCount());
        assertEquals(0, restores);
    }

    @Test
    void transportFailurePreservesLiveSandbox() throws Exception {
        saved();
        options.setHttpClient(
                new OkHttpClient.Builder()
                        .addInterceptor(
                                chain -> {
                                    throw new IOException("network unavailable");
                                })
                        .build());
        failedCallPreservesStoredState();
        assertEquals("retained-live", state.getSandboxId());
        assertEquals(0, server.getRequestCount());
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void workspaceFailurePreservesExistingSandboxWithoutRestoringOrDeleting(boolean probe)
            throws Exception {
        saved();
        failProbe = probe;
        missingWorkspace = !probe;
        failSetup = !probe;
        connected("retained-live");
        failedCallPreservesStoredState();
        assertEquals("retained-live", state.getSandboxId());
        assertEquals(1, server.getRequestCount());
        assertEquals(0, restores);
        request("POST", "/sandboxes/retained-live/connect");
        failProbe = false;
        missingWorkspace = false;
        failSetup = false;
        connected("retained-live");
        middleware.acquireForCall(context);
        assertTrue(context.get(SandboxAcquireResult.class).getSandbox().isRunning());
        assertEquals(2, server.getRequestCount());
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void failedNewAllocationIsDeletedWithoutSnapshotGc(boolean expired) throws Exception {
        if (expired) {
            state.setSnapshot(new LocalSnapshotSpec(temp).build("archive"));
            state.getSnapshot().persist(new ByteArrayInputStream(new byte[] {1}));
            failRestore = true;
            server.enqueue(new MockResponse().setResponseCode(404));
        } else {
            state.setSandboxId(null);
            state.setWorkspaceRootReady(false);
            failSetup = true;
        }
        saved();
        connected("new-allocation");
        server.enqueue(new MockResponse().setResponseCode(200));
        failedCallPreservesStoredState();
        if (expired) {
            request("POST", "/sandboxes/retained-live/connect");
        }
        request("POST", "/sandboxes");
        request("DELETE", "/sandboxes/new-allocation");
        assertEquals(expired ? 3 : 2, server.getRequestCount());
        assertNull(state.getSandboxId());
        assertEquals(List.of("old", "latest"), state.getSnapshotIds());
        assertEquals(expired ? 1 : 0, restores);
    }
}
