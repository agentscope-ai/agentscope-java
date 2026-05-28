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
package io.agentscope.builder.web.workspace;

import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Objects;

/**
 * Resolves on-disk paths derived from the platform-wide shared workspace root, without composing
 * any filesystem instances. Owning the routing/composition responsibility is the harness; this
 * utility only exists so platform features that need a stable side-channel directory (notably the
 * per-user marketplaces git-clone cache and per-agent workspace scaffolding) can derive a
 * predictable path without reinventing the layout.
 */
public final class SharedWorkspacePaths {

    private final Path workspaceRoot;

    public SharedWorkspacePaths(Path workspaceRoot) {
        this.workspaceRoot = Objects.requireNonNull(workspaceRoot, "workspaceRoot");
    }

    /**
     * Returns the on-disk root directory under which all shared workspace content lives. Callers
     * should append their own segments rather than walking the filesystem directly.
     */
    public Path workspaceRoot() {
        return workspaceRoot;
    }

    /**
     * Resolves the user-supplied workspace path for an agent into an absolute on-disk data root.
     *
     * <ul>
     *   <li>If {@code workspacePath} is null or blank, the agent id is used in its place.
     *   <li>If the result is an absolute path, it is returned normalized.
     *   <li>If the result is a relative path that, when resolved against the current working
     *       directory, already lies under {@code ${cwd}/.agentscope/}, it is used as-is (so users
     *       who type {@code .agentscope/foo} are not double-prefixed).
     *   <li>Otherwise it is resolved against {@code ${cwd}/.agentscope/}.
     * </ul>
     */
    public Path resolveAgentDataPath(String workspacePath, String fallbackAgentId) {
        String raw =
                (workspacePath != null && !workspacePath.isBlank())
                        ? workspacePath.trim()
                        : fallbackAgentId;
        if (raw == null || raw.isBlank()) {
            throw new IllegalArgumentException(
                    "workspacePath and fallbackAgentId are both null/blank");
        }
        Path p = Paths.get(raw);
        if (p.isAbsolute()) {
            return p.normalize();
        }
        Path cwd = Paths.get(System.getProperty("user.dir")).toAbsolutePath().normalize();
        Path agentScopeBase = cwd.resolve(".agentscope").normalize();
        Path resolvedAgainstCwd = cwd.resolve(p).normalize();
        if (resolvedAgainstCwd.startsWith(agentScopeBase)) {
            return resolvedAgainstCwd;
        }
        return agentScopeBase.resolve(p).normalize();
    }
}
