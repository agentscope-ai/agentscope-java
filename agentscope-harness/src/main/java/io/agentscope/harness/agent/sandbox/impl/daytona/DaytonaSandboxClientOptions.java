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

import io.agentscope.harness.agent.sandbox.SandboxClient;
import io.agentscope.harness.agent.sandbox.SandboxClientOptions;
import okhttp3.OkHttpClient;

/** Options for {@link DaytonaSandboxClient}. */
public class DaytonaSandboxClientOptions extends SandboxClientOptions {

    private OkHttpClient httpClient;
    private String apiKey;
    private String controlPlaneBaseUrl = "https://app.daytona.io/api";
    private String toolboxBaseUrl = "https://proxy.app.daytona.io";
    private String image = "ubuntu:22.04";
    private String snapshotId;
    private Integer cpu = 1;
    private Integer memory = 1;
    private Integer disk = 3;
    private String workspaceRoot = DaytonaSandboxState.DEFAULT_WORKSPACE_ROOT;
    private int connectTimeoutSeconds = 30;
    private int readTimeoutSeconds = 120;
    private int maxRetries = 3;

    @Override
    public String getType() {
        return "daytona";
    }

    @Override
    public SandboxClient<? extends SandboxClientOptions> createClient() {
        return new DaytonaSandboxClient(this, null);
    }

    public OkHttpClient getHttpClient() {
        return httpClient;
    }

    public void setHttpClient(OkHttpClient httpClient) {
        this.httpClient = httpClient;
    }

    public String getApiKey() {
        return apiKey;
    }

    public void setApiKey(String apiKey) {
        this.apiKey = apiKey;
    }

    public String getControlPlaneBaseUrl() {
        return controlPlaneBaseUrl;
    }

    public void setControlPlaneBaseUrl(String controlPlaneBaseUrl) {
        this.controlPlaneBaseUrl = controlPlaneBaseUrl;
    }

    public String getToolboxBaseUrl() {
        return toolboxBaseUrl;
    }

    public void setToolboxBaseUrl(String toolboxBaseUrl) {
        this.toolboxBaseUrl = toolboxBaseUrl;
    }

    public String getImage() {
        return image;
    }

    public void setImage(String image) {
        this.image = image;
    }

    public String getSnapshotId() {
        return snapshotId;
    }

    public void setSnapshotId(String snapshotId) {
        this.snapshotId = snapshotId;
    }

    public Integer getCpu() {
        return cpu;
    }

    public void setCpu(Integer cpu) {
        this.cpu = cpu;
    }

    public Integer getMemory() {
        return memory;
    }

    public void setMemory(Integer memory) {
        this.memory = memory;
    }

    public Integer getDisk() {
        return disk;
    }

    public void setDisk(Integer disk) {
        this.disk = disk;
    }

    public String getWorkspaceRoot() {
        return workspaceRoot;
    }

    public void setWorkspaceRoot(String workspaceRoot) {
        this.workspaceRoot =
                workspaceRoot != null ? workspaceRoot : DaytonaSandboxState.DEFAULT_WORKSPACE_ROOT;
    }

    public int getConnectTimeoutSeconds() {
        return connectTimeoutSeconds;
    }

    public void setConnectTimeoutSeconds(int connectTimeoutSeconds) {
        this.connectTimeoutSeconds = connectTimeoutSeconds;
    }

    public int getReadTimeoutSeconds() {
        return readTimeoutSeconds;
    }

    public void setReadTimeoutSeconds(int readTimeoutSeconds) {
        this.readTimeoutSeconds = readTimeoutSeconds;
    }

    public int getMaxRetries() {
        return maxRetries;
    }

    public void setMaxRetries(int maxRetries) {
        this.maxRetries = maxRetries;
    }
}
