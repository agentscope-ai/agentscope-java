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
package io.agentscope.harness.agent.sandbox.filesystem;

import io.agentscope.harness.agent.sandbox.SandboxClient;
import io.agentscope.harness.agent.sandbox.SandboxClientOptions;
import io.agentscope.harness.agent.sandbox.WorkspaceSpec;
import io.agentscope.harness.agent.sandbox.impl.docker.DockerSandboxClientOptions;
import io.agentscope.harness.agent.sandbox.snapshot.NoopSnapshotSpec;
import io.agentscope.harness.agent.sandbox.snapshot.SandboxSnapshotSpec;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Sandbox filesystem spec for Docker backend.
 */
public class DockerFilesystemSpec extends SandboxFilesystemSpec {

    private SandboxClient<?> client;
    private final DockerSandboxClientOptions options = new DockerSandboxClientOptions();
    private SandboxSnapshotSpec snapshotSpec = new NoopSnapshotSpec();
    private WorkspaceSpec defaultWorkspaceSpec = new WorkspaceSpec();

    public DockerFilesystemSpec client(SandboxClient<?> client) {
        this.client = client;
        return this;
    }

    public DockerFilesystemSpec image(String image) {
        options.image(image);
        return this;
    }

    public DockerFilesystemSpec workspaceRoot(String workspaceRoot) {
        options.workspaceRoot(workspaceRoot);
        return this;
    }

    public DockerFilesystemSpec environment(Map<String, String> environment) {
        options.setEnvironment(
                environment != null ? new LinkedHashMap<>(environment) : new LinkedHashMap<>());
        return this;
    }

    public DockerFilesystemSpec memorySizeBytes(Long memorySizeBytes) {
        options.memorySizeBytes(memorySizeBytes);
        return this;
    }

    public DockerFilesystemSpec cpuCount(Long cpuCount) {
        options.cpuCount(cpuCount);
        return this;
    }

    public DockerFilesystemSpec exposedPorts(int... exposedPorts) {
        options.exposedPorts(exposedPorts);
        return this;
    }

    public DockerFilesystemSpec network(String network) {
        options.network(network);
        return this;
    }

    public DockerFilesystemSpec additionalRunArgs(String... additionalRunArgs) {
        options.additionalRunArgs(additionalRunArgs);
        return this;
    }

    public DockerFilesystemSpec additionalRunArgs(List<String> additionalRunArgs) {
        options.setAdditionalRunArgs(additionalRunArgs);
        return this;
    }

    public DockerFilesystemSpec snapshotSpec(SandboxSnapshotSpec snapshotSpec) {
        this.snapshotSpec = snapshotSpec;
        return this;
    }

    public DockerFilesystemSpec workspaceSpec(WorkspaceSpec workspaceSpec) {
        this.defaultWorkspaceSpec = workspaceSpec;
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
