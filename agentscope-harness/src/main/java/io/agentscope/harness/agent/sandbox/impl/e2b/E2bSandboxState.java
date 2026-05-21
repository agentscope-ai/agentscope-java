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

import io.agentscope.harness.agent.sandbox.SandboxState;

/** Serializable state for an E2B-backed sandbox. */
public class E2bSandboxState extends SandboxState {

    private String sandboxId;
    private String templateId = "base";
    private String sandboxDomain;
    private String envdAccessToken;
    private String envdVersion = "0.1.5";
    private String workspaceRoot = "/home/user";
    private boolean sandboxOwned = true;
    private E2bPersistenceMode persistenceMode = E2bPersistenceMode.TAR;

    public String getSandboxId() {
        return sandboxId;
    }

    public void setSandboxId(String sandboxId) {
        this.sandboxId = sandboxId;
    }

    public String getTemplateId() {
        return templateId;
    }

    public void setTemplateId(String templateId) {
        this.templateId = templateId;
    }

    public String getSandboxDomain() {
        return sandboxDomain;
    }

    public void setSandboxDomain(String sandboxDomain) {
        this.sandboxDomain = sandboxDomain;
    }

    public String getEnvdAccessToken() {
        return envdAccessToken;
    }

    public void setEnvdAccessToken(String envdAccessToken) {
        this.envdAccessToken = envdAccessToken;
    }

    public String getEnvdVersion() {
        return envdVersion;
    }

    public void setEnvdVersion(String envdVersion) {
        this.envdVersion = envdVersion;
    }

    public String getWorkspaceRoot() {
        return workspaceRoot;
    }

    public void setWorkspaceRoot(String workspaceRoot) {
        this.workspaceRoot = workspaceRoot;
    }

    public boolean isSandboxOwned() {
        return sandboxOwned;
    }

    public void setSandboxOwned(boolean sandboxOwned) {
        this.sandboxOwned = sandboxOwned;
    }

    public E2bPersistenceMode getPersistenceMode() {
        return persistenceMode;
    }

    public void setPersistenceMode(E2bPersistenceMode persistenceMode) {
        this.persistenceMode = persistenceMode != null ? persistenceMode : E2bPersistenceMode.TAR;
    }
}
