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
import io.agentscope.core.state.InMemoryAgentStateStore;
import io.agentscope.core.state.State;
import io.agentscope.harness.agent.IsolationScope;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class SessionSandboxStateStoreTest {

    private static final String AGENT_ID = "test-agent";
    private static final String JSON = "{\"sessionId\":\"s-1\"}";

    private SessionSandboxStateStore store;

    @BeforeEach
    void setUp() {
        store = new SessionSandboxStateStore(new InMemoryAgentStateStore(), AGENT_ID);
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
    void slotSessionId_hasNoPathSeparators_soSqlStoresAccept_allScopes() throws Exception {
        // SQL-backed AgentStateStore impls (Postgres/MySQL) reject '/' and '\' in the sessionId
        // via validateSessionId. A store whose slot ids contain path separators makes sandbox
        // state persistence throw on those backends for every isolation scope (issue #2327).
        SessionSandboxStateStore sqlLikeStore =
                new SessionSandboxStateStore(new PathSeparatorRejectingStore(), AGENT_ID);

        for (SandboxIsolationKey key :
                new SandboxIsolationKey[] {
                    isolationKey(IsolationScope.SESSION, "sess-001"),
                    isolationKey(IsolationScope.USER, "user-123"),
                    isolationKey(IsolationScope.AGENT, AGENT_ID),
                    isolationKey(IsolationScope.GLOBAL, SandboxIsolationKey.GLOBAL_VALUE)
                }) {
            sqlLikeStore.save(key, JSON);
            assertEquals(JSON, sqlLikeStore.load(key).orElseThrow());
            sqlLikeStore.delete(key);
            assertFalse(sqlLikeStore.load(key).isPresent());
        }
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
            b.sessionId(value);
        } else if (scope == IsolationScope.USER) {
            b.userId(value);
        }
        return b.build();
    }

    /** Simulates sessions whose per-key delete is not implemented (default no-op). */
    private static final class NoDeleteSession extends InMemoryAgentStateStore {
        @Override
        public void delete(String userId, String sessionId, String key) {
            // no-op
        }
    }

    /**
     * Mirrors the {@code validateSessionId} contract of the SQL-backed stores
     * ({@code PostgresAgentStateStore}/{@code MysqlAgentStateStore}): the sessionId must not contain
     * a path separator. Lets the round-trip test fail exactly the way those backends do.
     */
    private static final class PathSeparatorRejectingStore extends InMemoryAgentStateStore {
        private static void validate(String sessionId) {
            if (sessionId != null && (sessionId.contains("/") || sessionId.contains("\\"))) {
                throw new IllegalArgumentException(
                        "AgentStateStore ID cannot contain path separators: " + sessionId);
            }
        }

        @Override
        public void save(String userId, String sessionId, String key, State value) {
            validate(sessionId);
            super.save(userId, sessionId, key, value);
        }

        @Override
        public <T extends State> Optional<T> get(
                String userId, String sessionId, String key, Class<T> type) {
            validate(sessionId);
            return super.get(userId, sessionId, key, type);
        }

        @Override
        public void delete(String userId, String sessionId, String key) {
            validate(sessionId);
            super.delete(userId, sessionId, key);
        }
    }
}
