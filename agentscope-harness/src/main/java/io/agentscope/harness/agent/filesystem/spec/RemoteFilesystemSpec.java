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
package io.agentscope.harness.agent.filesystem.spec;

import io.agentscope.harness.agent.IsolationScope;
import io.agentscope.harness.agent.filesystem.AbstractFilesystem;
import io.agentscope.harness.agent.filesystem.CompositeFilesystem;
import io.agentscope.harness.agent.filesystem.OverlayFilesystem;
import io.agentscope.harness.agent.filesystem.local.LocalFilesystem;
import io.agentscope.harness.agent.filesystem.local.LocalFilesystemWithShell;
import io.agentscope.harness.agent.filesystem.remote.RemoteFilesystem;
import io.agentscope.harness.agent.filesystem.remote.store.BaseStore;
import io.agentscope.harness.agent.filesystem.remote.store.NamespaceFactory;
import io.agentscope.harness.agent.workspace.WorkspaceIndex;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Specification for the non-sandbox "composite" filesystem mode.
 *
 * <p>This spec produces a {@link CompositeFilesystem} that blends:
 *
 * <ul>
 *   <li>a plain {@link LocalFilesystem} (no shell) for workspace-local, unmanaged files;
 *   <li>per-route {@link RemoteFilesystem} instances for cross-node paths (memory, skills,
 *       subagents, knowledge, sessions, tasks). Each route gets its own store namespace
 *       segment to prevent key collisions across routes.
 * </ul>
 *
 * <p>Because the default backend is {@link LocalFilesystem} (not {@link LocalFilesystemWithShell}),
 * shell execution is intentionally not available in this mode — use a sandbox filesystem spec or
 * {@link LocalFilesystemWithShell} if shell is required.
 *
 * <p>Default shared routes (each gets an isolated store namespace segment):
 *
 * <ul>
 *   <li>{@code AGENTS.md}, {@code MEMORY.md} → segment {@code root}
 *   <li>{@code memory/} → segment {@code memory}
 *   <li>{@code skills/} → segment {@code skills}
 *   <li>{@code subagents/} → segment {@code subagents}
 *   <li>{@code knowledge/} → segment {@code knowledge}
 *   <li>{@code plans/} → segment {@code plans}
 *   <li>{@code agents/<agentId>/sessions/} → segment {@code sessions}
 *   <li>{@code agents/<agentId>/tasks/} → segment {@code tasks}
 * </ul>
 *
 * <p>The store namespace for shared files is controlled by {@link #isolationScope(IsolationScope)},
 * which mirrors the sandbox isolation semantics:
 *
 * <ul>
 *   <li>{@link IsolationScope#SESSION} — namespace per session</li>
 *   <li>{@link IsolationScope#USER} (default) — namespace per user, shared across sessions</li>
 *   <li>{@link IsolationScope#AGENT} — namespace per agent, shared across all users</li>
 *   <li>{@link IsolationScope#GLOBAL} — single global namespace</li>
 * </ul>
 */
public class RemoteFilesystemSpec {

    private BaseStore store;
    private final Set<String> extraSharedPrefixes = new LinkedHashSet<>();
    private String anonymousUserId = "_default";
    private IsolationScope isolationScope = IsolationScope.USER;
    private WorkspaceIndex workspaceIndex = null;

    /**
     * Creates a remote filesystem spec that defers store resolution to
     * {@link io.agentscope.harness.agent.DistributedStore#baseStore()}.
     *
     * <p>When used with {@code HarnessAgent.builder().distributedStore(store)},
     * the store is injected automatically during build. Without a distributed store,
     * an {@link IllegalStateException} is thrown at build time.
     */
    public RemoteFilesystemSpec() {
        this.store = null;
    }

    public RemoteFilesystemSpec(BaseStore store) {
        if (store == null) {
            throw new IllegalArgumentException("store must not be null");
        }
        this.store = store;
    }

    /**
     * Returns whether the store has been set (either via constructor or injection).
     */
    public boolean hasStore() {
        return store != null;
    }

    /**
     * Injects the store if not already set. Called by the builder during auto-wiring.
     */
    public void injectStoreIfAbsent(BaseStore store) {
        if (this.store == null) {
            this.store = store;
        }
    }

    /**
     * Adds an extra workspace-relative prefix routed to the shared store.
     *
     * <p>Examples: {@code knowledge/}, {@code prompts/}.
     */
    public RemoteFilesystemSpec addSharedPrefix(String prefix) {
        if (prefix != null && !prefix.isBlank()) {
            extraSharedPrefixes.add(normalizePrefix(prefix));
        }
        return this;
    }

    /**
     * Sets the fallback user identifier when runtime {@code userId} is absent/blank.
     */
    public RemoteFilesystemSpec anonymousUserId(String userId) {
        if (userId == null || userId.isBlank()) {
            throw new IllegalArgumentException("anonymous user id must not be blank");
        }
        this.anonymousUserId = userId;
        return this;
    }

    /**
     * Sets the isolation scope that controls the store namespace for shared files.
     *
     * <p>Mirrors the sandbox {@link io.agentscope.harness.agent.sandbox.SandboxContext} isolation
     * semantics. Defaults to {@link IsolationScope#USER}.
     *
     * @param scope isolation scope
     * @return this spec
     */
    public RemoteFilesystemSpec isolationScope(IsolationScope scope) {
        if (scope == null) {
            throw new IllegalArgumentException("isolation scope must not be null");
        }
        this.isolationScope = scope;
        return this;
    }

    public IsolationScope getIsolationScope() {
        return isolationScope;
    }

    /**
     * Sets the workspace index for accelerating remote filesystem reads (ls/glob/exists/grep).
     * If not set, the remote filesystem falls back to full store scans.
     */
    public RemoteFilesystemSpec workspaceIndex(WorkspaceIndex index) {
        this.workspaceIndex = index;
        return this;
    }

    /**
     *
     * <ul>
     *   <li>default backend: {@link LocalFilesystem} (no shell), per-user namespaced
     *   <li>shared <b>prefix</b> routes ({@code memory/}, {@code skills/}, {@code subagents/},
     *       {@code knowledge/}, {@code plans/}, {@code agents/<id>/sessions/},
     *       {@code agents/<id>/tasks/}, plus
     *       any {@code addSharedPrefix} extras): wrapped in an {@link OverlayFilesystem} where
     *       the <em>upper</em> layer is the {@link RemoteFilesystem} (per-user, persisted in the
     *       {@link BaseStore}) and the <em>lower</em> layer is a read-only {@link LocalFilesystem}
     *       rooted at {@code workspace.resolve(<routeDir>)}. So scaffolded template content under
     *       {@code <workspace>/skills/}, {@code <workspace>/subagents/}, etc. is visible as a
     *       baseline; per-user edits land in the remote store via copy-on-write and override the
     *       template on subsequent reads.
     *   <li>{@code AGENTS.md}, {@code MEMORY.md}, {@code tools.json} exact-file routes: wrapped
     *       in an {@link OverlayFilesystem} where the <em>upper</em> is the {@code root}-segment
     *       {@link RemoteFilesystem} and the <em>lower</em> is a read-only {@link LocalFilesystem}
     *       rooted at the workspace, so the scaffolded template files at the workspace root are
     *       visible as the baseline. {@link CompositeFilesystem} does not recurse into exact-file
     *       routes when listing/globbing the tree; it performs a single {@code exists} check
     *       against the overlay, which is satisfied by either layer.
     * </ul>
     */
    public AbstractFilesystem toFilesystem(
            Path workspace, String agentId, NamespaceFactory localNamespaceFactory) {
        if (store == null) {
            throw new IllegalStateException(
                    "RemoteFilesystemSpec has no BaseStore. Either pass one via the constructor"
                            + " or configure a DistributedStore on the HarnessAgent builder.");
        }
        String effectiveAgentId = agentId == null || agentId.isBlank() ? "HarnessAgent" : agentId;
        AbstractFilesystem local = new LocalFilesystem(workspace, false, 10, localNamespaceFactory);

        // Read-only workspace-root template view for the exact-file overlays below. The lower
        // technically exposes the entire workspace, but CompositeFilesystem does not recurse into
        // exact-file routes (it does single-key exists/read), so the over-exposure is unreachable.
        LocalFilesystem workspaceTemplate = new LocalFilesystem(workspace, true, 10, null);

        Map<String, AbstractFilesystem> routes = new LinkedHashMap<>();
        routes.put("AGENTS.md", exactFileOverlay("root", effectiveAgentId, workspaceTemplate));
        routes.put("MEMORY.md", exactFileOverlay("root", effectiveAgentId, workspaceTemplate));
        routes.put("tools.json", exactFileOverlay("root", effectiveAgentId, workspaceTemplate));
        routes.put(
                "memory/", overlayRoute(workspace.resolve("memory"), "memory", effectiveAgentId));
        routes.put(
                "skills/", overlayRoute(workspace.resolve("skills"), "skills", effectiveAgentId));
        routes.put(
                "subagents/",
                overlayRoute(workspace.resolve("subagents"), "subagents", effectiveAgentId));
        routes.put(
                "knowledge/",
                overlayRoute(workspace.resolve("knowledge"), "knowledge", effectiveAgentId));
        routes.put("plans/", overlayRoute(workspace.resolve("plans"), "plans", effectiveAgentId));
        routes.put(
                "large_tool_results/",
                overlayRoute(
                        workspace.resolve("large_tool_results"),
                        "large_tool_results",
                        effectiveAgentId));
        routes.put(
                "agents/" + effectiveAgentId + "/sessions/",
                overlayRoute(
                        workspace.resolve("agents").resolve(effectiveAgentId).resolve("sessions"),
                        "sessions",
                        effectiveAgentId));
        routes.put(
                "agents/" + effectiveAgentId + "/tasks/",
                overlayRoute(
                        workspace.resolve("agents").resolve(effectiveAgentId).resolve("tasks"),
                        "tasks",
                        effectiveAgentId));
        for (String extra : extraSharedPrefixes) {
            String segment = routeSegmentFromPrefix(extra);
            routes.put(extra, overlayRoute(workspace.resolve(segment), segment, effectiveAgentId));
        }
        return new CompositeFilesystem(local, routes);
    }

    /**
     * Builds an {@link OverlayFilesystem} for a workspace-prefix route. The upper layer is the
     * per-user {@link RemoteFilesystem} backed by {@link BaseStore}; the lower layer is a read-only
     * {@link LocalFilesystem} rooted at {@code localTemplateDir} so scaffolded template content is
     * visible as the baseline. {@code virtualMode=true} on the lower so it reports paths anchored
     * to its own root, which is what {@link CompositeFilesystem}'s route remapping expects.
     */
    private OverlayFilesystem overlayRoute(
            Path localTemplateDir, String routeSegment, String agentId) {
        RemoteFilesystem upper = remoteForRoute(routeSegment, agentId);
        LocalFilesystem lower = new LocalFilesystem(localTemplateDir, true, 10, null);
        return new OverlayFilesystem(upper, lower);
    }

    /**
     * Builds an {@link OverlayFilesystem} for an exact-file route (e.g. {@code AGENTS.md}).
     * The upper layer is the per-user {@link RemoteFilesystem} on the {@code root} namespace
     * segment; the lower layer is the shared workspace-root {@link LocalFilesystem} so the
     * scaffolded template file ({@code workspace/<filename>}) is visible as the baseline.
     */
    private OverlayFilesystem exactFileOverlay(
            String routeSegment, String agentId, LocalFilesystem workspaceTemplate) {
        RemoteFilesystem upper = remoteForRoute(routeSegment, agentId);
        return new OverlayFilesystem(upper, workspaceTemplate);
    }

    private RemoteFilesystem remoteForRoute(String routeSegment, String agentId) {
        NamespaceFactory base = storeNamespace(agentId);
        NamespaceFactory extended =
                rc -> {
                    List<String> ns = new ArrayList<>(base.getNamespace(rc));
                    ns.add(routeSegment);
                    return ns;
                };
        return new RemoteFilesystem(store, extended).withIndex(workspaceIndex);
    }

    private static String routeSegmentFromPrefix(String normalizedPrefix) {
        String segment = normalizedPrefix;
        while (segment.endsWith("/")) {
            segment = segment.substring(0, segment.length() - 1);
        }
        return segment.isEmpty() ? "extra" : segment;
    }

    private NamespaceFactory storeNamespace(String agentId) {
        return rc -> {
            String uid = rc != null ? rc.getUserId() : null;
            String sid = rc != null ? rc.getSessionId() : null;

            return switch (isolationScope) {
                case SESSION -> {
                    String effectiveSid = (sid != null && !sid.isBlank()) ? sid : "default";
                    yield List.of("agents", agentId, "sessions", effectiveSid);
                }
                case USER -> {
                    String effectiveUid = (uid != null && !uid.isBlank()) ? uid : anonymousUserId;
                    yield List.of("agents", agentId, "users", effectiveUid);
                }
                case AGENT -> List.of("agents", agentId, "shared");
                case GLOBAL -> List.of("global");
            };
        };
    }

    private static String normalizePrefix(String prefix) {
        String normalized = prefix.replace('\\', '/').strip();
        while (normalized.startsWith("/")) {
            normalized = normalized.substring(1);
        }
        return normalized;
    }
}
