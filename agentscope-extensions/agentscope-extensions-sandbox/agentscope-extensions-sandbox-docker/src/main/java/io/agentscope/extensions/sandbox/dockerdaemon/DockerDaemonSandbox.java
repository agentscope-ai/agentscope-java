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

import com.github.dockerjava.api.DockerClient;
import com.github.dockerjava.api.command.CreateContainerCmd;
import com.github.dockerjava.api.command.CreateContainerResponse;
import com.github.dockerjava.api.command.ExecCreateCmd;
import com.github.dockerjava.api.command.ExecCreateCmdResponse;
import com.github.dockerjava.api.command.InspectContainerResponse;
import com.github.dockerjava.api.exception.NotFoundException;
import com.github.dockerjava.api.model.Bind;
import com.github.dockerjava.api.model.ExposedPort;
import com.github.dockerjava.api.model.HostConfig;
import com.github.dockerjava.api.model.Mount;
import com.github.dockerjava.api.model.PortBinding;
import com.github.dockerjava.core.command.ExecStartResultCallback;
import io.agentscope.core.agent.RuntimeContext;
import io.agentscope.harness.agent.sandbox.AbstractBaseSandbox;
import io.agentscope.harness.agent.sandbox.ExecResult;
import io.agentscope.harness.agent.sandbox.SandboxErrorCode;
import io.agentscope.harness.agent.sandbox.SandboxException;
import io.agentscope.harness.agent.sandbox.WorkspaceMountSupport;
import io.agentscope.harness.agent.sandbox.layout.BindMountEntry;
import io.agentscope.harness.agent.sandbox.layout.WorkspaceEntry;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Docker {@link io.agentscope.harness.agent.sandbox.Sandbox} that runs commands in a container
 * via docker-java over the daemon's Engine HTTP API.
 *
 * <p>No docker CLI, local socket, or docker-in-docker is required; the daemon is reached over
 * TCP when {@code daemonUrl} (or {@code DOCKER_HOST}) is configured.
 *
 * <h2>Container Lifecycle</h2>
 * <ul>
 *   <li>On {@link #start()}: the container is created and started if it does not exist;
 *       if the container exists but is stopped it is restarted; if it is already running
 *       the existing container is reused.</li>
 *   <li>On {@link #stop()}: the workspace snapshot is persisted (if configured).
 *       The container keeps running.</li>
 *   <li>On {@link #shutdown()}: the container is stopped and removed if self-managed.</li>
 * </ul>
 *
 * <h2>Workspace Operations</h2>
 * <ul>
 *   <li>Exec: {@code execCreateCmd + execStartCmd} with {@code sh -c <command>} in the root</li>
 *   <li>PersistWorkspace: exec {@code tar} (with bind-mount excludes), stdout frames captured</li>
 *   <li>HydrateWorkspace: exec {@code mkdir -p} then {@code copyArchiveToContainerCmd}
 *       (Docker archive API — avoids the poorly supported exec-stdin path)</li>
 * </ul>
 */
public class DockerDaemonSandbox extends AbstractBaseSandbox {

    private static final Logger log = LoggerFactory.getLogger(DockerDaemonSandbox.class);

    private static final int OUTPUT_TRUNCATE_BYTES = 512 * 1024; // 512 KB per stream
    private static final int CONTAINER_START_TIMEOUT_SECONDS = 60;
    private static final int CONTAINER_STOP_TIMEOUT_SECONDS = 30;
    private static final int TAR_TIMEOUT_SECONDS = 120;

    private final DockerDaemonSandboxState dockerState;
    private volatile DockerClient dockerClient;

    public DockerDaemonSandbox(DockerDaemonSandboxState state) {
        super(state);
        this.dockerState = state;
    }

    /** Test-only constructor with an injected client. */
    DockerDaemonSandbox(DockerDaemonSandboxState state, DockerClient client) {
        super(state);
        this.dockerState = state;
        this.dockerClient = client;
    }

    /**
     * Lazily builds and reuses the {@link DockerClient}. No network I/O happens at
     * construction; the first command connects.
     */
    DockerClient dockerClient() {
        DockerClient client = dockerClient;
        if (client == null) {
            synchronized (this) {
                client = dockerClient;
                if (client == null) {
                    client = DockerDaemonClientFactory.buildClient(dockerState);
                    dockerClient = client;
                }
            }
        }
        return client;
    }

    @Override
    public void start() throws Exception {
        doEnsureContainerRunning();
        super.start();
    }

    @Override
    public void shutdown() throws Exception {
        String containerId = dockerState.getContainerId();
        if (containerId != null && !containerId.isBlank()) {
            if (dockerState.isContainerOwned()) {
                try {
                    dockerClient()
                            .stopContainerCmd(containerId)
                            .withTimeout(CONTAINER_STOP_TIMEOUT_SECONDS)
                            .exec();
                    log.debug("[sandbox-docker-daemon] Container stopped: {}", containerId);
                } catch (Exception e) {
                    log.warn(
                            "[sandbox-docker-daemon] Failed to stop container {}: {}",
                            containerId,
                            e.getMessage());
                }
                try {
                    dockerClient().removeContainerCmd(containerId).withForce(true).exec();
                    log.debug("[sandbox-docker-daemon] Container removed: {}", containerId);
                } catch (Exception e) {
                    log.warn(
                            "[sandbox-docker-daemon] Failed to remove container {}: {}",
                            containerId,
                            e.getMessage());
                }
            } else {
                log.debug(
                        "[sandbox-docker-daemon] Skipping shutdown: container is user-managed: {}",
                        containerId);
            }
        }
        DockerClient client = dockerClient;
        if (client != null) {
            try {
                client.close();
            } catch (Exception e) {
                log.warn(
                        "[sandbox-docker-daemon] Failed to close docker client: {}",
                        e.getMessage());
            }
            dockerClient = null;
        }
    }

    @Override
    protected ExecResult doExec(RuntimeContext runtimeContext, String command, int timeoutSeconds)
            throws Exception {
        ExecOutcome outcome =
                execInContainer(
                        dockerState.getContainerId(),
                        dockerState.getWorkspaceRoot(),
                        List.of("sh", "-c", command),
                        timeoutSeconds);
        ExecResult result =
                new ExecResult(
                        outcome.exitCode(),
                        outcome.stdout().asString(),
                        outcome.stderr().asString(),
                        outcome.stdout().truncated() || outcome.stderr().truncated());
        if (!result.ok()) {
            throw new SandboxException.ExecException(
                    result.exitCode(), result.stdout(), result.stderr());
        }
        return result;
    }

    @Override
    protected InputStream doPersistWorkspace() throws Exception {
        List<String> tarCmd = new ArrayList<>();
        tarCmd.add("tar");
        tarCmd.addAll(
                WorkspaceMountSupport.tarExcludeArgsForBindMounts(getState().getWorkspaceSpec()));
        tarCmd.addAll(List.of("-cf", "-", "-C", dockerState.getWorkspaceRoot(), "."));

        ExecOutcome outcome =
                execInContainer(
                        dockerState.getContainerId(),
                        null,
                        tarCmd,
                        TAR_TIMEOUT_SECONDS,
                        Integer.MAX_VALUE);
        if (outcome.exitCode() != 0) {
            throw new SandboxException.SandboxRuntimeException(
                    SandboxErrorCode.WORKSPACE_ARCHIVE_WRITE_ERROR,
                    "docker exec tar failed (exit="
                            + outcome.exitCode()
                            + "): "
                            + outcome.stderr().asString());
        }
        return new ByteArrayInputStream(outcome.stdout().toByteArray());
    }

    @Override
    protected void doHydrateWorkspace(InputStream archive) throws Exception {
        String containerId = dockerState.getContainerId();
        String workspaceRoot = dockerState.getWorkspaceRoot();
        ExecOutcome mkdir =
                execInContainer(
                        containerId,
                        null,
                        List.of("mkdir", "-p", workspaceRoot),
                        CONTAINER_START_TIMEOUT_SECONDS);
        if (mkdir.exitCode() != 0) {
            throw new SandboxException.SandboxRuntimeException(
                    SandboxErrorCode.WORKSPACE_ARCHIVE_READ_ERROR,
                    "mkdir -p "
                            + workspaceRoot
                            + " failed (exit="
                            + mkdir.exitCode()
                            + "): "
                            + mkdir.stderr().asString());
        }
        try {
            dockerClient()
                    .copyArchiveToContainerCmd(containerId)
                    .withTarInputStream(archive)
                    .withRemotePath(workspaceRoot)
                    .exec();
        } catch (Exception e) {
            throw new SandboxException.SandboxRuntimeException(
                    SandboxErrorCode.WORKSPACE_ARCHIVE_READ_ERROR,
                    "docker copy-archive failed for container: " + containerId,
                    e);
        }
    }

    @Override
    protected void doSetupWorkspace() throws Exception {
        ExecOutcome outcome =
                execInContainer(
                        dockerState.getContainerId(),
                        null,
                        List.of("mkdir", "-p", dockerState.getWorkspaceRoot()),
                        CONTAINER_START_TIMEOUT_SECONDS);
        if (outcome.exitCode() != 0) {
            throw new SandboxException.SandboxRuntimeException(
                    SandboxErrorCode.WORKSPACE_START_ERROR,
                    "mkdir -p "
                            + dockerState.getWorkspaceRoot()
                            + " failed (exit="
                            + outcome.exitCode()
                            + "): "
                            + outcome.stderr().asString());
        }
    }

    @Override
    protected void doDestroyWorkspace() throws Exception {
        String containerId = dockerState.getContainerId();
        if (containerId == null || containerId.isBlank()) {
            return;
        }
        try {
            execInContainer(
                    containerId,
                    null,
                    List.of("rm", "-rf", dockerState.getWorkspaceRoot()),
                    CONTAINER_START_TIMEOUT_SECONDS);
        } catch (Exception e) {
            log.warn(
                    "[sandbox-docker-daemon] Failed to destroy workspace {} in container {}: {}",
                    dockerState.getWorkspaceRoot(),
                    containerId,
                    e.getMessage());
        }
    }

    @Override
    protected String getWorkspaceRoot() {
        return dockerState.getWorkspaceRoot();
    }

    // -----------------------------------------------------------------
    //  Container management
    // -----------------------------------------------------------------

    private void doEnsureContainerRunning() throws Exception {
        String containerId = dockerState.getContainerId();

        if (containerId != null && !containerId.isBlank()) {
            ContainerState state = inspectContainerState(containerId);
            if (state == ContainerState.RUNNING) {
                log.debug("[sandbox-docker-daemon] Container already running: {}", containerId);
                return;
            } else if (state == ContainerState.STOPPED) {
                log.debug("[sandbox-docker-daemon] Restarting stopped container: {}", containerId);
                try {
                    dockerClient().startContainerCmd(containerId).exec();
                } catch (Exception e) {
                    throw new SandboxException.SandboxRuntimeException(
                            SandboxErrorCode.WORKSPACE_START_ERROR,
                            "docker start failed for container: " + containerId,
                            e);
                }
                return;
            }
            log.warn(
                    "[sandbox-docker-daemon] Container {} not found, creating a new one",
                    containerId);
            dockerState.setWorkspaceRootReady(false);
        }

        createAndStartContainer();
    }

    private void createAndStartContainer() throws Exception {
        String containerName = "agentscope-sandbox-" + dockerState.getSessionId();
        dockerState.setContainerName(containerName);

        log.debug(
                "[sandbox-docker-daemon] Creating container: image={}, name={}",
                dockerState.getImage(),
                containerName);

        CreateContainerCmd cmd =
                dockerClient()
                        .createContainerCmd(dockerState.getImage())
                        .withName(containerName)
                        .withCmd("sh", "-c", "while :; do sleep 3600; done");

        Map<String, String> manifestEnv =
                getState().getWorkspaceSpec() != null
                        ? getState().getWorkspaceSpec().getEnvironment()
                        : null;
        if (manifestEnv != null && !manifestEnv.isEmpty()) {
            cmd.withEnv(
                    manifestEnv.entrySet().stream()
                            .map(e -> e.getKey() + "=" + e.getValue())
                            .toList());
        }

        HostConfig hostConfig = HostConfig.newHostConfig();
        if (dockerState.getMemorySizeBytes() != null) {
            hostConfig.withMemory(dockerState.getMemorySizeBytes());
        }
        if (dockerState.getCpuCount() != null) {
            hostConfig.withNanoCPUs(dockerState.getCpuCount() * 1_000_000_000L);
        }
        if (dockerState.getExposedPorts() != null && dockerState.getExposedPorts().length > 0) {
            int[] ports = dockerState.getExposedPorts();
            ExposedPort[] exposed = new ExposedPort[ports.length];
            PortBinding[] bindings = new PortBinding[ports.length];
            for (int i = 0; i < ports.length; i++) {
                exposed[i] = ExposedPort.tcp(ports[i]);
                bindings[i] = PortBinding.parse(ports[i] + ":" + ports[i]);
            }
            cmd.withExposedPorts(exposed);
            hostConfig.withPortBindings(bindings);
        }
        String network = dockerState.getNetwork();
        hostConfig.withNetworkMode(network == null ? "none" : network);

        List<Bind> binds = new ArrayList<>();
        if (getState().getWorkspaceSpec() != null) {
            for (Map.Entry<String, WorkspaceEntry> e :
                    getState().getWorkspaceSpec().getEntries().entrySet()) {
                if (e.getValue() instanceof BindMountEntry bm) {
                    String host = WorkspaceMountSupport.normalizedHostPath(bm.getHostPath());
                    if (host.isEmpty()) {
                        log.warn(
                                "[sandbox-docker-daemon] Skipping bind mount at key {}: blank"
                                        + " hostPath",
                                e.getKey());
                        continue;
                    }
                    String containerPath =
                            WorkspaceMountSupport.containerMountPath(
                                    dockerState.getWorkspaceRoot(), e.getKey());
                    String mode = bm.isReadOnly() ? "ro" : "rw";
                    binds.add(Bind.parse(host + ":" + containerPath + ":" + mode));
                }
            }
        }
        if (!binds.isEmpty()) {
            hostConfig.withBinds(binds.toArray(Bind[]::new));
        }
        if (dockerState.getMounts() != null && !dockerState.getMounts().isEmpty()) {
            hostConfig.withMounts(
                    dockerState.getMounts().stream().map(this::validatedDockerJavaMount).toList());
        }

        cmd.withHostConfig(hostConfig);
        CreateContainerResponse response;
        try {
            response = cmd.exec();
        } catch (Exception e) {
            throw new SandboxException.SandboxRuntimeException(
                    SandboxErrorCode.WORKSPACE_START_ERROR,
                    "docker create failed for image: " + dockerState.getImage(),
                    e);
        }
        try {
            dockerClient().startContainerCmd(response.getId()).exec();
        } catch (Exception e) {
            try {
                dockerClient().removeContainerCmd(response.getId()).withForce(true).exec();
                log.warn(
                        "[sandbox-docker-daemon] Removed orphaned container after start failure:"
                                + " {}",
                        response.getId());
            } catch (Exception removeEx) {
                log.warn(
                        "[sandbox-docker-daemon] Failed to remove orphaned container {}: {}",
                        response.getId(),
                        removeEx.getMessage());
            }
            throw new SandboxException.SandboxRuntimeException(
                    SandboxErrorCode.WORKSPACE_START_ERROR,
                    "docker start failed for container: " + response.getId(),
                    e);
        }

        dockerState.setContainerId(response.getId());
        log.info(
                "[sandbox-docker-daemon] Container started: id={}, name={}",
                response.getId(),
                containerName);
    }

    private Mount validatedDockerJavaMount(MountSpec mountSpec) {
        if (mountSpec == null) {
            // hand-crafted persisted state JSON ("mounts": [null]) deserializes a null entry;
            // treat it like any other invalid mount instead of letting an NPE escape
            throw new SandboxException.SandboxRuntimeException(
                    SandboxErrorCode.WORKSPACE_START_ERROR,
                    "Invalid mount in sandbox state: null mount entry",
                    null);
        }
        try {
            // re-validate: persisted state JSON bypasses MountSpec.of() (Jackson uses the
            // canonical constructor), so never trust it blindly
            return MountSpec.of(
                            mountSpec.type(),
                            mountSpec.source(),
                            mountSpec.target(),
                            mountSpec.readOnly())
                    .toDockerJavaMount();
        } catch (IllegalArgumentException e) {
            throw new SandboxException.SandboxRuntimeException(
                    SandboxErrorCode.WORKSPACE_START_ERROR,
                    "Invalid mount in sandbox state: " + e.getMessage(),
                    e);
        }
    }

    private ContainerState inspectContainerState(String containerId) {
        try {
            InspectContainerResponse response =
                    dockerClient().inspectContainerCmd(containerId).exec();
            return Boolean.TRUE.equals(response.getState().getRunning())
                    ? ContainerState.RUNNING
                    : ContainerState.STOPPED;
        } catch (NotFoundException e) {
            return ContainerState.UNKNOWN;
        } catch (Exception e) {
            log.debug(
                    "[sandbox-docker-daemon] Failed to inspect container {}: {}",
                    containerId,
                    e.getMessage());
            return ContainerState.UNKNOWN;
        }
    }

    private enum ContainerState {
        RUNNING,
        STOPPED,
        UNKNOWN
    }

    // -----------------------------------------------------------------
    //  Exec plumbing
    // -----------------------------------------------------------------

    /**
     * Runs a command in the container via the exec API and waits for completion.
     *
     * @param containerId  target container
     * @param workingDir   working directory for the command, or {@code null}
     * @param cmd          argv (no shell wrapping — pass {@code sh -c} explicitly when needed)
     * @param timeoutSeconds max wait time; the remote exec may outlive this (no exec-kill API)
     * @return raw stdout/stderr bytes plus exit code
     * @throws Exception on transport or timeout failures
     */
    private ExecOutcome execInContainer(
            String containerId, String workingDir, List<String> cmd, int timeoutSeconds)
            throws Exception {
        return execInContainer(containerId, workingDir, cmd, timeoutSeconds, OUTPUT_TRUNCATE_BYTES);
    }

    private ExecOutcome execInContainer(
            String containerId,
            String workingDir,
            List<String> cmd,
            int timeoutSeconds,
            int stdoutMaxBytes)
            throws Exception {
        try {
            return doExecInContainer(containerId, workingDir, cmd, timeoutSeconds, stdoutMaxBytes);
        } catch (SandboxException | InterruptedException e) {
            throw e;
        } catch (Exception e) {
            throw new SandboxException.SandboxRuntimeException(
                    SandboxErrorCode.WORKSPACE_START_ERROR,
                    "docker exec failed for container " + containerId + ": " + e.getMessage(),
                    e);
        }
    }

    private ExecOutcome doExecInContainer(
            String containerId,
            String workingDir,
            List<String> cmd,
            int timeoutSeconds,
            int stdoutMaxBytes)
            throws Exception {
        ExecCreateCmd execCreateCmd = dockerClient().execCreateCmd(containerId);
        if (workingDir != null) {
            execCreateCmd.withWorkingDir(workingDir);
        }
        ExecCreateCmdResponse execCreate =
                execCreateCmd
                        .withAttachStdout(true)
                        .withAttachStderr(true)
                        .withCmd(cmd.toArray(String[]::new))
                        .exec();

        TruncatingOutputStream stdout = new TruncatingOutputStream(stdoutMaxBytes);
        TruncatingOutputStream stderr = new TruncatingOutputStream(OUTPUT_TRUNCATE_BYTES);
        ExecStartResultCallback callback = new ExecStartResultCallback(stdout, stderr);
        ExecStartResultCallback returned =
                dockerClient().execStartCmd(execCreate.getId()).withDetach(false).exec(callback);

        boolean finished;
        try {
            finished = returned.awaitCompletion(timeoutSeconds, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw e;
        }
        if (!finished) {
            throw new SandboxException.ExecTimeoutException(String.join(" ", cmd), timeoutSeconds);
        }

        Integer exitCode = dockerClient().inspectExecCmd(execCreate.getId()).exec().getExitCode();
        return new ExecOutcome(exitCode != null ? exitCode : -1, stdout, stderr);
    }

    /** Raw exec result: exit code plus capped stdout/stderr buffers. */
    private record ExecOutcome(
            int exitCode, TruncatingOutputStream stdout, TruncatingOutputStream stderr) {}

    /**
     * Output stream that keeps at most {@code maxBytes} and records whether more was written,
     * mirroring the CLI sandbox's 512 KB truncation per stream.
     */
    private static final class TruncatingOutputStream extends OutputStream {

        private final ByteArrayOutputStream buffer = new ByteArrayOutputStream();
        private final int maxBytes;
        private boolean truncated;

        TruncatingOutputStream(int maxBytes) {
            this.maxBytes = maxBytes;
        }

        @Override
        public void write(int b) {
            if (buffer.size() < maxBytes) {
                buffer.write(b);
            } else {
                truncated = true;
            }
        }

        @Override
        public void write(byte[] b, int off, int len) {
            int room = maxBytes - buffer.size();
            if (room > 0) {
                buffer.write(b, off, Math.min(len, room));
            }
            if (len > room) {
                truncated = true;
            }
        }

        String asString() {
            return buffer.toString(StandardCharsets.UTF_8);
        }

        byte[] toByteArray() {
            return buffer.toByteArray();
        }

        boolean truncated() {
            return truncated;
        }
    }
}
