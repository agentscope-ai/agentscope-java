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
import io.agentscope.harness.agent.store.BaseStore;
import io.agentscope.harness.agent.store.NamespaceFactory;
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

    private final BaseStore store;
    private final Set<String> extraSharedPrefixes = new LinkedHashSet<>();
    private String anonymousUserId = "_default";
    private IsolationScope isolationScope = IsolationScope.USER;
    private WorkspaceIndex workspaceIndex = null;

    public RemoteFilesystemSpec(BaseStore store) {
        if (store == null) {
            throw new IllegalArgumentException("store must not be null");
        }
        this.store = store;
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
     *       {@code knowledge/}, {@code agents/<id>/sessions/}, {@code agents/<id>/tasks/}, plus
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
        // agentId 兜底值
        String effectiveAgentId = agentId == null || agentId.isBlank() ? "HarnessAgent" : agentId;
        // 默认层：本地文件系统，负责未被路由覆盖的路径（无 shell）
        AbstractFilesystem local = new LocalFilesystem(workspace, false, 10, localNamespaceFactory);

        // 只读的 workspace 根目录模板视图（用于精确文件路由的下层基线）
        // 虽然技术上暴露了整个 workspace，但 CompositeFilesystem 对精确文件路由
        // 只做单 key 的 exists/read 操作，不会递归遍历，所以过曝是不可达的
        LocalFilesystem workspaceTemplate = new LocalFilesystem(workspace, true, 10, null);

        // 按路径前缀分发到不同的 OverlayFilesystem
        // 每个路由 = OverlayFilesystem(上层: RemoteFilesystem可写, 下层: LocalFilesystem只读模板)
        Map<String, AbstractFilesystem> routes = new LinkedHashMap<>();

        // ── 精确文件路由（不以 / 结尾）─────────────────────────────
        // CompositeFilesystem 只对单个文件名做 exists/read，不递归
        routes.put("AGENTS.md", exactFileOverlay("root", effectiveAgentId, workspaceTemplate));
        routes.put("MEMORY.md", exactFileOverlay("root", effectiveAgentId, workspaceTemplate));
        routes.put("tools.json", exactFileOverlay("root", effectiveAgentId, workspaceTemplate));

        // ── 目录前缀路由（以 / 结尾，递归匹配子路径）─────────────────
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
        // sessions: 会话 JSONL 归档，每次对话独立
        routes.put(
                "agents/" + effectiveAgentId + "/sessions/",
                overlayRoute(
                        workspace.resolve("agents").resolve(effectiveAgentId).resolve("sessions"),
                        "sessions",
                        effectiveAgentId));
        // tasks: PlanNotebook 任务持久化
        routes.put(
                "agents/" + effectiveAgentId + "/tasks/",
                overlayRoute(
                        workspace.resolve("agents").resolve(effectiveAgentId).resolve("tasks"),
                        "tasks",
                        effectiveAgentId));
        // 用户通过 addSharedPrefix() 注册的额外共享路径
        for (String extra : extraSharedPrefixes) {
            String segment = routeSegmentFromPrefix(extra);
            routes.put(extra, overlayRoute(workspace.resolve(segment), segment, effectiveAgentId));
        }
        return new CompositeFilesystem(local, routes);
    }

    /**
     * 目录路由：构建 OverlayFilesystem(上层 Remote + 下层只读 Local)。
     *
     * <p>上层负责持久化（写入远程 BaseStore，按用户/session 命名空间隔离），
     * 下层是本地模板目录（只读基线）。读取时优先查上层，没有则回退到下层模板。
     */
    private OverlayFilesystem overlayRoute(
            Path localTemplateDir, String routeSegment, String agentId) {
        RemoteFilesystem upper = remoteForRoute(routeSegment, agentId);
        LocalFilesystem lower = new LocalFilesystem(localTemplateDir, true, 10, null);
        return new OverlayFilesystem(upper, lower);
    }

    /**
     * 精确文件路由：同 overlayRoute，但下层用的是 workspace 根目录的模板视图。
     * 用于 AGENTS.md、MEMORY.md、tools.json 这类不在子目录中的单文件。
     */
    private OverlayFilesystem exactFileOverlay(
            String routeSegment, String agentId, LocalFilesystem workspaceTemplate) {
        RemoteFilesystem upper = remoteForRoute(routeSegment, agentId);
        return new OverlayFilesystem(upper, workspaceTemplate);
    }

    /**
     * 为指定路由段创建 RemoteFilesystem，命名空间 = 基础命名空间 + 路由段。
     * 例如 USER 模式 + routeSegment="skills" → ["agents", "assistant", "users", "bob", "skills"]
     */
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
