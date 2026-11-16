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

import io.agentscope.core.agent.RuntimeContext;
import io.agentscope.harness.agent.sandbox.snapshot.SandboxSnapshot;
import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.TreeSet;
import java.util.concurrent.atomic.AtomicBoolean;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Abstract base implementation of {@link Sandbox} with the 4-branch workspace start logic.
 *
 * <h2>4-Branch Start Logic</h2>
 * <pre>
 * Branch A: workspaceRootReady=true  &amp; workspace dir exists   → apply ephemeral-only entries
 * Branch B: workspaceRootReady=true  &amp; workspace dir missing  → restore from snapshot + ephemeral entries
 * Branch C: workspaceRootReady=false &amp; snapshot is restorable → hydrate from snapshot + all entries
 * Branch D: workspaceRootReady=false &amp; no restorable snapshot → fresh init from full workspace spec
 * </pre>
 *
 * <p>Subclasses implement the backend-specific operations:
 * <ul>
 *   <li>{@link #doExec(RuntimeContext, String, int)} — execute a shell command in the workspace</li>
 *   <li>{@link #doPersistWorkspace()} — create a tar archive of the workspace</li>
 *   <li>{@link #doHydrateWorkspace(InputStream)} — extract a tar archive into the workspace</li>
 *   <li>{@link #doSetupWorkspace()} — create the workspace root directory</li>
 *   <li>{@link #doDestroyWorkspace()} — delete the workspace root directory (on shutdown)</li>
 *   <li>{@link #getWorkspaceRoot()} — return the workspace root path string</li>
 * </ul>
 */
public abstract class AbstractBaseSandbox implements Sandbox {

    private static final Logger log = LoggerFactory.getLogger(AbstractBaseSandbox.class);

    /** Default timeout in seconds for workspace probing commands. */
    private static final int PROBE_TIMEOUT_SECONDS = 10;

    /** Max projected paths revoked per delete command, keeping commands within length limits. */
    private static final int DELETE_CHUNK_SIZE = 50;

    private final SandboxState state;
    private final WorkspaceSpecApplier workspaceSpecApplier;
    private final AtomicBoolean running = new AtomicBoolean(false);

    protected AbstractBaseSandbox(SandboxState state) {
        this.state = state;
        this.workspaceSpecApplier = new WorkspaceSpecApplier(state.getWorkspaceSpec().getRoot());
    }

    /**
     * Executes the 4-branch workspace start logic.
     *
     * @throws Exception if the workspace fails to start
     */
    @Override
    public void start() throws Exception {
        WorkspaceSpec spec = state.getWorkspaceSpec();
        SandboxSnapshot snapshot = state.getSnapshot();
        boolean restoredFromSnapshot = false;

        try {
            if (state.isWorkspaceRootReady()) {
                // Workspace was ready at last stop — check if it still exists
                boolean stillExists = probeWorkspaceRootForPreservedResume();
                if (stillExists) {
                    // Branch A: workspace preserved — only apply ephemeral entries
                    log.debug(
                            "[sandbox] Branch A: workspace preserved, applying ephemeral entries");
                    workspaceSpecApplier.applyWorkspaceSpec(spec, true);
                } else {
                    // Branch B: workspace was lost — restore from snapshot + ephemeral entries
                    log.debug("[sandbox] Branch B: workspace lost, restoring from snapshot");
                    Optional<InputStream> archive = openSnapshotArchive(snapshot);
                    if (archive.isPresent()) {
                        restoredFromSnapshot = true;
                        try (InputStream stream = archive.get()) {
                            doSetupWorkspace();
                            doHydrateWorkspace(stream);
                        }
                        workspaceSpecApplier.applyWorkspaceSpec(spec, true);
                    } else {
                        // Degrade to Branch D: no usable snapshot
                        log.warn("[sandbox] Branch B degraded to D: snapshot not restorable");
                        doSetupWorkspace();
                        workspaceSpecApplier.applyWorkspaceSpec(spec, false);
                    }
                }
            } else {
                // Workspace was not ready at last stop
                Optional<InputStream> archive = openSnapshotArchive(snapshot);
                if (archive.isPresent()) {
                    // Branch C: restore from snapshot + all spec entries
                    log.debug("[sandbox] Branch C: restoring from snapshot");
                    restoredFromSnapshot = true;
                    try (InputStream stream = archive.get()) {
                        doSetupWorkspace();
                        doHydrateWorkspace(stream);
                    }
                    workspaceSpecApplier.applyWorkspaceSpec(spec, false);
                } else {
                    // Branch D: fresh initialization from full workspace spec
                    log.debug("[sandbox] Branch D: fresh workspace initialization");
                    doSetupWorkspace();
                    workspaceSpecApplier.applyWorkspaceSpec(spec, false);
                }
            }
            // Snapshot restores are untrusted events for grant-managed projections: the
            // archive can hold wider-era content no bookkeeping describes (persist-order
            // skew, concurrent last-write-wins, backend snapshot rebinding), so a managed
            // restore never hash-skips and always rebuilds the namespaces.
            applyWorkspaceProjectionIfChanged(spec, restoredFromSnapshot);
            state.setWorkspaceRootReady(true);
            running.set(true);
        } catch (Exception e) {
            state.setWorkspaceRootReady(false);
            throw new SandboxException.WorkspaceStartException(
                    java.nio.file.Path.of(state.getWorkspaceSpec().getRoot()), e);
        }
    }

    /**
     * Persists the workspace snapshot and marks the workspace root as ready.
     *
     * @throws Exception if snapshot persistence fails
     */
    @Override
    public void stop() throws Exception {
        SandboxSnapshot snapshot = state.getSnapshot();
        if (snapshot != null && snapshot.isPersistenceEnabled()) {
            try (InputStream archive = doPersistWorkspace()) {
                snapshot.persist(archive);
            }
        }
        state.setWorkspaceRootReady(true);
        running.set(false);
    }

    /**
     * {@inheritDoc}
     *
     * <p>Calls {@link #stop()} then {@link #shutdown()}.
     * Stop failures are logged but do not prevent shutdown.
     */
    @Override
    public void close() throws Exception {
        try {
            stop();
        } catch (Exception e) {
            log.warn("[sandbox] Failed to stop sandbox during close, continuing shutdown", e);
        }
        shutdown();
    }

    @Override
    public boolean isRunning() {
        return running.get();
    }

    @Override
    public SandboxState getState() {
        return state;
    }

    /**
     * Delegates to {@link #doExec(RuntimeContext, String, int)} with a fallback timeout.
     */
    @Override
    public ExecResult exec(RuntimeContext runtimeContext, String command, Integer timeoutSeconds)
            throws Exception {
        int timeout = timeoutSeconds != null ? timeoutSeconds : getDefaultExecTimeoutSeconds();
        return doExec(runtimeContext, command, timeout);
    }

    @Override
    public InputStream persistWorkspace() throws Exception {
        return doPersistWorkspace();
    }

    /**
     * Opens the snapshot archive for the current start, or returns empty when no snapshot is
     * available. Backends that select a restore source before {@link #start()} can override this
     * to reuse their decision instead of opening the persisted snapshot a second time.
     */
    protected Optional<InputStream> openSnapshotArchive(SandboxSnapshot snapshot) throws Exception {
        if (snapshot == null || !snapshot.isRestorable()) {
            return Optional.empty();
        }
        return Optional.of(snapshot.restore());
    }

    @Override
    public void hydrateWorkspace(InputStream archive) throws Exception {
        doHydrateWorkspace(archive);
    }

    /**
     * Probes whether the workspace root directory still exists, using a backend exec.
     *
     * <p>Uses {@code test -d {workspaceRoot}} with a {@value #PROBE_TIMEOUT_SECONDS}-second
     * timeout. Returns {@code true} if the command exits with code 0.
     *
     * @return true if the workspace root exists
     */
    protected boolean probeWorkspaceRootForPreservedResume() {
        try {
            ExecResult result =
                    doExec(
                            null,
                            "test -d " + shellQuote(getWorkspaceRoot()),
                            PROBE_TIMEOUT_SECONDS);
            return result.ok();
        } catch (Exception e) {
            log.warn(
                    "[sandbox] Probe for workspace root failed, assuming lost: {}", e.getMessage());
            return false;
        }
    }

    /**
     * Returns the default command execution timeout in seconds.
     *
     * @return default timeout (120 seconds)
     */
    protected int getDefaultExecTimeoutSeconds() {
        return 120;
    }

    /**
     * Executes a shell command within the workspace.
     *
     * @param runtimeContext per-call context; may be {@code null} for internal probes
     * @param command shell command string
     * @param timeoutSeconds maximum execution time
     * @return execution result
     * @throws Exception if execution fails
     */
    protected abstract ExecResult doExec(
            RuntimeContext runtimeContext, String command, int timeoutSeconds) throws Exception;

    /**
     * Creates a tar archive of the current workspace contents.
     *
     * @return an {@link InputStream} over the tar stream; caller must close
     * @throws Exception if archiving fails
     */
    protected abstract InputStream doPersistWorkspace() throws Exception;

    /**
     * Extracts a tar archive into the workspace.
     *
     * @param archive the tar archive stream to extract
     * @throws Exception if extraction fails
     */
    protected abstract void doHydrateWorkspace(InputStream archive) throws Exception;

    /**
     * Creates the workspace root directory.
     *
     * @throws Exception if directory creation fails
     */
    protected abstract void doSetupWorkspace() throws Exception;

    /**
     * Destroys the workspace root and any backend resources.
     *
     * @throws Exception if cleanup fails
     */
    protected abstract void doDestroyWorkspace() throws Exception;

    /**
     * Returns the absolute path of the workspace root directory.
     *
     * @return workspace root path string
     */
    protected abstract String getWorkspaceRoot();

    /**
     * Deletes workspace files that a previous projection put in place and the current one
     * no longer authorizes. The deletion runs inside the sandbox via {@link
     * #doExec(RuntimeContext, String, int)} — remote backends expose no host-side file API,
     * so a shell command is the only universal channel, and it is safe here: every path is
     * boundary-validated and shell-quoted (any legal filename round-trips), and a failed
     * deletion fails the start rather than leaving unauthorized files behind. Backends with
     * a native delete primitive may override; each sandbox owns its workspace root, so only
     * this sandbox's own projected files are ever touched.
     *
     * @param relativePaths workspace-relative POSIX paths previously projected
     * @throws Exception when a manifest path is invalid or deletion fails
     */
    protected void doDeleteWorkspacePaths(List<String> relativePaths) throws Exception {
        String root = getWorkspaceRoot();
        for (String path : relativePaths) {
            requireSafeProjectedPath(path);
        }
        for (int from = 0; from < relativePaths.size(); from += DELETE_CHUNK_SIZE) {
            List<String> chunk =
                    relativePaths.subList(
                            from, Math.min(from + DELETE_CHUNK_SIZE, relativePaths.size()));
            // rm inside the sandbox: boundary-validated paths, shell-quoted — legal
            // filenames round-trip and nothing can expand or escape the projected set.
            StringBuilder command = new StringBuilder("rm -f --");
            for (String path : chunk) {
                command.append(' ').append(shellQuote(root + "/" + path));
            }
            ExecResult result = doExec(null, command.toString(), PROBE_TIMEOUT_SECONDS);
            if (!result.ok()) {
                throw new SandboxException.WorkspaceStartException(
                        Path.of(root),
                        new IllegalStateException(
                                "failed to revoke stale projected files (exit "
                                        + result.exitCode()
                                        + "): "
                                        + result.combinedOutput()));
            }
        }
        pruneEmptiedAncestorDirs(root, relativePaths);
    }

    /**
     * Best-effort {@code rmdir} of the (deepest-first) parent directories of revoked files:
     * skill names live in directory paths, so a leftover empty tree still enumerates the
     * revoked catalog. {@code rmdir} only removes empty directories — a directory still
     * holding another call's files is simply not removed — so pruning can never delete
     * content. Non-zero exits are expected on non-empty directories and are ignored.
     *
     * @param root absolute workspace root
     * @param relativePaths revoked workspace-relative file paths
     */
    private void pruneEmptiedAncestorDirs(String root, List<String> relativePaths) {
        Set<String> ancestors =
                new TreeSet<>(
                        Comparator.comparingInt((String p) -> p.split("/").length)
                                .reversed()
                                .thenComparing(Comparator.naturalOrder()));
        for (String path : relativePaths) {
            for (int end = path.lastIndexOf('/'); end > 0; end = path.lastIndexOf('/', end - 1)) {
                ancestors.add(path.substring(0, end));
            }
        }
        if (ancestors.isEmpty()) {
            return;
        }
        List<String> ordered = List.copyOf(ancestors);
        for (int from = 0; from < ordered.size(); from += DELETE_CHUNK_SIZE) {
            List<String> chunk =
                    ordered.subList(from, Math.min(from + DELETE_CHUNK_SIZE, ordered.size()));
            StringBuilder command = new StringBuilder("rmdir --");
            for (String dir : chunk) {
                command.append(' ').append(shellQuote(root + "/" + dir));
            }
            try {
                doExec(null, command.toString(), PROBE_TIMEOUT_SECONDS);
            } catch (Exception e) {
                log.debug("[sandbox] Empty-parent pruning skipped: {}", e.getMessage());
            }
        }
    }

    /**
     * Validates one manifest path's boundaries before it reaches a delete command. The
     * manifest is persisted JSON — treat it as untrusted on read: absolute paths, empty or
     * {@code .}/{@code ..} segments, backslashes and NUL are rejected loudly. Everything
     * else — spaces, unicode, even shell metacharacters — can be a legal filename and is
     * passed through: command safety comes from {@link #shellQuote(String)}, never from
     * restricting the charset, so revocation cannot break legal resource names.
     *
     * @param path the recorded workspace-relative POSIX path
     * @throws IllegalStateException when the path could target anything outside the
     *     projected file set
     */
    private static void requireSafeProjectedPath(String path) {
        if (path == null
                || path.isBlank()
                || path.startsWith("/")
                || path.contains("\\")
                || path.indexOf('\0') >= 0) {
            throw new IllegalStateException("unsafe projected path in manifest: " + path);
        }
        for (String segment : path.split("/", -1)) {
            if (segment.isEmpty() || segment.equals(".") || segment.equals("..")) {
                throw new IllegalStateException("unsafe projected path in manifest: " + path);
            }
        }
    }

    /**
     * POSIX single-quote wrapping for one shell operand. Any legal filename round-trips:
     * the only character with meaning inside single quotes — a quote itself — is closed,
     * escaped and reopened, so no expansion or injection is possible regardless of content.
     */
    private static String shellQuote(String value) {
        return "'" + value.replace("'", "'\\''") + "'";
    }

    /**
     * Applies the workspace projection. Specs carrying a grant-managed projection entry
     * ({@link WorkspaceProjectionApplier#isAuthorizationManaged}) are applied
     * authoritatively: the current payload is hydrated and every file of the previously
     * applied projection that the current one no longer authorizes is revoked (a narrowed
     * grant must remove, not overlay) — only projection-managed paths are ever deleted,
     * sandbox user files are untouched, which is also what keeps concurrent sandboxes
     * sharing a host directory safe. When the recorded manifest is missing, the
     * workspace's projected content is unaccounted (pre-manifest upgrade, state loss with
     * a carried-over snapshot, an unmanaged overlay since the last managed pass): instead
     * of treating that as "nothing to revoke" or refusing, the managed namespaces are
     * rebuilt from scratch — wiped via {@link #doDeleteWorkspaceRoots}, then re-projected
     * with a fresh manifest. Unmarked specs keep the generic overlay semantics (hydrate
     * only, never delete): fixing the authorization boundary must not change projection
     * behaviour for everyone else.
     *
     * @param spec the workspace spec carrying the projection entries
     * @param force whether the workspace was just hydrated from a snapshot: restores are
     *     untrusted events for managed projections — the archive can hold wider-era
     *     content that no bookkeeping describes — so a managed restore never skips and
     *     always rebuilds the namespaces, even with an intact manifest and unchanged hash
     */
    private void applyWorkspaceProjectionIfChanged(WorkspaceSpec spec, boolean force)
            throws Exception {
        WorkspaceProjectionApplier.ProjectionPayload payload =
                WorkspaceProjectionApplier.build(spec);
        String currentHash = payload != null ? payload.hash() : null;
        String storedHash = state.getWorkspaceProjectionHash();
        boolean managed = WorkspaceProjectionApplier.isAuthorizationManaged(spec);
        List<String> manifest = state.getProjectedWorkspacePaths();
        // Identical content: nothing to hydrate, nothing to revoke (for unmanaged specs
        // this also restores the historic skip on snapshot restores). Managed specs
        // without a manifest still fall through — their projected content is unaccounted
        // and must be rebuilt, not skipped.
        if (Objects.equals(currentHash, storedHash) && !(managed && (manifest == null || force))) {
            log.debug("[sandbox] Workspace projection unchanged, skipping");
            return;
        }
        if (!managed) {
            // Generic path (grant layer not engaged): overlay hydration only — never
            // delete, never refuse. A content change is outside managed bookkeeping, so
            // the manifest is invalidated (the hash is kept): the next managed pass
            // rebuilds against bookkeeping it can no longer trust. A spec without any
            // projection entry changes nothing and leaves all bookkeeping untouched.
            if (payload == null) {
                return;
            }
            if (payload.fileCount() > 0) {
                try (InputStream archive = new ByteArrayInputStream(payload.tarBytes())) {
                    doHydrateWorkspace(archive);
                }
            }
            state.setWorkspaceProjectionHash(currentHash);
            state.setProjectedWorkspacePaths(null);
            log.debug(
                    "[sandbox] Workspace projection applied (unmanaged): files={}",
                    payload.fileCount());
            return;
        }
        // Managed path. Without a trusted manifest the workspace's projected content is
        // unaccounted — never "nothing to revoke". Rebuild the managed namespaces from
        // scratch so no stale file of any earlier era survives (audit: resume-failure
        // carry-over, entry-less specs, pre-manifest upgrades all converge here).
        List<String> currentPaths = payload != null ? payload.paths() : List.of();
        // Snapshot restores are untrusted events for managed projections: the archive can
        // hold content from a wider era than any bookkeeping describes (persist-order
        // skew, concurrent last-write-wins, backend snapshot rebinding), so a restore
        // always rebuilds rather than diffing against possibly-stale bookkeeping.
        if (manifest == null || force) {
            List<String> revokeRoots = WorkspaceProjectionApplier.managedRevokeRoots(spec);
            if (revokeRoots.isEmpty()) {
                // A marked entry without rebuild scope cannot be made safe: fail closed
                // rather than assume the workspace holds nothing foreign.
                throw new SandboxException.WorkspaceStartException(
                        Path.of(getWorkspaceRoot()),
                        new IllegalStateException(
                                "grant-managed projection without revokeRoots cannot be"
                                        + " rebuilt safely; the workspace content is"
                                        + " unaccounted. Configure the managed projection"
                                        + " namespaces (revokeRoots) to continue."));
            }
            doDeleteWorkspaceRoots(revokeRoots);
        } else {
            // Set lookup: manifests can hold thousands of paths and every start diffs them.
            Set<String> currentPathSet = new HashSet<>(currentPaths);
            List<String> stale =
                    manifest.stream().filter(path -> !currentPathSet.contains(path)).toList();
            if (!stale.isEmpty()) {
                doDeleteWorkspacePaths(stale);
            }
        }
        if (payload != null && payload.fileCount() > 0) {
            try (InputStream archive = new ByteArrayInputStream(payload.tarBytes())) {
                doHydrateWorkspace(archive);
            }
        }
        state.setWorkspaceProjectionHash(currentHash);
        state.setProjectedWorkspacePaths(currentPaths);
        log.debug(
                "[sandbox] Workspace projection applied: files={}, mode={}, hash={}",
                currentPaths.size(),
                (manifest == null || force) ? "rebuild" : "diff",
                currentHash);
    }

    /**
     * Rebuild primitive: clears whole projection namespaces ({@code revokeRoots} of the
     * managed entries, e.g. the {@code .skills-cache} tree) so a workspace with missing
     * or untrusted bookkeeping can be re-projected from scratch. Unlike {@link
     * #doDeleteWorkspacePaths} this is recursive by design: the roots come from the
     * trusted current spec (grant-aware wiring), never from a persisted manifest, are
     * boundary-validated and shell-quoted like every generated command, and a failure
     * fails the start (fail-closed). Only these spec-declared namespaces are ever
     * touched — sandbox user files outside them are unaffected.
     *
     * @param revokeRoots workspace-relative namespaces to clear
     * @throws Exception when a root is invalid or the deletion fails
     */
    protected void doDeleteWorkspaceRoots(List<String> revokeRoots) throws Exception {
        if (revokeRoots == null || revokeRoots.isEmpty()) {
            return;
        }
        for (String root : revokeRoots) {
            requireSafeProjectedPath(root);
        }
        String workspaceRoot = getWorkspaceRoot();
        for (int from = 0; from < revokeRoots.size(); from += DELETE_CHUNK_SIZE) {
            List<String> chunk =
                    revokeRoots.subList(
                            from, Math.min(from + DELETE_CHUNK_SIZE, revokeRoots.size()));
            StringBuilder command = new StringBuilder("rm -rf --");
            for (String root : chunk) {
                command.append(' ').append(shellQuote(workspaceRoot + "/" + root));
            }
            ExecResult result = doExec(null, command.toString(), PROBE_TIMEOUT_SECONDS);
            if (!result.ok()) {
                throw new SandboxException.WorkspaceStartException(
                        Path.of(workspaceRoot),
                        new IllegalStateException(
                                "failed to clear projected namespaces for rebuild (exit "
                                        + result.exitCode()
                                        + "): "
                                        + result.combinedOutput()));
            }
        }
    }
}
