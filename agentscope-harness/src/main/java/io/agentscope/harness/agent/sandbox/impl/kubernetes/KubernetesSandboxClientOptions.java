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
package io.agentscope.harness.agent.sandbox.impl.kubernetes;

import io.agentscope.harness.agent.sandbox.SandboxClient;
import io.agentscope.harness.agent.sandbox.SandboxClientOptions;
import io.fabric8.kubernetes.client.Config;
import io.fabric8.kubernetes.client.KubernetesClient;
import java.util.HashMap;
import java.util.Map;

/** Options for {@link KubernetesSandboxClient}. */
public class KubernetesSandboxClientOptions extends SandboxClientOptions {

    private KubernetesClient kubernetesClient;
    private Config kubernetesConfig;
    private String namespace = "default";
    private String image = "ubuntu:22.04";
    private String containerName = "workspace";
    private String workspaceRoot = "/workspace";
    private String serviceAccount;
    private Map<String, String> nodeSelector = new HashMap<>();
    private Map<String, String> podLabels = new HashMap<>();
    private String cpuRequest;
    private String memoryRequest;

    @Override
    public String getType() {
        return "kubernetes";
    }

    @Override
    public SandboxClient<? extends SandboxClientOptions> createClient() {
        return new KubernetesSandboxClient(this, null);
    }

    /**
     * Returns an explicit Kubernetes client. When {@code null}, a client is built from {@link
     * #getKubernetesConfig()} or the in-cluster / default kubeconfig chain.
     */
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

    public String getImage() {
        return image;
    }

    public void setImage(String image) {
        this.image = image;
    }

    public String getContainerName() {
        return containerName;
    }

    public void setContainerName(String containerName) {
        this.containerName = containerName != null ? containerName : "workspace";
    }

    public String getWorkspaceRoot() {
        return workspaceRoot;
    }

    public void setWorkspaceRoot(String workspaceRoot) {
        this.workspaceRoot = workspaceRoot != null ? workspaceRoot : "/workspace";
    }

    public String getServiceAccount() {
        return serviceAccount;
    }

    public void setServiceAccount(String serviceAccount) {
        this.serviceAccount = serviceAccount;
    }

    public Map<String, String> getNodeSelector() {
        return nodeSelector;
    }

    public void setNodeSelector(Map<String, String> nodeSelector) {
        this.nodeSelector = nodeSelector != null ? new HashMap<>(nodeSelector) : new HashMap<>();
    }

    public Map<String, String> getPodLabels() {
        return podLabels;
    }

    public void setPodLabels(Map<String, String> podLabels) {
        this.podLabels = podLabels != null ? new HashMap<>(podLabels) : new HashMap<>();
    }

    public String getCpuRequest() {
        return cpuRequest;
    }

    public void setCpuRequest(String cpuRequest) {
        this.cpuRequest = cpuRequest;
    }

    public String getMemoryRequest() {
        return memoryRequest;
    }

    public void setMemoryRequest(String memoryRequest) {
        this.memoryRequest = memoryRequest;
    }
}
