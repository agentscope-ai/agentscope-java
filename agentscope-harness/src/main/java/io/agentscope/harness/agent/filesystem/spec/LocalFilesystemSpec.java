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

import io.agentscope.core.agent.RuntimeContext;
import io.agentscope.harness.agent.IsolationScope;
import io.agentscope.harness.agent.filesystem.AbstractFilesystem;
import io.agentscope.harness.agent.filesystem.CompositeFilesystem;
import io.agentscope.harness.agent.filesystem.OverlayFilesystem;
import io.agentscope.harness.agent.filesystem.ProjectAwareOverlay;
import io.agentscope.harness.agent.filesystem.local.LocalFilesystem;
import io.agentscope.harness.agent.filesystem.local.LocalFilesystemWithShell;
import io.agentscope.harness.agent.filesystem.model.ExecuteResponse;
import io.agentscope.harness.agent.filesystem.model.FileInfo;
import io.agentscope.harness.agent.filesystem.model.GlobResult;
import io.agentscope.harness.agent.filesystem.model.GrepMatch;
import io.agentscope.harness.agent.filesystem.model.GrepResult;
import io.agentscope.harness.agent.filesystem.model.LsResult;
import io.agentscope.harness.agent.filesystem.remote.store.NamespaceFactory;
import io.agentscope.harness.agent.filesystem.sandbox.AbstractSandboxFilesystem;
import io.agentscope.harness.agent.filesystem.util.SharedPrefixUtils;
import io.agentscope.harness.agent.workspace.LocalFsMode;
import io.agentscope.harness.agent.workspace.PathPolicy;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Specification for the local filesystem mode (with shell execution).
 *
 * <p>This spec produces a {@link LocalFilesystemWithShell} whose root is the agent workspace and
 * whose shell runs directly on the host as {@code sh -c <command>}. Long-term memory
 * ({@code MEMORY.md}, {@code memory/}) and session logs live on the same local disk.
 *
 * <p>Suitable for single-process deployments (personal assistants, CLI tools, local dev loops)
 * where distributed sharing is not required and the agent is trusted to run host shell commands.
 *
 * <p>For distributed deployments where long-term memory must be shared across replicas, prefer
 * {@link RemoteFilesystemSpec} (no shell) or a sandbox filesystem spec (shell via sandbox).
 */
public class LocalFilesystemSpec {

    private int executeTimeoutSeconds = LocalFilesystemWithShell.DEFAULT_EXECUTE_TIMEOUT;
    private int maxOutputBytes = 100_000;
    private final Map<String, String> env = new LinkedHashMap<>();
    private final Set<String> sharedPrefixes = new LinkedHashSet<>();
    private boolean inheritEnv = false;

    /**
     * Path-resolution policy for the upper {@link LocalFilesystemWithShell}. Defaults to
     * {@link LocalFsMode#ROOTED}, so absolute paths supplied by the agent are accepted only when
     * they fall under one of the configured roots (project + workspace + additionalRoots).
     */
    private LocalFsMode mode = LocalFsMode.ROOTED;

    private IsolationScope isolationScope;

    /**
     * User project root (lower layer of the resulting {@link OverlayFilesystem}). The agent reads
     * project-authored content (e.g. {@code AGENTS.md}, {@code knowledge/}, {@code skills/}) from
     * this directory and copies-on-write into the agent {@code workspace} when modified. Also
     * the shell {@code pwd} for {@code execute()} so command output matches user expectation.
     *
     * <p>{@code null} until {@link #project(Path)} is called; defaults to
     * {@link System#getProperty(String) System.getProperty("user.dir")} at
     * {@link #toFilesystem} time.
     */
    private Path project;

    /**
     * Extra host directories beyond {@code project} and {@code workspace} that the agent is
     * allowed to touch in {@link LocalFsMode#ROOTED} mode. Mirrors Claude Code CLI's
     * {@code --add-dir} flag.
     */
    private final List<Path> additionalRoots = new ArrayList<>();

    /**
     * When {@code true}, the agent's file-write operations for non-workspace paths (i.e. paths
     * that are not workspace metadata like {@code MEMORY.md}, {@code agents/}, {@code skills/})
     * are routed to the project directory instead of the workspace. Workspace metadata paths
     * continue to be written to the workspace.
     *
     * <p>Defaults to {@code false}, preserving the original overlay behaviour where all writes
     * land in the workspace.
     */
    private boolean projectWritable = false;

    /**
     * Sets the default command execution timeout in seconds.
     *
     * @param seconds timeout (must be positive)
     * @return this spec
     */
    public LocalFilesystemSpec executeTimeoutSeconds(int seconds) {
        if (seconds <= 0) {
            throw new IllegalArgumentException("timeout must be positive, got " + seconds);
        }
        this.executeTimeoutSeconds = seconds;
        return this;
    }

    /**
     * Sets the maximum number of output bytes captured from any single shell command.
     *
     * @param bytes byte cap (must be positive)
     * @return this spec
     */
    public LocalFilesystemSpec maxOutputBytes(int bytes) {
        if (bytes <= 0) {
            throw new IllegalArgumentException("maxOutputBytes must be positive, got " + bytes);
        }
        this.maxOutputBytes = bytes;
        return this;
    }

    /**
     * Adds an environment variable that will be set for every shell command.
     *
     * @param name variable name
     * @param value variable value
     * @return this spec
     */
    public LocalFilesystemSpec env(String name, String value) {
        if (name == null || name.isBlank()) {
            throw new IllegalArgumentException("env name must not be blank");
        }
        this.env.put(name, value);
        return this;
    }

    /**
     * Controls whether the parent process environment is inherited by shell commands. When
     * {@code false} (default), only variables added via {@link #env(String, String)} are visible.
     *
     * @param inherit whether to inherit parent env
     * @return this spec
     */
    public LocalFilesystemSpec inheritEnv(boolean inherit) {
        this.inheritEnv = inherit;
        return this;
    }

    /**
     * Legacy: {@code true} maps to {@link LocalFsMode#SANDBOXED}, {@code false} to
     * {@link LocalFsMode#UNRESTRICTED}. Prefer {@link #mode(LocalFsMode)} so {@link LocalFsMode#ROOTED}
     * is also reachable.
     *
     * @param virtual whether to enable virtual mode
     * @return this spec
     * @deprecated use {@link #mode(LocalFsMode)} for the full three-way selection
     */
    @Deprecated
    public LocalFilesystemSpec virtualMode(boolean virtual) {
        return mode(virtual ? LocalFsMode.SANDBOXED : LocalFsMode.UNRESTRICTED);
    }

    /**
     * Sets the path-resolution policy for the upper {@link LocalFilesystemWithShell}. Defaults
     * to {@link LocalFsMode#ROOTED}.
     *
     * @param mode policy mode
     * @return this spec
     */
    public LocalFilesystemSpec mode(LocalFsMode mode) {
        if (mode == null) {
            throw new IllegalArgumentException("mode must not be null");
        }
        this.mode = mode;
        return this;
    }

    /**
     * Sets the isolation scope controlling how file paths are namespaced per user, session, or
     * agent. Defaults to {@link IsolationScope#USER} (consistent with
     * {@link RemoteFilesystemSpec} and sandbox specs).
     *
     * @param scope isolation scope
     * @return this spec
     */
    public LocalFilesystemSpec isolationScope(IsolationScope scope) {
        this.isolationScope = scope;
        return this;
    }

    /** Returns the configured isolation scope, or {@code null} to use the default. */
    public IsolationScope getIsolationScope() {
        return isolationScope;
    }

    /**
     * Adds a workspace-relative prefix whose files are exposed as a shared, read-only baseline.
     * Per-user writes still land in the namespaced workspace upper layer and therefore override,
     * rather than mutate, the shared source files.
     *
     * <p>For example, {@code addSharedPrefix("docs/")} exposes {@code <workspace>/docs/} to every
     * user while a write by user {@code bob} lands under {@code <workspace>/bob/docs/}.
     *
     * @param prefix workspace-relative directory prefix
     * @return this spec
     */
    public LocalFilesystemSpec addSharedPrefix(String prefix) {
        if (prefix != null && !prefix.isBlank()) {
            sharedPrefixes.add(SharedPrefixUtils.normalizeDirectoryPrefix(prefix));
        }
        return this;
    }

    /** Replaces the configured shared, read-only workspace prefixes. */
    public LocalFilesystemSpec sharedPrefixes(Collection<String> prefixes) {
        sharedPrefixes.clear();
        if (prefixes != null) {
            for (String prefix : prefixes) {
                addSharedPrefix(prefix);
            }
        }
        return this;
    }

    /** Returns the normalized shared prefixes in registration order. */
    public Set<String> getSharedPrefixes() {
        return Collections.unmodifiableSet(new LinkedHashSet<>(sharedPrefixes));
    }

    /**
     * Adds an extra host directory the agent is allowed to access by absolute path under
     * {@link LocalFsMode#ROOTED}. {@code null} entries are ignored.
     *
     * @param root extra root to allow
     * @return this spec
     */
    public LocalFilesystemSpec addRoot(Path root) {
        if (root != null) {
            this.additionalRoots.add(root);
        }
        return this;
    }

    /**
     * Enables or disables project-writable mode. When {@code true}, the agent's file-write
     * operations for non-workspace paths are routed to the project directory.
     *
     * @param writable whether non-workspace writes go to the project directory
     * @return this spec
     */
    public LocalFilesystemSpec projectWritable(boolean writable) {
        this.projectWritable = writable;
        return this;
    }

    /** Returns whether project-writable mode is enabled. */
    public boolean isProjectWritable() {
        return projectWritable;
    }

    /**
     * Replaces the list of extra host directories. See {@link #addRoot(Path)}.
     *
     * @param roots extra roots ({@code null} clears)
     * @return this spec
     */
    public LocalFilesystemSpec additionalRoots(Collection<Path> roots) {
        this.additionalRoots.clear();
        if (roots != null) {
            for (Path r : roots) {
                if (r != null) {
                    this.additionalRoots.add(r);
                }
            }
        }
        return this;
    }

    /**
     * Sets the user project root used as the lower layer of the resulting
     * {@link OverlayFilesystem}. Reads of {@code AGENTS.md}, {@code knowledge/}, {@code skills/}
     * etc. fall back to this directory when the agent {@code workspace} does not contain them;
     * shell {@code execute()} runs with {@code pwd} set to this directory.
     *
     * <p>Defaults to {@code System.getProperty("user.dir")} when not set.
     *
     * @param project project root path
     * @return this spec
     */
    public LocalFilesystemSpec project(Path project) {
        this.project = project;
        return this;
    }

    /**
     * Builds the effective filesystem as an {@link OverlayFilesystem} with the agent
     * {@code workspace} as the upper (read-write, shell host) layer and the user
     * {@link #project(Path)} as the read-only lower layer. Writes always land in
     * {@code workspace}; reads check {@code workspace} first then fall back to {@code project},
     * giving copy-on-write semantics for files that originate in the project tree.
     *
     * @param workspace agent workspace root (becomes overlay upper)
     * @param localNamespaceFactory optional namespace factory for per-user/session folder scoping
     * @return an {@link OverlayFilesystem} wired with the options in this spec
     */
    /** Project root explicitly configured, or {@code null} to fall back to {@code ${user.dir}}. */
    public Path getProject() {
        return project;
    }

    /** Currently configured path-resolution policy mode. */
    public LocalFsMode getMode() {
        return mode;
    }

    /** Snapshot of configured extra roots. */
    public List<Path> getAdditionalRoots() {
        return List.copyOf(additionalRoots);
    }

    public AbstractFilesystem toFilesystem(Path workspace, NamespaceFactory localNamespaceFactory) {
        Path effectiveProject =
                project != null ? project : Paths.get(System.getProperty("user.dir"));
        List<Path> policyRoots = new ArrayList<>();
        policyRoots.add(effectiveProject);
        policyRoots.add(workspace);
        policyRoots.addAll(additionalRoots);
        PathPolicy pathPolicy = PathPolicy.of(policyRoots);
        LocalFilesystemWithShell upper =
                new LocalFilesystemWithShell(
                        workspace,
                        mode,
                        pathPolicy,
                        executeTimeoutSeconds,
                        maxOutputBytes,
                        env.isEmpty() ? null : Map.copyOf(env),
                        inheritEnv,
                        localNamespaceFactory,
                        effectiveProject);
        AbstractFilesystem lower = new LocalFilesystem(effectiveProject, true, 10, null);
        AbstractFilesystem defaultFilesystem;
        if (projectWritable) {
            LocalFilesystem projectFs =
                    new LocalFilesystem(
                            effectiveProject, mode, pathPolicy, 10, localNamespaceFactory);
            defaultFilesystem =
                    new ProjectAwareOverlay(
                            (AbstractSandboxFilesystem) upper, lower, projectFs, workspace);
        } else {
            defaultFilesystem = OverlayFilesystem.of(upper, lower);
        }

        if (sharedPrefixes.isEmpty()) {
            return defaultFilesystem;
        }

        Map<String, AbstractFilesystem> routes = new LinkedHashMap<>();
        for (String prefix : sharedPrefixes) {
            String routeSegment = SharedPrefixUtils.routeSegment(prefix);
            routes.put(
                    prefix,
                    localOverlayRoute(workspace, routeSegment, localNamespaceFactory, pathPolicy));
        }
        return new LocalCompositeFilesystem(
                defaultFilesystem, routes, (AbstractSandboxFilesystem) defaultFilesystem);
    }

    /**
     * Builds the copy-on-write view for one shared prefix. The upper store is rooted at the main
     * workspace and receives a tenant namespace plus {@code routeSegment}, so every tenant writes
     * to its own override directory. The lower store is rooted directly at the un-namespaced
     * shared directory and therefore acts as the common read-only baseline. {@link
     * OverlayFilesystem} resolves reads from upper to lower while routing all mutations to upper.
     */
    private static OverlayFilesystem localOverlayRoute(
            Path workspace,
            String routeSegment,
            NamespaceFactory localNamespaceFactory,
            PathPolicy pathPolicy) {
        NamespaceFactory routeNamespace =
                rc -> {
                    List<String> namespace =
                            localNamespaceFactory != null
                                    ? new ArrayList<>(localNamespaceFactory.getNamespace(rc))
                                    : new ArrayList<>();
                    // USER/SESSION normally provide a namespace. AGENT/GLOBAL (and missing
                    // runtime identity) do not, so keep their writable overlay in a hidden
                    // directory rather than writing into the shared source itself.
                    if (namespace.isEmpty()) {
                        namespace.add(".shared-overrides");
                    }
                    for (String segment : routeSegment.split("/")) {
                        if (!segment.isBlank()) {
                            namespace.add(segment);
                        }
                    }
                    return namespace;
                };
        LocalFilesystem upper = new RouteLocalFilesystem(workspace, pathPolicy, routeNamespace);
        Path sharedRoot = workspace.resolve(routeSegment);
        LocalFilesystem lower =
                new RouteLocalFilesystem(sharedRoot, PathPolicy.of(List.of(sharedRoot)), null);
        return new LocalRouteOverlay(upper, lower);
    }

    /**
     * CompositeFilesystem supplies route-local paths with a leading slash. Strip it inside the
     * Local implementation so LocalFilesystem applies the tenant namespace instead of treating
     * the path as an already-scoped host absolute path.
     */
    private static final class RouteLocalFilesystem extends LocalFilesystem {

        private RouteLocalFilesystem(
                Path root, PathPolicy pathPolicy, NamespaceFactory namespaceFactory) {
            super(root, LocalFsMode.ROOTED, pathPolicy, 10, namespaceFactory);
        }

        @Override
        protected Path resolvePath(RuntimeContext runtimeContext, String path) {
            return super.resolvePath(runtimeContext, relativeRoutePath(path));
        }
    }

    /**
     * Copy-on-write overlay for a single shared route.
     *
     * <p>The delegate {@link LocalFilesystem} instances report paths relative to their physical
     * roots. Before those results return to {@link CompositeFilesystem}, this adapter converts
     * them to route-local absolute paths (for example, {@code /guide.md}). The composite layer can
     * then prepend the registered prefix exactly once, without exposing tenant namespace segments
     * or duplicating the shared prefix in {@code ls}, {@code grep}, and {@code glob} results.
     */
    private static final class LocalRouteOverlay extends OverlayFilesystem {

        /**
         * Creates a route-local CoW view.
         *
         * @param upper tenant-namespaced writable overrides for this shared prefix
         * @param lower un-namespaced shared baseline used only when upper has no override
         */
        private LocalRouteOverlay(AbstractFilesystem upper, AbstractFilesystem lower) {
            super(upper, lower);
        }

        @Override
        public LsResult ls(RuntimeContext runtimeContext, String path) {
            LsResult result = super.ls(runtimeContext, path);
            if (!result.isSuccess() || result.entries() == null) {
                return result;
            }
            return LsResult.success(result.entries().stream().map(this::routeFileInfo).toList());
        }

        @Override
        public GrepResult grep(
                RuntimeContext runtimeContext, String pattern, String path, String glob) {
            GrepResult result = super.grep(runtimeContext, pattern, path, glob);
            if (!result.isSuccess() || result.matches() == null) {
                return result;
            }
            return GrepResult.success(
                    result.matches().stream()
                            .map(
                                    match ->
                                            new GrepMatch(
                                                    routeAbsolutePath(match.path()),
                                                    match.line(),
                                                    match.text()))
                            .toList());
        }

        @Override
        public GlobResult glob(RuntimeContext runtimeContext, String pattern, String path) {
            GlobResult result = super.glob(runtimeContext, pattern, path);
            if (!result.isSuccess() || result.matches() == null) {
                return result;
            }
            return GlobResult.success(result.matches().stream().map(this::routeFileInfo).toList());
        }

        private FileInfo routeFileInfo(FileInfo file) {
            return new FileInfo(
                    routeAbsolutePath(file.path()),
                    file.isDirectory(),
                    file.size(),
                    file.modifiedAt());
        }
    }

    /** Keeps LocalFilesystemSpec's existing shell capability outside the routed file view. */
    private static final class LocalCompositeFilesystem extends CompositeFilesystem
            implements AbstractSandboxFilesystem {

        private final AbstractSandboxFilesystem shell;

        private LocalCompositeFilesystem(
                AbstractFilesystem defaultFilesystem,
                Map<String, AbstractFilesystem> routes,
                AbstractSandboxFilesystem shell) {
            super(defaultFilesystem, routes);
            this.shell = shell;
        }

        @Override
        public String id() {
            return shell.id();
        }

        @Override
        public ExecuteResponse execute(
                RuntimeContext runtimeContext, String command, Integer timeoutSeconds) {
            return shell.execute(runtimeContext, command, timeoutSeconds);
        }
    }

    private static String relativeRoutePath(String path) {
        if (path == null) {
            return null;
        }
        String normalized = path.replace('\\', '/');
        int first = 0;
        while (first < normalized.length() && normalized.charAt(first) == '/') {
            first++;
        }
        String relative = normalized.substring(first);
        return relative.isEmpty() ? "." : relative;
    }

    private static String routeAbsolutePath(String path) {
        String relative = relativeRoutePath(path);
        return ".".equals(relative) ? "/" : "/" + relative;
    }
}
