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
package io.agentscope.extensions.sandbox.dockerdaemon;

import io.agentscope.harness.agent.sandbox.SandboxClient;
import io.agentscope.harness.agent.sandbox.SandboxClientOptions;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Configuration options for the docker-java based Docker daemon sandbox client.
 *
 * <p>Unlike the CLI-based {@code DockerSandboxClientOptions} in harness, this client talks to
 * the Docker daemon directly over its Engine HTTP API (via docker-java), so no docker CLI is
 * required on the host. Set {@link #daemonUrl} (or the {@code DOCKER_HOST} environment
 * variable) to reach a remote daemon over TCP.
 */
public class DockerDaemonSandboxClientOptions extends SandboxClientOptions {

    /** Docker image to run. Defaults to {@code ubuntu:22.04}. */
    private String image = "ubuntu:22.04";

    /** Workspace root path inside the container. Defaults to {@code /workspace}. */
    private String workspaceRoot = "/workspace";

    /** Environment variables to inject into the container at creation. */
    private Map<String, String> environment = new LinkedHashMap<>();

    /** Optional memory limit in bytes (e.g. {@code 512 * 1024 * 1024L} for 512 MB). */
    private Long memorySizeBytes;

    /** Optional CPU count limit (e.g. {@code 2L} for two CPUs). */
    private Long cpuCount;

    /** Host ports to expose from the container ({@code hostPort:containerPort} mapping). */
    private int[] exposedPorts = {};

    /** Docker network mode or network name ({@code docker run --network}). */
    private String network;

    /** Remote Docker daemon URL (e.g. {@code tcp://host:2375}); falls back to the
     * {@code DOCKER_HOST} environment variable when unset. */
    private String daemonUrl;

    /** Additional mounts applied to the container at creation (like {@code docker run --mount}). */
    private List<MountSpec> mounts = new ArrayList<>();

    @Override
    public String getType() {
        return "docker-daemon";
    }

    /**
     * Creates a {@link DockerDaemonSandboxClient} for these options.
     *
     * @return new Docker daemon sandbox client
     */
    @Override
    public SandboxClient<DockerDaemonSandboxClientOptions> createClient() {
        return new DockerDaemonSandboxClient();
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
    public DockerDaemonSandboxClientOptions image(String image) {
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
    public DockerDaemonSandboxClientOptions workspaceRoot(String workspaceRoot) {
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
    public DockerDaemonSandboxClientOptions memorySizeBytes(Long memorySizeBytes) {
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
    public DockerDaemonSandboxClientOptions cpuCount(Long cpuCount) {
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
    public DockerDaemonSandboxClientOptions exposedPorts(int... exposedPorts) {
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
    public DockerDaemonSandboxClientOptions network(String network) {
        this.network = normalize(network);
        return this;
    }

    /**
     * Sets the docker network mode or network name.
     *
     * @param network docker network value
     */
    public void setNetwork(String network) {
        this.network = normalize(network);
    }

    /**
     * Returns the remote Docker daemon URL.
     *
     * @return daemon URL, or {@code null} when unset
     */
    public String getDaemonUrl() {
        return daemonUrl;
    }

    /**
     * Sets the remote Docker daemon URL.
     *
     * @param daemonUrl daemon URL (e.g. {@code tcp://host:2375} or {@code https://host:2376})
     * @return this options instance
     */
    public DockerDaemonSandboxClientOptions daemonUrl(String daemonUrl) {
        this.daemonUrl = normalize(daemonUrl);
        return this;
    }

    /**
     * Sets the remote Docker daemon URL.
     *
     * @param daemonUrl daemon URL
     */
    public void setDaemonUrl(String daemonUrl) {
        this.daemonUrl = normalize(daemonUrl);
    }

    /**
     * Returns the additional container mounts.
     *
     * @return mounts list
     */
    public List<MountSpec> getMounts() {
        return mounts;
    }

    /**
     * Sets the additional container mounts.
     *
     * @param mounts mounts list
     */
    public void setMounts(List<MountSpec> mounts) {
        this.mounts = mounts != null ? new ArrayList<>(mounts) : new ArrayList<>();
    }

    /**
     * Appends a container mount, mirroring {@code docker run --mount}.
     *
     * @param type mount type: {@code bind}, {@code volume} or {@code tmpfs}
     * @param source host path (bind), volume name (volume), or {@code null} (tmpfs)
     * @param target container path
     * @return this options instance
     */
    public DockerDaemonSandboxClientOptions mount(String type, String source, String target) {
        return mount(type, source, target, false);
    }

    /**
     * Appends a container mount with read-only flag.
     *
     * @param type mount type: {@code bind}, {@code volume} or {@code tmpfs}
     * @param source host path (bind), volume name (volume), or {@code null} (tmpfs)
     * @param target container path
     * @param readOnly whether the mount is read-only
     * @return this options instance
     */
    public DockerDaemonSandboxClientOptions mount(
            String type, String source, String target, boolean readOnly) {
        mounts.add(MountSpec.of(type, source, target, readOnly));
        return this;
    }

    private static String normalize(String value) {
        if (value == null) {
            return null;
        }
        String trimmed = value.trim();
        return trimmed.isEmpty() ? null : trimmed;
    }
}
