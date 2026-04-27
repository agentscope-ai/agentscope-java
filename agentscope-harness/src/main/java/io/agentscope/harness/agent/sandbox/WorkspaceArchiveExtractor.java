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

import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import org.apache.commons.compress.archivers.tar.TarArchiveEntry;
import org.apache.commons.compress.archivers.tar.TarArchiveInputStream;

/**
 * Securely extracts tar archives into a target directory.
 *
 * <p>Applies strict path traversal guards before extracting each archive entry:
 * <ol>
 *   <li>Rejects entries with absolute paths (starting with {@code /} or {@code \})</li>
 *   <li>Rejects entries containing {@code ..} path segments</li>
 *   <li>Verifies the resolved destination path starts with the canonical root</li>
 * </ol>
 *
 * <p>These guards defend against the "Zip Slip" class of path traversal attacks.
 */
public class WorkspaceArchiveExtractor {

    private WorkspaceArchiveExtractor() {
        // Utility class, no instances
    }

    /**
     * Extracts a tar archive stream into the given destination directory.
     *
     * <p>The destination directory must already exist. Existing files at the same paths
     * will be replaced.
     *
     * @param destRoot the directory to extract into; must already exist
     * @param tarStream the tar archive input stream; caller is responsible for closing
     * @throws SandboxException.SandboxRuntimeException if a path traversal attempt is detected
     * @throws Exception if extraction fails for any other reason
     */
    public static void extractTarArchive(Path destRoot, InputStream tarStream) throws Exception {
        Path canonicalRoot = destRoot.normalize().toAbsolutePath();

        try (TarArchiveInputStream tar = new TarArchiveInputStream(tarStream)) {
            TarArchiveEntry entry;
            while ((entry = tar.getNextEntry()) != null) {
                validateEntryName(entry.getName(), canonicalRoot);

                Path dest = canonicalRoot.resolve(entry.getName()).normalize();

                // Final guard: verify destination is strictly within the root
                if (!dest.startsWith(canonicalRoot)) {
                    throw new SandboxException.SandboxRuntimeException(
                            SandboxErrorCode.WORKSPACE_ARCHIVE_READ_ERROR,
                            "Tar entry escapes destination root: " + entry.getName());
                }

                if (entry.isDirectory()) {
                    Files.createDirectories(dest);
                } else {
                    if (dest.getParent() != null) {
                        Files.createDirectories(dest.getParent());
                    }
                    Files.copy(tar, dest, StandardCopyOption.REPLACE_EXISTING);
                }
            }
        }
    }

    private static void validateEntryName(String name, Path canonicalRoot) {
        if (name == null || name.isEmpty()) {
            return;
        }

        // Reject absolute paths
        if (name.startsWith("/") || name.startsWith("\\")) {
            throw new SandboxException.SandboxRuntimeException(
                    SandboxErrorCode.WORKSPACE_ARCHIVE_READ_ERROR,
                    "Tar entry has absolute path (rejected): " + name);
        }

        // Reject path traversal sequences
        if (name.contains("..")) {
            // Normalize and check for actual traversal segments
            for (String segment : name.split("[/\\\\]")) {
                if ("..".equals(segment)) {
                    throw new SandboxException.SandboxRuntimeException(
                            SandboxErrorCode.WORKSPACE_ARCHIVE_READ_ERROR,
                            "Tar entry contains path traversal (rejected): " + name);
                }
            }
        }

        // Reject null bytes
        if (name.contains("\0")) {
            throw new SandboxException.SandboxRuntimeException(
                    SandboxErrorCode.WORKSPACE_ARCHIVE_READ_ERROR,
                    "Tar entry name contains null byte (rejected): " + name);
        }
    }
}
