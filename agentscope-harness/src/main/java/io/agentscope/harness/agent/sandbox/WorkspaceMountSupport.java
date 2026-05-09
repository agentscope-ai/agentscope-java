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

import io.agentscope.harness.agent.sandbox.layout.BindMountEntry;
import io.agentscope.harness.agent.sandbox.layout.DirEntry;
import io.agentscope.harness.agent.sandbox.layout.WorkspaceEntry;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Helpers for {@link io.agentscope.harness.agent.sandbox.layout.BindMountEntry} handling. */
public final class WorkspaceMountSupport {

    private WorkspaceMountSupport() {}

    /**
     * Returns relative workspace paths (POSIX-style, no leading slash) for every bind mount in
     * the spec tree, in stable iteration order.
     */
    public static List<String> bindMountRelativePaths(WorkspaceSpec spec) {
        if (spec == null || spec.getEntries().isEmpty()) {
            return List.of();
        }
        List<String> out = new ArrayList<>();
        walk(spec.getEntries(), "", out);
        return out;
    }

    public static boolean hasBindMounts(WorkspaceSpec spec) {
        return !bindMountRelativePaths(spec).isEmpty();
    }

    /**
     * Absolute mount path inside the sandbox for a top-level entry key (slashes normalized).
     */
    public static String containerMountPath(String workspaceRoot, String entryKey) {
        String root =
                workspaceRoot.endsWith("/")
                        ? workspaceRoot.substring(0, workspaceRoot.length() - 1)
                        : workspaceRoot;
        String key = entryKey.startsWith("/") ? entryKey.substring(1) : entryKey;
        key = key.replace('\\', '/');
        return root + "/" + key;
    }

    /**
     * {@code tar} CLI {@code --exclude} arguments ({@code ./relative}) for each bind mount path,
     * excluding snapshot tar from traversing mount content.
     */
    public static List<String> tarExcludeArgsForBindMounts(WorkspaceSpec spec) {
        List<String> args = new ArrayList<>();
        for (String rel : bindMountRelativePaths(spec)) {
            String normalized = rel.replace('\\', '/');
            if (normalized.isEmpty()) {
                continue;
            }
            args.add("--exclude=./" + normalized);
        }
        return args;
    }

    private static void walk(Map<String, WorkspaceEntry> entries, String prefix, List<String> out) {
        for (Map.Entry<String, WorkspaceEntry> e : entries.entrySet()) {
            String rel =
                    prefix.isEmpty()
                            ? e.getKey().replace('\\', '/')
                            : prefix + "/" + e.getKey().replace('\\', '/');
            WorkspaceEntry v = e.getValue();
            if (v instanceof BindMountEntry) {
                out.add(rel);
            } else if (v instanceof DirEntry dir
                    && dir.getChildren() != null
                    && !dir.getChildren().isEmpty()) {
                walk(dir.getChildren(), rel, out);
            }
        }
    }

    /**
     * Top-level {@link BindMountEntry} instances keyed by entry key, for backends that only apply
     * flat manifest entries (Docker {@code -v}, Kubernetes HostPath).
     */
    public static Map<String, BindMountEntry> topLevelBindMounts(WorkspaceSpec spec) {
        Map<String, BindMountEntry> m = new LinkedHashMap<>();
        if (spec == null) {
            return m;
        }
        for (Map.Entry<String, WorkspaceEntry> e : spec.getEntries().entrySet()) {
            if (e.getValue() instanceof BindMountEntry b) {
                m.put(e.getKey(), b);
            }
        }
        return m;
    }

    /** Normalized absolute host path for Docker / Kubernetes HostPath. */
    public static String normalizedHostPath(String hostPath) {
        if (hostPath == null || hostPath.isBlank()) {
            return "";
        }
        return Path.of(hostPath).toAbsolutePath().normalize().toString();
    }
}
