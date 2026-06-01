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
package io.agentscope.harness.agent.sandbox.snapshot;

import com.fasterxml.jackson.annotation.JsonSubTypes;
import com.fasterxml.jackson.annotation.JsonTypeInfo;
import java.io.InputStream;

/**
 * Represents a persisted snapshot of a sandbox workspace.
 *
 * <p>Snapshots allow workspace state to be preserved between session stops and restored on
 * subsequent session starts. Implementations determine where and how the snapshot is stored:
 * local disk ({@link LocalSandboxSnapshot}), remote storage ({@link RemoteSandboxSnapshot}),
 * or not at all ({@link NoopSandboxSnapshot}).
 */
@JsonTypeInfo(use = JsonTypeInfo.Id.NAME, include = JsonTypeInfo.As.PROPERTY, property = "type")
@JsonSubTypes({
    @JsonSubTypes.Type(value = NoopSandboxSnapshot.class, name = "noop"),
    @JsonSubTypes.Type(value = LocalSandboxSnapshot.class, name = "local"),
    @JsonSubTypes.Type(value = RemoteSandboxSnapshot.class, name = "remote"),
})
public interface SandboxSnapshot {

    /**
     * Persists the workspace archive to this snapshot.
     *
     * @param workspaceArchive tar stream of the workspace to persist; caller is responsible
     *                         for closing the stream after this call
     * @throws Exception if the persist operation fails
     */
    void persist(InputStream workspaceArchive) throws Exception;

    /**
     * Restores the workspace archive from this snapshot.
     *
     * @return tar stream of the workspace; caller is responsible for closing
     * @throws Exception if the restore operation fails or the snapshot is not restorable
     */
    InputStream restore() throws Exception;

    /**
     * Returns whether this snapshot can currently be restored.
     *
     * @return true if {@link #restore()} would succeed
     * @throws Exception if checking restorability fails
     */
    boolean isRestorable() throws Exception;

    /**
     * Returns the unique identifier for this snapshot.
     *
     * @return snapshot id
     */
    String getId();

    /**
     * Returns the snapshot type discriminator used in JSON serialization.
     *
     * @return type string (e.g. "noop", "local", "remote")
     */
    String getType();

    /**
     * Returns whether this snapshot actually persists data.
     *
     * <p>When {@code false}, {@link io.agentscope.harness.agent.sandbox.AbstractBaseSandbox#stop()} skips the
     * potentially expensive workspace archive step entirely. Defaults to {@code true}.
     *
     * @return false only for no-op implementations that discard all archive data
     */
    default boolean isPersistenceEnabled() {
        return true;
    }
}
