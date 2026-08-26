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
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.agentscope.harness.agent.sandbox.WorkspaceSpec;
import java.util.List;
import org.junit.jupiter.api.Test;

class E2bSandboxOptionsAndStateTest {

    @Test
    void stateSnapshotIdsNullBecomesEmpty() {
        E2bSandboxState s = new E2bSandboxState();
        s.setSnapshotIds(null);
        assertNotNull(s.getSnapshotIds());
        assertTrue(s.getSnapshotIds().isEmpty());
        s.setSnapshotIds(List.of("a", "b"));
        assertEquals(List.of("a", "b"), s.getSnapshotIds());
    }

    @Test
    void stateCodecNullDefaultsToProto() {
        E2bSandboxState s = new E2bSandboxState();
        s.setCodec(null);
        assertEquals(E2bCodec.PROTO, s.getCodec());
        s.setCodec(E2bCodec.JSON);
        assertEquals(E2bCodec.JSON, s.getCodec());
    }

    @Test
    void statePersistenceModeNullDefaultsToTar() {
        E2bSandboxState s = new E2bSandboxState();
        s.setPersistenceMode(null);
        assertEquals(E2bPersistenceMode.TAR, s.getPersistenceMode());
    }

    @Test
    void optionsSnapshotRetentionAndCodec() {
        E2bSandboxClientOptions opt = new E2bSandboxClientOptions();
        assertEquals(0, opt.getSnapshotRetention());
        opt.setSnapshotRetention(3);
        assertEquals(3, opt.getSnapshotRetention());
        opt.setSnapshotRetention(0);
        assertEquals(0, opt.getSnapshotRetention());

        assertEquals(E2bCodec.PROTO, opt.getCodec());
        opt.setCodec(E2bCodec.JSON);
        assertEquals(E2bCodec.JSON, opt.getCodec());
        opt.setCodec(null);
        assertEquals(E2bCodec.PROTO, opt.getCodec());

        assertEquals(E2bPersistenceMode.TAR, opt.getPersistenceMode());
        opt.setPersistenceMode(E2bPersistenceMode.NATIVE_SNAPSHOT);
        assertEquals(E2bPersistenceMode.NATIVE_SNAPSHOT, opt.getPersistenceMode());
        opt.setPersistenceMode(null);
        assertEquals(E2bPersistenceMode.TAR, opt.getPersistenceMode());
    }

    @Test
    void optionsGetType() {
        assertEquals("e2b", new E2bSandboxClientOptions().getType());
    }

    @Test
    void filesystemSpecSnapshotRetentionAndCodec() {
        E2bFilesystemSpec spec = new E2bFilesystemSpec();
        spec.snapshotRetention(5);
        assertEquals(5, ((E2bSandboxClientOptions) spec.clientOptions()).getSnapshotRetention());
        spec.snapshotRetention(0);
        assertEquals(0, ((E2bSandboxClientOptions) spec.clientOptions()).getSnapshotRetention());

        spec.codec(E2bCodec.JSON);
        assertEquals(E2bCodec.JSON, ((E2bSandboxClientOptions) spec.clientOptions()).getCodec());
        spec.codec(E2bCodec.PROTO);
        assertEquals(E2bCodec.PROTO, ((E2bSandboxClientOptions) spec.clientOptions()).getCodec());

        spec.apiKey("k")
                .apiBaseUrl("https://example.com")
                .domain("example.com")
                .templateId("tpl")
                .workspaceRoot("/ws")
                .sandboxTimeoutSeconds(60)
                .runUser("u")
                .persistenceMode(E2bPersistenceMode.NATIVE_SNAPSHOT)
                .connectTimeoutSeconds(10)
                .readTimeoutSeconds(20)
                .maxRetries(2);
        E2bSandboxClientOptions o = (E2bSandboxClientOptions) spec.clientOptions();
        assertEquals("k", o.getApiKey());
        assertEquals("https://example.com", o.getApiBaseUrl());
        assertEquals("example.com", o.getDomain());
        assertEquals("tpl", o.getTemplateId());
        assertEquals("/ws", o.getWorkspaceRoot());
        assertEquals(60, o.getSandboxTimeoutSeconds());
        assertEquals("u", o.getRunUser());
        assertEquals(E2bPersistenceMode.NATIVE_SNAPSHOT, o.getPersistenceMode());
        assertEquals(10, o.getConnectTimeoutSeconds());
        assertEquals(20, o.getReadTimeoutSeconds());
        assertEquals(2, o.getMaxRetries());
        assertNotNull(spec.createClient());
        assertNotNull(spec.workspaceSpec());
        assertNotNull(spec.snapshotSpec());
    }

    @Test
    void clientCreateAndResumePreservesCodec() throws Exception {
        E2bSandboxClientOptions defaults = new E2bSandboxClientOptions();
        defaults.setCodec(E2bCodec.JSON);
        defaults.setPersistenceMode(E2bPersistenceMode.NATIVE_SNAPSHOT);
        defaults.setTemplateId("tpl-1");
        defaults.setSnapshotRetention(2);
        E2bSandboxClient client = new E2bSandboxClient(defaults, null);

        // create without call options -> uses defaults
        io.agentscope.harness.agent.sandbox.Sandbox sandbox =
                client.create(new WorkspaceSpec(), null, null);
        assertNotNull(sandbox);
        E2bSandboxState created = getE2bState(sandbox);
        assertEquals("tpl-1", created.getTemplateId());
        String json = client.serializeState(created);
        assertTrue(json.contains("tpl-1"));

        // resume preserves codec from state
        E2bSandboxState state = new E2bSandboxState();
        state.setWorkspaceSpec(new WorkspaceSpec());
        state.setCodec(E2bCodec.JSON);
        state.setSandboxId("old");
        io.agentscope.harness.agent.sandbox.Sandbox resumed = client.resume(state);
        assertNotNull(resumed);
    }

    @Test
    void clientMergeOverridesAndNullHandling() throws Exception {
        E2bSandboxClientOptions defaults = new E2bSandboxClientOptions();
        defaults.setApiKey("default-key");
        defaults.setSnapshotRetention(1);
        defaults.setMaxRetries(1);
        E2bSandboxClient client = new E2bSandboxClient(defaults, null);

        E2bSandboxClientOptions call = new E2bSandboxClientOptions();
        call.setApiKey("call-key");
        call.setSnapshotRetention(5);
        call.setMaxRetries(3);
        call.setConnectTimeoutSeconds(99);
        call.setReadTimeoutSeconds(99);
        call.setPersistenceMode(E2bPersistenceMode.NATIVE_SNAPSHOT);
        call.setCodec(E2bCodec.JSON);

        io.agentscope.harness.agent.sandbox.Sandbox s =
                client.create(new WorkspaceSpec(), null, call);
        // verify merged values via state
        E2bSandboxState st = getE2bState(s);
        assertEquals(E2bCodec.JSON, st.getCodec());
        assertEquals(E2bPersistenceMode.NATIVE_SNAPSHOT, st.getPersistenceMode());

        // null call
        io.agentscope.harness.agent.sandbox.Sandbox s2 =
                client.create(new WorkspaceSpec(), null, null);
        assertNotNull(s2);

        // resume with wrong type
        try {
            client.resume(new io.agentscope.harness.agent.sandbox.SandboxState() {});
            assertTrue(false, "should throw");
        } catch (IllegalArgumentException e) {
            assertTrue(e.getMessage().contains("Expected E2bSandboxState"));
        }

        // delete is no-op
        client.delete(s);
        // serialize/deserialize round-trip with snapshotIds
        E2bSandboxState withIds = new E2bSandboxState();
        withIds.setWorkspaceSpec(new WorkspaceSpec());
        withIds.setSessionId("sid");
        withIds.setSnapshotIds(List.of("snap-1", "snap-2"));
        String ser = client.serializeState(withIds);
        assertTrue(ser.contains("snap-1"));
        io.agentscope.harness.agent.sandbox.SandboxState deser = client.deserializeState(ser);
        assertTrue(deser instanceof E2bSandboxState);
        assertEquals(List.of("snap-1", "snap-2"), ((E2bSandboxState) deser).getSnapshotIds());

        // create client with null defaults
        E2bSandboxClient c2 = new E2bSandboxClient(null, null);
        assertNotNull(c2.create(new WorkspaceSpec(), null, null));
    }

    @Test
    void snapshotNameFormat() throws Exception {
        // via reflection invoke private snapshotName
        java.lang.reflect.Method m = E2bSandbox.class.getDeclaredMethod("snapshotName");
        m.setAccessible(true);
        String name = (String) m.invoke(null);
        assertTrue(name.matches("^agentscope-[0-9a-f]{8}-\\d{13}$"), name);
    }

    @Test
    void shutdownSkipsWhenNotOwnedOrNoSandboxId() throws Exception {
        E2bSandboxState state = new E2bSandboxState();
        state.setWorkspaceSpec(new WorkspaceSpec());
        state.setSandboxOwned(false);
        state.setSandboxId("sbx-1");
        E2bSandboxClientOptions opt = new E2bSandboxClientOptions();
        opt.setSnapshotRetention(2);
        E2bSandbox sandbox = new E2bSandbox(state, opt);
        sandbox.shutdown(); // should return immediately

        state.setSandboxOwned(true);
        state.setSandboxId(null);
        sandbox.shutdown(); // no kill

        state.setSandboxId("   ");
        sandbox.shutdown();
    }

    private static E2bSandboxState getE2bState(io.agentscope.harness.agent.sandbox.Sandbox sandbox)
            throws Exception {
        java.lang.reflect.Field f = E2bSandbox.class.getDeclaredField("e2bState");
        f.setAccessible(true);
        return (E2bSandboxState) f.get(sandbox);
    }

    @Test
    void shutdownCleanupWithMockServer() throws Exception {
        okhttp3.mockwebserver.MockWebServer server = new okhttp3.mockwebserver.MockWebServer();
        server.start();
        try {
            server.enqueue(new okhttp3.mockwebserver.MockResponse().setResponseCode(200));
            server.enqueue(new okhttp3.mockwebserver.MockResponse().setResponseCode(200));
            server.enqueue(new okhttp3.mockwebserver.MockResponse().setResponseCode(200));

            E2bSandboxState state = new E2bSandboxState();
            state.setWorkspaceSpec(new WorkspaceSpec());
            state.setSandboxOwned(true);
            state.setSandboxId("sbx-123");
            state.setSnapshotIds(
                    new java.util.ArrayList<>(List.of("snap-1", "snap-2", "snap-3", "snap-4")));

            E2bSandboxClientOptions opt = new E2bSandboxClientOptions();
            opt.setApiKey("test-key");
            opt.setApiBaseUrl(server.url("/").toString());
            opt.setSnapshotRetention(2);
            opt.setMaxRetries(1);

            E2bSandbox sandbox = new E2bSandbox(state, opt);
            sandbox.shutdown();

            // kill + 2 deletes = 3 requests
            assertEquals(3, server.getRequestCount());
            assertEquals(List.of("snap-3", "snap-4"), state.getSnapshotIds());
        } finally {
            server.shutdown();
        }
    }

    @Test
    void shutdownCleanupSkippedWhenRetentionZero() throws Exception {
        okhttp3.mockwebserver.MockWebServer server = new okhttp3.mockwebserver.MockWebServer();
        server.start();
        try {
            server.enqueue(new okhttp3.mockwebserver.MockResponse().setResponseCode(200));
            E2bSandboxState state = new E2bSandboxState();
            state.setWorkspaceSpec(new WorkspaceSpec());
            state.setSandboxOwned(true);
            state.setSandboxId("sbx-1");
            state.setSnapshotIds(new java.util.ArrayList<>(List.of("snap-1", "snap-2")));

            E2bSandboxClientOptions opt = new E2bSandboxClientOptions();
            opt.setApiKey("k");
            opt.setApiBaseUrl(server.url("/").toString());
            opt.setSnapshotRetention(0);
            E2bSandbox sb = new E2bSandbox(state, opt);
            sb.shutdown();
            // only kill, no cleanup
            assertEquals(1, server.getRequestCount());
            assertEquals(List.of("snap-1", "snap-2"), state.getSnapshotIds());
        } finally {
            server.shutdown();
        }
    }

    @Test
    void persistWorkspaceNativeSnapshotAddsId() throws Exception {
        okhttp3.mockwebserver.MockWebServer server = new okhttp3.mockwebserver.MockWebServer();
        server.start();
        try {
            server.enqueue(
                    new okhttp3.mockwebserver.MockResponse()
                            .setBody("{\"snapshotID\":\"team/agentscope-abc:tag\"}"));

            E2bSandboxState state = new E2bSandboxState();
            state.setWorkspaceSpec(new WorkspaceSpec());
            state.setSandboxId("sbx-1");
            state.setPersistenceMode(E2bPersistenceMode.NATIVE_SNAPSHOT);
            state.setCodec(E2bCodec.PROTO);

            E2bSandboxClientOptions opt = new E2bSandboxClientOptions();
            opt.setApiKey("k");
            opt.setApiBaseUrl(server.url("/").toString());
            opt.setMaxRetries(1);
            E2bSandbox sandbox = new E2bSandbox(state, opt);

            java.lang.reflect.Method m = E2bSandbox.class.getDeclaredMethod("doPersistWorkspace");
            m.setAccessible(true);
            java.io.InputStream in = (java.io.InputStream) m.invoke(sandbox);
            byte[] bytes = in.readAllBytes();
            String decoded = E2bSnapshotRefs.decodeSnapshotIdIfPresent(bytes);
            assertEquals("team/agentscope-abc:tag", decoded);
            assertEquals(List.of("team/agentscope-abc:tag"), state.getSnapshotIds());
            assertEquals("/sandboxes/sbx-1/snapshots", server.takeRequest().getPath());
        } finally {
            server.shutdown();
        }
    }

    @Test
    void persistWorkspaceNativeSnapshotThrowsWhenMissingId() throws Exception {
        okhttp3.mockwebserver.MockWebServer server = new okhttp3.mockwebserver.MockWebServer();
        server.start();
        try {
            server.enqueue(new okhttp3.mockwebserver.MockResponse().setBody("{}"));
            E2bSandboxState state = new E2bSandboxState();
            state.setWorkspaceSpec(new WorkspaceSpec());
            state.setSandboxId("sbx-1");
            state.setPersistenceMode(E2bPersistenceMode.NATIVE_SNAPSHOT);
            E2bSandboxClientOptions opt = new E2bSandboxClientOptions();
            opt.setApiKey("k");
            opt.setApiBaseUrl(server.url("/").toString());
            opt.setMaxRetries(1);
            E2bSandbox sandbox = new E2bSandbox(state, opt);
            java.lang.reflect.Method m = E2bSandbox.class.getDeclaredMethod("doPersistWorkspace");
            m.setAccessible(true);
            try {
                m.invoke(sandbox);
                assertTrue(false, "should throw");
            } catch (java.lang.reflect.InvocationTargetException e) {
                assertTrue(
                        e.getCause().getMessage().contains("missing snapshotID"),
                        e.getCause().getMessage());
            }
        } finally {
            server.shutdown();
        }
    }
}
