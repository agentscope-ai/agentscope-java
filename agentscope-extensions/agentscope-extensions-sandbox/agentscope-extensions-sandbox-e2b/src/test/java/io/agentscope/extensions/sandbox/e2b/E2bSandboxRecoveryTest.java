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
}
