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
package io.agentscope.harness.agent.tools;

import io.agentscope.core.agent.RuntimeContext;
import io.agentscope.core.tool.Toolkit;
import io.agentscope.core.tool.mcp.McpClientWrapper;
import io.agentscope.harness.agent.workspace.WorkspaceManager;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.regex.Pattern;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Runtime MCP tool manager: a build-time baseline plus per-{@link RuntimeContext} server sets.
 *
 * <p>The baseline is the declarative MCP configuration of the workspace ({@code mcp.json} and
 * {@code tools.json#mcpServers}), registered into the agent's shared toolkit at build time exactly
 * as before. This manager adds the two capabilities that a shared toolkit cannot express:
 *
 * <ul>
 *   <li><b>Runtime changes</b> — {@link #register}, {@link #unregister}, {@link #reload} and
 *       {@link #reloadAll} add, remove, or refresh servers without rebuilding the agent. A scope's
 *       changes are persisted to {@code mcp/<scope>.json} in the workspace, so they survive
 *       restarts, while {@code mcp.json} stays the declarative baseline.
 *   <li><b>Context isolation</b> — every context gets its own server set. A context with no
 *       runtime servers resolves to the shared toolkit; one with runtime servers gets a toolkit
 *       copy that carries only that context's additional MCP tools. One user's servers are never
 *       visible to, or executable by, another user.
 * </ul>
 *
 * <p>Scopes are keyed by {@code userId} when present, then {@code sessionId}, then a shared
 * {@code default} scope. Runtime servers are additive: a baseline server name cannot be
 * re-registered in a scope, because the baseline toolkit is shared by every context.
 *
 * <p>Mutations are serialized per scope. A scope toolkit is rebuilt in place (clients of replaced
 * or removed servers are closed), so a mutation that races an in-flight call can fail that call's
 * MCP invocation — mutations are administrative operations, not part of the hot path.
 *
 * <p>Thread-safe. Attach it to the agent through {@code HarnessAgent#mcpTools()}; the per-call
 * toolkit is installed with {@code ReActAgent.Builder#toolkitForContext}.
 */
public final class McpToolManager implements AutoCloseable {

    private static final Logger log = LoggerFactory.getLogger(McpToolManager.class);

    /** Declarative workspace MCP configuration, read at build time. */
    public static final String MCP_JSON = "mcp.json";

    /** Directory holding the per-scope runtime overrides. */
    public static final String MCP_DIR = "mcp";

    private static final String DEFAULT_SCOPE = "default";
    private static final int MAX_SCOPE_LENGTH = 48;
    private static final Pattern UNSAFE_SCOPE_CHARS = Pattern.compile("[^A-Za-z0-9._-]");

    /** Creates the MCP client for one server entry; overridable for tests. */
    @FunctionalInterface
    interface ClientProvider {
        McpClientWrapper create(String name, McpServerConfig config);
    }

    /** One context's runtime state. Guarded by its own monitor. */
    private static final class Scope {
        private final String key;
        private final Map<String, McpServerConfig> servers = new LinkedHashMap<>();
        private Toolkit toolkit;
        private boolean loaded;

        private Scope(String key) {
            this.key = key;
        }
    }

    private final Toolkit baseline;
    private final Map<String, McpServerConfig> baselineServers;
    private final WorkspaceManager workspace;
    private final McpServerRegistrationListener listener;
    private final ClientProvider clientProvider;
    private final ConcurrentMap<String, Scope> scopes = new ConcurrentHashMap<>();
    private final AtomicBoolean closed = new AtomicBoolean();

    private McpToolManager(
            Toolkit baseline,
            Map<String, McpServerConfig> baselineServers,
            WorkspaceManager workspace,
            McpServerRegistrationListener listener,
            ClientProvider clientProvider) {
        this.baseline = baseline;
        this.baselineServers =
                baselineServers == null ? Map.of() : Collections.unmodifiableMap(baselineServers);
        this.workspace = workspace;
        this.listener = listener;
        this.clientProvider = clientProvider;
    }

    /**
     * Creates a manager for an agent whose shared toolkit already carries the baseline MCP
     * servers.
     *
     * @param baselineToolkit the agent's own toolkit; scoped toolkits are copies of it
     * @param baselineServers baseline server names (declarative configuration), may be null
     * @param workspace workspace used to persist runtime changes, may be null (no persistence)
     * @param listener optional registration listener, may be null
     * @return a manager; callers own its lifecycle
     */
    public static McpToolManager create(
            Toolkit baselineToolkit,
            Map<String, McpServerConfig> baselineServers,
            WorkspaceManager workspace,
            McpServerRegistrationListener listener) {
        return create(
                baselineToolkit,
                baselineServers,
                workspace,
                listener,
                (name, config) -> McpServerRegistrar.buildClient(name, config));
    }

    static McpToolManager create(
            Toolkit baselineToolkit,
            Map<String, McpServerConfig> baselineServers,
            WorkspaceManager workspace,
            McpServerRegistrationListener listener,
            ClientProvider clientProvider) {
        return new McpToolManager(
                Objects.requireNonNull(baselineToolkit, "baselineToolkit"),
                baselineServers,
                workspace,
                listener,
                Objects.requireNonNull(clientProvider, "clientProvider"));
    }

    /**
     * Returns the toolkit to use for one call: the shared baseline when the context has no runtime
     * servers, otherwise that context's toolkit (a copy of the baseline plus its own servers).
     *
     * <p>Persisted runtime servers are loaded on first use of a context.
     */
    public Toolkit toolkitFor(RuntimeContext context) {
        Scope scope = scopes.computeIfAbsent(scopeKey(context), Scope::new);
        synchronized (scope) {
            ensureLoaded(scope);
            return scope.toolkit != null ? scope.toolkit : baseline;
        }
    }

    /**
     * Returns the servers visible to one context: the baseline configuration plus that context's
     * runtime servers. The returned map is an immutable snapshot.
     */
    public Map<String, McpServerConfig> servers(RuntimeContext context) {
        Scope scope = scopes.computeIfAbsent(scopeKey(context), Scope::new);
        synchronized (scope) {
            ensureLoaded(scope);
            Map<String, McpServerConfig> all = new LinkedHashMap<>(baselineServers);
            all.putAll(scope.servers);
            return Collections.unmodifiableMap(all);
        }
    }

    /**
     * Registers (or replaces) one runtime MCP server for one context and persists the change.
     *
     * @throws IllegalArgumentException when the name is blank or shadows a baseline server
     * @throws IllegalStateException when the manager is closed
     * @throws RuntimeException when the server cannot be connected; scope state is unchanged
     */
    public void register(RuntimeContext context, String name, McpServerConfig config) {
        requireOpen();
        if (name == null || name.isBlank()) {
            throw new IllegalArgumentException("MCP server name must not be blank.");
        }
        if (config == null) {
            throw new IllegalArgumentException("MCP server config must not be null.");
        }
        if (baselineServers.containsKey(name)) {
            throw new IllegalArgumentException(
                    "MCP server '"
                            + name
                            + "' is part of the baseline configuration and cannot be re-registered"
                            + " for a single context.");
        }
        Scope scope = scopes.computeIfAbsent(scopeKey(context), Scope::new);
        synchronized (scope) {
            ensureLoaded(scope);
            removeLocked(scope, name);
            registerLocked(scope, name, config);
            persist(scope);
        }
    }

    /**
     * Removes one runtime MCP server from one context and persists the change. Baseline servers
     * cannot be removed per context.
     *
     * @return {@code true} when a runtime server was removed
     */
    public boolean unregister(RuntimeContext context, String name) {
        requireOpen();
        if (baselineServers.containsKey(name)) {
            throw new IllegalArgumentException(
                    "MCP server '" + name + "' belongs to the baseline configuration.");
        }
        Scope scope = scopes.computeIfAbsent(scopeKey(context), Scope::new);
        synchronized (scope) {
            ensureLoaded(scope);
            boolean removed = removeLocked(scope, name);
            if (removed) {
                persist(scope);
            }
            return removed;
        }
    }

    /**
     * Reconnects one runtime MCP server for one context, picking up changed behavior on the
     * server side without rebuilding the agent.
     *
     * @throws IllegalStateException when the context has no such runtime server
     */
    public void reload(RuntimeContext context, String name) {
        requireOpen();
        Scope scope = scopes.computeIfAbsent(scopeKey(context), Scope::new);
        synchronized (scope) {
            ensureLoaded(scope);
            McpServerConfig config = scope.servers.get(name);
            if (config == null) {
                throw new IllegalStateException(
                        "No runtime MCP server named '"
                                + name
                                + "' in this context (baseline servers are not reloadable"
                                + " per context).");
            }
            removeLocked(scope, name);
            registerLocked(scope, name, config);
        }
    }

    /**
     * Reconnects every runtime MCP server of one context.
     *
     * @return the number of servers reloaded
     */
    public int reloadAll(RuntimeContext context) {
        requireOpen();
        Scope scope = scopes.computeIfAbsent(scopeKey(context), Scope::new);
        synchronized (scope) {
            ensureLoaded(scope);
            Map<String, McpServerConfig> snapshot = new LinkedHashMap<>(scope.servers);
            int reloaded = 0;
            for (Map.Entry<String, McpServerConfig> entry : snapshot.entrySet()) {
                removeLocked(scope, entry.getKey());
                registerLocked(scope, entry.getKey(), entry.getValue());
                reloaded++;
            }
            return reloaded;
        }
    }

    /** Server names of the baseline configuration, in declaration order. */
    public Set<String> baselineServerNames() {
        return baselineServers.keySet();
    }

    /**
     * Loads the declarative workspace baseline at {@code mcp.json} (same schema as {@code
     * tools.json#mcpServers}). Returns an empty map when the file is absent or unreadable.
     */
    public static Map<String, McpServerConfig> loadDeclarative(WorkspaceManager workspace) {
        return McpConfigFile.loadDeclarative(workspace);
    }

    /** Releases the MCP clients owned by every scope. The baseline toolkit is left untouched. */
    @Override
    public void close() {
        if (!closed.compareAndSet(false, true)) {
            return;
        }
        for (Scope scope : scopes.values()) {
            synchronized (scope) {
                if (scope.toolkit != null) {
                    try {
                        scope.toolkit.closeMcpClients();
                    } catch (RuntimeException e) {
                        log.warn("Failed to close MCP clients of scope '{}'.", scope.key, e);
                    }
                    scope.toolkit = null;
                }
                scope.servers.clear();
            }
        }
        scopes.clear();
    }

    // ==================== internals ====================

    private void registerLocked(Scope scope, String name, McpServerConfig config) {
        if (scope.toolkit == null) {
            scope.toolkit = baseline.copy();
        }
        try {
            McpServerRegistrar.registerClient(
                    scope.toolkit, name, config, clientProvider.create(name, config));
        } catch (RuntimeException | Error failure) {
            if (scope.servers.isEmpty()) {
                scope.toolkit = null;
            }
            throw failure;
        }
        scope.servers.put(name, config);
        log.info("Registered runtime MCP server '{}' for scope '{}'.", name, scope.key);
    }

    private boolean removeLocked(Scope scope, String name) {
        if (!scope.servers.containsKey(name)) {
            return false;
        }
        if (scope.toolkit != null) {
            try {
                scope.toolkit.removeMcpClient(name).block();
            } catch (RuntimeException e) {
                log.warn("Failed to remove MCP client '{}' of scope '{}'.", name, scope.key, e);
            }
        }
        scope.servers.remove(name);
        if (scope.servers.isEmpty() && scope.toolkit != null) {
            try {
                scope.toolkit.closeMcpClients();
            } catch (RuntimeException e) {
                log.warn("Failed to close MCP clients of scope '{}'.", scope.key, e);
            }
            scope.toolkit = null;
        }
        log.info("Removed runtime MCP server '{}' from scope '{}'.", name, scope.key);
        return true;
    }

    /** Loads persisted runtime servers for a scope exactly once. */
    private void ensureLoaded(Scope scope) {
        if (scope.loaded) {
            return;
        }
        scope.loaded = true;
        for (Map.Entry<String, McpServerConfig> entry :
                McpConfigFile.load(workspace, persistedPath(scope.key)).entrySet()) {
            String name = entry.getKey();
            if (baselineServers.containsKey(name)) {
                log.warn(
                        "Ignoring persisted MCP server '{}' for scope '{}': the name belongs to"
                                + " the baseline configuration.",
                        name,
                        scope.key);
                continue;
            }
            try {
                registerLocked(scope, name, entry.getValue());
            } catch (RuntimeException e) {
                log.warn(
                        "Failed to load persisted MCP server '{}' for scope '{}': {}",
                        name,
                        scope.key,
                        e.getMessage());
            }
        }
    }

    private void persist(Scope scope) {
        if (workspace == null) {
            return;
        }
        try {
            workspace.writeUtf8WorkspaceRelative(
                    RuntimeContext.empty(),
                    persistedPath(scope.key),
                    McpConfigFile.toJson(scope.servers));
        } catch (RuntimeException e) {
            log.warn("Failed to persist MCP servers of scope '{}'.", scope.key, e);
        }
    }

    private static String persistedPath(String scopeKey) {
        return MCP_DIR + "/" + scopeKey + ".json";
    }

    private void requireOpen() {
        if (closed.get()) {
            throw new IllegalStateException("MCP tool manager is closed.");
        }
    }

    /** Resolves the isolation key: user first, then session, then the shared default scope. */
    static String scopeKey(RuntimeContext context) {
        if (context == null) {
            return DEFAULT_SCOPE;
        }
        String userId = context.getUserId();
        if (userId != null && !userId.isBlank()) {
            return sanitize("user-" + userId);
        }
        String sessionId = context.getSessionId();
        if (sessionId != null && !sessionId.isBlank()) {
            return sanitize("session-" + sessionId);
        }
        return DEFAULT_SCOPE;
    }

    private static String sanitize(String raw) {
        String safe = UNSAFE_SCOPE_CHARS.matcher(raw).replaceAll("_");
        if (safe.length() > MAX_SCOPE_LENGTH) {
            safe = safe.substring(0, MAX_SCOPE_LENGTH);
        }
        return safe.equals(raw) ? safe : safe + "-" + Integer.toHexString(raw.hashCode());
    }
}
