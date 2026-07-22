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
package io.agentscope.extensions.sandbox.kubernetes;

import io.agentscope.harness.agent.filesystem.spec.SandboxFilesystemSpec;
import io.agentscope.harness.agent.sandbox.SandboxClient;
import io.agentscope.harness.agent.sandbox.SandboxClientOptions;
import io.agentscope.harness.agent.sandbox.WorkspaceSpec;
import io.agentscope.harness.agent.sandbox.snapshot.NoopSnapshotSpec;
import io.agentscope.harness.agent.sandbox.snapshot.SandboxSnapshotSpec;

/** {@link SandboxFilesystemSpec} for the agent-sandbox backed Kubernetes sandbox store. */
public class KubernetesFilesystemSpec extends SandboxFilesystemSpec {

    private SandboxClient<?> client;
    private final KubernetesSandboxClientOptions options = new KubernetesSandboxClientOptions();
    private SandboxSnapshotSpec snapshotSpec = new NoopSnapshotSpec();
    private WorkspaceSpec defaultWorkspaceSpec = new WorkspaceSpec();

    public KubernetesFilesystemSpec client(SandboxClient<?> client) {
        this.client = client;
        return this;
    }

    public KubernetesFilesystemSpec kubernetesClient(
            io.fabric8.kubernetes.client.KubernetesClient kc) {
        options.setKubernetesClient(kc);
        return this;
    }

    public KubernetesFilesystemSpec namespace(String namespace) {
        options.setNamespace(namespace);
        return this;
    }

    public KubernetesFilesystemSpec warmPoolName(String warmPoolName) {
        options.setWarmPoolName(warmPoolName);
        return this;
    }

    public KubernetesFilesystemSpec workspaceRoot(String workspaceRoot) {
        options.setWorkspaceRoot(workspaceRoot);
        return this;
    }

    public KubernetesFilesystemSpec fileApiBaseDir(String fileApiBaseDir) {
        options.setFileApiBaseDir(fileApiBaseDir);
        return this;
    }

    public KubernetesFilesystemSpec apiUrl(String apiUrl) {
        options.setApiUrl(apiUrl);
        return this;
    }

    public KubernetesFilesystemSpec gatewayName(String gatewayName) {
        options.setGatewayName(gatewayName);
        return this;
    }

    public KubernetesFilesystemSpec gatewayNamespace(String gatewayNamespace) {
        options.setGatewayNamespace(gatewayNamespace);
        return this;
    }

    public KubernetesFilesystemSpec gatewayScheme(String gatewayScheme) {
        options.setGatewayScheme(gatewayScheme);
        return this;
    }

    public KubernetesFilesystemSpec serverPort(int serverPort) {
        options.setServerPort(serverPort);
        return this;
    }

    public KubernetesFilesystemSpec snapshotSpec(SandboxSnapshotSpec snapshotSpec) {
        this.snapshotSpec = snapshotSpec;
        return this;
    }

    public KubernetesFilesystemSpec workspaceSpec(WorkspaceSpec workspaceSpec) {
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
