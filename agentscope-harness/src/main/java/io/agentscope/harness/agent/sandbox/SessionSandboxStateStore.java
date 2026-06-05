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

import io.agentscope.core.state.AgentStateStore;
import io.agentscope.core.state.State;
import io.agentscope.harness.agent.IsolationScope;
import java.io.IOException;
import java.util.Objects;
import java.util.Optional;

/**
 * Sandbox state store backed by the generic AgentScope {@link AgentStateStore} abstraction.
 *
 * <p>This store keeps sandbox lifecycle state in the same state backend as ReActAgent runtime
 * state. As a result, providing a distributed {@link AgentStateStore} implementation (for example Redis)
 * automatically enables distributed sandbox resume state.
 */
public final class SessionSandboxStateStore {

    private static final String SANDBOX_STATE_KEY = "_sandbox_state";

    private final AgentStateStore stateStore;
    private final String agentId;

    public SessionSandboxStateStore(AgentStateStore stateStore, String agentId) {
        this.stateStore = Objects.requireNonNull(stateStore, "stateStore must not be null");
        this.agentId = Objects.requireNonNull(agentId, "agentId must not be null");
    }

    public Optional<String> load(SandboxIsolationKey key) throws IOException {
        try {
            String slotSid = slotSessionId(key);
            Optional<SandboxStateSlot> state =
                    stateStore.get(null, slotSid, SANDBOX_STATE_KEY, SandboxStateSlot.class);
            if (state.isEmpty() || state.get().deleted() || state.get().json() == null) {
                return Optional.empty();
            }
            return Optional.of(state.get().json());
        } catch (Exception e) {
            throw asIo("load", key, e);
        }
    }

    public void save(SandboxIsolationKey key, String json) throws IOException {
        try {
            stateStore.save(
                    null, slotSessionId(key), SANDBOX_STATE_KEY, new SandboxStateSlot(json, false));
        } catch (Exception e) {
            throw asIo("save", key, e);
        }
    }

    public void delete(SandboxIsolationKey key) throws IOException {
        try {
            // Not all AgentStateStore implementations support per-key delete; tombstone keeps
            // behavior consistent across backends.
            stateStore.save(
                    null, slotSessionId(key), SANDBOX_STATE_KEY, SandboxStateSlot.tombstone());
        } catch (Exception e) {
            throw asIo("delete", key, e);
        }
    }

    /**
     * Pack the sandbox isolation key into a single sessionId string that fits the
     * {@link AgentStateStore} 2-arg slot model. The userId column is always {@code null} because
     * sandbox state is conceptually agent-scoped, not user-scoped: USER/AGENT/GLOBAL scopes are
     * encoded into the sessionId prefix rather than the userId slot.
     */
    private String slotSessionId(SandboxIsolationKey key) {
        IsolationScope scope = key.getScope();
        return switch (scope) {
            case SESSION -> "sandbox/session/" + key.getValue();
            case USER -> "sandbox/user/" + agentId + "/" + key.getValue();
            case AGENT -> "sandbox/agent/" + agentId;
            case GLOBAL -> "sandbox/global";
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
