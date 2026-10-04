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

import io.agentscope.harness.agent.filesystem.util.FilesystemUtils;
import io.agentscope.harness.agent.sandbox.layout.DirEntry;
import io.agentscope.harness.agent.sandbox.layout.WorkspaceEntry;
import io.agentscope.harness.agent.sandbox.layout.WorkspaceProjectionEntry;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.apache.commons.compress.archivers.tar.TarArchiveEntry;
import org.apache.commons.compress.archivers.tar.TarArchiveOutputStream;

/**
 * Builds a deterministic archive payload for workspace projection entries.
 *
 * <p>The payload contains:
 * <ul>
 *   <li>projected files as a tar archive (for sandbox hydration)</li>
 *   <li>a content hash used to skip unchanged projections across calls</li>
 * </ul>
 */
public final class WorkspaceProjectionApplier {

    private WorkspaceProjectionApplier() {}

    /**
     * Builds projection payload from all {@link WorkspaceProjectionEntry} items in the spec.
     */
    public static ProjectionPayload build(WorkspaceSpec spec) throws Exception {
        if (spec == null || spec.getEntries().isEmpty()) {
            return null;
        }
        List<WorkspaceProjectionEntry> entries = new ArrayList<>();
        collectProjectionEntries(spec.getEntries(), entries);
        if (entries.isEmpty()) {
            return null;
        }

        Map<String, Path> projectedFiles = collectProjectedFiles(entries);
        var authoritativePaths =
                collectProjectedFiles(
                                entries.stream()
                                        .filter(
                                                WorkspaceProjectionEntry
                                                        ::isHostAuthoritativeDefinitions)
                                        .toList())
                        .keySet();
        Map<String, String> fileHashes = new LinkedHashMap<>();
        List<Map.Entry<String, Path>> ordered = new ArrayList<>(projectedFiles.entrySet());
        ordered.sort(Comparator.comparing(Map.Entry::getKey));

        MessageDigest digest = MessageDigest.getInstance("SHA-256");
        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        try (TarArchiveOutputStream tar = new TarArchiveOutputStream(baos)) {
            tar.setLongFileMode(TarArchiveOutputStream.LONGFILE_POSIX);
            for (Map.Entry<String, Path> file : ordered) {
                String rel = file.getKey();
                Path src = file.getValue();
                byte[] content = Files.readAllBytes(src);
                if (authoritativePaths.contains(rel)) {
                    fileHashes.put(
                            rel, bytesToHex(MessageDigest.getInstance("SHA-256").digest(content)));
                }

                digest.update(rel.getBytes(java.nio.charset.StandardCharsets.UTF_8));
                digest.update((byte) 0);
                digest.update(content);

                TarArchiveEntry entry = new TarArchiveEntry(rel);
                entry.setSize(content.length);
                tar.putArchiveEntry(entry);
                tar.write(content);
                tar.closeArchiveEntry();
            }
            tar.finish();
        }

        String hash = bytesToHex(digest.digest());
        return new ProjectionPayload(hash, baos.toByteArray(), ordered.size(), fileHashes);
    }

    /**
     * Checks the live copy, using bounded exec batches. A missing hashing utility, malformed
     * output, timeout, or backend error must force hydration rather than trust the old hash.
     */
    static boolean matchesSandbox(ProjectionPayload payload, Sandbox sandbox, String workspaceRoot)
            throws Exception {
        List<Map.Entry<String, String>> files = new ArrayList<>(payload.fileHashes().entrySet());
        for (int start = 0; start < files.size(); start += 64) {
            List<Map.Entry<String, String>> batch =
                    files.subList(start, Math.min(start + 64, files.size()));
            StringBuilder command = new StringBuilder();
            for (var file : batch) {
                String path = FilesystemUtils.shellQuote(workspaceRoot + "/" + file.getKey());
                command.append("test -f ")
                        .append(path)
                        .append(" && test ! -L ")
                        .append(path)
                        .append(" && sha256sum < ")
                        .append(path)
                        .append(" || exit 1;\n");
            }
            ExecResult result = sandbox.exec(null, command.toString(), 10);
            if (!result.ok() || result.truncated() || result.stdout() == null) return false;
            String[] lines = result.stdout().strip().split("\\R");
            if (lines.length != batch.size()) return false;
            for (int i = 0; i < lines.length; i++) {
                if (!lines[i].matches("[0-9a-fA-F]{64}\\s+-")
                        || !lines[i].substring(0, 64).equalsIgnoreCase(batch.get(i).getValue()))
                    return false;
            }
        }
        return true;
    }

    private static void collectProjectionEntries(
            Map<String, WorkspaceEntry> map, List<WorkspaceProjectionEntry> out) {
        for (WorkspaceEntry entry : map.values()) {
            if (entry instanceof WorkspaceProjectionEntry projection) {
                out.add(projection);
            } else if (entry instanceof DirEntry dir && !dir.getChildren().isEmpty()) {
                collectProjectionEntries(dir.getChildren(), out);
            }
        }
    }

    private static Map<String, Path> collectProjectedFiles(List<WorkspaceProjectionEntry> entries)
            throws IOException {
        Map<String, Path> files = new LinkedHashMap<>();
        for (WorkspaceProjectionEntry entry : entries) {
            if (entry.getSourceRoot() == null || entry.getSourceRoot().isBlank()) {
                continue;
            }
            Path sourceRoot = Path.of(entry.getSourceRoot()).toAbsolutePath().normalize();
            for (String root : entry.getIncludeRoots()) {
                if (root == null || root.isBlank()) {
                    continue;
                }
                Path resolved = sourceRoot.resolve(root).normalize();
                if (!resolved.startsWith(sourceRoot) || !Files.exists(resolved)) {
                    continue;
                }
                if (Files.isRegularFile(resolved)) {
                    files.put(normalizePath(root), resolved);
                } else if (Files.isDirectory(resolved)) {
                    try (var walk = Files.walk(resolved)) {
                        walk.filter(Files::isRegularFile)
                                .forEach(
                                        p -> {
                                            String rel =
                                                    normalizePath(
                                                            sourceRoot.relativize(p).toString());
                                            files.put(rel, p);
                                        });
                    }
                }
            }
        }
        return files;
    }

    private static String normalizePath(String path) {
        return path.replace('\\', '/');
    }

    private static String bytesToHex(byte[] bytes) {
        StringBuilder sb = new StringBuilder(bytes.length * 2);
        for (byte b : bytes) {
            sb.append(String.format("%02x", b));
        }
        return sb.toString();
    }

    /**
     * Projection result used at sandbox start.
     *
     * @param hash deterministic hash over projected file paths and bytes
     * @param tarBytes tar archive containing projected files
     * @param fileCount number of projected files
     * @param fileHashes hashes of files whose live sandbox copies require verification
     */
    public record ProjectionPayload(
            String hash, byte[] tarBytes, int fileCount, Map<String, String> fileHashes) {
        public ProjectionPayload {
            fileHashes = java.util.Collections.unmodifiableMap(new LinkedHashMap<>(fileHashes));
        }

        /** Preserves the original payload constructor for callers that do not request verification. */
        public ProjectionPayload(String hash, byte[] tarBytes, int fileCount) {
            this(hash, tarBytes, fileCount, Map.of());
        }
    }
}
