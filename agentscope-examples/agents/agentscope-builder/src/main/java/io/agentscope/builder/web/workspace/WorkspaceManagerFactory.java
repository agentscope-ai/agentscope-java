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

import io.agentscope.harness.agent.filesystem.AbstractFilesystem;
import io.agentscope.harness.agent.filesystem.CompositeFilesystem;
import io.agentscope.harness.agent.filesystem.OverlayFilesystem;
import io.agentscope.harness.agent.filesystem.local.LocalFilesystem;
import io.agentscope.harness.agent.filesystem.remote.RemoteFilesystem;
import io.agentscope.harness.agent.store.BaseStore;
import io.agentscope.harness.agent.store.NamespaceFactory;
import io.agentscope.harness.agent.workspace.WorkspaceManager;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * Builds {@link WorkspaceManager} instances that use {@link CompositeFilesystem} routing to blend
 * a shared workspace with per-(owner, agent) {@link RemoteFilesystem} routes.
 *
 * <p>Shared content (AGENTS.md, knowledge/, default skills/ + subagents/ shipped on disk) lives at
 * the local workspace root and is visible to all users. Per-user runtime data ({@code memory/},
 * {@code MEMORY.md}, {@code sessions/}, {@code tasks/}) is routed to a namespace-isolated {@link
 * RemoteFilesystem} backed by the shared {@link BaseStore}, so writes are visible across pods.
 *
 * <p>For user-scoped agents, {@code skills/} and {@code subagents/} use an {@link
 * OverlayFilesystem} whose upper layer is the per-(owner, agent) {@code RemoteFilesystem} and
 * lower layer is the shared on-disk content — enabling per-user customization without modifying
 * shared definitions.
 *
 * <p>For global agents, {@code skills/} and {@code subagents/} are purely shared (admin-managed);
 * only runtime data is user-isolated.
 *
 * <p>Builder requires a {@link BaseStore}; this class does not provide a fallback. Construction is
 * cheap; callers may invoke {@link #forAgent(String, String)} or {@link #forGlobalAgent(String,
 * String)} per request without caching.
 */
public final class WorkspaceManagerFactory {

    private final Path workspaceRoot;
    private final int maxFileSizeMb;
    private final BaseStore remoteStore;

    public WorkspaceManagerFactory(Path workspaceRoot, int maxFileSizeMb, BaseStore remoteStore) {
        this.workspaceRoot = Objects.requireNonNull(workspaceRoot, "workspaceRoot");
        this.maxFileSizeMb = maxFileSizeMb;
        this.remoteStore = Objects.requireNonNull(remoteStore, "remoteStore");
    }

    /**
     * Returns a {@link WorkspaceManager} for a user-scoped agent using the default per-agent data
     * root (resolved from {@code agentId} alone). Equivalent to {@link #forAgent(String, String,
     * String)} with a null {@code workspacePath}.
     */
    public WorkspaceManager forAgent(String ownerId, String agentId) {
        return forAgent(ownerId, agentId, null);
    }

    /**
     * Returns a {@link WorkspaceManager} for a user-scoped agent. {@code memory/}, {@code MEMORY.md},
     * {@code sessions/} and {@code tasks/} are routed to per-(owner, agent) {@link RemoteFilesystem}
     * routes; {@code skills/} and {@code subagents/} use {@link OverlayFilesystem} with the remote
     * route as the upper layer and the shared on-disk content as the lower layer.
     */
    public WorkspaceManager forAgent(String ownerId, String agentId, String workspacePath) {
        validateSegment("ownerId", ownerId);
        validateSegment("agentId", agentId);

        Path dataPath = resolveAgentDataPath(workspacePath, agentId);

        LocalFilesystem sharedFs = new LocalFilesystem(workspaceRoot, true, maxFileSizeMb, null);

        Map<String, AbstractFilesystem> routes = new LinkedHashMap<>();
        routes.put("memory/", remoteRoute(ownerId, agentId, "memory"));
        routes.put("MEMORY.md", remoteRoute(ownerId, agentId, "memory"));
        routes.put("sessions/", remoteRoute(ownerId, agentId, "sessions"));
        routes.put("tasks/", remoteRoute(ownerId, agentId, "tasks"));
        routes.put("skills/", buildSkillsOverlay(ownerId, agentId, "skills"));
        routes.put("subagents/", buildSkillsOverlay(ownerId, agentId, "subagents"));

        CompositeFilesystem composite = new CompositeFilesystem(sharedFs, routes);
        return new WorkspaceManager(dataPath, composite);
    }

    /**
     * Returns a {@link WorkspaceManager} for a global agent using the default per-agent data root.
     * Equivalent to {@link #forGlobalAgent(String, String, String)} with null {@code workspacePath}.
     */
    public WorkspaceManager forGlobalAgent(String userId, String agentId) {
        return forGlobalAgent(userId, agentId, null);
    }

    /**
     * Returns a {@link WorkspaceManager} for a global agent accessed by a specific user. Skills and
     * subagents are purely shared (admin-managed); only {@code memory/}/{@code sessions/}/
     * {@code tasks/} are routed to the per-(user, agent) {@link RemoteFilesystem}.
     */
    public WorkspaceManager forGlobalAgent(String userId, String agentId, String workspacePath) {
        validateSegment("userId", userId);
        validateSegment("agentId", agentId);

        Path dataPath = resolveAgentDataPath(workspacePath, agentId);

        LocalFilesystem sharedFs = new LocalFilesystem(workspaceRoot, true, maxFileSizeMb, null);

        Map<String, AbstractFilesystem> routes = new LinkedHashMap<>();
        routes.put("memory/", remoteRoute(userId, agentId, "memory"));
        routes.put("MEMORY.md", remoteRoute(userId, agentId, "memory"));
        routes.put("sessions/", remoteRoute(userId, agentId, "sessions"));
        routes.put("tasks/", remoteRoute(userId, agentId, "tasks"));

        CompositeFilesystem composite = new CompositeFilesystem(sharedFs, routes);
        return new WorkspaceManager(dataPath, composite);
    }

    /**
     * Returns only the per-user data filesystem layer (no shared overlay), suitable for callers
     * that must write/read inside the user-isolated subtree without touching shared content —
     * notably the activity log (which would otherwise route to the shared default backend and leak
     * across tenants) and clone source/target traversal.
     *
     * <p>The returned filesystem is a {@link RemoteFilesystem} namespaced to {@code [users,
     * ownerId, agents, agentId]}. Paths reported by the filesystem are prefixed with that
     * namespace.
     */
    public AbstractFilesystem userDataFs(String ownerId, String agentId, String workspacePath) {
        validateSegment("ownerId", ownerId);
        validateSegment("agentId", agentId);
        return new RemoteFilesystem(remoteStore, namespaceFor(ownerId, agentId));
    }

    /**
     * Returns the absolute path prefix under which {@link #userDataFs(String, String, String)}
     * reports file paths — the namespace prefix of the per-(owner, agent) {@link RemoteFilesystem}.
     */
    public String userDataPathPrefix(String ownerId, String agentId, String workspacePath) {
        validateSegment("ownerId", ownerId);
        validateSegment("agentId", agentId);
        return "/" + String.join("/", namespaceFor(ownerId, agentId).getNamespace());
    }

    /**
     * Returns a {@code NamespaceFactory} that emits the canonical {@code [users, ownerId, agents,
     * agentId]} tuple.
     */
    public NamespaceFactory namespaceFor(String ownerId, String agentId) {
        validateSegment("ownerId", ownerId);
        validateSegment("agentId", agentId);
        List<String> ns = List.of("users", ownerId, "agents", agentId);
        return () -> ns;
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

    /**
     * Returns the on-disk path that corresponds to the given agent's user-isolated data using the
     * default convention (no user-supplied workspace path). Equivalent to
     * {@link #resolveAgentDataPath(String, String)} with a null {@code workspacePath}.
     */
    public Path localWorkspacePath(String ownerId, String agentId) {
        validateSegment("ownerId", ownerId);
        validateSegment("agentId", agentId);
        return resolveAgentDataPath(null, agentId);
    }

    /**
     * Builds a per-(owner, agent) {@link RemoteFilesystem} for a single composite route. The
     * namespace is {@code [users, ownerId, agents, agentId, routeSegment]} so each route is
     * isolated from the others and from other (owner, agent) pairs.
     */
    private RemoteFilesystem remoteRoute(String ownerId, String agentId, String routeSegment) {
        NamespaceFactory base = namespaceFor(ownerId, agentId);
        NamespaceFactory extended =
                () -> {
                    List<String> ns = new ArrayList<>(base.getNamespace());
                    ns.add(routeSegment);
                    return ns;
                };
        return new RemoteFilesystem(remoteStore, extended);
    }

    /**
     * Builds the {@code skills/} or {@code subagents/} overlay: per-(owner, agent) {@link
     * RemoteFilesystem} on top, shared on-disk {@link LocalFilesystem} (read-through) below.
     */
    private OverlayFilesystem buildSkillsOverlay(String ownerId, String agentId, String prefix) {
        AbstractFilesystem upper = remoteRoute(ownerId, agentId, prefix);
        List<String> ns = List.of(prefix);
        LocalFilesystem lowerFs = new LocalFilesystem(workspaceRoot, true, maxFileSizeMb, () -> ns);
        return new OverlayFilesystem(upper, lowerFs);
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
