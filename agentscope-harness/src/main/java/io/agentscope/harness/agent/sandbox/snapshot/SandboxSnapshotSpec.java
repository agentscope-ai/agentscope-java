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

/**
 * Factory that creates {@link SandboxSnapshot} instances for a given session ID.
 *
 * <p>Implementations configure WHERE snapshots are stored:
 * {@link NoopSnapshotSpec} (disabled), {@link LocalSnapshotSpec} (local disk),
 * {@link RemoteSnapshotSpec} (remote storage).
 *
 * <p>The {@code snapshotId} parameter passed to {@link #build} allows each session to have
 * its own isolated snapshot file/object, while sharing the same storage configuration.
 */
public interface SandboxSnapshotSpec {

    /**
     * Creates a {@link SandboxSnapshot} for the given session ID.
     *
     * @param snapshotId unique identifier for the snapshot (typically the session UUID)
     * @return a new snapshot instance configured for the given ID
     */
    SandboxSnapshot build(String snapshotId);
}
