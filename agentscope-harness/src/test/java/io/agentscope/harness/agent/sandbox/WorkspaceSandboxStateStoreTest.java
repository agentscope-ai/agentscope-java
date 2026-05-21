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

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.agentscope.core.state.SimpleSessionKey;
import io.agentscope.harness.agent.IsolationScope;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class WorkspaceSandboxStateStoreTest {

    @TempDir Path workspace;

    private static final String AGENT_ID = "test-agent";
    private static final String JSON = "{\"sessionId\":\"s1\"}";

    private WorkspaceSandboxStateStore store;

    @BeforeEach
    void setUp() {
        store = new WorkspaceSandboxStateStore(workspace, AGENT_ID);
    }

    // ---- SESSION scope ----

    @Test
    void sessionScope_saveLoadDelete_roundTrip() throws Exception {
        SandboxIsolationKey key = isolationKey(IsolationScope.SESSION, "sess-001");

        assertFalse(store.load(key).isPresent());

        store.save(key, JSON);
        Optional<String> loaded = store.load(key);
        assertTrue(loaded.isPresent());
        assertEquals(JSON, loaded.get());

        // Verify session-scoped state location
        Path expected =
                workspace
                        .resolve("agents")
                        .resolve(AGENT_ID)
                        .resolve("context")
                        .resolve("sess-001")
                        .resolve("_sandbox.json");
        assertTrue(Files.exists(expected));

        store.delete(key);
        assertFalse(store.load(key).isPresent());
    }

    // ---- USER scope ----

    @Test
    void userScope_saveLoadDelete_roundTrip() throws Exception {
        SandboxIsolationKey key = isolationKey(IsolationScope.USER, "user-abc");

        store.save(key, JSON);
        Optional<String> loaded = store.load(key);
        assertTrue(loaded.isPresent());
        assertEquals(JSON, loaded.get());

        Path expected =
                workspace
                        .resolve("agents")
                        .resolve(AGENT_ID)
                        .resolve("sandboxes")
                        .resolve("user")
                        .resolve("user-abc.json");
        assertTrue(Files.exists(expected));

        store.delete(key);
        assertFalse(store.load(key).isPresent());
    }

    @Test
    void userScope_unsafeCharacters_encodedInFilename() throws Exception {
        String userId = "user@example.com/special";
        SandboxIsolationKey key = isolationKey(IsolationScope.USER, userId);

        store.save(key, JSON);
        assertTrue(store.load(key).isPresent());

        // Filename must not contain the raw special chars
        Path userDir =
                workspace.resolve("agents").resolve(AGENT_ID).resolve("sandboxes").resolve("user");
        boolean rawFileExists = Files.exists(userDir.resolve(userId + ".json"));
        assertFalse(rawFileExists, "Raw unsafe filename should not exist");

        // A Base64-encoded file should exist instead
        long encodedFiles = Files.list(userDir).count();
        assertEquals(1, encodedFiles);

        store.delete(key);
        assertFalse(store.load(key).isPresent());
    }

    // ---- AGENT scope ----

    @Test
    void agentScope_saveLoadDelete_roundTrip() throws Exception {
        SandboxIsolationKey key = isolationKey(IsolationScope.AGENT, AGENT_ID);

        store.save(key, JSON);
        Optional<String> loaded = store.load(key);
        assertTrue(loaded.isPresent());
        assertEquals(JSON, loaded.get());

        Path expected =
                workspace
                        .resolve("agents")
                        .resolve(AGENT_ID)
                        .resolve("sandboxes")
                        .resolve("agent.json");
        assertTrue(Files.exists(expected));

        store.delete(key);
        assertFalse(store.load(key).isPresent());
    }

    // ---- GLOBAL scope ----

    @Test
    void globalScope_saveLoadDelete_roundTrip() throws Exception {
        SandboxIsolationKey key =
                isolationKey(IsolationScope.GLOBAL, SandboxIsolationKey.GLOBAL_VALUE);

        store.save(key, JSON);
        Optional<String> loaded = store.load(key);
        assertTrue(loaded.isPresent());
        assertEquals(JSON, loaded.get());

        Path expected = workspace.resolve("sandboxes").resolve("global.json");
        assertTrue(Files.exists(expected));

        store.delete(key);
        assertFalse(store.load(key).isPresent());
    }

    @Test
    void multipleScopes_doNotInterfere() throws Exception {
        SandboxIsolationKey sessionKey = isolationKey(IsolationScope.SESSION, "sess-x");
        SandboxIsolationKey userKey = isolationKey(IsolationScope.USER, "user-x");
        SandboxIsolationKey agentKey = isolationKey(IsolationScope.AGENT, AGENT_ID);
        SandboxIsolationKey globalKey =
                isolationKey(IsolationScope.GLOBAL, SandboxIsolationKey.GLOBAL_VALUE);

        store.save(sessionKey, "{\"scope\":\"session\"}");
        store.save(userKey, "{\"scope\":\"user\"}");
        store.save(agentKey, "{\"scope\":\"agent\"}");
        store.save(globalKey, "{\"scope\":\"global\"}");

        assertEquals("{\"scope\":\"session\"}", store.load(sessionKey).orElseThrow());
        assertEquals("{\"scope\":\"user\"}", store.load(userKey).orElseThrow());
        assertEquals("{\"scope\":\"agent\"}", store.load(agentKey).orElseThrow());
        assertEquals("{\"scope\":\"global\"}", store.load(globalKey).orElseThrow());
    }

    @Test
    void save_overwrites_existingValue() throws Exception {
        SandboxIsolationKey key = isolationKey(IsolationScope.AGENT, AGENT_ID);
        store.save(key, "{\"v\":1}");
        store.save(key, "{\"v\":2}");
        assertEquals("{\"v\":2}", store.load(key).orElseThrow());
    }

    @Test
    void delete_nonExistent_isNoOp() throws Exception {
        SandboxIsolationKey key = isolationKey(IsolationScope.USER, "nobody");
        // Should not throw
        store.delete(key);
    }

    @Test
    void sessionScope_usesDirectFileIo() throws Exception {
        WorkspaceSandboxStateStore storeNoSession =
                new WorkspaceSandboxStateStore(workspace, AGENT_ID);
        SandboxIsolationKey key = isolationKey(IsolationScope.SESSION, "sess-direct");

        storeNoSession.save(key, JSON);
        Optional<String> loaded = storeNoSession.load(key);
        assertTrue(loaded.isPresent());
        assertEquals(JSON, loaded.get());

        storeNoSession.delete(key);
        assertFalse(storeNoSession.load(key).isPresent());
    }

    // ---- helpers ----

    private static SandboxIsolationKey isolationKey(IsolationScope scope, String value) {
        return SandboxIsolationKey.resolve(
                        scope,
                        buildCtxForScope(scope, value),
                        value.startsWith("user") || scope == IsolationScope.GLOBAL
                                ? "test-agent"
                                : value)
                .orElseThrow(() -> new IllegalStateException("Key could not be resolved"));
    }

    private static io.agentscope.core.agent.RuntimeContext buildCtxForScope(
            IsolationScope scope, String value) {
        io.agentscope.core.agent.RuntimeContext.Builder b =
                io.agentscope.core.agent.RuntimeContext.builder();
        switch (scope) {
            case SESSION -> b.sessionKey(SimpleSessionKey.of(value));
            case USER -> b.userId(value);
            default -> {
                /* AGENT and GLOBAL do not need context fields */
            }
        }
        return b.build();
    }
}
