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
package io.agentscope.extensions.agentprotocol;

import java.util.Optional;

/**
 * Persistence for {@link ProtocolTaskContext}, the per-task submission metadata that {@code
 * /resume} needs after the original {@code POST /tasks}.
 *
 * <p>Entries are written on submit, must survive an {@code awaiting_confirm} pause, and are cleared
 * whenever the task record reaches a terminal state. The store does not expire them on its own.
 *
 * <p>The shipped implementation is {@link BaseStoreProtocolTaskContextStore}, which keeps one entry
 * per task in a {@link io.agentscope.harness.agent.filesystem.remote.store.BaseStore}; its {@code
 * inMemory()} factory is the process-local default. A multi-instance deployment points that
 * implementation at a shared store (Redis, JDBC, ...), because the {@code /resume} that follows a
 * pause is usually served by a different instance than the submit.
 *
 * <p>An implementation must not expire entries independently of the task record. An entry that
 * disappears while its record is still resumable turns a later {@code /resume} into a silent
 * downgrade — default stream detail and no user identity — rather than an error the client could act
 * on. Bounding how long a task may stay paused is the caller's decision, and a record that is
 * settled is cleared together with its entry.
 */
public interface ProtocolTaskContextStore {

    /** Upserts the submission metadata for {@code taskId}. */
    void save(String taskId, ProtocolTaskContext context);

    /** Returns the stored metadata for {@code taskId}, or empty if unknown or expired. */
    Optional<ProtocolTaskContext> find(String taskId);

    /** Drops the metadata for a terminal task. */
    void clear(String taskId);
}
