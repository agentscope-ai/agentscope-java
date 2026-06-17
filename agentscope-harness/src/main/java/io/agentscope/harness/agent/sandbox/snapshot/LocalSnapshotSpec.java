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

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Optional;
import java.util.stream.Stream;

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
     * Scans the base directory for an existing restorable snapshot tar file.
     *
     * <p>If multiple snapshot files exist, the most recently modified one is returned. This
     * allows a freshly created sandbox (Priority 4) to discover and reuse a snapshot that was
     * persisted by a previous sandbox instance for the same isolation slot.
     *
     * @return the most recently modified restorable snapshot, or empty if none exists
     * @throws IOException if scanning the directory fails
     */
    @Override
    public Optional<SandboxSnapshot> findRestorable() throws IOException {
        Path dir = Path.of(basePath);
        if (!Files.isDirectory(dir)) {
            return Optional.empty();
        }
        try (Stream<Path> entries = Files.list(dir)) {
            return entries.filter(p -> p.toString().endsWith(".tar"))
                    .filter(Files::isRegularFile)
                    .max(
                            (a, b) -> {
                                try {
                                    return Files.getLastModifiedTime(a)
                                            .compareTo(Files.getLastModifiedTime(b));
                                } catch (IOException e) {
                                    return 0;
                                }
                            })
                    .map(
                            p -> {
                                String fileName = p.getFileName().toString();
                                String id =
                                        fileName.substring(0, fileName.length() - ".tar".length());
                                return (SandboxSnapshot) new LocalSandboxSnapshot(basePath, id);
                            })
                    .filter(
                            snapshot -> {
                                try {
                                    return snapshot.isRestorable();
                                } catch (Exception e) {
                                    return false;
                                }
                            });
        }
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
