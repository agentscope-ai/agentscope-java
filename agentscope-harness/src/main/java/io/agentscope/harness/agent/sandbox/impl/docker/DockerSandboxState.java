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
package io.agentscope.harness.agent.sandbox.impl.docker;

import io.agentscope.harness.agent.sandbox.SandboxState;
import java.util.ArrayList;
import java.util.List;

/**
 * Serializable state for a Docker-backed {@link io.agentscope.harness.agent.sandbox.Sandbox}.
 *
 * <p>Persisted after each call to enable transparent container resume. If the container
 * identified by {@link #containerId} is still alive on resume, the sandbox reconnects. If
 * it is stopped, it is restarted. If it has been removed, a new container is created and the
 * workspace is restored from snapshot.
 */
public class DockerSandboxState extends SandboxState {

    /** Docker container ID of the backing container. */
    private String containerId;

    /** Human-readable container name (e.g. {@code agentscope-sandbox-<sessionId>}). */
    private String containerName;

    /** Docker image used to create this container. */
    private String image;

    /** Workspace root path inside the container. */
    private String workspaceRoot;

    /**
     * Whether the SDK owns the container lifecycle (create/stop/remove).
     * When {@code false}, the container was injected by the developer and will not be removed.
     */
    private boolean containerOwned = true;

    /** Optional memory limit in bytes stored for container recreation on resume. */
    private Long memorySizeBytes;

    /** Optional CPU count limit stored for container recreation on resume. */
    private Long cpuCount;

    /** Exposed port numbers stored for container recreation on resume. */
    private int[] exposedPorts = {};

    /** Docker network mode or network name passed to {@code docker run --network}. */
    private String network;

    /** Additional raw arguments appended to {@code docker run} before the image name. */
    private List<String> additionalRunArgs = new ArrayList<>();

    /**
     * Returns the Docker container ID.
     *
     * @return container ID, or {@code null} if the container has not been created yet
     */
    public String getContainerId() {
        return containerId;
    }

    /**
     * Sets the Docker container ID.
     *
     * @param containerId Docker container ID
     */
    public void setContainerId(String containerId) {
        this.containerId = containerId;
    }

    /**
     * Returns the container name.
     *
     * @return container name
     */
    public String getContainerName() {
        return containerName;
    }

    /**
     * Sets the container name.
     *
     * @param containerName container name
     */
    public void setContainerName(String containerName) {
        this.containerName = containerName;
    }

    /**
     * Returns the Docker image used for this container.
     *
     * @return Docker image
     */
    public String getImage() {
        return image;
    }

    /**
     * Sets the Docker image.
     *
     * @param image Docker image
     */
    public void setImage(String image) {
        this.image = image;
    }

    /**
     * Returns the workspace root path inside the container.
     *
     * @return workspace root path
     */
    public String getWorkspaceRoot() {
        return workspaceRoot;
    }

    /**
     * Sets the workspace root path inside the container.
     *
     * @param workspaceRoot absolute path inside the container
     */
    public void setWorkspaceRoot(String workspaceRoot) {
        this.workspaceRoot = workspaceRoot;
    }

    /**
     * Returns whether the SDK owns the container lifecycle.
     *
     * @return {@code true} if the SDK manages container creation and removal
     */
    public boolean isContainerOwned() {
        return containerOwned;
    }

    /**
     * Sets whether the SDK owns the container lifecycle.
     *
     * @param containerOwned {@code true} if the SDK should stop and remove the container on shutdown
     */
    public void setContainerOwned(boolean containerOwned) {
        this.containerOwned = containerOwned;
    }

    /**
     * Returns the optional memory limit in bytes.
     *
     * @return memory limit or {@code null}
     */
    public Long getMemorySizeBytes() {
        return memorySizeBytes;
    }

    /**
     * Sets the memory limit in bytes.
     *
     * @param memorySizeBytes memory limit
     */
    public void setMemorySizeBytes(Long memorySizeBytes) {
        this.memorySizeBytes = memorySizeBytes;
    }

    /**
     * Returns the optional CPU count limit.
     *
     * @return CPU count or {@code null}
     */
    public Long getCpuCount() {
        return cpuCount;
    }

    /**
     * Sets the CPU count limit.
     *
     * @param cpuCount number of CPUs
     */
    public void setCpuCount(Long cpuCount) {
        this.cpuCount = cpuCount;
    }

    /**
     * Returns the exposed port numbers.
     *
     * @return port numbers array
     */
    public int[] getExposedPorts() {
        return exposedPorts;
    }

    /**
     * Sets the exposed port numbers.
     *
     * @param exposedPorts port numbers
     */
    public void setExposedPorts(int[] exposedPorts) {
        this.exposedPorts = exposedPorts != null ? exposedPorts : new int[0];
    }

    /**
     * Returns the docker network mode or network name.
     *
     * @return docker network value, or {@code null} when unset
     */
    public String getNetwork() {
        return network;
    }

    /**
     * Sets the docker network mode or network name.
     *
     * @param network docker network value
     */
    public void setNetwork(String network) {
        if (network == null) {
            this.network = null;
            return;
        }
        String trimmed = network.trim();
        this.network = trimmed.isEmpty() ? null : trimmed;
    }

    /**
     * Returns additional raw arguments appended to {@code docker run}.
     *
     * @return additional docker run arguments
     */
    public List<String> getAdditionalRunArgs() {
        return additionalRunArgs;
    }

    /**
     * Sets additional raw arguments appended to {@code docker run}.
     *
     * @param additionalRunArgs additional docker run arguments
     */
    public void setAdditionalRunArgs(List<String> additionalRunArgs) {
        this.additionalRunArgs = new ArrayList<>();
        if (additionalRunArgs == null) {
            return;
        }
        for (String additionalRunArg : additionalRunArgs) {
            if (additionalRunArg != null && !additionalRunArg.isBlank()) {
                this.additionalRunArgs.add(additionalRunArg);
            }
        }
    }
}
