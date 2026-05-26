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

import java.io.IOException;
import java.util.Optional;

/**
 * Storage abstraction for persisting and loading sandbox session state keyed by
 * {@link SandboxIsolationKey}.
 *
 * <p>Implementations must be safe to call from multiple threads, but they are not required to
 * provide transactional or atomic semantics across concurrent writes to the same key.
 *
 * @see WorkspaceSandboxStateStore
 */
public interface SandboxStateStore {

    /**
     * Loads the persisted sandbox state JSON for the given key.
     *
     * @param key the isolation key identifying the state slot
     * @return the serialized {@link SandboxState} JSON, or empty if no state is stored
     * @throws IOException if a storage error occurs
     */
    Optional<String> load(SandboxIsolationKey key) throws IOException;

    /**
     * Saves the sandbox state JSON for the given key.
     *
     * <p>An existing value for the same key is overwritten.
     *
     * @param key  the isolation key identifying the state slot
     * @param json the serialized {@link SandboxState} JSON
     * @throws IOException if a storage error occurs
     */
    void save(SandboxIsolationKey key, String json) throws IOException;

    /**
     * Deletes the sandbox state for the given key.
     *
     * <p>No-op if no state is stored for the key.
     *
     * @param key the isolation key identifying the state slot
     * @throws IOException if a storage error occurs
     */
    void delete(SandboxIsolationKey key) throws IOException;
}
