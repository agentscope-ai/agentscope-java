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

import io.agentscope.harness.agent.sandbox.SandboxErrorCode;
import io.agentscope.harness.agent.sandbox.SandboxException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.util.UUID;

/**
 * Snapshot that persists workspace archives as tar files on the local filesystem.
 *
 * <p>Archives are written atomically: the data is first written to a temporary file
 * (prefixed with {@code .}) in the same directory, then moved to the final path using
 * {@link StandardCopyOption#ATOMIC_MOVE}. This ensures the snapshot is either fully
 * written or not present — never partially written.
 *
 * <p>Security: {@code id} must be a single path segment with no {@code /} or {@code ..}
 * characters to prevent path traversal attacks.
 */
public class LocalSandboxSnapshot implements SandboxSnapshot {

    private final String basePath;
    private final String id;

    /**
     * Creates a local snapshot.
     *
     * @param basePath directory where snapshot tar files are stored
     * @param id unique identifier for this snapshot (must be a safe single path segment)
     * @throws IllegalArgumentException if {@code id} contains unsafe characters
     */
    public LocalSandboxSnapshot(String basePath, String id) {
        validateId(id);
        this.basePath = basePath;
        this.id = id;
    }

    /**
     * {@inheritDoc}
     *
     * <p>Writes the archive atomically to {@code {basePath}/{id}.tar}.
     */
    @Override
    public void persist(InputStream workspaceArchive) throws Exception {
        Path targetPath = Path.of(basePath).resolve(id + ".tar");
        Path tmpPath = targetPath.resolveSibling("." + id + "." + UUID.randomUUID() + ".tmp");

        try {
            Files.createDirectories(targetPath.getParent());
            try (OutputStream out =
                    Files.newOutputStream(
                            tmpPath,
                            StandardOpenOption.CREATE,
                            StandardOpenOption.TRUNCATE_EXISTING)) {
                workspaceArchive.transferTo(out);
            }
            Files.move(
                    tmpPath,
                    targetPath,
                    StandardCopyOption.ATOMIC_MOVE,
                    StandardCopyOption.REPLACE_EXISTING);
        } catch (Exception e) {
            try {
                Files.deleteIfExists(tmpPath);
            } catch (Exception ignored) {
                // Best-effort cleanup of the temp file
            }
            throw new SandboxException.SnapshotException(id, "Failed to persist snapshot", e);
        }
    }

    /**
     * {@inheritDoc}
     *
     * <p>Opens the snapshot tar file at {@code {basePath}/{id}.tar} for reading.
     */
    @Override
    public InputStream restore() throws Exception {
        Path path = Path.of(basePath).resolve(id + ".tar");
        if (!Files.exists(path)) {
            throw new SandboxException.SnapshotException(id);
        }
        try {
            return Files.newInputStream(path);
        } catch (Exception e) {
            throw new SandboxException.SnapshotException(id, "Failed to read snapshot", e);
        }
    }

    /**
     * {@inheritDoc}
     *
     * @return {@code true} if the snapshot tar file exists
     */
    @Override
    public boolean isRestorable() {
        return Files.exists(Path.of(basePath).resolve(id + ".tar"));
    }

    @Override
    public String getId() {
        return id;
    }

    @Override
    public String getType() {
        return "local";
    }

    /**
     * Returns the base directory where snapshot tar files are stored.
     *
     * @return base path string
     */
    public String getBasePath() {
        return basePath;
    }

    private static void validateId(String id) {
        if (id == null || id.isBlank()) {
            throw new IllegalArgumentException("Snapshot id must not be null or blank");
        }
        if (id.contains("/") || id.contains("\\") || id.contains("..") || id.contains("\0")) {
            throw new SandboxException(
                    SandboxErrorCode.INVALID_MANIFEST_PATH,
                    "Snapshot id contains unsafe characters: " + id);
        }
    }
}
