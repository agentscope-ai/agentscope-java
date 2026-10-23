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
package io.agentscope.harness.agent.gateway;

import java.util.Optional;

/**
 * Durable registry of exposed subagents, keyed by {@code subagentId}.
 *
 * <p>Decouples a subagent's user-facing handle from any single process: the in-memory
 * {@link HarnessGateway} keeps a live-agent cache for the fast same-node path, while this registry
 * persists the {@link SubagentRecord} recipe so that any node (or the same node after a restart)
 * can resolve the handle and re-materialize the agent.
 *
 * <p>Implementations:
 *
 * <ul>
 *   <li>{@link InMemorySubagentRegistry} — default, single-process, equivalent to the legacy
 *       behaviour.
 *   <li>{@link StoreBackedSubagentRegistry} — backed by a distributed
 *       {@link io.agentscope.harness.agent.filesystem.remote.store.BaseStore} (Redis / OSS / MySQL
 *       via {@link io.agentscope.harness.agent.DistributedStore#baseStore()}).
 * </ul>
 *
 * <p>Implementations must be thread-safe.
 */
public interface SubagentRegistry {

    /**
     * Persists an exposure record. Idempotent on {@link SubagentRecord#subagentId()} — re-registering
     * the same id overwrites the previous record.
     *
     * @param record the record to persist (must carry a non-null {@code subagentId})
     */
    void register(SubagentRecord record);

    /**
     * Resolves a subagent handle. Implementations should treat an expired record (see
     * {@link SubagentRecord#isExpired}) as absent and may evict it lazily.
     *
     * @param subagentId the handle to resolve
     * @return the record, or {@link Optional#empty()} if unknown or expired
     */
    Optional<SubagentRecord> find(String subagentId);

    /** Removes a single exposure record. No-op when the id is unknown. */
    void revoke(String subagentId);

    /**
     * Removes all exposure records associated with the given parent session. Used to clean up when a
     * parent conversation ends. Implementations that cannot enumerate by parent may no-op.
     *
     * @param parentSessionId the parent session whose exposed subagents should be revoked
     */
    default void revokeByParentSession(String parentSessionId) {
        // optional capability
    }
}
