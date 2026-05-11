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

import io.agentscope.core.agent.RuntimeContext;
import io.agentscope.core.session.InMemorySession;
import io.agentscope.core.state.SimpleSessionKey;
import io.agentscope.harness.agent.IsolationScope;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class SessionSandboxStateStoreTest {

    private static final String AGENT_ID = "test-agent";
    private static final String JSON = "{\"sessionId\":\"s-1\"}";

    private SessionSandboxStateStore store;

    @BeforeEach
    void setUp() {
        store = new SessionSandboxStateStore(new InMemorySession(), AGENT_ID);
    }

    @Test
    void sessionScope_roundTrip() throws Exception {
        SandboxIsolationKey key = isolationKey(IsolationScope.SESSION, "sess-001");
        assertFalse(store.load(key).isPresent());

        store.save(key, JSON);
        assertEquals(JSON, store.load(key).orElseThrow());

        store.delete(key);
        assertFalse(store.load(key).isPresent());
    }

    @Test
    void userScope_roundTrip() throws Exception {
        SandboxIsolationKey key = isolationKey(IsolationScope.USER, "user-123");
        store.save(key, JSON);
        assertEquals(JSON, store.load(key).orElseThrow());
        store.delete(key);
        assertFalse(store.load(key).isPresent());
    }

    @Test
    void agentAndGlobalScope_doNotInterfere() throws Exception {
        SandboxIsolationKey agentKey = isolationKey(IsolationScope.AGENT, AGENT_ID);
        SandboxIsolationKey globalKey =
                isolationKey(IsolationScope.GLOBAL, SandboxIsolationKey.GLOBAL_VALUE);

        store.save(agentKey, "{\"scope\":\"agent\"}");
        store.save(globalKey, "{\"scope\":\"global\"}");

        assertEquals("{\"scope\":\"agent\"}", store.load(agentKey).orElseThrow());
        assertEquals("{\"scope\":\"global\"}", store.load(globalKey).orElseThrow());
    }

    @Test
    void deleteUsesTombstone_evenWhenSessionDeleteUnsupported() throws Exception {
        SessionSandboxStateStore redisLikeStore =
                new SessionSandboxStateStore(new NoDeleteSession(), AGENT_ID);
        SandboxIsolationKey key = isolationKey(IsolationScope.SESSION, "sess-del");

        redisLikeStore.save(key, JSON);
        assertTrue(redisLikeStore.load(key).isPresent());

        redisLikeStore.delete(key);
        assertFalse(redisLikeStore.load(key).isPresent());
    }

    private static SandboxIsolationKey isolationKey(IsolationScope scope, String value) {
        return SandboxIsolationKey.resolve(scope, runtimeContext(scope, value), AGENT_ID)
                .orElseThrow();
    }

    private static RuntimeContext runtimeContext(IsolationScope scope, String value) {
        RuntimeContext.Builder b = RuntimeContext.builder();
        if (scope == IsolationScope.SESSION) {
            b.sessionKey(SimpleSessionKey.of(value));
        } else if (scope == IsolationScope.USER) {
            b.userId(value);
        }
        return b.build();
    }

    /** Simulates sessions whose per-key delete is not implemented (default no-op). */
    private static final class NoDeleteSession extends InMemorySession {
        @Override
        public void delete(io.agentscope.core.state.SessionKey sessionKey, String key) {
            // no-op
        }
    }
}
