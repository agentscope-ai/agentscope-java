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
import io.agentscope.harness.agent.sandbox.SandboxFileTransfer;
import io.agentscope.harness.agent.sandbox.WorkspaceMountSupport;
import io.agentscope.harness.agent.sandbox.layout.BindMountEntry;
import io.agentscope.harness.agent.sandbox.layout.WorkspaceEntry;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
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
 *   <li>UploadFile / DownloadFile ({@link SandboxFileTransfer}): a host temp file round-tripped
 *       through {@code docker cp}, so file bytes never pass through exec argv or the stdout
 *       capture cap. Scoped to the workspace subtree by lexical validation; uploads land with
 *       mode 0644, matching the archive-hydrate fallback. Symlinks inside the sandbox are not
 *       resolved — the isolation boundary is the container, not the subtree.</li>
 * </ul>
 */
public class DockerSandbox extends AbstractBaseSandbox implements SandboxFileTransfer {

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

    @Override
    public boolean supportsFileTransfer(String path) {
        try {
            requireTransferPath(path);
            return true;
        } catch (IllegalArgumentException e) {
            return false;
        }
    }

    @Override
    public void uploadFile(String path, byte[] content) throws Exception {
        if (content == null) {
            throw new IllegalArgumentException("File content must not be null");
        }
        String containerPath = requireTransferPath(path);
        Path temp = Files.createTempFile("agentscope-docker-upload-", ".bin");
        applyUploadFileMode(temp);
        try {
            Files.write(temp, content);
            int slash = containerPath.lastIndexOf('/');
            String parent = slash > 0 ? containerPath.substring(0, slash) : "/";
            runDockerCliBlocking(
                    30, "docker", "exec", dockerState.getContainerId(), "mkdir", "-p", parent);
            runDockerCliBlocking(
                    TAR_TIMEOUT_SECONDS,
                    "docker",
                    "cp",
                    temp.toString(),
                    dockerState.getContainerId() + ":" + containerPath);
        } finally {
            Files.deleteIfExists(temp);
        }
    }

    @Override
    public byte[] downloadFile(String path) throws Exception {
        String containerPath = requireTransferPath(path);
        Path temp = Files.createTempFile("agentscope-docker-download-", ".bin");
        try {
            runDockerCliBlocking(
                    TAR_TIMEOUT_SECONDS,
                    "docker",
                    "cp",
                    dockerState.getContainerId() + ":" + containerPath,
                    temp.toString());
            return Files.readAllBytes(temp);
        } finally {
            Files.deleteIfExists(temp);
        }
    }

    private String requireTransferPath(String path) {
        if (dockerState.getContainerId() == null || dockerState.getContainerId().isBlank()) {
            throw new IllegalArgumentException("Docker container is unavailable");
        }
        String resolved = resolveContainerPath(path);
        String root = normalizeAbsolutePath(dockerState.getWorkspaceRoot());
        if (root == null
                || resolved.equals(root)
                || !resolved.startsWith("/".equals(root) ? "/" : root + "/")) {
            throw new IllegalArgumentException("Path is outside the sandbox workspace: " + path);
        }
        return resolved;
    }

    private String resolveContainerPath(String path) {
        if (path == null || path.isBlank()) {
            throw new IllegalArgumentException("Path must identify a file");
        }
        String normalized = path.replace('\\', '/');
        while (normalized.startsWith("./")) {
            normalized = normalized.substring(2);
        }
        String[] parts = normalized.split("/", -1);
        for (int i = 0; i < parts.length; i++) {
            String part = parts[i];
            if (((i > 0 || !normalized.startsWith("/")) && part.isEmpty())
                    || ".".equals(part)
                    || "..".equals(part)) {
                throw new IllegalArgumentException("Path contains traversal segments: " + path);
            }
        }
        if (path.startsWith("/") || normalized.startsWith("/")) {
            return normalizeAbsolutePath(normalized);
        }
        String root = normalizeAbsolutePath(dockerState.getWorkspaceRoot());
        if (root == null) {
            throw new IllegalArgumentException("Sandbox workspace root is unavailable");
        }
        return "/".equals(root) ? "/" + normalized : root + "/" + normalized;
    }

    private static String normalizeAbsolutePath(String path) {
        if (path == null || path.isBlank() || !path.startsWith("/")) {
            return null;
        }
        String normalized = path.replace('\\', '/');
        while (normalized.endsWith("/") && normalized.length() > 1) {
            normalized = normalized.substring(0, normalized.length() - 1);
        }
        return normalized;
    }

    /**
     * Gives the upload temp file mode 0644 so {@code docker cp}, which preserves the source
     * mode, matches the archive fallback's tar default instead of {@code createTempFile}'s
     * 0600 — uploads must stay readable when the container runs as a non-root user. Skipped on
     * filesystems without POSIX permissions (e.g. Windows), which carry no mode to preserve.
     */
    private static void applyUploadFileMode(Path temp) throws IOException {
        try {
            Files.setPosixFilePermissions(temp, PosixFilePermissions.fromString("rw-r--r--"));
        } catch (UnsupportedOperationException e) {
            // No POSIX permissions on this filesystem — nothing to align.
        }
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
            if (state == ContainerState.UNKNOWN) {
                // A probe that could not answer is not evidence that the container is gone.
                // Creating a second one now would race the daemon and could leave two containers
                // claiming the same workspace; the next command reports the transport failure.
                log.warn(
                        "[sandbox-docker] Container {} could not be inspected; keeping it",
                        containerId);
                return;
            }
            log.warn(
                    "[sandbox-docker] Container {} no longer exists, reconciling by name",
                    containerId);
            dockerState.setContainerId(null);
            dockerState.setWorkspaceRootReady(false);
        }

        // The name is derived from the session id, so the daemon can still hold a container under
        // it while no usable id is persisted -- docker run may have succeeded with only persisting
        // its id failing. Creating another container under that name is rejected on every turn.
        String containerName = containerName();
        if (adoptContainerByName(containerName)) {
            return;
        }
        createAndStartContainer(containerName);
    }

    /**
     * Adopts a container the daemon still holds under the session's deterministic name.
     *
     * @param containerName the container name derived from the session id
     * @return whether an existing container now backs the session
     */
    private boolean adoptContainerByName(String containerName) throws Exception {
        ContainerState state = inspectContainerState(containerName);
        if (state == ContainerState.RUNNING) {
            adoptContainer(containerName);
            log.info("[sandbox-docker] Adopted container found by name: {}", containerName);
            return true;
        }
        if (state == ContainerState.STOPPED) {
            log.warn("[sandbox-docker] Starting container found by name: {}", containerName);
            runDockerCliBlocking(CONTAINER_START_TIMEOUT_SECONDS, "docker", "start", containerName);
            adoptContainer(containerName);
            return true;
        }
        return false;
    }

    private void adoptContainer(String containerName) {
        String resolved = resolveContainerId(containerName);
        if (resolved != null) {
            dockerState.setContainerId(resolved);
        }
        dockerState.setContainerName(containerName);
        // The workspace inside an adopted container has unknown provenance.
        dockerState.setWorkspaceRootReady(false);
    }

    /**
     * @return the deterministic container name for this session
     */
    private String containerName() {
        return "agentscope-sandbox-" + dockerState.getSessionId();
    }

    private void createAndStartContainer(String containerName) throws Exception {
        dockerState.setContainerName(containerName);

        List<String> cmd = buildDockerRunCommand(containerName);

        log.debug(
                "[sandbox-docker] Creating container: image={}, name={}",
                dockerState.getImage(),
                containerName);

        CliResult result =
                runDockerCli(CONTAINER_START_TIMEOUT_SECONDS, cmd.toArray(new String[0]));
        if (!result.succeeded()) {
            throw new SandboxException.SandboxRuntimeException(
                    SandboxErrorCode.WORKSPACE_START_ERROR,
                    "docker run failed (exit=" + result.exitCode() + "): " + result.stderr());
        }
        if (result.stdout().isBlank()) {
            throw new SandboxException.SandboxRuntimeException(
                    SandboxErrorCode.WORKSPACE_START_ERROR,
                    "docker run produced no container ID. stderr: " + result.stderr());
        }

        dockerState.setContainerId(result.stdout());
        log.info(
                "[sandbox-docker] Container started: id={}, name={}",
                result.stdout(),
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
        // Keep the container alive with an idle shell loop. Install a SIGTERM trap and
        // background the sleep with `wait`, so PID 1 (sh) exits promptly on `docker stop`
        // instead of blocking in a foreground `sleep` until the stop timeout forces a
        // SIGKILL. Relies only on `sh` + integer `sleep`, so it stays portable across
        // minimal images (e.g. BusyBox/Alpine) where `sleep infinity` is unsupported.
        cmd.add("sh");
        cmd.add("-c");
        cmd.add("trap 'exit 0' TERM; while :; do sleep 3600 & wait $!; done");

        return cmd;
    }

    /**
     * Inspects a container and returns whether it is running, stopped, or unknown.
     */
    /**
     * Inspects a container and reports whether it is running, stopped, missing, or unknown.
     *
     * <p>"Missing" and "unknown" are deliberately distinct: a missing container can be replaced,
     * while an unanswered probe must not be treated as evidence that the container is gone.
     */
    private ContainerState inspectContainerState(String identifier) {
        try {
            CliResult result =
                    runDockerCli(10, "docker", "inspect", "-f", "{{.State.Running}}", identifier);
            if (!result.succeeded()) {
                return result.reportedMissing() ? ContainerState.MISSING : ContainerState.UNKNOWN;
            }
            return "true".equals(result.stdout()) ? ContainerState.RUNNING : ContainerState.STOPPED;
        } catch (Exception e) {
            log.debug(
                    "[sandbox-docker] Failed to inspect container {}: {}",
                    identifier,
                    e.getMessage());
            return ContainerState.UNKNOWN;
        }
    }

    /**
     * Resolves the id of a container by name.
     *
     * @param containerName the container name
     * @return the container id, or {@code null} when it cannot be resolved
     */
    private String resolveContainerId(String containerName) {
        try {
            CliResult result =
                    runDockerCli(10, "docker", "inspect", "-f", "{{.Id}}", containerName);
            return result.succeeded() && !result.stdout().isBlank() ? result.stdout() : null;
        } catch (Exception e) {
            log.debug(
                    "[sandbox-docker] Failed to resolve id of container {}: {}",
                    containerName,
                    e.getMessage());
            return null;
        }
    }

    /**
     * Runs a Docker CLI command, blocking until completion.
     *
     * @param timeoutSeconds maximum time to wait
     * @param command        command and arguments
     * @throws SandboxException.SandboxRuntimeException if the command fails or times out
     */
    // Visible for testing so unit tests can intercept the docker CLI round trip
    // (upload/download temp-file plumbing) without a live Docker daemon.
    /**
     * Result of one Docker CLI invocation.
     *
     * <p>Callers need the exit code and both streams separately: {@code docker inspect} reports a
     * missing container as a plain non-zero exit, which has to be told apart from a transport
     * failure.
     */
    static final class CliResult {

        private final int exitCode;
        private final String stdout;
        private final String stderr;

        CliResult(int exitCode, String stdout, String stderr) {
            this.exitCode = exitCode;
            this.stdout = stdout;
            this.stderr = stderr;
        }

        int exitCode() {
            return exitCode;
        }

        String stdout() {
            return stdout;
        }

        String stderr() {
            return stderr;
        }

        boolean succeeded() {
            return exitCode == 0;
        }

        /**
         * @return whether the daemon answered that the named object does not exist
         */
        boolean reportedMissing() {
            if (succeeded() || stderr == null) {
                return false;
            }
            String lower = stderr.toLowerCase(Locale.ROOT);
            return lower.contains("no such object") || lower.contains("no such container");
        }
    }

    /**
     * Runs a Docker CLI command, blocking until completion.
     *
     * <p>Single entry point for every Docker CLI round trip so the container lifecycle can be
     * exercised without a live daemon.
     *
     * @param timeoutSeconds maximum time to wait
     * @param command        command and arguments
     * @return the exit code and both streams
     * @throws SandboxException.SandboxRuntimeException if the command times out
     */
    CliResult runDockerCli(int timeoutSeconds, String... command) throws Exception {
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
        Future<String> stdoutFuture =
                drainer.submit(() -> readStream(process.getInputStream(), 64 * 1024));
        Future<String> stderrFuture =
                drainer.submit(() -> readStream(process.getErrorStream(), 64 * 1024));
        drainer.shutdown();

        boolean exited = process.waitFor(timeoutSeconds, TimeUnit.SECONDS);
        if (!exited) {
            process.destroyForcibly();
            drainer.shutdownNow();
            throw new SandboxException.SandboxRuntimeException(
                    SandboxErrorCode.WORKSPACE_START_ERROR,
                    "docker command timed out: " + command[0] + " " + command[1]);
        }
        return new CliResult(
                process.exitValue(), stdoutFuture.get().trim(), stderrFuture.get().trim());
    }

    /**
     * Runs a Docker CLI command, blocking until completion.
     *
     * @param timeoutSeconds maximum time to wait
     * @param command        command and arguments
     * @throws SandboxException.SandboxRuntimeException if the command fails or times out
     */
    // Visible for testing so unit tests can intercept the docker CLI round trip
    // (upload/download temp-file plumbing) without a live Docker daemon.
    void runDockerCliBlocking(int timeoutSeconds, String... command) throws Exception {
        CliResult result = runDockerCli(timeoutSeconds, command);
        if (!result.succeeded()) {
            throw new SandboxException.SandboxRuntimeException(
                    SandboxErrorCode.WORKSPACE_START_ERROR,
                    "docker command failed (exit=" + result.exitCode() + "): " + result.stderr());
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
        MISSING,
        UNKNOWN
    }
}
