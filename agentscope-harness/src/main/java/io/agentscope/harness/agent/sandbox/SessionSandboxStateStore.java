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

import io.agentscope.core.session.Session;
import io.agentscope.core.state.SessionKey;
import io.agentscope.core.state.SimpleSessionKey;
import io.agentscope.core.state.State;
import io.agentscope.harness.agent.IsolationScope;
import java.io.IOException;
import java.util.Objects;
import java.util.Optional;

/**
 * {@link SandboxStateStore} backed by the generic AgentScope {@link Session} abstraction.
 *
 * <p>This store keeps sandbox lifecycle state in the same state backend as ReActAgent runtime
 * state. As a result, providing a distributed {@link Session} implementation (for example Redis)
 * automatically enables distributed sandbox resume state.
 */
public final class SessionSandboxStateStore implements SandboxStateStore {

    private static final String SANDBOX_STATE_KEY = "_sandbox_state";

    private final Session session;
    private final String agentId;

    public SessionSandboxStateStore(Session session, String agentId) {
        this.session = Objects.requireNonNull(session, "session must not be null");
        this.agentId = Objects.requireNonNull(agentId, "agentId must not be null");
    }

    @Override
    public Optional<String> load(SandboxIsolationKey key) throws IOException {
        try {
            SessionKey slot = slotKey(key);
            Optional<SandboxStateSlot> state =
                    session.get(slot, SANDBOX_STATE_KEY, SandboxStateSlot.class);
            if (state.isEmpty() || state.get().deleted() || state.get().json() == null) {
                return Optional.empty();
            }
            return Optional.of(state.get().json());
        } catch (Exception e) {
            throw asIo("load", key, e);
        }
    }

    @Override
    public void save(SandboxIsolationKey key, String json) throws IOException {
        try {
            session.save(slotKey(key), SANDBOX_STATE_KEY, new SandboxStateSlot(json, false));
        } catch (Exception e) {
            throw asIo("save", key, e);
        }
    }

    @Override
    public void delete(SandboxIsolationKey key) throws IOException {
        try {
            // Not all Session implementations support per-key delete. Tombstone keeps behavior
            // consistent across backends.
            session.save(slotKey(key), SANDBOX_STATE_KEY, SandboxStateSlot.tombstone());
        } catch (Exception e) {
            throw asIo("delete", key, e);
        }
    }

    private SessionKey slotKey(SandboxIsolationKey key) {
        IsolationScope scope = key.getScope();
        return switch (scope) {
            case SESSION -> SimpleSessionKey.of("sandbox/session/" + key.getValue());
            case USER -> SimpleSessionKey.of("sandbox/user/" + agentId + "/" + key.getValue());
            case AGENT -> SimpleSessionKey.of("sandbox/agent/" + agentId);
            case GLOBAL -> SimpleSessionKey.of("sandbox/global");
        };
    }

    private static IOException asIo(String op, SandboxIsolationKey key, Exception e) {
        return new IOException("Failed to " + op + " sandbox state for " + key, e);
    }

    private record SandboxStateSlot(String json, boolean deleted) implements State {
        static SandboxStateSlot tombstone() {
            return new SandboxStateSlot("", true);
        }
    }
}
