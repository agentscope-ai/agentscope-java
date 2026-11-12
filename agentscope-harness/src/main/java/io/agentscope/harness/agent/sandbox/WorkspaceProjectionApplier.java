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

import io.agentscope.harness.agent.sandbox.layout.DirEntry;
import io.agentscope.harness.agent.sandbox.layout.WorkspaceEntry;
import io.agentscope.harness.agent.sandbox.layout.WorkspaceProjectionEntry;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
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
     * Whether any projection entry in the spec is marked as managed by the capability
     * grant layer. Only marked specs run the authoritative (revoking) application;
     * unmarked specs keep the generic overlay semantics, so the authorization fix does
     * not change projection behaviour for everyone else.
     *
     * @param spec the workspace spec to inspect ({@code null} counts as unmanaged)
     * @return {@code true} when at least one projection entry is grant-managed
     */
    public static boolean isAuthorizationManaged(WorkspaceSpec spec) {
        if (spec == null || spec.getEntries() == null) {
            return false;
        }
        List<WorkspaceProjectionEntry> projections = new ArrayList<>();
        collectProjectionEntries(spec.getEntries(), projections);
        return projections.stream().anyMatch(WorkspaceProjectionEntry::isAuthorizationManaged);
    }

    /**
     * Merged {@code revokeRoots} of the managed projection entries: the whole projection
     * namespaces a rebuild must clear (e.g. the {@code .skills-cache} tree) so that no
     * stale file of any earlier era survives. Empty when the spec is unmanaged or no
     * managed entry declared its roots.
     *
     * @param spec the workspace spec to inspect
     * @return the deduplicated, order-preserving namespace roots to clear on rebuild
     */
    public static List<String> managedRevokeRoots(WorkspaceSpec spec) {
        if (spec == null || spec.getEntries() == null) {
            return List.of();
        }
        List<WorkspaceProjectionEntry> projections = new ArrayList<>();
        collectProjectionEntries(spec.getEntries(), projections);
        Set<String> roots = new LinkedHashSet<>();
        for (WorkspaceProjectionEntry entry : projections) {
            if (entry.isAuthorizationManaged() && entry.getRevokeRoots() != null) {
                roots.addAll(entry.getRevokeRoots());
            }
        }
        return List.copyOf(roots);
    }

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
        List<Map.Entry<String, Path>> ordered = new ArrayList<>(projectedFiles.entrySet());
        ordered.sort(Comparator.comparing(Map.Entry::getKey));

        MessageDigest digest = MessageDigest.getInstance("SHA-256");
        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        // Pin entry names to UTF-8: the platform default mangles non-ASCII names wherever
        // it is not UTF-8 (JDK 17 on Windows follows the OS locale — cp1252 runners
        // %U-escape CJK names), so the hydrated file would land under a different name
        // than the revocation manifest records. UTF-8 also matches the reader side
        // (WorkspaceArchiveExtractor) and container-side tar of every backend.
        try (TarArchiveOutputStream tar =
                new TarArchiveOutputStream(baos, StandardCharsets.UTF_8.name())) {
            tar.setLongFileMode(TarArchiveOutputStream.LONGFILE_POSIX);
            for (Map.Entry<String, Path> file : ordered) {
                String rel = file.getKey();
                Path src = file.getValue();
                byte[] content = Files.readAllBytes(src);

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
        List<String> paths = ordered.stream().map(Map.Entry::getKey).toList();
        return new ProjectionPayload(hash, baos.toByteArray(), ordered.size(), paths);
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
     * @param paths workspace-relative POSIX paths of the projected files; the sandbox keeps
     *     the last applied list so a later, narrower projection can revoke exactly these
     *     files (authoritative replace, never overlay)
     */
    public record ProjectionPayload(
            String hash, byte[] tarBytes, int fileCount, List<String> paths) {

        /**
         * Compatibility constructor for the pre-manifest three-component shape; the
         * recorded path list defaults to empty (nothing revocable). Prefer the canonical
         * constructor.
         *
         * @param hash deterministic hash over projected file paths and bytes
         * @param tarBytes tar archive containing projected files
         * @param fileCount number of projected files
         */
        public ProjectionPayload(String hash, byte[] tarBytes, int fileCount) {
            this(hash, tarBytes, fileCount, List.of());
        }
    }
}
