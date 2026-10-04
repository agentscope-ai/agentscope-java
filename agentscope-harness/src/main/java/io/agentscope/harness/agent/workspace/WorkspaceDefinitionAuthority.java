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
package io.agentscope.harness.agent.workspace;

import io.agentscope.core.agent.RuntimeContext;
import io.agentscope.harness.agent.filesystem.AbstractFilesystem;
import io.agentscope.harness.agent.filesystem.RoutedSandboxFilesystem;
import io.agentscope.harness.agent.filesystem.local.LocalFilesystem;
import io.agentscope.harness.agent.filesystem.model.FileInfo;
import io.agentscope.harness.agent.filesystem.model.GlobResult;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Selects the host authority for configured projection roots, without changing tool I/O. */
public final class WorkspaceDefinitionAuthority {
    private final List<String> roots;
    private final AbstractFilesystem host;
    private final AbstractFilesystem runtime;

    public WorkspaceDefinitionAuthority(
            Path workspace, List<String> roots, AbstractFilesystem runtime) {
        this.roots =
                roots.stream()
                        .filter(
                                p ->
                                        p != null
                                                && !p.isBlank()
                                                && !Path.of(p.replace('\\', '/')).isAbsolute())
                        .map(WorkspaceDefinitionAuthority::normalize)
                        .filter(p -> !p.isEmpty())
                        .distinct()
                        .toList();
        this.host = new LocalFilesystem(workspace, true, 10);
        this.runtime = runtime;
    }

    public boolean owns(String path) {
        String normalized = normalize(path);
        if (normalized.isEmpty()) return false;
        if (runtime instanceof RoutedSandboxFilesystem routed
                && routed.backendFor(normalized) != routed.primary()) return false;
        return roots.stream()
                .anyMatch(
                        root ->
                                root.equals(".")
                                        || normalized.equals(root)
                                        || normalized.startsWith(root + "/"));
    }

    public AbstractFilesystem readFilesystem(String path, AbstractFilesystem fallback) {
        return owns(path) ? host : fallback;
    }

    /** Excludes sandbox-only entries beneath owned roots, including partial directory projections. */
    public GlobResult glob(
            RuntimeContext rc, AbstractFilesystem fallback, String pattern, String path) {
        Map<String, FileInfo> matches = new LinkedHashMap<>();
        // A fully owned directory needs no sandbox enumeration unless explicit routes exist.
        if (!owns(path) || runtime instanceof RoutedSandboxFilesystem) {
            GlobResult sandbox = fallback.glob(rc, pattern, path);
            if (sandbox.isSuccess() && sandbox.matches() != null)
                for (FileInfo file : sandbox.matches()) {
                    if (!owns(file.path())) matches.put(normalize(file.path()), file);
                }
        }
        GlobResult local = host.glob(rc, pattern, path);
        if (local.isSuccess() && local.matches() != null)
            for (FileInfo file : local.matches()) {
                if (owns(file.path())) matches.put(normalize(file.path()), file);
            }
        return GlobResult.success(List.copyOf(matches.values()));
    }

    private static String normalize(String path) {
        if (path == null || path.isBlank()) return "";
        String relative = path.replace('\\', '/').replaceFirst("^/+", "");
        Path normalized = Path.of(relative).normalize();
        if (normalized.isAbsolute() || normalized.startsWith("..")) return "";
        String result = normalized.toString().replace('\\', '/');
        return result.isEmpty() ? "." : result;
    }
}
