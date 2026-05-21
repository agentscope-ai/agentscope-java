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
package io.agentscope.harness.agent.sandbox;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.annotation.JsonTypeInfo;
import io.agentscope.harness.agent.sandbox.snapshot.SandboxSnapshot;

/**
 * Serializable state of a sandbox, persisted by {@link SandboxStateStore} so a sandbox can
 * be resumed across calls.
 *
 * <p>The {@link #workspaceRootReady} flag drives the 4-branch start logic: it records whether
 * the workspace was fully initialized at the last stop.
 *
 * <p>Concrete subtypes are registered for Jackson via {@link
 * io.agentscope.harness.agent.sandbox.json.HarnessSandboxJacksonModule} (and optional {@link
 * com.fasterxml.jackson.databind.ObjectMapper#registerSubtypes} for extension types), not via
 * {@code @JsonSubTypes} on this class.
 */
@JsonTypeInfo(use = JsonTypeInfo.Id.NAME, include = JsonTypeInfo.As.PROPERTY, property = "type")
public abstract class SandboxState {

    private String sessionId;

    @JsonProperty("manifest")
    private WorkspaceSpec workspaceSpec;

    private SandboxSnapshot snapshot;
    private String workspaceProjectionHash;
    private boolean workspaceRootReady = false;

    public String getSessionId() {
        return sessionId;
    }

    public void setSessionId(String sessionId) {
        this.sessionId = sessionId;
    }

    public WorkspaceSpec getWorkspaceSpec() {
        return workspaceSpec;
    }

    public void setWorkspaceSpec(WorkspaceSpec workspaceSpec) {
        this.workspaceSpec = workspaceSpec;
    }

    public SandboxSnapshot getSnapshot() {
        return snapshot;
    }

    public void setSnapshot(SandboxSnapshot snapshot) {
        this.snapshot = snapshot;
    }

    public String getWorkspaceProjectionHash() {
        return workspaceProjectionHash;
    }

    public void setWorkspaceProjectionHash(String workspaceProjectionHash) {
        this.workspaceProjectionHash = workspaceProjectionHash;
    }

    public boolean isWorkspaceRootReady() {
        return workspaceRootReady;
    }

    public void setWorkspaceRootReady(boolean workspaceRootReady) {
        this.workspaceRootReady = workspaceRootReady;
    }
}
