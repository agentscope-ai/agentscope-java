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

import io.agentscope.harness.agent.sandbox.SandboxState;

/** Serializable state for an agent-sandbox-backed Kubernetes sandbox. */
public class KubernetesSandboxState extends SandboxState {

    private String namespace;
    private String claimName;
    private String sandboxName;
    private String warmPoolName;
    private String podName;
    private String podIP;

    /**
     * Base directory of the runtime file API ({@code /upload}, {@code /download}); paths sent
     * to the file API are resolved relative to it. Null or blank disables file-API transfer.
     */
    private String fileApiBaseDir = "/workspace";

    private boolean claimOwned = true;

    public String getNamespace() {
        return namespace;
    }

    public void setNamespace(String namespace) {
        this.namespace = namespace;
    }

    public String getClaimName() {
        return claimName;
    }

    public void setClaimName(String claimName) {
        this.claimName = claimName;
    }

    public String getSandboxName() {
        return sandboxName;
    }

    public void setSandboxName(String sandboxName) {
        this.sandboxName = sandboxName;
    }

    public String getWarmPoolName() {
        return warmPoolName;
    }

    public void setWarmPoolName(String warmPoolName) {
        this.warmPoolName = warmPoolName;
    }

    public String getPodName() {
        return podName;
    }

    public void setPodName(String podName) {
        this.podName = podName;
    }

    public String getPodIP() {
        return podIP;
    }

    public void setPodIP(String podIP) {
        this.podIP = podIP;
    }

    public String getFileApiBaseDir() {
        return fileApiBaseDir;
    }

    public void setFileApiBaseDir(String fileApiBaseDir) {
        this.fileApiBaseDir = fileApiBaseDir;
    }

    public boolean isClaimOwned() {
        return claimOwned;
    }

    public void setClaimOwned(boolean claimOwned) {
        this.claimOwned = claimOwned;
    }
}
