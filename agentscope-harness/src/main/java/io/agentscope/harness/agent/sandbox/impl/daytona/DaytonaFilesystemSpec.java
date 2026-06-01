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
package io.agentscope.harness.agent.sandbox.impl.daytona;

import io.agentscope.harness.agent.filesystem.spec.SandboxFilesystemSpec;
import io.agentscope.harness.agent.sandbox.SandboxClient;
import io.agentscope.harness.agent.sandbox.SandboxClientOptions;
import io.agentscope.harness.agent.sandbox.WorkspaceSpec;
import io.agentscope.harness.agent.sandbox.snapshot.NoopSnapshotSpec;
import io.agentscope.harness.agent.sandbox.snapshot.SandboxSnapshotSpec;

/** {@link SandboxFilesystemSpec} for Daytona. */
public class DaytonaFilesystemSpec extends SandboxFilesystemSpec {

    private SandboxClient<?> client;
    private final DaytonaSandboxClientOptions options = new DaytonaSandboxClientOptions();
    private SandboxSnapshotSpec snapshotSpec = new NoopSnapshotSpec();
    private WorkspaceSpec defaultWorkspaceSpec = new WorkspaceSpec();

    public DaytonaFilesystemSpec client(SandboxClient<?> client) {
        this.client = client;
        return this;
    }

    public DaytonaFilesystemSpec apiKey(String apiKey) {
        options.setApiKey(apiKey);
        return this;
    }

    public DaytonaFilesystemSpec controlPlaneBaseUrl(String url) {
        options.setControlPlaneBaseUrl(url);
        return this;
    }

    public DaytonaFilesystemSpec toolboxBaseUrl(String url) {
        options.setToolboxBaseUrl(url);
        return this;
    }

    public DaytonaFilesystemSpec image(String image) {
        options.setImage(image);
        return this;
    }

    public DaytonaFilesystemSpec snapshotId(String snapshotId) {
        options.setSnapshotId(snapshotId);
        return this;
    }

    public DaytonaFilesystemSpec cpu(int cpu) {
        options.setCpu(cpu);
        return this;
    }

    public DaytonaFilesystemSpec memory(int memoryGiB) {
        options.setMemory(memoryGiB);
        return this;
    }

    public DaytonaFilesystemSpec disk(int diskGiB) {
        options.setDisk(diskGiB);
        return this;
    }

    public DaytonaFilesystemSpec workspaceRoot(String workspaceRoot) {
        options.setWorkspaceRoot(workspaceRoot);
        return this;
    }

    public DaytonaFilesystemSpec snapshotSpec(SandboxSnapshotSpec snapshotSpec) {
        this.snapshotSpec = snapshotSpec;
        return this;
    }

    public DaytonaFilesystemSpec workspaceSpec(WorkspaceSpec workspaceSpec) {
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
