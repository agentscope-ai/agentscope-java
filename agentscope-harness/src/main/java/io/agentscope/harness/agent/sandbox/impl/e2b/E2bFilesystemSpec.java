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
package io.agentscope.harness.agent.sandbox.impl.e2b;

import io.agentscope.harness.agent.filesystem.spec.SandboxFilesystemSpec;
import io.agentscope.harness.agent.sandbox.SandboxClient;
import io.agentscope.harness.agent.sandbox.SandboxClientOptions;
import io.agentscope.harness.agent.sandbox.WorkspaceSpec;
import io.agentscope.harness.agent.sandbox.snapshot.NoopSnapshotSpec;
import io.agentscope.harness.agent.sandbox.snapshot.SandboxSnapshotSpec;

/** {@link SandboxFilesystemSpec} for E2B cloud sandboxes. */
public class E2bFilesystemSpec extends SandboxFilesystemSpec {

    private SandboxClient<?> client;
    private final E2bSandboxClientOptions options = new E2bSandboxClientOptions();
    private SandboxSnapshotSpec snapshotSpec = new NoopSnapshotSpec();
    private WorkspaceSpec defaultWorkspaceSpec = new WorkspaceSpec();

    public E2bFilesystemSpec client(SandboxClient<?> client) {
        this.client = client;
        return this;
    }

    public E2bFilesystemSpec apiKey(String apiKey) {
        options.setApiKey(apiKey);
        return this;
    }

    public E2bFilesystemSpec apiBaseUrl(String apiBaseUrl) {
        options.setApiBaseUrl(apiBaseUrl);
        return this;
    }

    public E2bFilesystemSpec domain(String domain) {
        options.setDomain(domain);
        return this;
    }

    public E2bFilesystemSpec templateId(String templateId) {
        options.setTemplateId(templateId);
        return this;
    }

    public E2bFilesystemSpec workspaceRoot(String workspaceRoot) {
        options.setWorkspaceRoot(workspaceRoot);
        return this;
    }

    public E2bFilesystemSpec sandboxTimeoutSeconds(int sandboxTimeoutSeconds) {
        options.setSandboxTimeoutSeconds(sandboxTimeoutSeconds);
        return this;
    }

    public E2bFilesystemSpec runUser(String runUser) {
        options.setRunUser(runUser);
        return this;
    }

    public E2bFilesystemSpec persistenceMode(E2bPersistenceMode persistenceMode) {
        options.setPersistenceMode(persistenceMode);
        return this;
    }

    public E2bFilesystemSpec connectTimeoutSeconds(int connectTimeoutSeconds) {
        options.setConnectTimeoutSeconds(connectTimeoutSeconds);
        return this;
    }

    public E2bFilesystemSpec readTimeoutSeconds(int readTimeoutSeconds) {
        options.setReadTimeoutSeconds(readTimeoutSeconds);
        return this;
    }

    public E2bFilesystemSpec maxRetries(int maxRetries) {
        options.setMaxRetries(maxRetries);
        return this;
    }

    public E2bFilesystemSpec snapshotSpec(SandboxSnapshotSpec snapshotSpec) {
        this.snapshotSpec = snapshotSpec;
        return this;
    }

    public E2bFilesystemSpec workspaceSpec(WorkspaceSpec workspaceSpec) {
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
