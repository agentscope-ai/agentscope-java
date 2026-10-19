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

import io.agentscope.core.agent.RuntimeContext;
import io.agentscope.harness.agent.IsolationScope;
import io.agentscope.harness.agent.sandbox.layout.WorkspaceProjectionEntry;
import io.agentscope.harness.agent.skill.runtime.MarketplaceStager;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.apache.commons.compress.archivers.tar.TarArchiveInputStream;

/** Opt-in reconciliation of derived cache subtrees; never owns the workspace or cache root. */
final class SkillsCacheProjection {

    // A bounded bootstrap consumes only the framed source; the script reads the remaining JSON.
    private static final List<String> COMMAND =
            List.of(
                    "python3",
                    "-I",
                    "-c",
                    "import sys; size=int(sys.stdin.buffer.readline()); "
                            + "source=sys.stdin.buffer.read(size); "
                            + "assert len(source)==size, 'Incomplete cache script'; "
                            + "exec(compile(source, '<cache-reconcile>', 'exec'))");

    private SkillsCacheProjection() {}

    static boolean enabled(WorkspaceSpec spec) {
        return entries(spec).stream().anyMatch(entry -> !entry.getSyncRoots().isEmpty());
    }

    static WorkspaceSpec bindScope(
            WorkspaceSpec spec, RuntimeContext ctx, IsolationScope isolationScope) {
        if (spec == null) return null;
        WorkspaceSpec bound = spec.copy();
        if (!enabled(bound)) return bound;
        String expected = MarketplaceStager.cacheScopeRootFor(ctx, isolationScope);
        validateRoot(expected);
        bound.bindCacheProjectionScope(expected);
        for (WorkspaceProjectionEntry entry : entries(bound)) {
            for (String configured : entry.getSyncRoots()) {
                String root = resolveRoot(configured, bound);
                validateRoot(root);
                if (!under(root, expected)) {
                    throw new IllegalArgumentException(
                            "Cache sync root does not belong to this call: " + root);
                }
            }
        }
        return bound;
    }

    static void restrictProjectedCache(WorkspaceSpec spec, Map<String, Path> files) {
        String scope = spec.cacheProjectionScope();
        if (scope != null && enabled(spec)) {
            files.keySet()
                    .removeIf(path -> path.startsWith(".skills-cache/") && !under(path, scope));
        }
    }

    private static String resolveRoot(String configured, WorkspaceSpec spec) {
        String template = ".skills-cache/{scope}";
        if (configured != null
                && (configured.equals(template) || configured.startsWith(template + "/"))) {
            if (spec.cacheProjectionScope() == null) {
                throw new IllegalArgumentException(
                        "Dynamic cache scope requires a bound invocation");
            }
            return spec.cacheProjectionScope() + configured.substring(template.length());
        }
        if (configured != null && configured.contains("{scope}")) {
            throw new IllegalArgumentException("Scope placeholder must be the cache scope segment");
        }
        return configured;
    }

    static void validateSources(WorkspaceSpec spec) throws IOException {
        Map<String, Path> roots = roots(spec);
        for (var root : roots.entrySet()) {
            Path source = root.getValue();
            Path subtree = source.resolve(root.getKey());
            Path current = source;
            for (String segment : root.getKey().split("/")) {
                current = current.resolve(segment);
                if (Files.isSymbolicLink(current)) {
                    throw new IOException("Symbolic link in synchronized cache: " + current);
                }
                if (Files.exists(current, LinkOption.NOFOLLOW_LINKS)
                        && (!Files.isDirectory(current, LinkOption.NOFOLLOW_LINKS)
                                || !current.toRealPath().equals(current))) {
                    throw new IOException("Unsafe synchronized cache directory: " + current);
                }
            }
            if (Files.exists(subtree, LinkOption.NOFOLLOW_LINKS)) {
                try (var walk = Files.walk(subtree)) {
                    var paths = walk.iterator();
                    while (paths.hasNext()) {
                        Path path = paths.next();
                        if (Files.isSymbolicLink(path) || !path.toRealPath().equals(path)) {
                            throw new IOException("Symbolic link in synchronized cache: " + path);
                        }
                    }
                }
            }
        }
    }

    static void reconcile(
            AbstractBaseSandbox sandbox,
            WorkspaceSpec spec,
            WorkspaceProjectionApplier.ProjectionPayload payload,
            String workspaceRoot)
            throws Exception {
        Map<String, Path> roots = roots(spec);
        if (roots.isEmpty()) {
            return;
        }
        // Validation carries no file manifest, and precedes all deletion.
        for (String root : roots.keySet()) {
            requireSuccess(execute(sandbox, workspaceRoot, root, Set.of(), false), root);
        }
        Set<String> files = new LinkedHashSet<>();
        try (TarArchiveInputStream tar =
                new TarArchiveInputStream(new ByteArrayInputStream(payload.tarBytes()))) {
            org.apache.commons.compress.archivers.tar.TarArchiveEntry entry;
            while ((entry = tar.getNextEntry()) != null) {
                files.add(entry.getName());
            }
        }
        // The caller's execution lease spans cleanup and hydrate.
        for (String root : roots.keySet()) {
            ExecResult deletion = execute(sandbox, workspaceRoot, root, files, true);
            requireSuccess(deletion, root);
        }
    }

    static void validateDestination(
            AbstractBaseSandbox sandbox, WorkspaceSpec spec, String workspaceRoot)
            throws Exception {
        for (String root : roots(spec).keySet()) {
            requireSuccess(execute(sandbox, workspaceRoot, root, Set.of(), false), root);
        }
    }

    private static void requireSuccess(ExecResult result, String root) throws IOException {
        if (!result.ok()
                || result.truncated()
                || result.stdout() == null
                || !"AGENTSCOPE_CACHE_RECONCILED".equals(result.stdout().strip())) {
            throw new IOException(
                    "Cache projection reconciliation failed for " + root + ": " + result.stderr());
        }
    }

    private static List<WorkspaceProjectionEntry> entries(WorkspaceSpec spec) {
        List<WorkspaceProjectionEntry> entries = new ArrayList<>();
        if (spec != null) {
            WorkspaceProjectionApplier.collectProjectionEntries(spec.getEntries(), entries);
        }
        return entries;
    }

    private static Map<String, Path> roots(WorkspaceSpec spec) throws IOException {
        Map<String, Path> roots = new LinkedHashMap<>();
        List<WorkspaceProjectionEntry> entries = entries(spec);
        for (WorkspaceProjectionEntry entry : entries) {
            for (String configured : entry.getSyncRoots()) {
                String root = resolveRoot(configured, spec);
                validateRoot(root);
                if (entry.getSourceRoot() == null || entry.getSourceRoot().isBlank()) {
                    throw new IOException("Cache synchronization requires a host workspace");
                }
                Path source = Path.of(entry.getSourceRoot()).toRealPath();
                if (!Files.isDirectory(source)) {
                    throw new IOException("Host workspace is not a directory: " + source);
                }
                boolean included = false;
                for (String include : entry.getIncludeRoots()) {
                    if (include == null || include.isBlank()) {
                        continue;
                    }
                    Path path = Path.of(include.replace('\\', '/'));
                    if (path.isAbsolute() || !source.resolve(path).normalize().startsWith(source)) {
                        throw new IOException(
                                "Projection include escapes host workspace: " + include);
                    }
                    String normalized = path.normalize().toString().replace('\\', '/');
                    included |=
                            normalized.isEmpty()
                                    || normalized.equals(".")
                                    || under(root, normalized);
                }
                if (!included) {
                    throw new IOException(
                            "Synchronized cache must be included in projection: " + root);
                }
                for (String mount : WorkspaceMountSupport.bindMountRelativePaths(spec)) {
                    String normalized = Path.of(mount).normalize().toString().replace('\\', '/');
                    if (normalized.isEmpty()
                            || normalized.equals(".")
                            || under(root, normalized)
                            || under(normalized, root)) {
                        throw new IOException("Synchronized cache overlaps bind mount: " + mount);
                    }
                }
                Path previous = roots.putIfAbsent(root, source);
                if (previous != null && !previous.equals(source)) {
                    throw new IOException("Conflicting synchronized cache sources: " + root);
                }
            }
        }
        // Another projection entry must not inject unvalidated files into an owned cache.
        for (var root : roots.entrySet()) {
            for (WorkspaceProjectionEntry entry : entries) {
                for (String include : entry.getIncludeRoots()) {
                    if (include == null || include.isBlank()) continue;
                    String normalized =
                            Path.of(include.replace('\\', '/'))
                                    .normalize()
                                    .toString()
                                    .replace('\\', '/');
                    if (normalized.isEmpty()
                            || normalized.equals(".")
                            || under(root.getKey(), normalized)
                            || under(normalized, root.getKey())) {
                        if (entry.getSourceRoot() == null
                                || !Path.of(entry.getSourceRoot())
                                        .toRealPath()
                                        .equals(root.getValue())) {
                            throw new IOException(
                                    "Conflicting projection into synchronized cache: "
                                            + root.getKey());
                        }
                    }
                }
            }
        }
        return roots;
    }

    private static void validateRoot(String root) {
        if (root == null
                || !root.startsWith(".skills-cache/")
                || root.indexOf('\\') >= 0
                || root.indexOf(':') >= 0
                || root.chars().anyMatch(Character::isISOControl)) {
            throw new IllegalArgumentException(
                    "Sync roots must be scoped .skills-cache subtrees: " + root);
        }
        for (String segment : root.split("/", -1)) {
            if (segment.isEmpty() || segment.equals(".") || segment.equals("..")) {
                throw new IllegalArgumentException("Unsafe cache sync root: " + root);
            }
        }
    }

    private static boolean under(String path, String root) {
        return path.equals(root) || path.startsWith(root + "/");
    }

    private static ExecResult execute(
            AbstractBaseSandbox sandbox,
            String workspace,
            String root,
            Set<String> files,
            boolean delete)
            throws Exception {
        byte[] script;
        try (var resource =
                SkillsCacheProjection.class.getResourceAsStream("skills_cache_reconcile.py")) {
            if (resource == null) throw new IOException("Cache reconciliation resource missing");
            script = resource.readAllBytes();
        }
        Map<String, Object> plan = new LinkedHashMap<>();
        plan.put("workspace", workspace);
        plan.put("root", root);
        plan.put("delete", delete);
        if (delete) {
            plan.put("files", files.stream().filter(path -> under(path, root)).toList());
        }
        byte[] json = new com.fasterxml.jackson.databind.ObjectMapper().writeValueAsBytes(plan);
        ByteArrayOutputStream input = new ByteArrayOutputStream();
        input.write((script.length + "\n").getBytes(StandardCharsets.US_ASCII));
        input.write(script);
        input.write(json);
        return sandbox.doExecWithInput(COMMAND, input.toByteArray(), 30);
    }
}
