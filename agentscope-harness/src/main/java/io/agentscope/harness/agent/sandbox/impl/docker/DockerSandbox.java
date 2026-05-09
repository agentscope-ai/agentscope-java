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
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Docker {@link io.agentscope.harness.agent.sandbox.Sandbox} that runs commands in a container.
 *
 * <p>Uses the {@code docker} CLI via {@link ProcessBuilder}; no docker-java library
 * dependency is required. The Docker daemon must be accessible on the host.
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
 *   <li>Exec: {@code docker exec -w <root> <containerId> sh -c <command>}</li>
 *   <li>PersistWorkspace: {@code docker exec <containerId> tar -cf - -C <root> .}</li>
 *   <li>HydrateWorkspace: {@code docker exec -i <containerId> tar -xf - -C <root>}</li>
 * </ul>
 */
public class DockerSandbox extends AbstractBaseSandbox {

    private static final Logger log = LoggerFactory.getLogger(DockerSandbox.class);

    private static final int OUTPUT_TRUNCATE_BYTES = 512 * 1024; // 512 KB per stream
    private static final int CONTAINER_START_TIMEOUT_SECONDS = 60;
    private static final int CONTAINER_STOP_TIMEOUT_SECONDS = 30;
    private static final int TAR_TIMEOUT_SECONDS = 120;

    private final DockerSandboxState dockerState;

    public DockerSandbox(DockerSandboxState state) {
        super(state);
        this.dockerState = state;
    }

    /**
     * Ensures the backing Docker container is running before executing the standard
     * 4-branch workspace start logic.
     *
     * @throws Exception if the container cannot be started
     */
    @Override
    public void start() throws Exception {
        doEnsureContainerRunning();
        super.start();
    }

    /**
     * Stops and removes the Docker container if self-managed.
     *
     * @throws Exception if the container cannot be stopped or removed
     */
    @Override
    public void shutdown() throws Exception {
        String containerId = dockerState.getContainerId();
        if (containerId == null || containerId.isBlank()) {
            return;
        }
        if (!dockerState.isContainerOwned()) {
            log.debug(
                    "[sandbox-docker] Skipping shutdown: container is user-managed: {}",
                    containerId);
            return;
        }
        try {
            runDockerCliBlocking(
                    CONTAINER_STOP_TIMEOUT_SECONDS * 2,
                    "docker",
                    "stop",
                    "--time=" + CONTAINER_STOP_TIMEOUT_SECONDS,
                    containerId);
            log.debug("[sandbox-docker] Container stopped: {}", containerId);
        } catch (Exception e) {
            log.warn(
                    "[sandbox-docker] Failed to stop container {}: {}",
                    containerId,
                    e.getMessage());
        }
        try {
            runDockerCliBlocking(30, "docker", "rm", "--force", containerId);
            log.debug("[sandbox-docker] Container removed: {}", containerId);
        } catch (Exception e) {
            log.warn(
                    "[sandbox-docker] Failed to remove container {}: {}",
                    containerId,
                    e.getMessage());
        }
    }

    @Override
    protected ExecResult doExec(RuntimeContext runtimeContext, String command, int timeoutSeconds)
            throws Exception {
        String containerId = dockerState.getContainerId();
        String workspaceRoot = dockerState.getWorkspaceRoot();

        List<String> cmd = new ArrayList<>();
        cmd.add("docker");
        cmd.add("exec");
        cmd.add("-w");
        cmd.add(workspaceRoot);
        cmd.add(containerId);
        cmd.add("sh");
        cmd.add("-c");
        cmd.add(command);

        ProcessBuilder pb = new ProcessBuilder(cmd);
        Process process = pb.start();

        ExecutorService drainer =
                Executors.newFixedThreadPool(
                        2,
                        r -> {
                            Thread t =
                                    new Thread(
                                            r,
                                            "sandbox-docker-drain-" + dockerState.getSessionId());
                            t.setDaemon(true);
                            return t;
                        });

        Future<String> stdoutFuture =
                drainer.submit(() -> readStream(process.getInputStream(), OUTPUT_TRUNCATE_BYTES));
        Future<String> stderrFuture =
                drainer.submit(() -> readStream(process.getErrorStream(), OUTPUT_TRUNCATE_BYTES));
        drainer.shutdown();

        boolean exited = process.waitFor(timeoutSeconds, TimeUnit.SECONDS);
        if (!exited) {
            process.destroyForcibly();
            drainer.shutdownNow();
            throw new SandboxException.ExecTimeoutException(command, timeoutSeconds);
        }

        String stdout = stdoutFuture.get();
        String stderr = stderrFuture.get();
        int exitCode = process.exitValue();

        boolean truncated =
                stdout.length() >= OUTPUT_TRUNCATE_BYTES
                        || stderr.length() >= OUTPUT_TRUNCATE_BYTES;
        ExecResult result = new ExecResult(exitCode, stdout, stderr, truncated);
        if (!result.ok()) {
            throw new SandboxException.ExecException(exitCode, stdout, stderr);
        }
        return result;
    }

    @Override
    protected InputStream doPersistWorkspace() throws Exception {
        String containerId = dockerState.getContainerId();
        String workspaceRoot = dockerState.getWorkspaceRoot();

        List<String> tarCmd = new ArrayList<>();
        tarCmd.add("docker");
        tarCmd.add("exec");
        tarCmd.add(containerId);
        tarCmd.add("tar");
        tarCmd.addAll(
                WorkspaceMountSupport.tarExcludeArgsForBindMounts(getState().getWorkspaceSpec()));
        tarCmd.add("-cf");
        tarCmd.add("-");
        tarCmd.add("-C");
        tarCmd.add(workspaceRoot);
        tarCmd.add(".");
        ProcessBuilder pb = new ProcessBuilder(tarCmd);

        Process process = pb.start();

        ExecutorService stderrDrainer =
                Executors.newSingleThreadExecutor(
                        r -> {
                            Thread t =
                                    new Thread(
                                            r,
                                            "sandbox-docker-tar-stderr-"
                                                    + dockerState.getSessionId());
                            t.setDaemon(true);
                            return t;
                        });
        Future<String> stderrFuture =
                stderrDrainer.submit(
                        () -> readStream(process.getErrorStream(), OUTPUT_TRUNCATE_BYTES));
        stderrDrainer.shutdown();

        ByteArrayOutputStream buffer = new ByteArrayOutputStream();
        process.getInputStream().transferTo(buffer);

        boolean exited = process.waitFor(TAR_TIMEOUT_SECONDS, TimeUnit.SECONDS);
        if (!exited) {
            process.destroyForcibly();
            throw new SandboxException.SandboxRuntimeException(
                    SandboxErrorCode.WORKSPACE_ARCHIVE_WRITE_ERROR,
                    "docker tar command timed out for container: " + containerId);
        }

        int exitCode = process.exitValue();
        if (exitCode != 0) {
            String stderr = stderrFuture.get();
            throw new SandboxException.SandboxRuntimeException(
                    SandboxErrorCode.WORKSPACE_ARCHIVE_WRITE_ERROR,
                    "docker tar command failed (exit=" + exitCode + "): " + stderr);
        }

        return new ByteArrayInputStream(buffer.toByteArray());
    }

    @Override
    protected void doHydrateWorkspace(InputStream archive) throws Exception {
        String containerId = dockerState.getContainerId();
        String workspaceRoot = dockerState.getWorkspaceRoot();

        // Ensure the workspace directory exists inside the container
        runDockerCliBlocking(30, "docker", "exec", containerId, "mkdir", "-p", workspaceRoot);

        // Pipe the tar archive into the container via docker exec stdin
        ProcessBuilder pb =
                new ProcessBuilder(
                        "docker",
                        "exec",
                        "-i",
                        containerId,
                        "tar",
                        "-xf",
                        "-",
                        "-C",
                        workspaceRoot);

        Process process = pb.start();

        ExecutorService ioExecutor =
                Executors.newFixedThreadPool(
                        2,
                        r -> {
                            Thread t =
                                    new Thread(
                                            r,
                                            "sandbox-docker-hydrate-" + dockerState.getSessionId());
                            t.setDaemon(true);
                            return t;
                        });

        // Write archive to process stdin in background
        Future<?> writeFuture =
                ioExecutor.submit(
                        () -> {
                            try (OutputStream stdin = process.getOutputStream()) {
                                archive.transferTo(stdin);
                            } catch (IOException e) {
                                log.warn(
                                        "[sandbox-docker] Error writing archive to container stdin",
                                        e);
                            }
                            return null;
                        });

        Future<String> stderrFuture =
                ioExecutor.submit(
                        () -> readStream(process.getErrorStream(), OUTPUT_TRUNCATE_BYTES));
        ioExecutor.shutdown();

        writeFuture.get(TAR_TIMEOUT_SECONDS, TimeUnit.SECONDS);

        boolean exited = process.waitFor(TAR_TIMEOUT_SECONDS, TimeUnit.SECONDS);
        if (!exited) {
            process.destroyForcibly();
            ioExecutor.shutdownNow();
            throw new SandboxException.SandboxRuntimeException(
                    SandboxErrorCode.WORKSPACE_ARCHIVE_READ_ERROR,
                    "docker tar extract timed out for container: " + containerId);
        }

        int exitCode = process.exitValue();
        if (exitCode != 0) {
            String stderr = stderrFuture.get();
            throw new SandboxException.SandboxRuntimeException(
                    SandboxErrorCode.WORKSPACE_ARCHIVE_READ_ERROR,
                    "docker tar extract failed (exit=" + exitCode + "): " + stderr);
        }
    }

    @Override
    protected void doSetupWorkspace() throws Exception {
        String containerId = dockerState.getContainerId();
        String workspaceRoot = dockerState.getWorkspaceRoot();
        runDockerCliBlocking(30, "docker", "exec", containerId, "mkdir", "-p", workspaceRoot);
    }

    @Override
    protected void doDestroyWorkspace() throws Exception {
        String containerId = dockerState.getContainerId();
        String workspaceRoot = dockerState.getWorkspaceRoot();
        if (containerId != null && !containerId.isBlank()) {
            try {
                runDockerCliBlocking(30, "docker", "exec", containerId, "rm", "-rf", workspaceRoot);
            } catch (Exception e) {
                log.warn(
                        "[sandbox-docker] Failed to destroy workspace {} in container {}: {}",
                        workspaceRoot,
                        containerId,
                        e.getMessage());
            }
        }
    }

    @Override
    protected String getWorkspaceRoot() {
        return dockerState.getWorkspaceRoot();
    }

    // -----------------------------------------------------------------
    //  Container management
    // -----------------------------------------------------------------

    /**
     * Ensures the Docker container is running.
     *
     * <p>Priority:
     * <ol>
     *   <li>If container exists and is running — reuse it.</li>
     *   <li>If container exists and is stopped — restart it.</li>
     *   <li>If container is missing or unknown — create a new container, reset
     *       {@code workspaceRootReady} to force full workspace reinitialisation.</li>
     * </ol>
     */
    private void doEnsureContainerRunning() throws Exception {
        String containerId = dockerState.getContainerId();

        if (containerId != null && !containerId.isBlank()) {
            ContainerState state = inspectContainerState(containerId);
            if (state == ContainerState.RUNNING) {
                log.debug("[sandbox-docker] Container already running: {}", containerId);
                return;
            } else if (state == ContainerState.STOPPED) {
                log.debug("[sandbox-docker] Restarting stopped container: {}", containerId);
                runDockerCliBlocking(
                        CONTAINER_START_TIMEOUT_SECONDS, "docker", "start", containerId);
                return;
            }
            // Container not found — fall through to create a new one
            log.warn("[sandbox-docker] Container {} not found, creating a new one", containerId);
            dockerState.setWorkspaceRootReady(false);
        }

        createAndStartContainer();
    }

    private void createAndStartContainer() throws Exception {
        String containerName = "agentscope-sandbox-" + dockerState.getSessionId();
        dockerState.setContainerName(containerName);

        List<String> cmd = buildDockerRunCommand(containerName);

        log.debug(
                "[sandbox-docker] Creating container: image={}, name={}",
                dockerState.getImage(),
                containerName);

        ProcessBuilder pb = new ProcessBuilder(cmd);
        Process process = pb.start();

        ExecutorService drainer =
                Executors.newFixedThreadPool(
                        2,
                        r -> {
                            Thread t =
                                    new Thread(
                                            r,
                                            "sandbox-docker-create-" + dockerState.getSessionId());
                            t.setDaemon(true);
                            return t;
                        });

        Future<String> stdoutFuture =
                drainer.submit(() -> readStream(process.getInputStream(), 64 * 1024));
        Future<String> stderrFuture =
                drainer.submit(() -> readStream(process.getErrorStream(), 64 * 1024));
        drainer.shutdown();

        boolean exited = process.waitFor(CONTAINER_START_TIMEOUT_SECONDS, TimeUnit.SECONDS);
        if (!exited) {
            process.destroyForcibly();
            drainer.shutdownNow();
            throw new SandboxException.SandboxRuntimeException(
                    SandboxErrorCode.WORKSPACE_START_ERROR,
                    "docker run timed out for image: " + dockerState.getImage());
        }

        int exitCode = process.exitValue();
        String stdout = stdoutFuture.get().trim();
        String stderr = stderrFuture.get().trim();

        if (exitCode != 0) {
            throw new SandboxException.SandboxRuntimeException(
                    SandboxErrorCode.WORKSPACE_START_ERROR,
                    "docker run failed (exit=" + exitCode + "): " + stderr);
        }

        // stdout is the new container ID
        String newContainerId = stdout.isBlank() ? null : stdout;
        if (newContainerId == null) {
            throw new SandboxException.SandboxRuntimeException(
                    SandboxErrorCode.WORKSPACE_START_ERROR,
                    "docker run produced no container ID. stderr: " + stderr);
        }

        dockerState.setContainerId(newContainerId);
        log.info(
                "[sandbox-docker] Container started: id={}, name={}",
                newContainerId,
                containerName);
    }

    private List<String> buildDockerRunCommand(String containerName) {
        List<String> cmd = new ArrayList<>();
        cmd.add("docker");
        cmd.add("run");
        cmd.add("-d");
        cmd.add("--name");
        cmd.add(containerName);

        // Environment variables from manifest
        Map<String, String> manifestEnv =
                getState().getWorkspaceSpec() != null
                        ? getState().getWorkspaceSpec().getEnvironment()
                        : null;
        if (manifestEnv != null) {
            for (Map.Entry<String, String> entry : manifestEnv.entrySet()) {
                cmd.add("-e");
                cmd.add(entry.getKey() + "=" + entry.getValue());
            }
        }

        // Memory limit
        if (dockerState.getMemorySizeBytes() != null) {
            cmd.add("--memory=" + dockerState.getMemorySizeBytes());
        }

        // CPU limit
        if (dockerState.getCpuCount() != null) {
            cmd.add("--cpus=" + dockerState.getCpuCount());
        }

        // Exposed ports (host:container mapping using same port number)
        if (dockerState.getExposedPorts() != null) {
            for (int port : dockerState.getExposedPorts()) {
                cmd.add("-p");
                cmd.add(port + ":" + port);
            }
        }

        String network = dockerState.getNetwork();
        cmd.add("--network=" + (network == null || network.isBlank() ? "none" : network));

        if (dockerState.getAdditionalRunArgs() != null) {
            cmd.addAll(dockerState.getAdditionalRunArgs());
        }

        if (getState().getWorkspaceSpec() != null) {
            for (Map.Entry<String, WorkspaceEntry> e :
                    getState().getWorkspaceSpec().getEntries().entrySet()) {
                if (e.getValue() instanceof BindMountEntry bm) {
                    String host = WorkspaceMountSupport.normalizedHostPath(bm.getHostPath());
                    if (host.isEmpty()) {
                        log.warn(
                                "[sandbox-docker] Skipping bind mount at key {}: blank hostPath",
                                e.getKey());
                        continue;
                    }
                    String containerPath =
                            WorkspaceMountSupport.containerMountPath(
                                    dockerState.getWorkspaceRoot(), e.getKey());
                    String mode = bm.isReadOnly() ? "ro" : "rw";
                    cmd.add("-v");
                    cmd.add(host + ":" + containerPath + ":" + mode);
                }
            }
        }

        cmd.add(dockerState.getImage());
        // Keep the container alive with an idle shell loop
        cmd.add("sh");
        cmd.add("-c");
        cmd.add("while :; do sleep 3600; done");

        return cmd;
    }

    /**
     * Inspects a container and returns whether it is running, stopped, or unknown.
     */
    private ContainerState inspectContainerState(String containerId) {
        try {
            ProcessBuilder pb =
                    new ProcessBuilder(
                            "docker", "inspect", "-f", "{{.State.Running}}", containerId);
            Process process = pb.start();

            Future<String> stdoutFuture;
            ExecutorService drainer =
                    Executors.newSingleThreadExecutor(
                            r -> {
                                Thread t = new Thread(r, "sandbox-docker-inspect");
                                t.setDaemon(true);
                                return t;
                            });
            stdoutFuture = drainer.submit(() -> readStream(process.getInputStream(), 1024));
            drainer.shutdown();

            boolean exited = process.waitFor(10, TimeUnit.SECONDS);
            if (!exited) {
                process.destroyForcibly();
                return ContainerState.UNKNOWN;
            }
            if (process.exitValue() != 0) {
                return ContainerState.UNKNOWN;
            }
            String output = stdoutFuture.get().trim();
            return "true".equals(output) ? ContainerState.RUNNING : ContainerState.STOPPED;
        } catch (Exception e) {
            log.debug(
                    "[sandbox-docker] Failed to inspect container {}: {}",
                    containerId,
                    e.getMessage());
            return ContainerState.UNKNOWN;
        }
    }

    /**
     * Runs a Docker CLI command, blocking until completion.
     *
     * @param timeoutSeconds maximum time to wait
     * @param command        command and arguments
     * @throws SandboxException.SandboxRuntimeException if the command fails or times out
     */
    private void runDockerCliBlocking(int timeoutSeconds, String... command) throws Exception {
        ProcessBuilder pb = new ProcessBuilder(command);
        Process process = pb.start();

        ExecutorService drainer =
                Executors.newFixedThreadPool(
                        2,
                        r -> {
                            Thread t = new Thread(r, "sandbox-docker-cli");
                            t.setDaemon(true);
                            return t;
                        });
        Future<String> stderrFuture =
                drainer.submit(() -> readStream(process.getErrorStream(), 64 * 1024));
        drainer.submit(() -> readStream(process.getInputStream(), 64 * 1024));
        drainer.shutdown();

        boolean exited = process.waitFor(timeoutSeconds, TimeUnit.SECONDS);
        if (!exited) {
            process.destroyForcibly();
            drainer.shutdownNow();
            throw new SandboxException.SandboxRuntimeException(
                    SandboxErrorCode.WORKSPACE_START_ERROR,
                    "docker command timed out: " + command[0] + " " + command[1]);
        }
        int exitCode = process.exitValue();
        if (exitCode != 0) {
            String stderr = stderrFuture.get();
            throw new SandboxException.SandboxRuntimeException(
                    SandboxErrorCode.WORKSPACE_START_ERROR,
                    "docker command failed (exit=" + exitCode + "): " + stderr);
        }
    }

    /**
     * Reads an InputStream into a String, truncating at {@code maxBytes}.
     */
    private static String readStream(InputStream in, int maxBytes) {
        try {
            byte[] buf = new byte[maxBytes];
            int total = 0;
            int read;
            while (total < maxBytes && (read = in.read(buf, total, maxBytes - total)) != -1) {
                total += read;
            }
            // Drain remaining bytes to prevent blocking
            if (total == maxBytes) {
                //noinspection ResultOfMethodCallIgnored
                in.skip(Long.MAX_VALUE);
            }
            return new String(buf, 0, total, StandardCharsets.UTF_8);
        } catch (IOException e) {
            return "";
        }
    }

    /** Container state as determined by {@code docker inspect}. */
    private enum ContainerState {
        RUNNING,
        STOPPED,
        UNKNOWN
    }
}
