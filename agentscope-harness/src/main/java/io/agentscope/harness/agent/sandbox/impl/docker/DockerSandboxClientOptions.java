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

import io.agentscope.harness.agent.sandbox.SandboxClient;
import io.agentscope.harness.agent.sandbox.SandboxClientOptions;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Configuration options for the Docker sandbox client.
 *
 * <p>Describes the Docker container configuration used when creating or resuming
 * a {@link io.agentscope.harness.agent.sandbox.impl.docker.DockerSandbox}. The Docker CLI must be
 * available on the host
 * system's {@code PATH}.
 */
public class DockerSandboxClientOptions extends SandboxClientOptions {

    /** Docker image to run. Defaults to {@code ubuntu:22.04}. */
    private String image = "ubuntu:22.04";

    /** Workspace root path inside the container. Defaults to {@code /workspace}. */
    private String workspaceRoot = "/workspace";

    /** Environment variables to inject into the container. */
    private Map<String, String> environment = new LinkedHashMap<>();

    /** Optional memory limit in bytes (e.g. {@code 512 * 1024 * 1024L} for 512 MB). */
    private Long memorySizeBytes;

    /** Optional CPU count limit (e.g. {@code 2L} for two CPUs). */
    private Long cpuCount;

    /** Host ports to expose from the container ({@code hostPort:containerPort} mapping). */
    private int[] exposedPorts = {};

    /** Additional raw arguments appended to {@code docker run} before the image name. */
    private List<String> additionalRunArgs = new ArrayList<>();

    /** Docker network mode or network name passed to {@code docker run --network}. */
    private String network;

    @Override
    public String getType() {
        return "docker";
    }

    /**
     * Creates a {@link DockerSandboxClient} for these options.
     *
     * @return new Docker sandbox client
     */
    @Override
    public SandboxClient<DockerSandboxClientOptions> createClient() {
        return new DockerSandboxClient();
    }

    /**
     * Returns the Docker image name.
     *
     * @return Docker image
     */
    public String getImage() {
        return image;
    }

    /**
     * Sets the Docker image name.
     *
     * @param image Docker image (e.g. {@code python:3.12-slim})
     * @return this options instance
     */
    public DockerSandboxClientOptions image(String image) {
        this.image = image;
        return this;
    }

    /**
     * Sets the Docker image name.
     *
     * @param image Docker image
     */
    public void setImage(String image) {
        this.image = image;
    }

    /**
     * Returns the workspace root path inside the container.
     *
     * @return workspace root
     */
    public String getWorkspaceRoot() {
        return workspaceRoot;
    }

    /**
     * Sets the workspace root path inside the container.
     *
     * @param workspaceRoot absolute path inside the container
     * @return this options instance
     */
    public DockerSandboxClientOptions workspaceRoot(String workspaceRoot) {
        this.workspaceRoot = workspaceRoot;
        return this;
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
     * Returns the container environment variables.
     *
     * @return environment variable map
     */
    public Map<String, String> getEnvironment() {
        return environment;
    }

    /**
     * Sets the container environment variables.
     *
     * @param environment key-value pairs
     */
    public void setEnvironment(Map<String, String> environment) {
        this.environment = environment != null ? environment : new LinkedHashMap<>();
    }

    /**
     * Returns the optional memory limit in bytes.
     *
     * @return memory limit or {@code null} if not set
     */
    public Long getMemorySizeBytes() {
        return memorySizeBytes;
    }

    /**
     * Sets the memory limit in bytes.
     *
     * @param memorySizeBytes memory limit (e.g. {@code 512 * 1024 * 1024L})
     * @return this options instance
     */
    public DockerSandboxClientOptions memorySizeBytes(Long memorySizeBytes) {
        this.memorySizeBytes = memorySizeBytes;
        return this;
    }

    /**
     * Sets the memory limit in bytes.
     *
     * @param memorySizeBytes memory limit in bytes
     */
    public void setMemorySizeBytes(Long memorySizeBytes) {
        this.memorySizeBytes = memorySizeBytes;
    }

    /**
     * Returns the optional CPU count limit.
     *
     * @return CPU count or {@code null} if not set
     */
    public Long getCpuCount() {
        return cpuCount;
    }

    /**
     * Sets the CPU count limit.
     *
     * @param cpuCount number of CPUs (e.g. {@code 2L})
     * @return this options instance
     */
    public DockerSandboxClientOptions cpuCount(Long cpuCount) {
        this.cpuCount = cpuCount;
        return this;
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
     * Returns the host ports to expose.
     *
     * @return exposed ports array
     */
    public int[] getExposedPorts() {
        return exposedPorts;
    }

    /**
     * Sets the host ports to expose from the container.
     *
     * @param exposedPorts port numbers
     * @return this options instance
     */
    public DockerSandboxClientOptions exposedPorts(int... exposedPorts) {
        this.exposedPorts = exposedPorts;
        return this;
    }

    /**
     * Sets the host ports to expose from the container.
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
     * @return this options instance
     */
    public DockerSandboxClientOptions network(String network) {
        this.network = normalizeNetwork(network);
        return this;
    }

    /**
     * Sets the docker network mode or network name.
     *
     * @param network docker network value
     */
    public void setNetwork(String network) {
        this.network = normalizeNetwork(network);
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
     * Appends additional raw arguments to {@code docker run} before the image name.
     *
     * @param additionalRunArgs additional docker run arguments
     * @return this options instance
     */
    public DockerSandboxClientOptions additionalRunArgs(String... additionalRunArgs) {
        if (additionalRunArgs == null) {
            return this;
        }
        for (String additionalRunArg : additionalRunArgs) {
            if (additionalRunArg != null && !additionalRunArg.isBlank()) {
                this.additionalRunArgs.add(additionalRunArg);
            }
        }
        return this;
    }

    /**
     * Sets the additional raw arguments appended to {@code docker run}.
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

    private String normalizeNetwork(String network) {
        if (network == null) {
            return null;
        }
        String trimmed = network.trim();
        return trimmed.isEmpty() ? null : trimmed;
    }
}
