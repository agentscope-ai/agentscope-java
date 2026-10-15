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
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.agentscope.harness.agent.sandbox.WorkspaceSpec;
import io.agentscope.harness.agent.sandbox.snapshot.SandboxSnapshot;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import okhttp3.mockwebserver.MockResponse;
import okhttp3.mockwebserver.MockWebServer;
import okhttp3.mockwebserver.RecordedRequest;
import okio.Buffer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class E2bSandboxRecoveryTest {

    private MockWebServer server;

    @BeforeEach
    void setUp() throws Exception {
        server = new MockWebServer();
        server.start();
    }

    @AfterEach
    void tearDown() throws Exception {
        server.shutdown();
    }

    @Test
    void recreateClearsStaleWorkspaceProjectionState() throws Exception {
        server.enqueue(new MockResponse().setResponseCode(500).setBody("connect failed"));
        server.enqueue(
                new MockResponse()
                        .setResponseCode(200)
                        .setHeader("Content-Type", "application/json")
                        .setBody(
                                "{\"sandboxID\":\"new-sandbox\","
                                        + "\"domain\":\"new.e2b.app\","
                                        + "\"envdAccessToken\":\"new-token\"}"));
        E2bSandboxState state = stateForResume();

        invokeEnsureSandbox(new E2bSandbox(state, options()));

        assertEquals("new-sandbox", state.getSandboxId());
        assertFalse(state.isWorkspaceRootReady());
        assertNull(state.getWorkspaceProjectionHash());
        RecordedRequest connect = server.takeRequest();
        RecordedRequest create = server.takeRequest();
        assertEquals("/sandboxes/old-sandbox/connect", connect.getPath());
        assertEquals("/sandboxes", create.getPath());
    }

    @Test
    void reconnectPreservesWorkspaceProjectionState() throws Exception {
        server.enqueue(
                new MockResponse()
                        .setResponseCode(200)
                        .setHeader("Content-Type", "application/json")
                        .setBody("{\"sandboxID\":\"old-sandbox\"}"));
        E2bSandboxState state = stateForResume();

        invokeEnsureSandbox(new E2bSandbox(state, options()));

        assertEquals("old-sandbox", state.getSandboxId());
        assertTrue(state.isWorkspaceRootReady());
        assertEquals("old-projection", state.getWorkspaceProjectionHash());
        assertEquals("/sandboxes/old-sandbox/connect", server.takeRequest().getPath());
    }

    @Test
    void startSkipsSnapshotRetryAfterTemplateFallback() throws Exception {
        server.enqueue(new MockResponse().setResponseCode(500).setBody("connect failed"));
        server.enqueue(new MockResponse().setResponseCode(500).setBody("snapshot unavailable"));
        server.enqueue(sandboxResponse("fresh-sandbox"));
        server.enqueue(envdStartResponse());
        E2bSandboxState state = stateForResumeWithNativeSnapshot("snap-123");

        new E2bSandbox(state, startOptions()).start();

        assertEquals(4, server.getRequestCount(), "start must not retry the failed snapshot");
        RecordedRequest connect = server.takeRequest();
        RecordedRequest snapshotCreate = server.takeRequest();
        RecordedRequest templateCreate = server.takeRequest();
        RecordedRequest setupWorkspace = server.takeRequest();
        assertEquals("/sandboxes/old-sandbox/connect", connect.getPath());
        assertEquals("/sandboxes", snapshotCreate.getPath());
        assertTrue(snapshotCreate.getBody().readUtf8().contains("snap-123"));
        assertEquals("/sandboxes", templateCreate.getPath());
        assertTrue(templateCreate.getBody().readUtf8().contains("\"base\""));
        assertEquals("/process.Process/Start", setupWorkspace.getPath());
        assertEquals("fresh-sandbox", state.getSandboxId());
        assertTrue(state.isWorkspaceRootReady());
    }

    @Test
    void startSkipsSnapshotRetryAfterPeekFailure() throws Exception {
        server.enqueue(new MockResponse().setResponseCode(500).setBody("connect failed"));
        server.enqueue(sandboxResponse("fresh-sandbox"));
        server.enqueue(envdStartResponse());
        E2bSandboxState state = stateForResumeWithNativeSnapshot("snap-123");
        state.setSnapshot(
                new FakeSnapshot(nativeArchive("snap-123"), true) {
                    @Override
                    public InputStream restore() throws Exception {
                        throw new IOException("snapshot store unreachable");
                    }
                });

        new E2bSandbox(state, startOptions()).start();

        assertEquals(3, server.getRequestCount(), "peek failure must not retry the snapshot");
        RecordedRequest connect = server.takeRequest();
        RecordedRequest templateCreate = server.takeRequest();
        RecordedRequest setupWorkspace = server.takeRequest();
        assertEquals("/sandboxes/old-sandbox/connect", connect.getPath());
        assertEquals("/sandboxes", templateCreate.getPath());
        assertTrue(templateCreate.getBody().readUtf8().contains("\"base\""));
        assertEquals("/process.Process/Start", setupWorkspace.getPath());
        assertEquals("fresh-sandbox", state.getSandboxId());
        assertTrue(state.isWorkspaceRootReady());
    }

    @Test
    void startRestoresSnapshotAfterConnectFailureWithoutReopeningSnapshotStore() throws Exception {
        server.enqueue(new MockResponse().setResponseCode(500).setBody("connect failed"));
        server.enqueue(sandboxResponse("restored-sandbox"));
        server.enqueue(envdStartResponse());
        E2bSandboxState state = stateForResumeWithNativeSnapshot("snap-123");
        FakeSnapshot snapshot = (FakeSnapshot) state.getSnapshot();

        new E2bSandbox(state, startOptions()).start();

        assertEquals(3, server.getRequestCount(), "snapshot restore must create one sandbox");
        RecordedRequest connect = server.takeRequest();
        RecordedRequest create = server.takeRequest();
        RecordedRequest setupWorkspace = server.takeRequest();
        assertEquals("/sandboxes/old-sandbox/connect", connect.getPath());
        assertEquals("/sandboxes", create.getPath());
        String createBody = create.getBody().readUtf8();
        assertTrue(createBody.contains("snap-123"), "create must target the snapshot id");
        assertFalse(createBody.contains("\"base\""), "must not create from the template");
        assertEquals("/process.Process/Start", setupWorkspace.getPath());
        assertEquals(1, snapshot.restoreCount(), "the peeked archive must be reused");
        assertEquals("restored-sandbox", state.getSandboxId());
        assertTrue(state.isWorkspaceRootReady());
    }

    /**
     * The negative path: a connected sandbox predates the snapshot, so the short-circuit must not
     * fire — start has to rebuild from the snapshot and kill the sandbox it replaces.
     */
    @Test
    void startRebuildsConnectedSandboxWhenSnapshotIsNewer() throws Exception {
        server.enqueue(sandboxResponse("old-sandbox"));
        server.enqueue(envdStartResponse());
        server.enqueue(sandboxResponse("rebuilt-sandbox"));
        server.enqueue(new MockResponse().setResponseCode(204));
        E2bSandboxState state = stateForResumeWithNativeSnapshot("snap-123");
        state.setWorkspaceRootReady(false);
        state.setWorkspaceProjectionHash(null);

        new E2bSandbox(state, startOptions()).start();

        assertEquals(4, server.getRequestCount(), "start must rebuild and kill the old sandbox");
        assertEquals("/sandboxes/old-sandbox/connect", server.takeRequest().getPath());
        assertEquals("/process.Process/Start", server.takeRequest().getPath());
        RecordedRequest rebuild = server.takeRequest();
        assertEquals("/sandboxes", rebuild.getPath());
        assertTrue(rebuild.getBody().readUtf8().contains("snap-123"));
        assertEquals("/sandboxes/old-sandbox", server.takeRequest().getPath());
        assertEquals("rebuilt-sandbox", state.getSandboxId());
        assertTrue(state.isWorkspaceRootReady());
    }

    /**
     * A killed sandbox leaves a dead id behind; clearing it after a successful kill keeps the next
     * resume from spending a guaranteed-404 connect before it creates a replacement.
     */
    @Test
    void shutdownClearsSandboxIdAfterKill() throws Exception {
        server.enqueue(new MockResponse().setResponseCode(204));
        E2bSandboxState state = stateForResume();

        new E2bSandbox(state, options()).shutdown();

        assertEquals("/sandboxes/old-sandbox", server.takeRequest().getPath());
        assertNull(state.getSandboxId(), "a killed sandbox must not leave a dead id behind");
    }

    /**
     * If the kill fails the sandbox may still be alive, so the id must survive: the next resume
     * retries the connect and can recover.
     */
    @Test
    void shutdownKeepsSandboxIdWhenKillFails() throws Exception {
        server.enqueue(new MockResponse().setResponseCode(500).setBody("kill failed"));
        E2bSandboxState state = stateForResume();

        try {
            new E2bSandbox(state, options()).shutdown();
        } catch (Exception expected) {
            // The kill failure propagates; the point is the id survives.
        }

        assertEquals("/sandboxes/old-sandbox", server.takeRequest().getPath());
        assertEquals("old-sandbox", state.getSandboxId());
    }

    private static MockResponse sandboxResponse(String sandboxId) {
        return new MockResponse()
                .setResponseCode(200)
                .setHeader("Content-Type", "application/json")
                .setBody(
                        "{\"sandboxID\":\""
                                + sandboxId
                                + "\",\"domain\":\"new.e2b.app\","
                                + "\"envdAccessToken\":\"new-token\"}");
    }

    private static MockResponse envdStartResponse() {
        byte[] payload = "{\"event\":{\"end\":{\"exitCode\":0}}}".getBytes(StandardCharsets.UTF_8);
        Buffer body =
                new Buffer()
                        .writeByte(0)
                        .writeInt(payload.length)
                        .write(payload)
                        .writeByte(2)
                        .writeInt(0);
        return new MockResponse()
                .setResponseCode(200)
                .setHeader("Content-Type", "application/connect+json")
                .setBody(body);
    }

    private E2bSandboxClientOptions options() {
        E2bSandboxClientOptions options = new E2bSandboxClientOptions();
        options.setApiBaseUrl(server.url("/").toString());
        options.setApiKey("test-key");
        options.setMaxRetries(1);
        return options;
    }

    private E2bSandboxClientOptions startOptions() {
        E2bSandboxClientOptions options = options();
        options.setCodec(E2bCodec.JSON);
        options.setEnvdSandboxUrl(server.url("/").toString());
        return options;
    }

    private static E2bSandboxState stateForResume() {
        E2bSandboxState state = new E2bSandboxState();
        state.setWorkspaceSpec(new WorkspaceSpec());
        state.setSandboxId("old-sandbox");
        state.setWorkspaceRootReady(true);
        state.setWorkspaceProjectionHash("old-projection");
        return state;
    }

    /** A resumable session whose dead sandbox id pairs with a restorable native snapshot. */
    private static E2bSandboxState stateForResumeWithNativeSnapshot(String snapshotId) {
        E2bSandboxState state = stateForResume();
        state.setPersistenceMode(E2bPersistenceMode.NATIVE_SNAPSHOT);
        try {
            state.setSnapshot(new FakeSnapshot(nativeArchive(snapshotId), true));
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
        return state;
    }

    /** A fresh session (no sandbox id) holding a restorable native snapshot. */
    private static E2bSandboxState stateWithNativeSnapshot(String snapshotId) {
        E2bSandboxState state = new E2bSandboxState();
        state.setWorkspaceSpec(new WorkspaceSpec());
        state.setSandboxId(null);
        state.setWorkspaceRootReady(false);
        state.setPersistenceMode(E2bPersistenceMode.NATIVE_SNAPSHOT);
        try {
            state.setSnapshot(new FakeSnapshot(nativeArchive(snapshotId), true));
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
        return state;
    }

    private static byte[] nativeArchive(String snapshotId) throws Exception {
        return E2bSnapshotRefs.encodeSnapshotId(snapshotId);
    }

    private static void invokeEnsureSandbox(E2bSandbox sandbox) throws Exception {
        Method method = E2bSandbox.class.getDeclaredMethod("ensureSandbox");
        method.setAccessible(true);
        try {
            method.invoke(sandbox);
        } catch (InvocationTargetException e) {
            if (e.getCause() instanceof Exception cause) {
                throw cause;
            }
            throw e;
        }
    }

    /** Minimal {@link SandboxSnapshot} over an in-memory archive. */
    private static class FakeSnapshot implements SandboxSnapshot {

        private final byte[] archive;
        private final boolean restorable;
        private int restoreCount;

        FakeSnapshot(byte[] archive, boolean restorable) {
            this.archive = archive;
            this.restorable = restorable;
        }

        @Override
        public void persist(InputStream workspaceArchive) {
            throw new UnsupportedOperationException("read-only fixture");
        }

        @Override
        public InputStream restore() throws Exception {
            restoreCount++;
            return new ByteArrayInputStream(archive);
        }

        @Override
        public boolean isRestorable() {
            return restorable;
        }

        @Override
        public String getId() {
            return "fake-snapshot";
        }

        @Override
        public String getType() {
            return "fake";
        }

        int restoreCount() {
            return restoreCount;
        }
    }
}
