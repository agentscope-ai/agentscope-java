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
package io.agentscope.extensions.sandbox.opensandbox;

import io.agentscope.harness.agent.filesystem.spec.SandboxFilesystemSpec;
import io.agentscope.harness.agent.sandbox.SandboxClient;
import io.agentscope.harness.agent.sandbox.SandboxClientOptions;
import io.agentscope.harness.agent.sandbox.WorkspaceSpec;
import io.agentscope.harness.agent.sandbox.snapshot.NoopSnapshotSpec;
import io.agentscope.harness.agent.sandbox.snapshot.SandboxSnapshotSpec;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Fluent {@link SandboxFilesystemSpec} for an OpenSandbox runtime. */
public class OpenSandboxFilesystemSpec extends SandboxFilesystemSpec {
    private SandboxClient<?> client;
    private final OpenSandboxClientOptions options = new OpenSandboxClientOptions();
    private SandboxSnapshotSpec snapshotSpec = new NoopSnapshotSpec();
    private WorkspaceSpec defaultWorkspaceSpec = createDefaultWorkspaceSpec();

    public OpenSandboxFilesystemSpec client(SandboxClient<?> client) {
        this.client = client;
        return this;
    }

    public OpenSandboxFilesystemSpec endpoint(String endpoint) {
        options.setEndpoint(endpoint);
        return this;
    }

    public OpenSandboxFilesystemSpec apiKey(String apiKey) {
        options.setApiKey(apiKey);
        return this;
    }

    public OpenSandboxFilesystemSpec image(String image) {
        options.setImage(image);
        return this;
    }

    public OpenSandboxFilesystemSpec entrypoint(List<String> entrypoint) {
        options.setEntrypoint(entrypoint);
        return this;
    }

    public OpenSandboxFilesystemSpec cpu(String cpu) {
        setResource("cpu", cpu);
        return this;
    }

    public OpenSandboxFilesystemSpec memory(String memory) {
        setResource("memory", memory);
        return this;
    }

    public OpenSandboxFilesystemSpec sandboxTimeoutSeconds(int seconds) {
        options.setSandboxTimeoutSeconds(seconds);
        return this;
    }

    public OpenSandboxFilesystemSpec readyTimeoutSeconds(int seconds) {
        options.setReadyTimeoutSeconds(seconds);
        return this;
    }

    public OpenSandboxFilesystemSpec requestTimeoutSeconds(int seconds) {
        options.setRequestTimeoutSeconds(seconds);
        return this;
    }

    public OpenSandboxFilesystemSpec useServerProxy(boolean enabled) {
        options.setUseServerProxy(enabled);
        return this;
    }

    public OpenSandboxFilesystemSpec workspaceRoot(String workspaceRoot) {
        defaultWorkspaceSpec.setRoot(workspaceRoot);
        return this;
    }

    public OpenSandboxFilesystemSpec workspaceSpec(WorkspaceSpec workspaceSpec) {
        this.defaultWorkspaceSpec = workspaceSpec;
        return this;
    }

    public OpenSandboxFilesystemSpec snapshotSpec(SandboxSnapshotSpec snapshotSpec) {
        this.snapshotSpec = snapshotSpec;
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

    private void setResource(String key, String value) {
        Map<String, String> resources = new LinkedHashMap<>(options.getResourceLimits());
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(key + " must not be blank");
        }
        resources.put(key, value);
        options.setResourceLimits(resources);
    }

    private static WorkspaceSpec createDefaultWorkspaceSpec() {
        WorkspaceSpec spec = new WorkspaceSpec();
        spec.setRoot(OpenSandboxState.DEFAULT_WORKSPACE_ROOT);
        return spec;
    }
}
