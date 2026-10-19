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
import io.agentscope.harness.agent.sandbox.SandboxClient;
import io.agentscope.harness.agent.sandbox.SandboxClientOptions;
import io.agentscope.harness.agent.sandbox.SandboxContext;
import io.agentscope.harness.agent.sandbox.SandboxExecutionGuard;
import io.agentscope.harness.agent.sandbox.WorkspaceSpec;
import io.agentscope.harness.agent.sandbox.layout.WorkspaceEntry;
import io.agentscope.harness.agent.sandbox.layout.WorkspaceProjectionEntry;
import io.agentscope.harness.agent.sandbox.snapshot.SandboxSnapshotSpec;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * Declarative sandbox filesystem configuration.
 *
 * <p>Unlike {@code AbstractFilesystem}, this type is not a runtime filesystem implementation.
 * It only describes how to create a sandbox-backed filesystem at build time.
 */
public abstract class SandboxFilesystemSpec {

    private static final List<String> DEFAULT_WORKSPACE_PROJECTION_ROOTS =
            List.of("AGENTS.md", "skills", "subagents", "knowledge", ".skills-cache");

    private IsolationScope isolationScope;
    private SandboxSnapshotSpec snapshotSpecOverride;
    private SandboxExecutionGuard executionGuard;
    private boolean workspaceProjectionEnabled = true;
    private List<String> workspaceProjectionRoots = DEFAULT_WORKSPACE_PROJECTION_ROOTS;
    private List<String> workspaceProjectionSyncRoots = List.of();

    protected abstract SandboxClient<?> createClient();

    protected abstract SandboxClientOptions clientOptions();

    protected abstract SandboxSnapshotSpec snapshotSpec();

    protected abstract WorkspaceSpec workspaceSpec();

    public SandboxFilesystemSpec isolationScope(IsolationScope scope) {
        this.isolationScope = scope;
        return this;
    }

    public IsolationScope getIsolationScope() {
        return isolationScope;
    }

    public SandboxFilesystemSpec snapshotSpec(SandboxSnapshotSpec snapshotSpec) {
        this.snapshotSpecOverride = snapshotSpec;
        return this;
    }

    public SandboxSnapshotSpec getSnapshotSpecOverride() {
        return snapshotSpecOverride;
    }

    /**
     * Sets a {@link SandboxExecutionGuard} that serialises concurrent executions on the same
     * isolation slot.
     *
     * <p>Only relevant for {@link io.agentscope.harness.agent.IsolationScope#AGENT} and
     * {@link io.agentscope.harness.agent.IsolationScope#GLOBAL} scopes, where multiple callers
     * could otherwise race on the same persistent state. When {@code null} (default), no guard is
     * applied and the existing no-lock behaviour is preserved.
     *
     * @param executionGuard the guard to apply, or {@code null} for no guard
     * @return this spec
     */
    public SandboxFilesystemSpec executionGuard(SandboxExecutionGuard executionGuard) {
        this.executionGuard = executionGuard;
        return this;
    }

    public SandboxExecutionGuard getExecutionGuard() {
        return executionGuard;
    }

    public SandboxFilesystemSpec workspaceProjectionEnabled(boolean enabled) {
        this.workspaceProjectionEnabled = enabled;
        return this;
    }

    public SandboxFilesystemSpec workspaceProjectionRoots(List<String> includeRoots) {
        this.workspaceProjectionRoots =
                includeRoots != null
                        ? List.copyOf(includeRoots)
                        : DEFAULT_WORKSPACE_PROJECTION_ROOTS;
        return this;
    }

    /**
     * Reconciles the invocation's derived cache subtree. Use {@code .skills-cache/{scope}} to
     * resolve the exact stager scope dynamically. Static roots must belong to the call too.
     * Defaults to empty (overlay only). Currently requires a newly created harness-owned Docker
     * container, Linux x86_64/aarch64 with openat2 (kernel 5.6+), and Python 3. The image must
     * be trusted and the workspace private, with no independent writer during maintenance. Requires
     * an execution guard whose correctness is the application's responsibility. Configuration is
     * not persisted.
     *
     * @param roots relative POSIX paths below {@code .skills-cache}, included in projection
     * @return this spec
     */
    public SandboxFilesystemSpec workspaceProjectionSyncRoots(List<String> roots) {
        this.workspaceProjectionSyncRoots = roots != null ? List.copyOf(roots) : List.of();
        return this;
    }

    public final SandboxContext toSandboxContext(Path hostWorkspaceRoot) {
        SandboxClient<?> client =
                Objects.requireNonNull(createClient(), "sandbox client is required");
        WorkspaceSpec withProjection = buildWorkspaceSpecWithProjection(hostWorkspaceRoot);
        return SandboxContext.builder()
                .client(client)
                .clientOptions(clientOptions())
                .snapshotSpec(snapshotSpecOverride != null ? snapshotSpecOverride : snapshotSpec())
                .workspaceSpec(withProjection)
                .isolationScope(isolationScope)
                .build();
    }

    public final SandboxContext toSandboxContext() {
        return toSandboxContext(null);
    }

    private WorkspaceSpec buildWorkspaceSpecWithProjection(Path hostWorkspaceRoot) {
        WorkspaceSpec base = workspaceSpec();
        WorkspaceSpec effective = base != null ? base.copy() : new WorkspaceSpec();
        if (!workspaceProjectionEnabled || hostWorkspaceRoot == null) {
            if (!workspaceProjectionSyncRoots.isEmpty()) {
                throw new IllegalArgumentException(
                        "Cache synchronization requires host projection");
            }
            return effective;
        }
        WorkspaceProjectionEntry projection = new WorkspaceProjectionEntry();
        projection.setSourceRoot(hostWorkspaceRoot.toAbsolutePath().normalize().toString());
        projection.setIncludeRoots(workspaceProjectionRoots);
        projection.setSyncRoots(workspaceProjectionSyncRoots);

        Map<String, WorkspaceEntry> entries = new LinkedHashMap<>(effective.getEntries());
        entries.put("__workspace_projection__", projection);
        effective.setEntries(entries);
        return effective;
    }
}
