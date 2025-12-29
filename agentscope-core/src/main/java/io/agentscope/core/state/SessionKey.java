/*
 * Copyright 2024-2025 the original author or authors.
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
public interface SessionKey {}
