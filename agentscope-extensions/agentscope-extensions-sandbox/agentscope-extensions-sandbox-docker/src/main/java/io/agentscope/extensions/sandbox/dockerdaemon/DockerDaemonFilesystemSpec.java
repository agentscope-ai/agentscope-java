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
package io.agentscope.extensions.sandbox.dockerdaemon;

import io.agentscope.harness.agent.IsolationScope;
import io.agentscope.harness.agent.filesystem.spec.SandboxFilesystemSpec;
import io.agentscope.harness.agent.sandbox.SandboxClient;
import io.agentscope.harness.agent.sandbox.SandboxClientOptions;
import io.agentscope.harness.agent.sandbox.SandboxExecutionGuard;
import io.agentscope.harness.agent.sandbox.WorkspaceSpec;
import io.agentscope.harness.agent.sandbox.snapshot.NoopSnapshotSpec;
import io.agentscope.harness.agent.sandbox.snapshot.SandboxSnapshotSpec;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Sandbox filesystem spec backed by a docker-java client talking to a (possibly remote) Docker
 * daemon over its Engine HTTP API.
 *
 * <p>Use {@link #daemonUrl(String)} (or the {@code DOCKER_HOST} environment variable) to reach
 * a remote daemon over TCP — no docker CLI, local socket, or docker-in-docker required.
 */
public class DockerDaemonFilesystemSpec extends SandboxFilesystemSpec {

    private SandboxClient<?> client;
    private final DockerDaemonSandboxClientOptions options = new DockerDaemonSandboxClientOptions();
    private SandboxSnapshotSpec snapshotSpec = new NoopSnapshotSpec();
    private WorkspaceSpec defaultWorkspaceSpec = new WorkspaceSpec();

    public DockerDaemonFilesystemSpec client(SandboxClient<?> client) {
        this.client = client;
        return this;
    }

    @Override
    public DockerDaemonFilesystemSpec isolationScope(IsolationScope scope) {
        super.isolationScope(scope);
        return this;
    }

    @Override
    public DockerDaemonFilesystemSpec executionGuard(SandboxExecutionGuard executionGuard) {
        super.executionGuard(executionGuard);
        return this;
    }

    @Override
    public DockerDaemonFilesystemSpec workspaceProjectionEnabled(boolean enabled) {
        super.workspaceProjectionEnabled(enabled);
        return this;
    }

    @Override
    public DockerDaemonFilesystemSpec workspaceProjectionRoots(List<String> includeRoots) {
        super.workspaceProjectionRoots(includeRoots);
        return this;
    }

    public DockerDaemonFilesystemSpec image(String image) {
        options.image(image);
        return this;
    }

    public DockerDaemonFilesystemSpec workspaceRoot(String workspaceRoot) {
        options.workspaceRoot(workspaceRoot);
        return this;
    }

    public DockerDaemonFilesystemSpec environment(Map<String, String> environment) {
        options.setEnvironment(
                environment != null ? new LinkedHashMap<>(environment) : new LinkedHashMap<>());
        return this;
    }

    public DockerDaemonFilesystemSpec memorySizeBytes(Long memorySizeBytes) {
        options.memorySizeBytes(memorySizeBytes);
        return this;
    }

    public DockerDaemonFilesystemSpec cpuCount(Long cpuCount) {
        options.cpuCount(cpuCount);
        return this;
    }

    public DockerDaemonFilesystemSpec exposedPorts(int... exposedPorts) {
        options.exposedPorts(exposedPorts);
        return this;
    }

    public DockerDaemonFilesystemSpec network(String network) {
        options.network(network);
        return this;
    }

    public DockerDaemonFilesystemSpec daemonUrl(String daemonUrl) {
        options.daemonUrl(daemonUrl);
        return this;
    }

    public DockerDaemonFilesystemSpec snapshotSpec(SandboxSnapshotSpec snapshotSpec) {
        this.snapshotSpec = snapshotSpec;
        return this;
    }

    public DockerDaemonFilesystemSpec workspaceSpec(WorkspaceSpec workspaceSpec) {
        this.defaultWorkspaceSpec = workspaceSpec;
        return this;
    }

    public DockerDaemonFilesystemSpec mount(String type, String source, String target) {
        options.mount(type, source, target);
        return this;
    }

    public DockerDaemonFilesystemSpec mount(
            String type, String source, String target, boolean readOnly) {
        options.mount(type, source, target, readOnly);
        return this;
    }

    @Override
    protected SandboxClient<?> createClient() {
        return client != null ? client : options.createClient();
    }

    @Override
    protected SandboxClientOptions clientOptions() {
        return options;
    }

    @Override
    protected SandboxSnapshotSpec snapshotSpec() {
        return snapshotSpec;
    }

    @Override
    protected WorkspaceSpec workspaceSpec() {
        return defaultWorkspaceSpec;
    }
}
