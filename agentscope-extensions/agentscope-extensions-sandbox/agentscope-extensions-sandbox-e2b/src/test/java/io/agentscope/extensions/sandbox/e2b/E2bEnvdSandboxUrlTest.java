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
import static org.junit.jupiter.api.Assertions.assertNull;

import io.agentscope.harness.agent.sandbox.ExecResult;
import io.agentscope.harness.agent.sandbox.Sandbox;
import io.agentscope.harness.agent.sandbox.WorkspaceSpec;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import okhttp3.mockwebserver.MockResponse;
import okhttp3.mockwebserver.MockWebServer;
import okhttp3.mockwebserver.RecordedRequest;
import org.junit.jupiter.api.Test;

class E2bEnvdSandboxUrlTest {

    /**
     * With {@code envdSandboxUrl} set, every sandbox's data plane goes to that fixed URL (the
     * official SDK's {@code E2B_SANDBOX_URL} semantics): the request path is untouched, the Host
     * header carries no sandbox routing attributes, and routing relies on the
     * {@code E2b-Sandbox-Id}/{@code E2b-Sandbox-Port} headers.
     */
    @Test
    void sandboxUrlReplacesPerSandboxEnvdAddress() throws Exception {
        MockWebServer server = new MockWebServer();
        server.start();
        try {
            server.enqueue(
                    new MockResponse()
                            .setResponseCode(200)
                            .setHeader("Content-Type", "application/connect+json")
                            .setBody(
                                    new okio.Buffer()
                                            .write(
                                                    connectFrames(
                                                            responseJson(
                                                                    base64("hello"), null, null),
                                                            responseJson(null, null, 0)))));

            E2bSandboxClientOptions options = new E2bSandboxClientOptions();
            options.setCodec(E2bCodec.JSON);
            // Trailing slash on purpose: it must be trimmed before paths are appended.
            options.setEnvdSandboxUrl(server.url("/").toString());
            E2bEnvdProcessClient client = new E2bEnvdProcessClient(options);

            E2bSandboxState state = new E2bSandboxState();
            state.setSandboxId("sbx-1");
            state.setSandboxDomain("cube.test");
            state.setEnvdAccessToken("envd-token");

            ExecResult result = client.runShell(state, "/home/user", "echo hello", 30);

            assertEquals(0, result.exitCode());
            assertEquals("hello", result.stdout());

            RecordedRequest recorded = server.takeRequest();
            assertEquals("/process.Process/Start", recorded.getPath());
            // No <port>-<id>.<domain> host shape: routing falls to the E2B headers.
            assertEquals(server.getHostName() + ":" + server.getPort(), recorded.getHeader("Host"));
            assertEquals("sbx-1", recorded.getHeader("E2b-Sandbox-Id"));
            assertEquals("49983", recorded.getHeader("E2b-Sandbox-Port"));
            assertEquals("envd-token", recorded.getHeader("X-Access-Token"));
        } finally {
            server.shutdown();
        }
    }

    @Test
    void unsetSandboxUrlKeepsPerSandboxAddress() throws Exception {
        E2bEnvdProcessClient client = new E2bEnvdProcessClient(new E2bSandboxClientOptions());
        E2bSandboxState state = new E2bSandboxState();
        state.setSandboxId("sbx-1");
        state.setSandboxDomain("cube.test");

        java.lang.reflect.Method m =
                E2bEnvdProcessClient.class.getDeclaredMethod("envdHost", E2bSandboxState.class);
        m.setAccessible(true);
        assertEquals("https://49983-sbx-1.cube.test", m.invoke(client, state));
    }

    @Test
    void clientDefaultAndCallLevelOverrideMerge() throws Exception {
        E2bSandboxClientOptions defaults = new E2bSandboxClientOptions();
        defaults.setEnvdSandboxUrl("http://10.0.0.1:8080");
        E2bSandboxClient client = new E2bSandboxClient(defaults, null);

        Sandbox sandbox = client.create(new WorkspaceSpec(), null, null);
        assertEquals("http://10.0.0.1:8080", optionsOf(sandbox).getEnvdSandboxUrl());

        E2bSandboxClientOptions call = new E2bSandboxClientOptions();
        call.setEnvdSandboxUrl("http://10.0.0.2:8080");
        Sandbox overridden = client.create(new WorkspaceSpec(), null, call);
        assertEquals("http://10.0.0.2:8080", optionsOf(overridden).getEnvdSandboxUrl());

        // A call leaving it unset must not clear the client default.
        Sandbox kept = client.create(new WorkspaceSpec(), null, new E2bSandboxClientOptions());
        assertEquals("http://10.0.0.1:8080", optionsOf(kept).getEnvdSandboxUrl());
    }

    @Test
    void resumeRestoresCallLevelSandboxUrlOverride() throws Exception {
        E2bSandboxClientOptions defaults = new E2bSandboxClientOptions();
        defaults.setEnvdSandboxUrl("http://10.0.0.1:8080");
        E2bSandboxClient client = new E2bSandboxClient(defaults, null);

        E2bSandboxClientOptions call = new E2bSandboxClientOptions();
        call.setEnvdSandboxUrl("http://10.0.0.2:8080");
        Sandbox created = client.create(new WorkspaceSpec(), null, call);

        E2bSandboxState state = (E2bSandboxState) created.getState();
        assertEquals("http://10.0.0.2:8080", state.getEnvdSandboxUrl());

        Sandbox resumed = client.resume(state);
        assertEquals("http://10.0.0.2:8080", optionsOf(resumed).getEnvdSandboxUrl());
    }

    @Test
    void nullSandboxUrlStaysNull() {
        assertNull(new E2bSandboxClientOptions().getEnvdSandboxUrl());
    }

    private static E2bSandboxClientOptions optionsOf(Sandbox sandbox) throws Exception {
        java.lang.reflect.Field f = E2bSandbox.class.getDeclaredField("opt");
        f.setAccessible(true);
        return (E2bSandboxClientOptions) f.get(sandbox);
    }

    private static byte[] connectFrames(String... jsons) {
        java.io.ByteArrayOutputStream out = new java.io.ByteArrayOutputStream();
        for (String json : jsons) {
            out.writeBytes(
                    E2bEnvdProcessClient.encodeUnaryEnvelope(
                            json.getBytes(StandardCharsets.UTF_8)));
        }
        return out.toByteArray();
    }

    private static String responseJson(String stdout, String stderr, Integer exitCode) {
        StringBuilder json = new StringBuilder("{\"event\":{");
        boolean needComma = false;
        if (stdout != null || stderr != null) {
            json.append("\"data\":{");
            if (stdout != null) {
                json.append("\"stdout\":\"").append(stdout).append("\"");
            } else {
                json.append("\"stderr\":\"").append(stderr).append("\"");
            }
            json.append('}');
            needComma = true;
        }
        if (exitCode != null) {
            if (needComma) {
                json.append(',');
            }
            json.append("\"end\":{\"exitCode\":").append(exitCode).append('}');
        }
        json.append("}}");
        return json.toString();
    }

    private static String base64(String value) {
        return Base64.getEncoder().encodeToString(value.getBytes(StandardCharsets.UTF_8));
    }
}
