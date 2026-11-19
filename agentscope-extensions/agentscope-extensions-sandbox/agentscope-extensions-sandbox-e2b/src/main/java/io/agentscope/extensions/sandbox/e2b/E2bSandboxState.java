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

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import io.agentscope.harness.agent.sandbox.SandboxState;
import java.util.ArrayList;
import java.util.List;

/** Serializable state for an E2B-backed sandbox. */
@JsonIgnoreProperties(ignoreUnknown = true)
public class E2bSandboxState extends SandboxState {

    private String sandboxId;
    private String templateId = "base";
    private String sandboxDomain;
    private String envdSandboxUrl;
    private String envdAccessToken;
    private String envdVersion = "0.1.5";
    private String workspaceRoot = "/home/user";
    private boolean sandboxOwned = true;
    private E2bPersistenceMode persistenceMode = E2bPersistenceMode.TAR;
    private E2bCodec codec = E2bCodec.PROTO;

    /**
     * Snapshot ids previously created by this session, most recent last. Persisted with the state
     * so pruning never depends on the (chain-changing) source sandbox id or a full snapshot list.
     */
    private List<String> snapshotIds = new ArrayList<>();

    /** Volume mounts attached at creation time (empty when none). */
    private List<E2bVolumeMount> volumeMounts = new ArrayList<>();

    /**
     * Whether {@link #workspaceRoot} lives on a volume mount. When true, workspace bytes are
     * durable via the volume: TAR persistence is a no-op (native snapshots still run to preserve
     * the software environment) and destroy skips {@code rm -rf}.
     */
    private boolean workspaceOnVolume = false;

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

    public String getEnvdSandboxUrl() {
        return envdSandboxUrl;
    }

    public void setEnvdSandboxUrl(String envdSandboxUrl) {
        this.envdSandboxUrl = envdSandboxUrl;
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

    public E2bCodec getCodec() {
        return codec;
    }

    public void setCodec(E2bCodec codec) {
        this.codec = codec != null ? codec : E2bCodec.PROTO;
    }

    public List<String> getSnapshotIds() {
        return snapshotIds;
    }

    public void setSnapshotIds(List<String> snapshotIds) {
        this.snapshotIds = snapshotIds != null ? snapshotIds : new ArrayList<>();
    }

    /**
     * Returns the volume mounts attached at creation time.
     *
     * @return defensive copy (never null; empty for sessions persisted before volumes support)
     */
    public List<E2bVolumeMount> getVolumeMounts() {
        if (volumeMounts == null) {
            return new ArrayList<>();
        }
        List<E2bVolumeMount> out = new ArrayList<>(volumeMounts.size());
        for (E2bVolumeMount m : volumeMounts) {
            if (m == null) {
                continue;
            }
            out.add(new E2bVolumeMount(m.getName(), m.getPath()));
        }
        return out;
    }

    public void setVolumeMounts(List<E2bVolumeMount> volumeMounts) {
        List<E2bVolumeMount> checked = new ArrayList<>();
        if (volumeMounts != null) {
            for (E2bVolumeMount m : volumeMounts) {
                if (m == null) {
                    continue;
                }
                checked.add(new E2bVolumeMount(m.getName(), m.getPath()));
            }
        }
        E2bVolumeMount.requireDistinctMountPaths(checked);
        this.volumeMounts = checked;
    }

    public boolean isWorkspaceOnVolume() {
        return workspaceOnVolume;
    }

    public void setWorkspaceOnVolume(boolean workspaceOnVolume) {
        this.workspaceOnVolume = workspaceOnVolume;
    }
}
