/*
 * Copyright 2024-2026 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      https://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package io.agentscope.core.state;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;

/**
 * Marker interface for session identifiers.
 *
 * <p>Users can define custom session identifier structures for complex scenarios like multi-tenant
 * applications. The default implementation {@link SimpleSessionKey} uses a simple string.
 *
 * <p>Custom Session implementations can interpret SessionKey structures to determine storage
 * strategies (e.g., multi-tenant database sharding).
 *
 * <p>Example custom implementation:
 *
 * <pre>{@code
 * // Multi-tenant scenario
 * public record TenantSessionKey(
 *     String tenantId,
 *     String userId,
 *     String sessionId
 * ) implements SessionKey {}
 *
 * // Usage
 * session.save(new TenantSessionKey("tenant_001", "user_123", "session_456"), "agent_meta", state);
 * }</pre>
 *
 * @see SimpleSessionKey
 * @see io.agentscope.core.session.Session
 */
public interface SessionKey {

    /** Shared ObjectMapper instance for JSON serialization. */
    ObjectMapper OBJECT_MAPPER = new ObjectMapper();

    /**
     * Returns a string identifier for this session key.
     *
     * <p>This method is used by Session implementations to convert the session key to a string
     * suitable for storage (e.g., as a directory name, database key, or Redis key prefix).
     *
     * <p>The default implementation uses JSON serialization. Implementations like {@link
     * SimpleSessionKey} override this to return the session ID directly for better readability.
     *
     * @return a string identifier for this session key
     */
    default String toIdentifier() {
        try {
            return OBJECT_MAPPER.writeValueAsString(this);
        } catch (JsonProcessingException e) {
            throw new RuntimeException("Failed to serialize SessionKey to JSON", e);
        }
    }
}
