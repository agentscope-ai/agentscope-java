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
import java.util.Objects;
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
                    if (snapshot != null && snapshot.isRestorable()) {
                        doSetupWorkspace();
                        try (InputStream archive = snapshot.restore()) {
                            doHydrateWorkspace(archive);
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
                if (snapshot != null && snapshot.isRestorable()) {
                    // Branch C: restore from snapshot + all spec entries
                    log.debug("[sandbox] Branch C: restoring from snapshot");
                    doSetupWorkspace();
                    try (InputStream archive = snapshot.restore()) {
                        doHydrateWorkspace(archive);
                    }
                    workspaceSpecApplier.applyWorkspaceSpec(spec, false);
                } else {
                    // Branch D: fresh initialization from full workspace spec
                    log.debug("[sandbox] Branch D: fresh workspace initialization");
                    doSetupWorkspace();
                    workspaceSpecApplier.applyWorkspaceSpec(spec, false);
                }
            }
            applyWorkspaceProjectionIfChanged(spec);
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
                    doExec(null, "test -d " + getWorkspaceRoot(), PROBE_TIMEOUT_SECONDS);
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

    private void applyWorkspaceProjectionIfChanged(WorkspaceSpec spec) throws Exception {
        WorkspaceProjectionApplier.ProjectionPayload payload =
                WorkspaceProjectionApplier.build(spec);
        if (payload == null) {
            return;
        }
        if (Objects.equals(payload.hash(), state.getWorkspaceProjectionHash())) {
            log.debug("[sandbox] Workspace projection unchanged, skipping");
            return;
        }
        if (payload.fileCount() > 0) {
            try (InputStream archive = new ByteArrayInputStream(payload.tarBytes())) {
                doHydrateWorkspace(archive);
            }
        }
        state.setWorkspaceProjectionHash(payload.hash());
        log.debug(
                "[sandbox] Workspace projection applied: files={}, hash={}",
                payload.fileCount(),
                payload.hash());
    }
}
