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
import okhttp3.mockwebserver.MockResponse;
import okhttp3.mockwebserver.MockWebServer;
import okhttp3.mockwebserver.RecordedRequest;
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

    /**
     * The core fix: a restorable native snapshot is used as the source template for the very first
     * create, so restoring a session costs exactly one sandbox instead of create-then-rebuild.
     */
    @Test
    void nativeSnapshotRestore_createsSandboxOnceFromSnapshot() throws Exception {
        server.enqueue(sandboxResponse("restored-sandbox"));
        E2bSandboxState state = stateWithNativeSnapshot("snap-123");

        E2bSandbox sandbox = new E2bSandbox(state, options());
        invokeEnsureSandbox(sandbox);

        RecordedRequest create = server.takeRequest();
        assertEquals("/sandboxes", create.getPath());
        String body = create.getBody().readUtf8();
        assertTrue(body.contains("snap-123"), "create must target the snapshot id: " + body);
        assertFalse(body.contains("\"base\""), "must not create from the original template");

        // The hydrate that follows in start() must not rebuild the sandbox.
        invokeDoHydrateWorkspace(sandbox, nativeArchive("snap-123"));
        assertEquals(1, server.getRequestCount(), "hydrate must not create a second sandbox");
        assertEquals("restored-sandbox", state.getSandboxId());
    }

    @Test
    void nativeSnapshotRestore_unrestorableSnapshotUsesTemplate() throws Exception {
        server.enqueue(sandboxResponse("fresh-sandbox"));
        E2bSandboxState state = stateWithNativeSnapshot("snap-123");
        state.setSnapshot(new FakeSnapshot(nativeArchive("snap-123"), false));

        invokeEnsureSandbox(new E2bSandbox(state, options()));

        String body = server.takeRequest().getBody().readUtf8();
        assertTrue(body.contains("\"base\""), "must fall back to the configured template id");
        assertFalse(body.contains("snap-123"));
    }

    /** A TAR archive can never carry the native magic prefix, so it must not be mistaken for one. */
    @Test
    void tarArchive_keepsOriginalTemplate() throws Exception {
        server.enqueue(sandboxResponse("fresh-sandbox"));
        E2bSandboxState state = stateWithNativeSnapshot("snap-123");
        state.setPersistenceMode(E2bPersistenceMode.TAR);
        state.setSnapshot(new FakeSnapshot(tarArchive(), true));

        invokeEnsureSandbox(new E2bSandbox(state, options()));

        String body = server.takeRequest().getBody().readUtf8();
        assertTrue(body.contains("\"base\""));
        assertFalse(body.contains("snap-123"));
    }

    /** A broken snapshot store must not fail start(): it degrades to the original template. */
    @Test
    void peekFailure_degradesToTemplate() throws Exception {
        server.enqueue(sandboxResponse("fresh-sandbox"));
        E2bSandboxState state = stateWithNativeSnapshot("snap-123");
        state.setSnapshot(
                new FakeSnapshot(nativeArchive("snap-123"), true) {
                    @Override
                    public InputStream restore() throws Exception {
                        throw new IOException("snapshot store unreachable");
                    }
                });

        invokeEnsureSandbox(new E2bSandbox(state, options()));

        String body = server.takeRequest().getBody().readUtf8();
        assertTrue(body.contains("\"base\""));
        assertEquals("fresh-sandbox", state.getSandboxId());
    }

    /**
     * A snapshot the provider refuses (deleted, rejected) must degrade to a fresh sandbox instead of
     * failing start(), and because nothing was created before the snapshot attempt, no discarded
     * first sandbox is left behind to delete.
     */
    @Test
    void snapshotCreateFailure_degradesToTemplateWithoutLeak() throws Exception {
        server.enqueue(
                new MockResponse().setResponseCode(500).setBody("snapshot no longer exists"));
        server.enqueue(sandboxResponse("fresh-sandbox"));
        E2bSandboxState state = stateWithNativeSnapshot("snap-123");

        invokeEnsureSandbox(new E2bSandbox(state, options()));

        RecordedRequest snapshotCreate = server.takeRequest();
        RecordedRequest templateCreate = server.takeRequest();
        assertEquals("/sandboxes", snapshotCreate.getPath());
        assertEquals("/sandboxes", templateCreate.getPath());
        assertTrue(snapshotCreate.getBody().readUtf8().contains("snap-123"));
        assertTrue(templateCreate.getBody().readUtf8().contains("\"base\""));
        assertEquals("fresh-sandbox", state.getSandboxId());
        assertEquals(2, server.getRequestCount(), "nothing may be deleted — no sandbox was wasted");
    }

    /**
     * The path #3390 makes universal: the sandbox id is dead, the connect fails, and the replacement
     * must be built straight from the snapshot. The snapshot is decided before the create here too,
     * so the reconnect failure does not turn into a template create followed by a rebuild.
     */
    @Test
    void connectFailure_restoresFromNativeSnapshotInOneCreate() throws Exception {
        server.enqueue(new MockResponse().setResponseCode(500).setBody("connect failed"));
        server.enqueue(sandboxResponse("restored-sandbox"));
        E2bSandboxState state = stateForResumeWithNativeSnapshot("snap-123");

        E2bSandbox sandbox = new E2bSandbox(state, options());
        invokeEnsureSandbox(sandbox);

        RecordedRequest connect = server.takeRequest();
        RecordedRequest create = server.takeRequest();
        assertEquals("/sandboxes/old-sandbox/connect", connect.getPath());
        assertEquals("/sandboxes", create.getPath());
        String body = create.getBody().readUtf8();
        assertTrue(body.contains("snap-123"), "create must target the snapshot id: " + body);
        assertFalse(body.contains("\"base\""), "must not create from the original template");
        // A stale sandbox is a lost workspace, so the recreate still clears the projection state.
        assertFalse(state.isWorkspaceRootReady());
        assertNull(state.getWorkspaceProjectionHash());

        // The hydrate that follows in start() must not rebuild the sandbox either.
        invokeDoHydrateWorkspace(sandbox, nativeArchive("snap-123"));
        assertEquals(2, server.getRequestCount(), "hydrate must not create a second sandbox");
        assertEquals("restored-sandbox", state.getSandboxId());
    }

    /**
     * The negative path: a connected sandbox predates the snapshot, so the short-circuit must not
     * fire — the hydrate has to rebuild from the snapshot and kill the sandbox it replaces. The
     * instance is warmed with a snapshot create first, so a reconnect branch that failed to clear
     * {@code createdFromSnapshotId} is caught here rather than silently leaving a stale workspace
     * behind.
     */
    @Test
    void hydrateRebuilds_whenConnectedSandboxWasNotRestoredFromTheSnapshot() throws Exception {
        server.enqueue(sandboxResponse("restored-sandbox"));
        E2bSandboxState state = stateWithNativeSnapshot("snap-123");
        E2bSandbox sandbox = new E2bSandbox(state, options());
        invokeEnsureSandbox(sandbox);

        // The same instance now reconnects to a live sandbox that predates the snapshot.
        server.enqueue(sandboxResponse("restored-sandbox"));
        invokeEnsureSandbox(sandbox);

        server.enqueue(sandboxResponse("rebuilt-sandbox"));
        server.enqueue(new MockResponse().setResponseCode(204));
        invokeDoHydrateWorkspace(sandbox, nativeArchive("snap-123"));

        // Assert the count first so a short-circuit regression fails fast instead of blocking on a
        // request that was never sent.
        assertEquals(4, server.getRequestCount(), "the hydrate must rebuild and kill the old one");
        assertEquals("/sandboxes", server.takeRequest().getPath());
        assertEquals("/sandboxes/restored-sandbox/connect", server.takeRequest().getPath());
        RecordedRequest rebuild = server.takeRequest();
        assertEquals("/sandboxes", rebuild.getPath());
        assertTrue(rebuild.getBody().readUtf8().contains("snap-123"));
        assertEquals("/sandboxes/restored-sandbox", server.takeRequest().getPath());
        assertEquals("rebuilt-sandbox", state.getSandboxId());
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

    private E2bSandboxClientOptions options() {
        E2bSandboxClientOptions options = new E2bSandboxClientOptions();
        options.setApiBaseUrl(server.url("/").toString());
        options.setApiKey("test-key");
        options.setMaxRetries(1);
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

    /** Bytes that cannot be mistaken for a native snapshot marker. */
    private static byte[] tarArchive() {
        return new byte[] {0x1f, (byte) 0x8b, 0x08, 0x00, 0x00, 0x00, 0x00, 0x00};
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

    private static void invokeDoHydrateWorkspace(E2bSandbox sandbox, byte[] archive)
            throws Exception {
        Method method = E2bSandbox.class.getDeclaredMethod("doHydrateWorkspace", InputStream.class);
        method.setAccessible(true);
        try {
            method.invoke(sandbox, new ByteArrayInputStream(archive));
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
    }
}
