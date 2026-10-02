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
package io.agentscope.harness.agent.transcript;

/**
 * Identifies a session transcript in a multi-tenant layout:
 * {@code {tenant}/{agentId}/{sessionId}/...}.
 */
public record TranscriptRef(String tenant, String agentId, String sessionId) {

    public TranscriptRef {
        if (tenant == null || tenant.isBlank()) {
            tenant = "default";
        }
        if (agentId == null || agentId.isBlank()) {
            throw new IllegalArgumentException("agentId required");
        }
        if (sessionId == null || sessionId.isBlank()) {
            throw new IllegalArgumentException("sessionId required");
        }
    }

    /** Prefix used for segment keys under this transcript. */
    public String prefix() {
        return tenant + "/" + agentId + "/" + sessionId;
    }
}
