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
package io.agentscope.builder.runtime.session;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import java.util.Collection;
import java.util.Optional;

/**
 * Durable session metadata registry.
 *
 * <p>Implementations decide where session metadata lives. The default local implementation keeps
 * the current {@code sessions.json} behavior, while distributed deployments can provide a remote
 * implementation shared by all replicas.
 */
public interface SessionStore {

    /**
     * Loads the backing store into the implementation cache, if any. Implementations that read
     * directly from a remote backend may no-op.
     */
    void load();

    /** Persists a single session entry (upsert). */
    void save(SessionEntry entry);

    /** Updates only the {@code lastActivityMs} for the given key. */
    void touch(String sessionKey, long lastActivityMs);

    /** Removes a session entry by key. */
    void remove(String sessionKey);

    /** Returns a snapshot of all stored entries. */
    Collection<StoredEntry> listAll();

    /** Returns a single entry by key, if present. */
    Optional<StoredEntry> get(String sessionKey);

    /** Returns the current number of stored entries. */
    int size();

    /**
     * JSON-serializable subset of {@link SessionEntry}. Uses
     * {@code @JsonIgnoreProperties(ignoreUnknown = true)} for forward compatibility when new
     * fields are added.
     */
    @JsonIgnoreProperties(ignoreUnknown = true)
    record StoredEntry(
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
            String gateKey,
            String userId) {

        public static StoredEntry from(SessionEntry e) {
            return new StoredEntry(
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
                    e.gateKey(),
                    e.userId());
        }

        public SessionEntry toSessionEntry() {
            SessionKind sk = "main".equals(kind) ? SessionKind.MAIN : SessionKind.SUBAGENT;
            return new SessionEntry(
                    sessionKey,
                    agentId,
                    sessionId,
                    label,
                    sk,
                    spawnedBy,
                    spawnDepth,
                    createdAtMs,
                    lastActivityMs,
                    sessionFilePath,
                    spawnRunId,
                    gateKey,
                    userId);
        }
    }
}
