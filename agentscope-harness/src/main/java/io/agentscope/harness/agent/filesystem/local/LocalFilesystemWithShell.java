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
package io.agentscope.harness.agent.filesystem.local;

import io.agentscope.core.agent.RuntimeContext;
import io.agentscope.harness.agent.filesystem.model.ExecuteResponse;
import io.agentscope.harness.agent.filesystem.remote.store.NamespaceFactory;
import io.agentscope.harness.agent.filesystem.sandbox.AbstractSandboxFilesystem;
import io.agentscope.harness.agent.workspace.LocalFsMode;
import io.agentscope.harness.agent.workspace.PathPolicy;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Stream;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Filesystem with unrestricted local shell command execution.
 *
 * <p>This implementation extends {@link LocalFilesystem} to add shell command execution
 * capabilities. Commands are executed directly on the host system without any
 * sandboxing, process isolation, or security restrictions.
 *
 * <p><b>WARNING:</b> This implementation grants agents BOTH direct filesystem access AND unrestricted
 * shell execution on your local machine. Use with extreme caution and only in
 * appropriate environments (local dev, CI/CD with proper secret management).
 */
public class LocalFilesystemWithShell extends LocalFilesystem implements AbstractSandboxFilesystem {

    private static final Logger log = LoggerFactory.getLogger(LocalFilesystemWithShell.class);

    /** Default timeout in seconds for shell command execution. */
    public static final int DEFAULT_EXECUTE_TIMEOUT = 120;

    /** Read buffer size (bytes) for the stream drainer threads. */
    private static final int DRAIN_CHUNK_BYTES = 8192;

    /** Shared deadline for obtaining immutable stdout and stderr snapshots after process exit. */
    private static final long DRAIN_TEARDOWN_TIMEOUT_MILLIS = 5000;

    /** Bounded wait for the directly launched process to terminate after a forced destroy. */
    private static final long PROCESS_TERMINATION_TIMEOUT_MILLIS = 1000;

    private final String sandboxId;
    private final int defaultTimeout;
    private final int maxOutputBytes;
    private final Map<String, String> env;

    /**
     * Working directory passed to {@link ProcessBuilder#directory(java.io.File)} for shell
     * commands. When {@code null}, falls back to {@link #getCwd()} (with per-call namespace
     * prefix). Decouples shell {@code pwd} from the filesystem root so overlay-mode callers can
     * keep filesystem operations rooted at the agent workspace while shell sees the user's
     * project directory.
     */
    private final Path shellCwd;

    /**
     * Creates an abstract filesystem with default settings.
     *
     * @param rootDir working directory for both filesystem and shell operations
     */
    public LocalFilesystemWithShell(Path rootDir) {
        this(rootDir, false, DEFAULT_EXECUTE_TIMEOUT, 100_000, null, false, null);
    }

    /**
     * Same as {@link #LocalFilesystemWithShell(Path)} with a path string; see
     * {@link LocalFilesystem#LocalFilesystem(String)} for {@code null} / blank rules.
     */
    public LocalFilesystemWithShell(String rootDir) {
        this(
                LocalFilesystem.rootDirFromString(rootDir),
                false,
                DEFAULT_EXECUTE_TIMEOUT,
                100_000,
                null,
                false,
                null);
    }

    /**
     * Creates an abstract filesystem with default settings and namespace support.
     *
     * @param rootDir working directory for both filesystem and shell operations
     * @param namespaceFactory optional namespace factory for path scoping ({@code null} for none)
     */
    public LocalFilesystemWithShell(Path rootDir, NamespaceFactory namespaceFactory) {
        this(rootDir, false, DEFAULT_EXECUTE_TIMEOUT, 100_000, null, false, namespaceFactory);
    }

    /**
     * Same as {@link #LocalFilesystemWithShell(Path, NamespaceFactory)} with a path string; see
     * {@link LocalFilesystem#LocalFilesystem(String)} for {@code null} / blank rules.
     */
    public LocalFilesystemWithShell(String rootDir, NamespaceFactory namespaceFactory) {
        this(
                LocalFilesystem.rootDirFromString(rootDir),
                false,
                DEFAULT_EXECUTE_TIMEOUT,
                100_000,
                null,
                false,
                namespaceFactory);
    }

    /**
     * Creates a abstract filesystem with full configuration.
     *
     * @param rootDir working directory for both filesystem and shell operations
     * @param virtualMode enable virtual path mode for filesystem operations
     * @param timeout default maximum time in seconds for shell command execution
     * @param maxOutputBytes maximum number of bytes to capture from command output
     * @param env environment variables for shell commands ({@code null} for empty)
     * @param inheritEnv whether to inherit the parent process's environment variables
     */
    public LocalFilesystemWithShell(
            Path rootDir,
            boolean virtualMode,
            int timeout,
            int maxOutputBytes,
            Map<String, String> env,
            boolean inheritEnv) {
        this(rootDir, virtualMode, timeout, maxOutputBytes, env, inheritEnv, null);
    }

    /**
     * Same as {@link #LocalFilesystemWithShell(Path, boolean, int, int, Map, boolean)} with a path
     * string; see {@link LocalFilesystem#LocalFilesystem(String)} for {@code null} / blank rules.
     */
    public LocalFilesystemWithShell(
            String rootDir,
            boolean virtualMode,
            int timeout,
            int maxOutputBytes,
            Map<String, String> env,
            boolean inheritEnv) {
        this(
                LocalFilesystem.rootDirFromString(rootDir),
                virtualMode,
                timeout,
                maxOutputBytes,
                env,
                inheritEnv,
                null);
    }

    /**
     * Creates a abstract filesystem with full configuration and namespace support.
     *
     * @param rootDir working directory for both filesystem and shell operations
     * @param virtualMode enable virtual path mode for filesystem operations
     * @param timeout default maximum time in seconds for shell command execution
     * @param maxOutputBytes maximum number of bytes to capture from command output
     * @param env environment variables for shell commands ({@code null} for empty)
     * @param inheritEnv whether to inherit the parent process's environment variables
     * @param namespaceFactory optional namespace factory for path scoping ({@code null} for none)
     */
    public LocalFilesystemWithShell(
            Path rootDir,
            boolean virtualMode,
            int timeout,
            int maxOutputBytes,
            Map<String, String> env,
            boolean inheritEnv,
            NamespaceFactory namespaceFactory) {
        this(
                rootDir,
                virtualMode,
                timeout,
                maxOutputBytes,
                env,
                inheritEnv,
                namespaceFactory,
                null);
    }

    /**
     * Creates a abstract filesystem with full configuration, namespace support, and a custom
     * shell working directory.
     *
     * @param rootDir working directory for filesystem operations (read/write/edit/glob/...)
     * @param virtualMode enable virtual path mode for filesystem operations
     * @param timeout default maximum time in seconds for shell command execution
     * @param maxOutputBytes maximum number of bytes to capture from command output
     * @param env environment variables for shell commands ({@code null} for empty)
     * @param inheritEnv whether to inherit the parent process's environment variables
     * @param namespaceFactory optional namespace factory for path scoping ({@code null} for none)
     * @param shellCwd working directory for shell command execution; when {@code null}, falls
     *     back to {@code rootDir} (with namespace prefix when configured)
     */
    public LocalFilesystemWithShell(
            Path rootDir,
            boolean virtualMode,
            int timeout,
            int maxOutputBytes,
            Map<String, String> env,
            boolean inheritEnv,
            NamespaceFactory namespaceFactory,
            Path shellCwd) {
        this(
                rootDir,
                virtualMode ? LocalFsMode.SANDBOXED : LocalFsMode.UNRESTRICTED,
                null,
                timeout,
                maxOutputBytes,
                env,
                inheritEnv,
                namespaceFactory,
                shellCwd);
    }

    /**
     * Most-complete constructor: filesystem operations follow {@code mode} and {@code pathPolicy}
     * (see {@link LocalFilesystem#LocalFilesystem(Path, LocalFsMode, PathPolicy, int, NamespaceFactory)});
     * shell commands run with {@code pwd = shellCwd} when set, otherwise the filesystem root.
     *
     * @param rootDir filesystem root for relative-path operations
     * @param mode path-resolution policy ({@code null} treated as {@link LocalFsMode#UNRESTRICTED})
     * @param pathPolicy allow-list for {@link LocalFsMode#ROOTED}; ignored otherwise
     * @param timeout default shell timeout (seconds, must be positive)
     * @param maxOutputBytes byte cap for captured shell output
     * @param env environment variables for shell commands ({@code null} for empty)
     * @param inheritEnv whether to inherit the parent process environment
     * @param namespaceFactory optional per-user/session namespace factory
     * @param shellCwd shell {@code pwd}; {@code null} falls back to {@code rootDir} (with
     *     namespace prefix when configured)
     */
    public LocalFilesystemWithShell(
            Path rootDir,
            LocalFsMode mode,
            PathPolicy pathPolicy,
            int timeout,
            int maxOutputBytes,
            Map<String, String> env,
            boolean inheritEnv,
            NamespaceFactory namespaceFactory,
            Path shellCwd) {
        super(rootDir, mode, pathPolicy, 10, namespaceFactory);

        if (timeout <= 0) {
            throw new IllegalArgumentException("timeout must be positive, got " + timeout);
        }
        if (maxOutputBytes <= 0) {
            throw new IllegalArgumentException(
                    "maxOutputBytes must be positive, got " + maxOutputBytes);
        }

        this.defaultTimeout = timeout;
        this.maxOutputBytes = maxOutputBytes;
        this.sandboxId = "local-" + UUID.randomUUID().toString().substring(0, 8);
        this.shellCwd = shellCwd != null ? shellCwd.toAbsolutePath().normalize() : null;

        if (inheritEnv) {
            Map<String, String> merged = new java.util.HashMap<>(System.getenv());
            if (env != null) {
                merged.putAll(env);
            }
            this.env = Map.copyOf(merged);
        } else {
            this.env = env != null ? Map.copyOf(env) : Map.of();
        }
    }

    /**
     * Same as {@link #LocalFilesystemWithShell(Path, boolean, int, int, Map, boolean,
     * NamespaceFactory)} with a path string; see {@link LocalFilesystem#LocalFilesystem(String)}
     * for {@code null} / blank rules.
     */
    public LocalFilesystemWithShell(
            String rootDir,
            boolean virtualMode,
            int timeout,
            int maxOutputBytes,
            Map<String, String> env,
            boolean inheritEnv,
            NamespaceFactory namespaceFactory) {
        this(
                LocalFilesystem.rootDirFromString(rootDir),
                virtualMode,
                timeout,
                maxOutputBytes,
                env,
                inheritEnv,
                namespaceFactory);
    }

    @Override
    public String id() {
        return sandboxId;
    }

    /**
     * Returns the working directory configured for shell {@code execute()} calls, or {@code null}
     * when shell falls back to the filesystem root (with namespace prefix). Used by upstream
     * code that needs to expose the user-visible project directory in prompts or diagnostics.
     */
    public Path getShellCwd() {
        return shellCwd;
    }

    @Override
    public ExecuteResponse execute(
            RuntimeContext runtimeContext, String command, Integer timeoutSeconds) {
        if (command == null || command.isBlank()) {
            return new ExecuteResponse("Error: Command must be a non-empty string.", 1, false);
        }

        int effectiveTimeout = timeoutSeconds != null ? timeoutSeconds : defaultTimeout;
        if (effectiveTimeout <= 0) {
            throw new IllegalArgumentException("timeout must be positive, got " + effectiveTimeout);
        }

        Process proc = null;
        InputStream stdoutStream = null;
        InputStream stderrStream = null;
        ExecutorService drainExecutor = null;
        Future<BoundedCapture> stdoutFuture = null;
        Future<BoundedCapture> stderrFuture = null;

        try {
            Path workDir = resolveExecuteCwd(runtimeContext);
            String osName = System.getProperty("os.name").toLowerCase();
            ProcessBuilder pb =
                    (osName.contains("win")
                                    ? new ProcessBuilder("cmd.exe", "/c", command)
                                    : new ProcessBuilder("sh", "-c", command))
                            .directory(workDir.toFile())
                            .redirectErrorStream(false);

            if (!env.isEmpty()) {
                pb.environment().clear();
                pb.environment().putAll(env);
            }

            proc = pb.start();
            proc.getOutputStream().close();

            // stdout/stderr must be drained concurrently with waitFor: if the child writes
            // more than the OS pipe buffer (~4 KB on Windows, 64 KB default on Linux) while
            // the parent blocks in waitFor, both sides deadlock and every such command is
            // misreported as a timeout (exit 124).
            stdoutStream = proc.getInputStream();
            stderrStream = proc.getErrorStream();
            drainExecutor = createDrainExecutor();
            InputStream stdoutSource = stdoutStream;
            InputStream stderrSource = stderrStream;
            stdoutFuture = drainExecutor.submit(() -> drainStream(stdoutSource, maxOutputBytes));
            stderrFuture = drainExecutor.submit(() -> drainStream(stderrSource, maxOutputBytes));
            drainExecutor.shutdown();

            boolean finished = proc.waitFor(effectiveTimeout, TimeUnit.SECONDS);
            if (!finished) {
                String msg;
                if (timeoutSeconds != null) {
                    msg =
                            "Error: Command timed out after "
                                    + effectiveTimeout
                                    + " seconds (custom timeout). The command may be stuck or"
                                    + " require more time.";
                } else {
                    msg =
                            "Error: Command timed out after "
                                    + effectiveTimeout
                                    + " seconds. For long-running commands, re-run using the"
                                    + " timeout parameter.";
                }
                return new ExecuteResponse(msg, 124, false);
            }

            long captureDeadline =
                    System.nanoTime()
                            + TimeUnit.MILLISECONDS.toNanos(DRAIN_TEARDOWN_TIMEOUT_MILLIS);
            BoundedCapture stdoutBuf = awaitCapture(stdoutFuture, stdoutStream, captureDeadline);
            BoundedCapture stderrBuf = awaitCapture(stderrFuture, stderrStream, captureDeadline);
            if (stdoutBuf == null || stderrBuf == null) {
                return new ExecuteResponse(
                        "Error: Command output capture did not complete within "
                                + DRAIN_TEARDOWN_TIMEOUT_MILLIS
                                + " milliseconds after the process exited.",
                        1,
                        false);
            }

            Charset outputCharset = outputCharset(osName);
            String stdout = stdoutBuf.asString(outputCharset);
            String stderr = stderrBuf.asString(outputCharset);

            StringBuilder output = new StringBuilder();
            if (stdout != null && !stdout.isEmpty()) {
                output.append(stdout);
            }
            if (stderr != null && !stderr.isBlank()) {
                String[] stderrLines = stderr.strip().split("\n");
                for (String line : stderrLines) {
                    if (!output.isEmpty()) {
                        output.append('\n');
                    }
                    output.append("[stderr] ").append(line);
                }
            }

            String outputStr = output.isEmpty() ? "<no output>" : output.toString();

            boolean truncated = stdoutBuf.truncated() || stderrBuf.truncated();
            if (outputStr.length() > maxOutputBytes) {
                outputStr = outputStr.substring(0, maxOutputBytes);
                truncated = true;
            }
            if (truncated) {
                outputStr += "\n\n... Output truncated at " + maxOutputBytes + " bytes.";
            }

            int exitCode = proc.exitValue();
            if (exitCode != 0) {
                outputStr = outputStr.stripTrailing() + "\n\nExit code: " + exitCode;
            }

            return new ExecuteResponse(outputStr, exitCode, truncated);

        } catch (IOException | InterruptedException e) {
            if (e instanceof InterruptedException) {
                Thread.currentThread().interrupt();
            }
            log.error("Command execution failed: {}", e.getMessage(), e);
            return new ExecuteResponse(
                    "Error executing command ("
                            + e.getClass().getSimpleName()
                            + "): "
                            + e.getMessage(),
                    1,
                    false);
        } finally {
            destroyProcessTree(proc);
            discardCapture(stdoutFuture, stdoutStream);
            discardCapture(stderrFuture, stderrStream);
            if (drainExecutor != null) {
                drainExecutor.shutdownNow();
            }
        }
    }

    private Path resolveExecuteCwd(RuntimeContext rc) {
        if (shellCwd != null) {
            return shellCwd;
        }
        NamespaceFactory nsf = getNamespaceFactory();
        if (nsf == null) {
            return getCwd();
        }
        List<String> ns = nsf.getNamespace(rc);
        if (ns == null || ns.isEmpty()) {
            return getCwd();
        }
        Path namespaced = getCwd();
        for (String segment : ns) {
            namespaced = namespaced.resolve(segment);
        }
        try {
            Files.createDirectories(namespaced);
        } catch (IOException e) {
            log.warn("Failed to create namespace directory {}: {}", namespaced, e.getMessage());
        }
        return namespaced;
    }

    private ExecutorService createDrainExecutor() {
        AtomicInteger readerIndex = new AtomicInteger();
        return Executors.newFixedThreadPool(
                2,
                task -> {
                    Thread thread =
                            new Thread(
                                    task,
                                    "agentscope-shell-drainer-"
                                            + sandboxId
                                            + "-"
                                            + readerIndex.incrementAndGet());
                    thread.setDaemon(true);
                    return thread;
                });
    }

    static BoundedCapture drainStream(InputStream in, int maxOutputBytes) {
        BoundedCapture capture = new BoundedCapture(maxOutputBytes);
        drainStream(in, capture);
        return capture;
    }

    private static void drainStream(InputStream in, BoundedCapture capture) {
        byte[] chunk = new byte[DRAIN_CHUNK_BYTES];
        int n;
        try {
            while ((n = in.read(chunk)) != -1) {
                capture.append(chunk, n);
            }
        } catch (IOException ignored) {
            // Stream closed because the process was destroyed; nothing to do.
        }
    }

    static final class BoundedCapture {
        private final ByteArrayOutputStream buffer;
        private final int maxBytes;
        private volatile boolean truncated;

        BoundedCapture(int maxBytes) {
            this.maxBytes = maxBytes;
            this.buffer =
                    new ByteArrayOutputStream(Math.max(0, Math.min(DRAIN_CHUNK_BYTES, maxBytes)));
        }

        private void append(byte[] chunk, int length) {
            synchronized (buffer) {
                int remaining = Math.max(0, maxBytes - buffer.size());
                int retained = Math.min(remaining, length);
                if (retained > 0) {
                    buffer.write(chunk, 0, retained);
                }
                if (retained < length) {
                    truncated = true;
                }
            }
        }

        int size() {
            return buffer.size();
        }

        boolean truncated() {
            return truncated;
        }

        String asString(Charset charset) {
            return buffer.toString(charset);
        }
    }

    static BoundedCapture awaitCapture(
            Future<BoundedCapture> future, InputStream stream, long deadlineNanos)
            throws InterruptedException {
        long remainingNanos = Math.max(0, deadlineNanos - System.nanoTime());
        try {
            return future.get(remainingNanos, TimeUnit.NANOSECONDS);
        } catch (InterruptedException e) {
            discardCapture(future, stream);
            throw e;
        } catch (TimeoutException e) {
            log.warn("Timed out while capturing command output");
            discardCapture(future, stream);
            return null;
        } catch (ExecutionException e) {
            log.warn("Failed while capturing command output", e.getCause());
            discardCapture(future, stream);
            return null;
        }
    }

    private static void discardCapture(Future<?> future, InputStream stream) {
        closeQuietly(stream);
        if (future != null && !future.isDone()) {
            future.cancel(true);
        }
    }

    private static void closeQuietly(InputStream stream) {
        if (stream == null) {
            return;
        }
        try {
            stream.close();
        } catch (IOException e) {
            log.debug("Failed to close command output stream", e);
        }
    }

    private static void destroyProcessTree(Process process) {
        if (process == null) {
            return;
        }

        boolean interrupted = Thread.interrupted();
        try {
            ProcessHandle root = process.toHandle();
            if (!root.isAlive()) {
                return;
            }

            List<ProcessHandle> descendants;
            try (Stream<ProcessHandle> handles = root.descendants()) {
                descendants = handles.toList();
            } catch (RuntimeException e) {
                descendants = List.of();
                log.warn("Failed to snapshot command descendants before termination", e);
            }

            // Snapshot before killing the parent, then stop the parent first so it cannot keep
            // forking. ProcessHandle is inherently best-effort: a child may fork or be reparented
            // between the snapshot and these destroy calls.
            try {
                process.destroyForcibly();
            } catch (RuntimeException e) {
                log.warn("Failed to destroy command process", e);
            }
            for (ProcessHandle descendant : descendants) {
                try {
                    if (descendant.isAlive() && !descendant.destroyForcibly()) {
                        log.warn("Failed to destroy command descendant pid={}", descendant.pid());
                    }
                } catch (RuntimeException e) {
                    log.warn("Failed to destroy command descendant pid={}", descendant.pid(), e);
                }
            }

            long terminationDeadline =
                    System.nanoTime()
                            + TimeUnit.MILLISECONDS.toNanos(PROCESS_TERMINATION_TIMEOUT_MILLIS);
            try {
                if (!awaitProcessExit(process, terminationDeadline)) {
                    log.warn("Command process did not terminate after forced destroy");
                }
            } catch (InterruptedException e) {
                interrupted = true;
            }
            for (ProcessHandle descendant : descendants) {
                try {
                    if (!awaitProcessExit(descendant, terminationDeadline)) {
                        log.warn(
                                "Command descendant did not terminate after forced destroy pid={}",
                                descendant.pid());
                    }
                } catch (InterruptedException e) {
                    interrupted = true;
                }
            }
        } catch (RuntimeException e) {
            log.warn("Failed to destroy command process tree", e);
        } finally {
            if (interrupted) {
                Thread.currentThread().interrupt();
            }
        }
    }

    private static boolean awaitProcessExit(Process process, long deadlineNanos)
            throws InterruptedException {
        if (!process.isAlive()) {
            return true;
        }
        long remainingNanos = Math.max(0, deadlineNanos - System.nanoTime());
        return process.waitFor(remainingNanos, TimeUnit.NANOSECONDS);
    }

    private static boolean awaitProcessExit(ProcessHandle process, long deadlineNanos)
            throws InterruptedException {
        if (!process.isAlive()) {
            return true;
        }
        long remainingNanos = Math.max(0, deadlineNanos - System.nanoTime());
        try {
            process.onExit().get(remainingNanos, TimeUnit.NANOSECONDS);
            return true;
        } catch (ExecutionException e) {
            log.warn("Failed while waiting for command descendant pid={}", process.pid(), e);
            return !process.isAlive();
        } catch (TimeoutException e) {
            return !process.isAlive();
        }
    }

    static Charset outputCharset(String osName) {
        return outputCharset(osName, System.getProperty("native.encoding"));
    }

    static Charset outputCharset(String osName, String nativeEncoding) {
        if (!osName.toLowerCase(Locale.ROOT).contains("win")) {
            return StandardCharsets.UTF_8;
        }

        if (nativeEncoding != null && Charset.isSupported(nativeEncoding)) {
            return Charset.forName(nativeEncoding);
        }
        return Charset.defaultCharset();
    }
}
