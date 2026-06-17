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
package io.agentscope.harness.agent.skill.runtime;

import io.agentscope.core.skill.AgentSkill;
import io.agentscope.core.skill.repository.AgentSkillRepository;
import io.agentscope.harness.agent.skill.WorkspaceSkillRepository;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermission;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Base64;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.HashSet;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Materialises non-workspace skill resources (Layer 1 / Layer 2 / marketplace) to
 * {@code <wsRoot>/.skills-cache/<source-ns>/<skill-name>/} so that:
 *
 * <ul>
 *   <li>shell-mode HarnessAgents (sandbox or Local-with-shell) can execute the staged scripts
 *       via absolute paths
 *   <li>sandbox projection (which includes {@code .skills-cache} in
 *       {@code DEFAULT_WORKSPACE_PROJECTION_ROOTS}) hydrates the staged content into the
 *       sandbox at start time
 * </ul>
 *
 * <p>The stager keeps no cross-call state: each invocation rebuilds the white-list of
 * directories that should remain under {@code .skills-cache}, materialises any files whose
 * SHA-256 has changed, and deletes orphan directories not present in the white-list.
 *
 * <p>Workspace-native skills (those produced by {@link WorkspaceSkillRepository}) are NOT
 * staged: they already live under {@code <wsRoot>/skills/} (or are produced lazily from the
 * sandbox-backed filesystem) and projection covers them through the regular {@code skills}
 * root.
 */
@SuppressWarnings("deprecation")
public final class MarketplaceStager {

    private static final Logger log = LoggerFactory.getLogger(MarketplaceStager.class);

    public static final String CACHE_DIR = ".skills-cache";
    public static final String GLOBAL_NAMESPACE = "_global";

    private final Path workspaceRoot;

    public MarketplaceStager(Path workspaceRoot) {
        this.workspaceRoot = workspaceRoot;
    }

    /**
     * Stage all eligible inputs and return a map from {@code skill.name} to its resolved
     * {@link StageResult}. Inputs whose source repository is a
     * {@link WorkspaceSkillRepository} are returned as {@link StageResult.WorkspaceNative}
     * — they need no staging because the workspace tree already contains them.
     *
     * <p>The white-list of staged directories is rebuilt every call; any pre-existing
     * directory under {@code .skills-cache/<source-ns>/} not in the white-list is removed
     * (cheap orphan GC: marketplace repos that no longer publish a given skill leave no
     * residue).
     *
     * @param visible       skill+repository pairs in compose order (winner per name already
     *                      deduped upstream)
     * @param sourceNs      map from repository identity to its resolved source namespace
     *                      (handles repos with colliding {@code getSource()} via {@code _idx}
     *                      suffix)
     * @return ordered map (insertion-order preserved) keyed by {@code skill.name}
     */
    public Map<String, StageResult> stage(
            List<RepoBound> visible, Map<AgentSkillRepository, String> sourceNs) {
        Map<String, StageResult> roots = new HashMap<>(visible.size());
        if (workspaceRoot == null) {
            // No host workspace available (rare; e.g. classpath-only build). Skip staging
            // and report no filesRoot for the affected skills; shell-mode rendering will
            // omit them gracefully.
            for (RepoBound bound : visible) {
                if (bound.repo() instanceof WorkspaceSkillRepository) {
                    roots.put(bound.skill().getName(), new StageResult.WorkspaceNative());
                } else {
                    roots.put(bound.skill().getName(), StageResult.NONE);
                }
            }
            return roots;
        }

        Path cacheRoot = workspaceRoot.resolve(CACHE_DIR);
        Set<Path> retained = new HashSet<>();

        for (RepoBound bound : visible) {
            AgentSkill skill = bound.skill();
            String name = skill.getName();
            if (name == null || name.isBlank()) {
                continue;
            }

            if (bound.repo() instanceof WorkspaceSkillRepository) {
                // Workspace-native: skills/<name>/ already on the right path.
                roots.put(name, new StageResult.WorkspaceNative());
                continue;
            }

            String ns = sourceNs.get(bound.repo());
            if (ns == null || ns.isBlank()) {
                ns = bound.repo().getSource();
                if (ns == null || ns.isBlank()) {
                    ns = GLOBAL_NAMESPACE;
                }
            }

            Path stagedDir = cacheRoot.resolve(ns).resolve(name);
            try {
                materializeIfChanged(stagedDir, skill.getResources());
                retained.add(stagedDir);
                roots.put(name, new StageResult.Cached(ns, name));
            } catch (Exception e) {
                log.warn("Failed to stage skill '{}' (source-ns={}): {}", name, ns, e.getMessage());
                roots.put(name, StageResult.NONE);
            }
        }

        garbageCollectOrphans(cacheRoot, retained);
        return roots;
    }

    /** Convenience for callers that don't care about return values. */
    public void invalidateAll() {
        if (workspaceRoot == null) {
            return;
        }
        Path cacheRoot = workspaceRoot.resolve(CACHE_DIR);
        if (Files.isDirectory(cacheRoot)) {
            try {
                deleteRecursively(cacheRoot);
            } catch (IOException e) {
                log.warn("Failed to clear {}: {}", cacheRoot, e.getMessage());
            }
        }
    }

    // =========================================================================
    //  Internals
    // =========================================================================

    private void materializeIfChanged(Path stagedDir, Map<String, String> resources)
            throws IOException {
        Files.createDirectories(stagedDir);
        if (resources == null || resources.isEmpty()) {
            return;
        }
        // Track files we expect; remove any extras under stagedDir afterwards.
        Set<Path> expected = new HashSet<>();
        for (Map.Entry<String, String> e : resources.entrySet()) {
            String rel = e.getKey();
            String content = e.getValue();
            if (rel == null || rel.isBlank() || content == null) {
                continue;
            }
            if (rel.startsWith("/") || rel.contains("..")) {
                log.debug("Skipping unsafe resource path '{}' during stage", rel);
                continue;
            }
            Path target = stagedDir.resolve(rel).normalize();
            if (!target.startsWith(stagedDir)) {
                log.debug("Skipping out-of-tree resource path '{}' during stage", rel);
                continue;
            }
            byte[] bytes = decode(content);
            writeIfChanged(target, bytes);
            expected.add(target);
        }
        // Remove stale files under the staged dir that no longer correspond to a published
        // resource for this skill. Keeps stage idempotent and self-cleaning per skill.
        removeUnexpected(stagedDir, expected);
    }

    private void writeIfChanged(Path target, byte[] bytes) throws IOException {
        Files.createDirectories(target.getParent());
        if (Files.exists(target)) {
            byte[] existing = Files.readAllBytes(target);
            if (sha256(existing).equals(sha256(bytes))) {
                return;
            }
        }
        Files.write(target, bytes);
        // Heuristic exec-bit recovery: the ingestion path turns files into Strings and discards
        // POSIX mode, so we re-derive +x from a shebang / known script extension. Not a true
        // mode preservation — pure-static skill assets (.json/.md/.txt) stay 644.
        maybeMarkExecutable(target, bytes);
    }

    /**
     * Script-detection heuristic: shebang at byte 0/1 OR a known-script suffix. Match → add
     * owner-exec on POSIX filesystems. Non-POSIX (Windows) silently no-op.
     */
    private static void maybeMarkExecutable(Path target, byte[] bytes) {
        if (!shouldBeExecutable(target, bytes)) {
            return;
        }
        try {
            Set<PosixFilePermission> current = new HashSet<>(Files.getPosixFilePermissions(target));
            // Only flip the exec bits the file already has *read* on, mirroring how `chmod +x`
            // behaves: a 640 file gets 750, not 751.
            EnumSet<PosixFilePermission> toAdd = EnumSet.noneOf(PosixFilePermission.class);
            if (current.contains(PosixFilePermission.OWNER_READ)) {
                toAdd.add(PosixFilePermission.OWNER_EXECUTE);
            }
            if (current.contains(PosixFilePermission.GROUP_READ)) {
                toAdd.add(PosixFilePermission.GROUP_EXECUTE);
            }
            if (current.contains(PosixFilePermission.OTHERS_READ)) {
                toAdd.add(PosixFilePermission.OTHERS_EXECUTE);
            }
            if (toAdd.isEmpty()) {
                return;
            }
            current.addAll(toAdd);
            Files.setPosixFilePermissions(target, current);
        } catch (UnsupportedOperationException e) {
            // POSIX permissions unavailable (Windows default FS); intentional no-op.
        } catch (IOException e) {
            log.debug("Failed to set exec bit on {}: {}", target, e.getMessage());
        }
    }

    /** Known interpreter / script suffixes. Conservative — we only mark "obvious" scripts. */
    private static final Set<String> SCRIPT_SUFFIXES =
            Set.of(".sh", ".bash", ".zsh", ".ksh", ".py", ".rb", ".pl", ".js", ".mjs");

    /** Package-private for direct heuristic testing without spinning up {@link #stage}. */
    static boolean shouldBeExecutable(Path target, byte[] bytes) {
        // 1. Shebang detection — strongest signal regardless of filename.
        if (bytes != null && bytes.length >= 2 && bytes[0] == '#' && bytes[1] == '!') {
            return true;
        }
        // 2. Filename suffix.
        String fileName = target.getFileName().toString().toLowerCase(Locale.ROOT);
        int dot = fileName.lastIndexOf('.');
        if (dot < 0) {
            return false;
        }
        return SCRIPT_SUFFIXES.contains(fileName.substring(dot));
    }

    private void removeUnexpected(Path stagedDir, Set<Path> expected) {
        try (var stream = Files.walk(stagedDir)) {
            List<Path> toDelete = new ArrayList<>();
            stream.filter(Files::isRegularFile)
                    .filter(p -> !expected.contains(p.normalize()))
                    .forEach(toDelete::add);
            for (Path p : toDelete) {
                Files.deleteIfExists(p);
            }
            // Prune now-empty subdirectories left after file removal.
            try (var dirStream = Files.walk(stagedDir)) {
                List<Path> dirs = new ArrayList<>();
                dirStream
                        .filter(Files::isDirectory)
                        .filter(p -> !p.equals(stagedDir))
                        .forEach(dirs::add);
                // Walk leaf-to-root so deletion succeeds.
                dirs.sort((a, b) -> b.getNameCount() - a.getNameCount());
                for (Path d : dirs) {
                    try (var probe = Files.list(d)) {
                        if (probe.findAny().isEmpty()) {
                            Files.deleteIfExists(d);
                        }
                    }
                }
            }
        } catch (IOException e) {
            log.debug("Cleanup of {} failed: {}", stagedDir, e.getMessage());
        }
    }

    private void garbageCollectOrphans(Path cacheRoot, Set<Path> retained) {
        if (!Files.isDirectory(cacheRoot)) {
            return;
        }
        // Two-level layout: <source-ns>/<skill-name>/
        try (var nsStream = Files.list(cacheRoot)) {
            List<Path> nsDirs = new ArrayList<>();
            nsStream.filter(Files::isDirectory).forEach(nsDirs::add);
            for (Path nsDir : nsDirs) {
                try (var skillStream = Files.list(nsDir)) {
                    List<Path> skillDirs = new ArrayList<>();
                    skillStream.filter(Files::isDirectory).forEach(skillDirs::add);
                    for (Path skillDir : skillDirs) {
                        if (!retained.contains(skillDir)) {
                            deleteRecursively(skillDir);
                        }
                    }
                }
                // Clean up empty namespace dir.
                try (var probe = Files.list(nsDir)) {
                    if (probe.findAny().isEmpty()) {
                        Files.deleteIfExists(nsDir);
                    }
                }
            }
        } catch (IOException e) {
            log.debug("Orphan GC under {} failed: {}", cacheRoot, e.getMessage());
        }
    }

    private void deleteRecursively(Path root) throws IOException {
        if (!Files.exists(root)) {
            return;
        }
        try (var stream = Files.walk(root)) {
            List<Path> all = new ArrayList<>();
            stream.forEach(all::add);
            all.sort((a, b) -> b.getNameCount() - a.getNameCount());
            for (Path p : all) {
                Files.deleteIfExists(p);
            }
        }
    }

    private static byte[] decode(String content) {
        if (content.startsWith("base64:")) {
            return Base64.getDecoder().decode(content.substring("base64:".length()));
        }
        return content.getBytes(StandardCharsets.UTF_8);
    }

    private static String sha256(byte[] bytes) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            md.update(bytes);
            byte[] hash = md.digest();
            StringBuilder sb = new StringBuilder(hash.length * 2);
            for (byte b : hash) {
                sb.append(String.format("%02x", b));
            }
            return sb.toString();
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 unavailable", e);
        }
    }

    /**
     * Resolves per-repository {@code source} namespaces. When two repositories report the same
     * {@code getSource()}, the second and subsequent ones receive an {@code _<idx>} suffix
     * (with a warning log). Layer-1 host repositories whose source string is empty get
     * {@link #GLOBAL_NAMESPACE}.
     */
    public static Map<AgentSkillRepository, String> resolveSourceNamespaces(
            List<AgentSkillRepository> repos) {
        Map<AgentSkillRepository, String> ns = new IdentityHashMap<>();
        if (repos == null || repos.isEmpty()) {
            return ns;
        }
        Map<String, Integer> count = new HashMap<>();
        for (AgentSkillRepository repo : repos) {
            String src = effectiveSource(repo);
            count.merge(src, 1, Integer::sum);
        }
        Map<String, Integer> seen = new HashMap<>();
        for (int i = 0; i < repos.size(); i++) {
            AgentSkillRepository repo = repos.get(i);
            String src = effectiveSource(repo);
            int total = count.getOrDefault(src, 1);
            if (total == 1) {
                ns.put(repo, src);
            } else {
                int idx = seen.merge(src, 1, Integer::sum);
                String resolved = src + "_" + idx;
                ns.put(repo, resolved);
                if (idx > 1 || total > 1) {
                    log.warn(
                            "Skill repository source '{}' is used by {} repositories;"
                                    + " disambiguating as '{}' for repo at index {} ({})",
                            src,
                            total,
                            resolved,
                            i,
                            repo.getClass().getSimpleName());
                }
            }
        }
        return ns;
    }

    private static String effectiveSource(AgentSkillRepository repo) {
        String s = repo.getSource();
        if (s == null || s.isBlank()) {
            return GLOBAL_NAMESPACE;
        }
        // WorkspaceSkillRepository does not consume a source-ns slot, but we still index it
        // for completeness so callers can pass a uniform map.
        return s;
    }

    /** Pairs a skill with the repository that produced it. */
    public record RepoBound(AgentSkill skill, AgentSkillRepository repo) {}

    /** Outcome of staging one skill. */
    public sealed interface StageResult {
        StageResult NONE = new None();

        /** No staging applied — skill source has no shell-reachable representation. */
        record None() implements StageResult {}

        /** Skill comes from {@link WorkspaceSkillRepository} (already in workspace/skills/). */
        record WorkspaceNative() implements StageResult {}

        /** Skill staged under {@code .skills-cache/<sourceNs>/<skillName>/}. */
        record Cached(String sourceNamespace, String skillName) implements StageResult {}
    }
}
