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
package io.agentscope.harness.claw.session;

/**
 * Internal metadata for a registered managed session (used by {@link SessionAgentManager}).
 *
 * @param gateKey routing key used by the gateway to map this session back to a channel context;
 *     only populated for {@link SessionKind#MAIN} sessions (nullable)
 * @param userId optional user identity that owns this session; used by HarnessAgent's
 *     NamespaceFactory for per-user filesystem isolation (nullable — null means single-tenant)
 */
public record SessionEntry(
        String sessionKey,
        String agentId,
        String sessionId,
        String label,
        SessionKind kind,
        String spawnedBy,
        int spawnDepth,
        long createdAtMs,
        long lastActivityMs,
        String sessionFilePath,
        String spawnRunId,
        String gateKey,
        String userId) {

    /** Backwards-compatible constructor without gateKey or userId. */
    public SessionEntry(
            String sessionKey,
            String agentId,
            String sessionId,
            String label,
            SessionKind kind,
            String spawnedBy,
            int spawnDepth,
            long createdAtMs,
            long lastActivityMs,
            String sessionFilePath,
            String spawnRunId) {
        this(
                sessionKey,
                agentId,
                sessionId,
                label,
                kind,
                spawnedBy,
                spawnDepth,
                createdAtMs,
                lastActivityMs,
                sessionFilePath,
                spawnRunId,
                null,
                null);
    }

    /** Backwards-compatible constructor with gateKey but without userId. */
    public SessionEntry(
            String sessionKey,
            String agentId,
            String sessionId,
            String label,
            SessionKind kind,
            String spawnedBy,
            int spawnDepth,
            long createdAtMs,
            long lastActivityMs,
            String sessionFilePath,
            String spawnRunId,
            String gateKey) {
        this(
                sessionKey,
                agentId,
                sessionId,
                label,
                kind,
                spawnedBy,
                spawnDepth,
                createdAtMs,
                lastActivityMs,
                sessionFilePath,
                spawnRunId,
                gateKey,
                null);
    }
}
