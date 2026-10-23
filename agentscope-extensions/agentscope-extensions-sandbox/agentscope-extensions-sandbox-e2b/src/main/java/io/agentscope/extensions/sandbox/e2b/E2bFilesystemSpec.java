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
package io.agentscope.extensions.sandbox.e2b;

import io.agentscope.harness.agent.filesystem.spec.SandboxFilesystemSpec;
import io.agentscope.harness.agent.sandbox.SandboxClient;
import io.agentscope.harness.agent.sandbox.SandboxClientOptions;
import io.agentscope.harness.agent.sandbox.WorkspaceSpec;
import io.agentscope.harness.agent.sandbox.snapshot.NoopSnapshotSpec;
import io.agentscope.harness.agent.sandbox.snapshot.SandboxSnapshotSpec;
import java.util.List;
import java.util.function.Supplier;

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

    /**
     * Sets a fixed envd gateway URL, equivalent to the official SDK's {@code E2B_SANDBOX_URL}.
     * When configured, envd traffic for every sandbox is sent to this gateway and routed by the
     * {@code E2b-Sandbox-Id}/{@code E2b-Sandbox-Port} request headers instead of wildcard-DNS
     * hosts derived from {@code domain}.
     *
     * @param envdSandboxUrl fixed data-plane gateway URL for envd requests
     * @return this spec
     */
    public E2bFilesystemSpec envdSandboxUrl(String envdSandboxUrl) {
        options.setEnvdSandboxUrl(envdSandboxUrl);
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

    /** Registers a sandbox-creation environment variable with a fixed value. */
    public E2bFilesystemSpec env(String name, String value) {
        options.env(name, value);
        return this;
    }

    /**
     * Registers a sandbox-creation environment variable whose value is resolved on every sandbox
     * creation — use this for short-lived values such as access tokens.
     */
    public E2bFilesystemSpec env(String name, Supplier<String> valueSupplier) {
        options.env(name, valueSupplier);
        return this;
    }

    public E2bFilesystemSpec persistenceMode(E2bPersistenceMode persistenceMode) {
        options.setPersistenceMode(persistenceMode);
        return this;
    }

    public E2bFilesystemSpec codec(E2bCodec codec) {
        options.setCodec(codec);
        return this;
    }

    public E2bFilesystemSpec volumeMount(String name, String path) {
        options.addVolumeMount(name, path);
        return this;
    }

    public E2bFilesystemSpec volumeMount(E2bVolumeMount mount) {
        options.addVolumeMount(mount);
        return this;
    }

    /**
     * Replaces all configured volume mounts.
     *
     * <p>A null or empty list clears mounts on this spec, but note that {@link E2bSandboxClient}
     * merging treats an empty call-level list as "not set" (same convention as AgentRun OSS
     * mounts), so clearing only takes effect when these options are also the client defaults.
     *
     * @param mounts replacement mounts (null or empty clears previously added ones)
     */
    public E2bFilesystemSpec volumeMounts(List<E2bVolumeMount> mounts) {
        options.setVolumeMounts(mounts);
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

    /**
     * Maximum number of snapshots created by this session that are kept on shutdown; {@code <= 0}
     * disables pruning. Defaults to {@code 0} (disabled), matching the historical no-pruning
     * behaviour. See {@link E2bSandboxClientOptions#setSnapshotRetention(int)} for the full
     * contract.
     */
    public E2bFilesystemSpec snapshotRetention(int snapshotRetention) {
        options.setSnapshotRetention(snapshotRetention);
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
