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
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.agentscope.harness.agent.sandbox.Sandbox;
import io.agentscope.harness.agent.sandbox.SandboxException;
import io.agentscope.harness.agent.sandbox.SandboxState;
import io.agentscope.harness.agent.sandbox.WorkspaceSpec;
import io.agentscope.harness.agent.sandbox.layout.BindMountEntry;
import java.util.Collections;
import java.util.List;
import okhttp3.mockwebserver.MockResponse;
import okhttp3.mockwebserver.MockWebServer;
import okhttp3.mockwebserver.RecordedRequest;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class E2bVolumeMountTest {

    private MockWebServer server;
    private final ObjectMapper json = new ObjectMapper();

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
    void mountValidationAndNormalization() {
        E2bVolumeMount m = new E2bVolumeMount("my-volume", "/mnt/data/");
        assertEquals("my-volume", m.getName());
        assertEquals("/mnt/data", m.getPath());

        assertEquals("/mnt/data", new E2bVolumeMount("v", "//mnt//data//").getPath());
        assertEquals("/", new E2bVolumeMount("v", "/").getPath());

        assertThrows(
                SandboxException.SandboxConfigurationException.class,
                () -> new E2bVolumeMount("  ", "/mnt/data"));
        assertThrows(
                SandboxException.SandboxConfigurationException.class,
                () -> new E2bVolumeMount("v", "mnt/data"));
        assertThrows(
                SandboxException.SandboxConfigurationException.class,
                () -> new E2bVolumeMount("v", "  "));
    }

    @Test
    void coversWorkspaceRootBoundaries() {
        assertTrue(E2bVolumeMount.coversWorkspaceRoot("/mnt/data", "/mnt/data"));
        assertTrue(E2bVolumeMount.coversWorkspaceRoot("/mnt/data/ws", "/mnt/data"));
        assertTrue(E2bVolumeMount.coversWorkspaceRoot("/mnt/data/", "/mnt/data/"));
        assertFalse(E2bVolumeMount.coversWorkspaceRoot("/mnt/data-x", "/mnt/data"));
        assertFalse(E2bVolumeMount.coversWorkspaceRoot("/mnt/other", "/mnt/data"));
        assertFalse(E2bVolumeMount.coversWorkspaceRoot("/home/user", "/mnt/data"));
        assertFalse(E2bVolumeMount.coversWorkspaceRoot(null, "/mnt/data"));
        assertFalse(E2bVolumeMount.coversWorkspaceRoot("/mnt/data", null));
        assertTrue(E2bVolumeMount.coversWorkspaceRoot("/home/user", "/"));
    }

    @Test
    void duplicateMountPathsRejected() {
        E2bSandboxClientOptions opt = new E2bSandboxClientOptions();
        opt.addVolumeMount("a", "/mnt/data");
        assertThrows(
                SandboxException.SandboxConfigurationException.class,
                () -> opt.addVolumeMount("b", "/mnt/data/"));
    }

    @Test
    void createSandboxBodyIncludesMounts() throws Exception {
        server.enqueue(
                new MockResponse()
                        .setResponseCode(200)
                        .setHeader("Content-Type", "application/json")
                        .setBody("{\"sandboxID\":\"sbx-1\"}"));

        new E2bPlatformHttp(options())
                .createSandbox(
                        "base", 300, List.of(new E2bVolumeMount("vol-a", "/mnt/data")), null);

        RecordedRequest req = server.takeRequest();
        assertEquals("/sandboxes", req.getPath());
        JsonNode body = json.readTree(req.getBody().readUtf8());
        assertEquals("base", body.get("templateID").asText());
        assertEquals(300, body.get("timeout").asInt());
        assertTrue(body.has("volumeMounts"), "volumeMounts must be present: " + body);
        assertEquals(1, body.get("volumeMounts").size());
        assertEquals("vol-a", body.get("volumeMounts").get(0).get("name").asText());
        assertEquals("/mnt/data", body.get("volumeMounts").get(0).get("path").asText());
    }

    @Test
    void createSandboxBodyOmitsMountsWhenEmpty() throws Exception {
        server.enqueue(
                new MockResponse()
                        .setResponseCode(200)
                        .setHeader("Content-Type", "application/json")
                        .setBody("{\"sandboxID\":\"sbx-1\"}"));

        new E2bPlatformHttp(options()).createSandbox("base", 300, List.of(), null);

        JsonNode body = json.readTree(server.takeRequest().getBody().readUtf8());
        assertFalse(body.has("volumeMounts"), "volumeMounts must be omitted when empty: " + body);
    }

    @Test
    void createSandboxBodyOmitsMountsWhenAllNull() throws Exception {
        server.enqueue(
                new MockResponse()
                        .setResponseCode(200)
                        .setHeader("Content-Type", "application/json")
                        .setBody("{\"sandboxID\":\"sbx-1\"}"));

        new E2bPlatformHttp(options())
                .createSandbox("base", 300, Collections.singletonList(null), null);

        JsonNode body = json.readTree(server.takeRequest().getBody().readUtf8());
        assertFalse(
                body.has("volumeMounts"), "volumeMounts must be omitted when all null: " + body);
    }

    @Test
    void createSandbox400PropagatesBody() {
        server.enqueue(new MockResponse().setResponseCode(400).setBody("volume not found: vol-x"));

        SandboxException.SandboxRuntimeException e =
                assertThrows(
                        SandboxException.SandboxRuntimeException.class,
                        () ->
                                new E2bPlatformHttp(options())
                                        .createSandbox(
                                                "base",
                                                300,
                                                List.of(new E2bVolumeMount("vol-x", "/mnt/data")),
                                                null));
        assertTrue(e.getMessage().contains("400"), e.getMessage());
        assertTrue(e.getMessage().contains("volume not found: vol-x"), e.getMessage());
    }

    @Test
    void clientCreateComputesWorkspaceOnVolume() {
        E2bSandboxClient client = new E2bSandboxClient();

        E2bSandboxClientOptions onVolume = new E2bSandboxClientOptions();
        onVolume.setWorkspaceRoot("/mnt/data/ws");
        onVolume.addVolumeMount("vol-a", "/mnt/data");
        Sandbox s = client.create(workspaceSpec(), null, onVolume);
        E2bSandboxState st = (E2bSandboxState) s.getState();
        assertTrue(st.isWorkspaceOnVolume());
        assertEquals(List.of(new E2bVolumeMount("vol-a", "/mnt/data")), st.getVolumeMounts());

        E2bSandboxClientOptions offVolume = new E2bSandboxClientOptions();
        offVolume.setWorkspaceRoot("/home/user");
        offVolume.addVolumeMount("vol-a", "/mnt/data");
        E2bSandboxState st2 =
                (E2bSandboxState) client.create(workspaceSpec(), null, offVolume).getState();
        assertFalse(st2.isWorkspaceOnVolume());
    }

    @Test
    void mergeKeepsDefaultMountsWhenCallHasNone() {
        E2bSandboxClientOptions defaults = new E2bSandboxClientOptions();
        defaults.addVolumeMount("vol-a", "/mnt/data");
        E2bSandboxClient client = new E2bSandboxClient(defaults, null);

        E2bSandboxState st =
                (E2bSandboxState)
                        client.create(workspaceSpec(), null, new E2bSandboxClientOptions())
                                .getState();
        assertEquals(List.of(new E2bVolumeMount("vol-a", "/mnt/data")), st.getVolumeMounts());

        E2bSandboxClientOptions override = new E2bSandboxClientOptions();
        override.addVolumeMount("vol-b", "/mnt/other");
        E2bSandboxState st2 =
                (E2bSandboxState) client.create(workspaceSpec(), null, override).getState();
        assertEquals(List.of(new E2bVolumeMount("vol-b", "/mnt/other")), st2.getVolumeMounts());
    }

    @Test
    void serdeRoundTripAndLegacyCompat() {
        E2bSandboxClient client = new E2bSandboxClient();

        E2bSandboxState state = new E2bSandboxState();
        state.setSessionId("session-1");
        state.setWorkspaceSpec(workspaceSpec());
        state.setVolumeMounts(List.of(new E2bVolumeMount("vol-a", "/mnt/data")));
        state.setWorkspaceOnVolume(true);

        String payload = client.serializeState(state);
        SandboxState read = client.deserializeState(payload);
        assertTrue(read instanceof E2bSandboxState);
        E2bSandboxState r = (E2bSandboxState) read;
        assertEquals(List.of(new E2bVolumeMount("vol-a", "/mnt/data")), r.getVolumeMounts());
        assertTrue(r.isWorkspaceOnVolume());

        // Sessions persisted before volumes support carry neither field.
        E2bSandboxState legacy =
                (E2bSandboxState)
                        client.deserializeState(
                                "{\"type\":\"e2b\",\"sessionId\":\"legacy\",\"workspaceRoot\":\"/home/user\"}");
        assertTrue(legacy.getVolumeMounts().isEmpty());
        assertFalse(legacy.isWorkspaceOnVolume());
    }

    @Test
    void recreateCarriesMounts() throws Exception {
        server.enqueue(new MockResponse().setResponseCode(500).setBody("connect failed"));
        server.enqueue(
                new MockResponse()
                        .setResponseCode(200)
                        .setHeader("Content-Type", "application/json")
                        .setBody("{\"sandboxID\":\"new-sandbox\"}"));

        E2bSandboxState state = new E2bSandboxState();
        state.setWorkspaceSpec(workspaceSpec());
        state.setSandboxId("old-sandbox");
        state.setVolumeMounts(List.of(new E2bVolumeMount("vol-a", "/mnt/data")));

        E2bSandboxClientOptions opt = options();
        java.lang.reflect.Method m = E2bSandbox.class.getDeclaredMethod("ensureSandbox");
        m.setAccessible(true);
        try {
            m.invoke(new E2bSandbox(state, opt));
        } catch (java.lang.reflect.InvocationTargetException e) {
            if (e.getCause() instanceof Exception cause) {
                throw cause;
            }
            throw e;
        }

        assertEquals("new-sandbox", state.getSandboxId());
        server.takeRequest(); // connect
        JsonNode createBody = json.readTree(server.takeRequest().getBody().readUtf8());
        assertEquals("vol-a", createBody.get("volumeMounts").get(0).get("name").asText());
        assertEquals("/mnt/data", createBody.get("volumeMounts").get(0).get("path").asText());
    }

    @Test
    void addVolumeMountIsFluent() {
        E2bSandboxClientOptions opt = new E2bSandboxClientOptions();
        assertTrue(opt.addVolumeMount("vol-a", "/mnt/data") == opt);
        assertTrue(opt.addVolumeMount(new E2bVolumeMount("vol-b", "/mnt/other")) == opt);
        assertTrue(opt.addVolumeMount(null) == opt);
        assertEquals(
                List.of(
                        new E2bVolumeMount("vol-a", "/mnt/data"),
                        new E2bVolumeMount("vol-b", "/mnt/other")),
                opt.getVolumeMounts());
    }

    @Test
    void createRejectsRelativeWorkspaceRoot() {
        E2bSandboxClient client = new E2bSandboxClient();
        E2bSandboxClientOptions relative = new E2bSandboxClientOptions();
        relative.setWorkspaceRoot("relative/ws");
        SandboxException.SandboxConfigurationException e =
                assertThrows(
                        SandboxException.SandboxConfigurationException.class,
                        () -> client.create(workspaceSpec(), null, relative));
        assertTrue(e.getMessage().contains("workspaceRoot"), e.getMessage());
    }

    @Test
    void tarCommandQuotesExcludeArgs() throws Exception {
        E2bSandboxState state = new E2bSandboxState();
        state.setWorkspaceSpec(workspaceSpec());
        state.setWorkspaceRoot("/home/user");
        state.setVolumeMounts(List.of(new E2bVolumeMount("vol-a", "/home/user/my data")));
        E2bSandbox sandbox = new E2bSandbox(state, options());

        java.lang.reflect.Method m =
                E2bSandbox.class.getDeclaredMethod(
                        "buildTarCommand", boolean.class, String.class, String.class);
        m.setAccessible(true);
        String script = (String) m.invoke(sandbox, true, "/tmp/ws.tar", "/home/user");
        assertTrue(
                script.contains("'--exclude=./my data'"),
                "exclude with spaces must be shell-quoted, got: " + script);
        String extract = (String) m.invoke(sandbox, false, "/tmp/ws.tar", "/home/user");
        assertTrue(
                extract.contains("'--exclude=./my data'"),
                "volume excludes must apply to the -xf branch too, got: " + extract);
    }

    @Test
    void tarCommandQuotesBindMountExcludeArgs() throws Exception {
        E2bSandboxState state = new E2bSandboxState();
        WorkspaceSpec ws = workspaceSpec();
        BindMountEntry bind = new BindMountEntry();
        bind.setHostPath("/host/shared dir");
        bind.setReadOnly(false);
        ws.getEntries().put("my shares", bind);
        state.setWorkspaceSpec(ws);
        state.setWorkspaceRoot("/home/user");
        E2bSandbox sandbox = new E2bSandbox(state, options());

        java.lang.reflect.Method m =
                E2bSandbox.class.getDeclaredMethod(
                        "buildTarCommand", boolean.class, String.class, String.class);
        m.setAccessible(true);
        String script = (String) m.invoke(sandbox, true, "/tmp/ws.tar", "/home/user");
        assertTrue(
                script.contains("'--exclude=./my shares'"),
                "bind-mount exclude with spaces must be shell-quoted, got: " + script);
        String extract = (String) m.invoke(sandbox, false, "/tmp/ws.tar", "/home/user");
        assertTrue(
                extract.contains("'--exclude=./my shares'"),
                "bind-mount excludes must be quoted on the -xf branch too, got: " + extract);
    }

    @Test
    void workspaceOnVolumeBranchesAreNoOpsWithoutEnvd() throws Exception {
        E2bSandboxState state = new E2bSandboxState();
        state.setWorkspaceSpec(workspaceSpec());
        state.setWorkspaceRoot("/mnt/data/ws");
        state.setVolumeMounts(List.of(new E2bVolumeMount("vol-a", "/mnt/data")));
        state.setWorkspaceOnVolume(true);
        // No sandboxId and no reachable envd: any backend call would fail,
        // proving the no-op branches short-circuit before touching envd.
        E2bSandbox sandbox = new E2bSandbox(state, options());

        try (java.io.InputStream archive = sandbox.persistWorkspace()) {
            assertEquals(0, archive.readAllBytes().length);
        }
        sandbox.hydrateWorkspace(java.io.InputStream.nullInputStream());
        invokeDoDestroyWorkspace(sandbox);
    }

    @Test
    void stateRejectsDuplicateMountPaths() {
        E2bSandboxState state = new E2bSandboxState();
        assertThrows(
                SandboxException.SandboxConfigurationException.class,
                () ->
                        state.setVolumeMounts(
                                List.of(
                                        new E2bVolumeMount("vol-a", "/mnt/data"),
                                        new E2bVolumeMount("vol-b", "/mnt/data/"))));
    }

    @Test
    void serdeIgnoresUnknownFieldsForForwardCompat() {
        E2bSandboxClient client = new E2bSandboxClient();
        E2bSandboxState read =
                (E2bSandboxState)
                        client.deserializeState(
                                "{\"type\":\"e2b\",\"sessionId\":\"fwd\",\"workspaceRoot\":\"/home/user\","
                                    + "\"someFutureField\":1}");
        assertEquals("fwd", read.getSessionId());
        assertTrue(read.getVolumeMounts().isEmpty());
    }

    private static void invokeDoDestroyWorkspace(E2bSandbox sandbox) throws Exception {
        java.lang.reflect.Method m = E2bSandbox.class.getDeclaredMethod("doDestroyWorkspace");
        m.setAccessible(true);
        try {
            m.invoke(sandbox);
        } catch (java.lang.reflect.InvocationTargetException e) {
            if (e.getCause() instanceof Exception cause) {
                throw cause;
            }
            throw e;
        }
    }

    private E2bSandboxClientOptions options() {
        E2bSandboxClientOptions opt = new E2bSandboxClientOptions();
        opt.setApiBaseUrl(server.url("/").toString());
        opt.setApiKey("test-key");
        opt.setMaxRetries(1);
        return opt;
    }

    private static WorkspaceSpec workspaceSpec() {
        WorkspaceSpec ws = new WorkspaceSpec();
        ws.setRoot("/tmp/agentscope-test-workspace");
        return ws;
    }
}
