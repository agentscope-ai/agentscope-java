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
package io.agentscope.harness.agent.filesystem;

import io.agentscope.harness.agent.IsolationScope;
import io.agentscope.harness.agent.store.BaseStore;
import io.agentscope.harness.agent.store.NamespaceFactory;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Supplier;

/**
 * Specification for the non-sandbox "composite" filesystem mode.
 *
 * <p>This spec produces a {@link CompositeFilesystem} that blends:
 *
 * <ul>
 *   <li>a plain {@link LocalFilesystem} (no shell) for workspace-local, ephemeral files such as
 *       skills/, knowledge/, additional context files, etc.;
 *   <li>a shared {@link RemoteFilesystem} for cross-node paths that must be identical across all
 *       replicas (long-term memory, offloaded session logs).
 * </ul>
 *
 * <p>Because the default backend is {@link LocalFilesystem} (not {@link LocalFilesystemWithShell}),
 * shell execution is intentionally not available in this mode — use a sandbox filesystem spec or
 * {@link LocalFilesystemWithShell} if shell is required.
 *
 * <p>Default shared routes:
 *
 * <ul>
 *   <li>{@code MEMORY.md}
 *   <li>{@code memory/}
 *   <li>{@code agents/<agentId>/sessions/}
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
     * Builds the effective filesystem:
     *
     * <ul>
     *   <li>default backend: {@link LocalFilesystem} (no shell)
     *   <li>shared routes: {@link RemoteFilesystem} with scope-driven namespace
     * </ul>
     */
    public AbstractFilesystem toFilesystem(
            Path workspace,
            String agentId,
            NamespaceFactory localNamespaceFactory,
            Supplier<String> userIdSupplier,
            Supplier<String> sessionIdSupplier) {
        String effectiveAgentId = agentId == null || agentId.isBlank() ? "HarnessAgent" : agentId;
        AbstractFilesystem local = new LocalFilesystem(workspace, false, 10, localNamespaceFactory);
        RemoteFilesystem shared =
                new RemoteFilesystem(
                        store, storeNamespace(effectiveAgentId, userIdSupplier, sessionIdSupplier));

        Map<String, AbstractFilesystem> routes = new LinkedHashMap<>();
        routes.put("MEMORY.md", shared);
        routes.put("memory/", shared);
        routes.put("agents/" + effectiveAgentId + "/sessions/", shared);
        for (String extra : extraSharedPrefixes) {
            routes.put(extra, shared);
        }
        return new CompositeFilesystem(local, routes);
    }

    /**
     * Builds the effective filesystem without session-level isolation.
     *
     * <p>Convenience overload for callers that don't provide a session-id supplier.
     * Equivalent to calling {@link #toFilesystem(Path, String, NamespaceFactory, Supplier, Supplier)}
     * with {@code () -> null} as the sessionIdSupplier.
     */
    public AbstractFilesystem toFilesystem(
            Path workspace,
            String agentId,
            NamespaceFactory localNamespaceFactory,
            Supplier<String> userIdSupplier) {
        return toFilesystem(workspace, agentId, localNamespaceFactory, userIdSupplier, () -> null);
    }

    private NamespaceFactory storeNamespace(
            String agentId, Supplier<String> userIdSupplier, Supplier<String> sessionIdSupplier) {
        return () -> {
            String uid = userIdSupplier != null ? userIdSupplier.get() : null;
            String sid = sessionIdSupplier != null ? sessionIdSupplier.get() : null;

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
