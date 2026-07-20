/*
 * Copyright 2024-2026 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package io.agentscope.core.a2a.server.hitl;

/** Stable AgentScope execution coordinates captured when a handoff is opened. */
public record HitlExecutionKey(String userId, String logicalAgentId, String contextId) {

    public HitlExecutionKey {
        userId = normalize(userId);
        logicalAgentId = normalize(logicalAgentId);
        contextId = normalize(contextId);
        if (logicalAgentId.isEmpty()) {
            throw new IllegalArgumentException("logicalAgentId must not be blank");
        }
        if (contextId.isEmpty()) {
            throw new IllegalArgumentException("contextId must not be blank");
        }
    }

    public String sessionKey() {
        return userId + '\u001f' + logicalAgentId + '\u001f' + contextId;
    }

    private static String normalize(String value) {
        return value == null ? "" : value.trim();
    }
}
