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
package io.agentscope.extensions.a2ui.state;

import io.agentscope.core.agent.RuntimeContext;
import io.agentscope.core.state.AgentStateStore;
import io.agentscope.extensions.a2ui.A2uiConfig;
import io.agentscope.extensions.a2ui.envelope.A2uiConstants;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.function.Supplier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Two-tier surface registry (spec §7): a run-scoped cache on {@link RuntimeContext} plus a
 * session-scoped list persisted through {@link AgentStateStore} when one is available. Surface
 * ids are system-generated ({@code "s-" + sha1(sessionId + ":" + ordinal) first 8 hex chars}) and
 * never exposed to the LLM schema.
 */
public final class A2uiSurfaceRegistry {

    private static final Logger log = LoggerFactory.getLogger(A2uiSurfaceRegistry.class);

    /** A resolved surface with the flag telling whether this call created it. */
    public record SurfaceRef(String surfaceId, boolean newlyCreated) {}

    private final A2uiConfig config;
    private final Supplier<AgentStateStore> stateStore;

    public A2uiSurfaceRegistry(A2uiConfig config, Supplier<AgentStateStore> stateStore) {
        this.config = config;
        this.stateStore = stateStore;
    }

    public SurfaceRef resolveOrCreate(RuntimeContext rc) {
        String runSurface =
                rc == null ? null : rc.get(A2uiConstants.RC_KEY_RUN_SURFACE, String.class);
        if (runSurface != null) {
            return new SurfaceRef(runSurface, false);
        }
        String userId = rc == null ? null : rc.getUserId();
        String sessionId = rc == null || rc.getSessionId() == null ? "default" : rc.getSessionId();
        A2uiSurfaceState state = loadState(userId, sessionId);
        String surfaceId = generateSurfaceId(sessionId, state.surfaceIds().size());
        if (rc != null) {
            rc.put(A2uiConstants.RC_KEY_RUN_SURFACE, surfaceId);
        }
        saveState(userId, sessionId, state.withAdded(surfaceId));
        return new SurfaceRef(surfaceId, true);
    }

    private A2uiSurfaceState loadState(String userId, String sessionId) {
        AgentStateStore store = stateStore.get();
        if (store == null || !config.surfacePersistEnabled()) {
            return A2uiSurfaceState.empty();
        }
        try {
            return store.get(
                            userId,
                            sessionId,
                            A2uiConstants.STATE_STORE_KEY_SURFACES,
                            A2uiSurfaceState.class)
                    .orElse(A2uiSurfaceState.empty());
        } catch (Exception e) {
            log.warn(
                    "A2UI: failed to load surface state for session {}; degrading to empty",
                    sessionId,
                    e);
            return A2uiSurfaceState.empty();
        }
    }

    private void saveState(String userId, String sessionId, A2uiSurfaceState state) {
        AgentStateStore store = stateStore.get();
        if (store == null || !config.surfacePersistEnabled()) {
            return;
        }
        try {
            store.save(userId, sessionId, A2uiConstants.STATE_STORE_KEY_SURFACES, state);
        } catch (Exception e) {
            log.warn("A2UI: failed to persist surface state for session {}", sessionId, e);
        }
    }

    static String generateSurfaceId(String sessionId, int ordinal) {
        String digest = sha1Hex(sessionId + ":" + ordinal);
        return "s-" + digest.substring(0, 8);
    }

    private static String sha1Hex(String input) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-1");
            byte[] bytes = digest.digest(input.getBytes(StandardCharsets.UTF_8));
            StringBuilder hex = new StringBuilder(bytes.length * 2);
            for (byte b : bytes) {
                hex.append(Character.forDigit((b >> 4) & 0xF, 16));
                hex.append(Character.forDigit(b & 0xF, 16));
            }
            return hex.toString();
        } catch (Exception e) {
            throw new IllegalStateException("SHA-1 unavailable", e);
        }
    }
}
