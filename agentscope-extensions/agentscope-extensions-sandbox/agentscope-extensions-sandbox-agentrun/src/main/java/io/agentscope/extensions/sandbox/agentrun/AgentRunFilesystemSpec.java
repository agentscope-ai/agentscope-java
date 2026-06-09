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
package io.agentscope.extensions.sandbox.agentrun;

import io.agentscope.harness.agent.filesystem.spec.SandboxFilesystemSpec;
import io.agentscope.harness.agent.sandbox.SandboxClient;
import io.agentscope.harness.agent.sandbox.SandboxClientOptions;
import io.agentscope.harness.agent.sandbox.WorkspaceSpec;
import io.agentscope.harness.agent.sandbox.snapshot.NoopSnapshotSpec;
import io.agentscope.harness.agent.sandbox.snapshot.SandboxSnapshotSpec;

/** {@link SandboxFilesystemSpec} for the Alibaba Cloud AgentRun store. */
public class AgentRunFilesystemSpec extends SandboxFilesystemSpec {

    private SandboxClient<?> client;
    private final AgentRunSandboxClientOptions options = new AgentRunSandboxClientOptions();
    private SandboxSnapshotSpec snapshotSpec = new NoopSnapshotSpec();
    private WorkspaceSpec defaultWorkspaceSpec = new WorkspaceSpec();

    public AgentRunFilesystemSpec client(SandboxClient<?> client) {
        this.client = client;
        return this;
    }

    public AgentRunFilesystemSpec apiKey(String apiKey) {
        options.setApiKey(apiKey);
        return this;
    }

    public AgentRunFilesystemSpec accountId(String accountId) {
        options.setAccountId(accountId);
        return this;
    }

    public AgentRunFilesystemSpec region(String region) {
        options.setRegion(region);
        return this;
    }

    public AgentRunFilesystemSpec dataPlaneBaseUrl(String url) {
        options.setDataPlaneBaseUrl(url);
        return this;
    }

    public AgentRunFilesystemSpec templateName(String templateName) {
        options.setTemplateName(templateName);
        return this;
    }

    public AgentRunFilesystemSpec mcpServerUrl(String mcpServerUrl) {
        options.setMcpServerUrl(mcpServerUrl);
        return this;
    }

    public AgentRunFilesystemSpec mcpEndpoint(String mcpEndpoint) {
        options.setMcpEndpoint(mcpEndpoint);
        return this;
    }

    public AgentRunFilesystemSpec sandboxIdleTimeoutSeconds(int seconds) {
        options.setSandboxIdleTimeoutSeconds(seconds);
        return this;
    }

    public AgentRunFilesystemSpec nasConfig(AgentRunNasMountConfig nas) {
        options.setNasConfig(nas);
        return this;
    }

    public AgentRunFilesystemSpec addOssMount(AgentRunOssMountConfig mount) {
        options.addOssMount(mount);
        return this;
    }

    public AgentRunFilesystemSpec workspaceRoot(String workspaceRoot) {
        options.setWorkspaceRoot(workspaceRoot);
        return this;
    }

    public AgentRunFilesystemSpec connectTimeoutSeconds(int seconds) {
        options.setConnectTimeoutSeconds(seconds);
        return this;
    }

    public AgentRunFilesystemSpec readTimeoutSeconds(int seconds) {
        options.setReadTimeoutSeconds(seconds);
        return this;
    }

    public AgentRunFilesystemSpec maxRetries(int maxRetries) {
        options.setMaxRetries(maxRetries);
        return this;
    }

    public AgentRunFilesystemSpec snapshotSpec(SandboxSnapshotSpec snapshotSpec) {
        this.snapshotSpec = snapshotSpec;
        return this;
    }

    public AgentRunFilesystemSpec workspaceSpec(WorkspaceSpec workspaceSpec) {
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
