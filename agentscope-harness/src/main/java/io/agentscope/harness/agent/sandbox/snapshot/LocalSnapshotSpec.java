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

import java.nio.file.Path;

/**
 * Snapshot spec that creates {@link LocalSandboxSnapshot} instances stored in a local directory.
 *
 * <p>Each session gets its own snapshot file at {@code {basePath}/{sessionId}.tar}.
 */
public class LocalSnapshotSpec implements SandboxSnapshotSpec {

    private final String basePath;

    /**
     * Creates a local snapshot spec.
     *
     * @param basePath directory where snapshot tar files will be stored
     */
    public LocalSnapshotSpec(Path basePath) {
        this.basePath = basePath.toString();
    }

    /**
     * Creates a local snapshot spec.
     *
     * @param basePath directory path string where snapshot tar files will be stored
     */
    public LocalSnapshotSpec(String basePath) {
        this.basePath = basePath;
    }

    /**
     * {@inheritDoc}
     *
     * @return a new {@link LocalSandboxSnapshot} storing at {@code {basePath}/{snapshotId}.tar}
     */
    @Override
    public SandboxSnapshot build(String snapshotId) {
        return new LocalSandboxSnapshot(basePath, snapshotId);
    }

    /**
     * Returns the base directory used for snapshot files.
     *
     * @return base path string
     */
    public String getBasePath() {
        return basePath;
    }
}
