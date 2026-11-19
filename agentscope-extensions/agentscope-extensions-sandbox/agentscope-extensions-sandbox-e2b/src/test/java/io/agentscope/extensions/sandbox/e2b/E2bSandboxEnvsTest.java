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
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.agentscope.harness.agent.sandbox.Sandbox;
import io.agentscope.harness.agent.sandbox.WorkspaceSpec;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Supplier;
import okhttp3.mockwebserver.MockResponse;
import okhttp3.mockwebserver.MockWebServer;
import okhttp3.mockwebserver.RecordedRequest;
import org.junit.jupiter.api.Test;

class E2bSandboxEnvsTest {

    /** Envs configured on the options must be sent as the {@code envVars} field of the create request. */
    @Test
    void createSandboxSendsEnvVars() throws Exception {
        MockWebServer server = new MockWebServer();
        server.start();
        try {
            server.enqueue(
                    new MockResponse()
                            .setResponseCode(200)
                            .setHeader("Content-Type", "application/json")
                            .setBody("{\"sandboxID\":\"sbx-1\"}"));
            E2bSandboxClientOptions opt = new E2bSandboxClientOptions();
            opt.setApiKey("test-key");
            opt.setApiBaseUrl(server.url("/").toString());
            E2bPlatformHttp platform = new E2bPlatformHttp(opt);

            platform.createSandbox(
                    "tpl-1",
                    60,
                    Map.of(
                            "LARKSUITE_CLI_APP_ID", "cli_x",
                            "LARKSUITE_CLI_USER_ACCESS_TOKEN", "u-1"));

            RecordedRequest req = server.takeRequest();
            assertEquals("POST", req.getMethod());
            assertEquals("/sandboxes", req.getPath());
            JsonNode body = new ObjectMapper().readTree(req.getBody().readUtf8());
            assertEquals("tpl-1", body.get("templateID").asText());
            assertEquals(60, body.get("timeout").asInt());
            assertEquals("cli_x", body.get("envVars").get("LARKSUITE_CLI_APP_ID").asText());
            assertEquals(
                    "u-1", body.get("envVars").get("LARKSUITE_CLI_USER_ACCESS_TOKEN").asText());
        } finally {
            server.shutdown();
        }
    }

    /** Without envs the create request must stay byte-compatible with the previous behaviour. */
    @Test
    void createSandboxOmitsEnvVarsWhenEmpty() throws Exception {
        MockWebServer server = new MockWebServer();
        server.start();
        try {
            server.enqueue(
                    new MockResponse()
                            .setResponseCode(200)
                            .setHeader("Content-Type", "application/json")
                            .setBody("{\"sandboxID\":\"sbx-1\"}"));
            E2bSandboxClientOptions opt = new E2bSandboxClientOptions();
            opt.setApiKey("test-key");
            opt.setApiBaseUrl(server.url("/").toString());
            E2bPlatformHttp platform = new E2bPlatformHttp(opt);

            platform.createSandbox("tpl-1", 60);

            RecordedRequest req = server.takeRequest();
            JsonNode body = new ObjectMapper().readTree(req.getBody().readUtf8());
            assertNull(body.get("envVars"));
        } finally {
            server.shutdown();
        }
    }

    /** Suppliers are resolved per creation (not once at construction) and null values are skipped. */
    @Test
    void envSuppliersResolveAtEachCreationAndSkipNull() {
        AtomicInteger counter = new AtomicInteger();
        E2bSandboxClientOptions opt = new E2bSandboxClientOptions();
        opt.env("TOKEN", () -> "v" + counter.incrementAndGet());
        opt.env("STATIC", "fixed");
        opt.env("MAYBE_NULL", () -> null);

        E2bSandboxState state = new E2bSandboxState();
        state.setWorkspaceSpec(new WorkspaceSpec());
        E2bSandbox sandbox = new E2bSandbox(state, opt);

        Map<String, String> first = sandbox.resolveEnvVars();
        assertEquals("v1", first.get("TOKEN"));
        assertEquals("fixed", first.get("STATIC"));
        assertEquals(2, first.size());

        Map<String, String> second = sandbox.resolveEnvVars();
        assertEquals("v2", second.get("TOKEN"));
    }

    /** The spec chain must pass env entries through to its client options. */
    @Test
    void filesystemSpecExposesEnvPassthrough() {
        E2bFilesystemSpec spec =
                new E2bFilesystemSpec()
                        .env("LARKSUITE_CLI_APP_ID", "cli_x")
                        .env("LARKSUITE_CLI_USER_ACCESS_TOKEN", () -> "u-1");

        E2bSandboxClientOptions options = (E2bSandboxClientOptions) spec.clientOptions();
        assertEquals("cli_x", options.getEnvs().get("LARKSUITE_CLI_APP_ID").get());
        assertEquals("u-1", options.getEnvs().get("LARKSUITE_CLI_USER_ACCESS_TOKEN").get());
    }

    /** Default and per-call envs merge, per-call entries win, and a null per-call set is fine. */
    @Test
    void clientMergesDefaultAndPerCallEnvs() {
        E2bSandboxClientOptions defaults = new E2bSandboxClientOptions();
        defaults.env("A", "default-a");
        defaults.env("B", "default-b");
        E2bSandboxClient client = new E2bSandboxClient(defaults, null);

        E2bSandboxClientOptions call = new E2bSandboxClientOptions();
        call.env("B", "call-b");
        call.env("C", () -> "call-c");

        Sandbox sandbox = client.create(new WorkspaceSpec(), null, call);
        Map<String, String> resolved = ((E2bSandbox) sandbox).resolveEnvVars();
        assertEquals("default-a", resolved.get("A"));
        assertEquals("call-b", resolved.get("B"));
        assertEquals("call-c", resolved.get("C"));

        Sandbox sandboxWithDefaults = client.create(new WorkspaceSpec(), null, null);
        assertEquals("default-a", ((E2bSandbox) sandboxWithDefaults).resolveEnvVars().get("A"));
    }

    /** Blank env names are rejected; a null env map resets to empty. */
    @Test
    void optionsValidateEnvNamesAndHandleNullMap() {
        E2bSandboxClientOptions opt = new E2bSandboxClientOptions();
        assertThrows(IllegalArgumentException.class, () -> opt.env("", "v"));
        assertThrows(IllegalArgumentException.class, () -> opt.env("  ", () -> "v"));

        opt.setEnvs(null);
        assertTrue(opt.getEnvs().isEmpty());

        Map<String, Supplier<String>> supplied = new HashMap<>();
        supplied.put("K", () -> "v");
        opt.setEnvs(supplied);
        assertEquals("v", opt.getEnvs().get("K").get());
    }
}
