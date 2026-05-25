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
package io.agentscope.dataagent.web.workspace;

import io.agentscope.harness.agent.filesystem.AbstractFilesystem;
import io.agentscope.harness.agent.sandbox.Sandbox;
import io.agentscope.harness.agent.workspace.WorkspaceManager;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Objects;

/**
 * Builds {@link WorkspaceManager} instances whose filesystem is backed by a per-{@code (userId,
 * agentId)} live {@link Sandbox} from {@link UserSandboxRegistry}.
 *
 * <p>The same {@link Sandbox} instance backs both the agent runtime (via {@code
 * SandboxContext.externalSandbox} injected by the HarnessGateway) and every browser controller's
 * workspace operation. This is what makes the workspace user-isolated: each user gets their own
 * container; there is no path-prefix namespace and no shared {@code LocalFilesystem} layer that
 * would otherwise leak content across tenants.
 *
 * <p>The {@link Path} returned by {@link WorkspaceManager#getWorkspace()} is a host-side label
 * (used for display, audit, and {@link RuntimeContext} metadata), <em>not</em> the on-disk
 * location of the file data. Actual reads/writes go through {@link SharedSandboxFilesystem} into
 * the container at {@code /workspace}.
 */
public final class WorkspaceManagerFactory {

    private final UserSandboxRegistry registry;

    public WorkspaceManagerFactory(UserSandboxRegistry registry) {
        this.registry = Objects.requireNonNull(registry, "registry");
    }

    /**
     * Returns a {@link WorkspaceManager} for a user-scoped agent. Equivalent to
     * {@link #forAgent(String, String, String)} with {@code workspacePath=null}.
     */
    public WorkspaceManager forAgent(String ownerId, String agentId) {
        return forAgent(ownerId, agentId, null);
    }

    /**
     * Returns a {@link WorkspaceManager} for a user-scoped agent. Borrows (and starts on first
     * use) the per-{@code (ownerId, agentId)} sandbox; the returned {@link WorkspaceManager} reads
     * and writes through that sandbox via {@link SharedSandboxFilesystem}.
     */
    public WorkspaceManager forAgent(String ownerId, String agentId, String workspacePath) {
        validateSegment("ownerId", ownerId);
        validateSegment("agentId", agentId);
        Sandbox sb = registry.borrow(ownerId, agentId);
        Path dataPath = resolveAgentDataPath(workspacePath, agentId);
        return new WorkspaceManager(dataPath, new SharedSandboxFilesystem(sb));
    }

    /**
     * Returns a {@link WorkspaceManager} for a global agent accessed by a specific user.
     * Equivalent to {@link #forAgent(String, String, String)} — once the filesystem layer is
     * sandbox-backed, the global/user distinction disappears because the sandbox itself is keyed
     * by {@code (userId, agentId)}. Kept as a separate entry point for call-site readability.
     */
    public WorkspaceManager forGlobalAgent(String userId, String agentId) {
        return forAgent(userId, agentId, null);
    }

    /** See {@link #forGlobalAgent(String, String)}. */
    public WorkspaceManager forGlobalAgent(String userId, String agentId, String workspacePath) {
        return forAgent(userId, agentId, workspacePath);
    }

    /**
     * Returns the raw per-{@code (userId, agentId)} {@link AbstractFilesystem} without the
     * {@link WorkspaceManager} wrapper, suitable for callers that need to enumerate / copy files
     * inside the user's isolated workspace (notably the audit/activity log and
     * {@link io.agentscope.dataagent.web.util.WorkspaceCopier}).
     */
    public AbstractFilesystem userDataFs(String ownerId, String agentId, String workspacePath) {
        validateSegment("ownerId", ownerId);
        validateSegment("agentId", agentId);
        return new SharedSandboxFilesystem(registry.borrow(ownerId, agentId));
    }

    /**
     * Path prefix under which {@link #userDataFs(String, String, String)} reports file paths.
     * With sandbox-backed isolation the entire container belongs to one user, so the prefix is
     * just the container root ({@code "/"}). Kept on the API surface to avoid forcing call-site
     * changes during the migration.
     */
    public String userDataPathPrefix(String ownerId, String agentId, String workspacePath) {
        validateSegment("ownerId", ownerId);
        validateSegment("agentId", agentId);
        return "/";
    }

    /**
     * Resolves the user-supplied workspace path for an agent into an absolute host-side data
     * root, mirroring the pre-sandbox behaviour so {@link WorkspaceManager#getWorkspace()} keeps
     * returning the same labels (audit logs, UI display).
     *
     * <ul>
     *   <li>If {@code workspacePath} is null or blank, {@code fallbackAgentId} is used in its
     *       place.
     *   <li>Absolute paths are returned normalized.
     *   <li>Relative paths that already start under {@code ${cwd}/.agentscope/} are used as-is.
     *   <li>Other relative paths are resolved against {@code ${cwd}/.agentscope/}.
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

    private static void validateSegment(String label, String value) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(label + " must not be null or blank");
        }
        if (value.contains("/") || value.contains("\\") || value.contains("..")) {
            throw new IllegalArgumentException(
                    label + " must not contain path separators or '..': " + value);
        }
    }
}
