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
package io.agentscope.core.storage;

import io.agentscope.core.state.AgentState;
import reactor.core.publisher.Mono;

/**
 * Storage abstraction for {@link AgentState}.
 *
 * <p>Each (sessionId, agentId) pair addresses one stored state. {@link #loadAgentState} emits
 * {@code Mono.empty()} when no entry exists; it should not emit an error for an unknown key.
 * {@link #saveAgentState} returns a completion signal once the write durably reaches the backend.
 *
 * <p>Implementations must be safe for concurrent calls from multiple subscribers.
 */
public abstract class StorageBase {

    /**
     * Load the previously saved state for {@code (sessionId, agentId)}, or emit empty if no entry
     * exists.
     */
    public abstract Mono<AgentState> loadAgentState(String sessionId, String agentId);

    /** Persist {@code state} under {@code (sessionId, agentId)}, replacing any prior entry. */
    public abstract Mono<Void> saveAgentState(String sessionId, String agentId, AgentState state);
}
