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

import io.agentscope.harness.agent.sandbox.SandboxState;

/** Serializable state for an AgentRun-backed sandbox. */
public class AgentRunSandboxState extends SandboxState {

    /** Default workspace root path inside an AgentRun sandbox container. */
    public static final String DEFAULT_WORKSPACE_ROOT = "/home/agentscope/workspace";

    private String sandboxId;
    private String workspaceRoot = DEFAULT_WORKSPACE_ROOT;
    private String templateName;
    private String accountId;
    private String region;
    private String mcpServerUrl;
    private boolean sandboxOwned = true;
    private boolean workspaceOnNas = false;

    public String getSandboxId() {
        return sandboxId;
    }

    public void setSandboxId(String sandboxId) {
        this.sandboxId = sandboxId;
    }

    public String getWorkspaceRoot() {
        return workspaceRoot;
    }

    public void setWorkspaceRoot(String workspaceRoot) {
        this.workspaceRoot = workspaceRoot != null ? workspaceRoot : DEFAULT_WORKSPACE_ROOT;
    }

    public String getTemplateName() {
        return templateName;
    }

    public void setTemplateName(String templateName) {
        this.templateName = templateName;
    }

    public String getAccountId() {
        return accountId;
    }

    public void setAccountId(String accountId) {
        this.accountId = accountId;
    }

    public String getRegion() {
        return region;
    }

    public void setRegion(String region) {
        this.region = region;
    }

    public String getMcpServerUrl() {
        return mcpServerUrl;
    }

    public void setMcpServerUrl(String mcpServerUrl) {
        this.mcpServerUrl = mcpServerUrl;
    }

    public boolean isSandboxOwned() {
        return sandboxOwned;
    }

    public void setSandboxOwned(boolean sandboxOwned) {
        this.sandboxOwned = sandboxOwned;
    }

    /**
     * Returns whether {@link #workspaceRoot} lives on a NAS or OSS persistent mount.
     *
     * <p>When {@code true}, the workspace survives sandbox deletion and the adapter skips tar-based
     * persistence; "resume" is achieved by recreating the sandbox with the same deterministic id
     * and the same mount configuration.
     *
     * @return true when the workspace root is backed by a persistent mount
     */
    public boolean isWorkspaceOnNas() {
        return workspaceOnNas;
    }

    public void setWorkspaceOnNas(boolean workspaceOnNas) {
        this.workspaceOnNas = workspaceOnNas;
    }
}
