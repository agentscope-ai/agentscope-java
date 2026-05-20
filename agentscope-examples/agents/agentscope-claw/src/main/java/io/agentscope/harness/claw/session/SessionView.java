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
 * Public view of a managed session. Returned by {@link SessionAgentManager#list} and {@link
 * SessionAgentManager#viewSession} to expose session information without internal mutable state.
 */
public record SessionView(
        String sessionKey,
        String agentId,
        String sessionId,
        String label,
        String kind,
        String spawnedBy,
        int spawnDepth,
        long createdAtMs,
        long lastActivityMs,
        String sessionFilePath,
        String spawnRunId,
        String gateKey) {

    public static SessionView from(SessionEntry e) {
        return new SessionView(
                e.sessionKey(),
                e.agentId(),
                e.sessionId(),
                e.label(),
                e.kind().getValue(),
                e.spawnedBy(),
                e.spawnDepth(),
                e.createdAtMs(),
                e.lastActivityMs(),
                e.sessionFilePath(),
                e.spawnRunId(),
                e.gateKey());
    }
}
