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

import io.agentscope.harness.agent.sandbox.SandboxClient;
import io.agentscope.harness.agent.sandbox.SandboxClientOptions;
import io.fabric8.kubernetes.client.Config;
import io.fabric8.kubernetes.client.KubernetesClient;

/** Options for {@link KubernetesSandboxClient} (agent-sandbox mode). */
public class KubernetesSandboxClientOptions extends SandboxClientOptions {

    private KubernetesClient kubernetesClient;
    private Config kubernetesConfig;

    // agent-sandbox core
    private String namespace = "default";
    private String warmPoolName;
    private String workspaceRoot = "/workspace";

    /**
     * Base directory of the runtime file API ({@code /upload} / {@code /download}), as
     * required by the AgentScope runtime image contract. Defaults to {@code /workspace},
     * matching the default workspace root. Workspace archives are transferred through the
     * file API using temp files under this directory; set to null or blank to disable and
     * use base64-over-exec instead.
     */
    private String fileApiBaseDir = "/workspace";

    // connection strategy
    private String apiUrl;
    private String gatewayName;
    private String gatewayNamespace;
    private String gatewayScheme = "http";
    private int serverPort = 8888;

    // timeouts
    private long sandboxReadyTimeoutSeconds = 180;
    private long cleanupTimeoutSeconds = 30;
    private long requestTimeoutSeconds = 180;
    private long perAttemptTimeoutSeconds = 60;
    private long portForwardTimeoutSeconds = 30;

    @Override
    public String getType() {
        return "kubernetes";
    }

    @Override
    public SandboxClient<? extends SandboxClientOptions> createClient() {
        return new KubernetesSandboxClient(this, null);
    }

    public KubernetesClient getKubernetesClient() {
        return kubernetesClient;
    }

    public void setKubernetesClient(KubernetesClient kubernetesClient) {
        this.kubernetesClient = kubernetesClient;
    }

    public Config getKubernetesConfig() {
        return kubernetesConfig;
    }

    public void setKubernetesConfig(Config kubernetesConfig) {
        this.kubernetesConfig = kubernetesConfig;
    }

    public String getNamespace() {
        return namespace;
    }

    public void setNamespace(String namespace) {
        this.namespace = namespace != null ? namespace : "default";
    }

    public String getWarmPoolName() {
        return warmPoolName;
    }

    public void setWarmPoolName(String warmPoolName) {
        this.warmPoolName = warmPoolName;
    }

    public String getWorkspaceRoot() {
        return workspaceRoot;
    }

    public void setWorkspaceRoot(String workspaceRoot) {
        this.workspaceRoot = workspaceRoot != null ? workspaceRoot : "/workspace";
    }

    public String getFileApiBaseDir() {
        return fileApiBaseDir;
    }

    public void setFileApiBaseDir(String fileApiBaseDir) {
        this.fileApiBaseDir = fileApiBaseDir;
    }

    public String getApiUrl() {
        return apiUrl;
    }

    public void setApiUrl(String apiUrl) {
        this.apiUrl = apiUrl;
    }

    public String getGatewayName() {
        return gatewayName;
    }

    public void setGatewayName(String gatewayName) {
        this.gatewayName = gatewayName;
    }

    public String getGatewayNamespace() {
        return gatewayNamespace;
    }

    public void setGatewayNamespace(String gatewayNamespace) {
        this.gatewayNamespace = gatewayNamespace;
    }

    public String getGatewayScheme() {
        return gatewayScheme;
    }

    public void setGatewayScheme(String gatewayScheme) {
        this.gatewayScheme = gatewayScheme;
    }

    public int getServerPort() {
        return serverPort;
    }

    public void setServerPort(int serverPort) {
        this.serverPort = serverPort;
    }

    public long getSandboxReadyTimeoutSeconds() {
        return sandboxReadyTimeoutSeconds;
    }

    public void setSandboxReadyTimeoutSeconds(long sandboxReadyTimeoutSeconds) {
        this.sandboxReadyTimeoutSeconds = sandboxReadyTimeoutSeconds;
    }

    public long getCleanupTimeoutSeconds() {
        return cleanupTimeoutSeconds;
    }

    public void setCleanupTimeoutSeconds(long cleanupTimeoutSeconds) {
        this.cleanupTimeoutSeconds = cleanupTimeoutSeconds;
    }

    public long getRequestTimeoutSeconds() {
        return requestTimeoutSeconds;
    }

    public void setRequestTimeoutSeconds(long requestTimeoutSeconds) {
        this.requestTimeoutSeconds = requestTimeoutSeconds;
    }

    public long getPerAttemptTimeoutSeconds() {
        return perAttemptTimeoutSeconds;
    }

    public void setPerAttemptTimeoutSeconds(long perAttemptTimeoutSeconds) {
        this.perAttemptTimeoutSeconds = perAttemptTimeoutSeconds;
    }

    public long getPortForwardTimeoutSeconds() {
        return portForwardTimeoutSeconds;
    }

    public void setPortForwardTimeoutSeconds(long portForwardTimeoutSeconds) {
        this.portForwardTimeoutSeconds = portForwardTimeoutSeconds;
    }
}
