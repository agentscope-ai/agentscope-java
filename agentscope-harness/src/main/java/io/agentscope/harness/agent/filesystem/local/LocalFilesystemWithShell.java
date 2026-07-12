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
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
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

            Process proc = pb.start();
            ExecutorService drainers =
                    Executors.newFixedThreadPool(
                            2,
                            runnable -> {
                                Thread thread = new Thread(runnable, "local-shell-output-drainer");
                                thread.setDaemon(true);
                                return thread;
                            });
            Future<CapturedOutput> stdoutFuture =
                    drainers.submit(() -> drain(proc.getInputStream(), maxOutputBytes));
            Future<CapturedOutput> stderrFuture =
                    drainers.submit(() -> drain(proc.getErrorStream(), maxOutputBytes));
            long deadlineNanos = System.nanoTime() + TimeUnit.SECONDS.toNanos(effectiveTimeout);
            Set<ProcessHandle> descendants = new HashSet<>();
            CapturedOutput stdoutCapture;
            CapturedOutput stderrCapture;
            try {
                boolean finished = waitForProcess(proc, deadlineNanos, descendants);
                if (!finished) {
                    terminateProcessTree(proc, descendants);
                    closeProcessStreams(proc);
                    return timeoutResponse(effectiveTimeout, timeoutSeconds != null);
                }
                try {
                    stdoutCapture = awaitCapture(stdoutFuture, deadlineNanos);
                    stderrCapture = awaitCapture(stderrFuture, deadlineNanos);
                } catch (TimeoutException e) {
                    terminateProcessTree(proc, descendants);
                    closeProcessStreams(proc);
                    return timeoutResponse(effectiveTimeout, timeoutSeconds != null);
                }
            } catch (InterruptedException | ExecutionException e) {
                terminateProcessTree(proc, descendants);
                closeProcessStreams(proc);
                throw e;
            } finally {
                drainers.shutdownNow();
            }

            String stdout = stdoutCapture.text();
            String stderr = stderrCapture.text();

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

            boolean truncated = stdoutCapture.truncated() || stderrCapture.truncated();
            if (truncated || outputStr.length() > maxOutputBytes) {
                if (outputStr.length() > maxOutputBytes) {
                    outputStr = outputStr.substring(0, maxOutputBytes);
                }
                outputStr += "\n\n... Output truncated at " + maxOutputBytes + " bytes.";
                truncated = true;
            }

            int exitCode = proc.exitValue();
            if (exitCode != 0) {
                outputStr = outputStr.stripTrailing() + "\n\nExit code: " + exitCode;
            }

            return new ExecuteResponse(outputStr, exitCode, truncated);

        } catch (IOException | InterruptedException | ExecutionException e) {
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
        }
    }

    private static CapturedOutput drain(InputStream stream, int maxBytes) throws IOException {
        ByteArrayOutputStream captured = new ByteArrayOutputStream(Math.min(maxBytes, 8192));
        byte[] buffer = new byte[8192];
        boolean truncated = false;
        int read;
        while ((read = stream.read(buffer)) != -1) {
            int remaining = maxBytes - captured.size();
            if (remaining > 0) {
                captured.write(buffer, 0, Math.min(remaining, read));
            }
            if (read > remaining) {
                truncated = true;
            }
        }
        return new CapturedOutput(captured.toString(StandardCharsets.UTF_8), truncated);
    }

    private static boolean waitForProcess(
            Process process, long deadlineNanos, Set<ProcessHandle> descendants)
            throws InterruptedException {
        while (true) {
            process.toHandle().descendants().forEach(descendants::add);
            if (!process.isAlive()) {
                return true;
            }
            long remaining = deadlineNanos - System.nanoTime();
            if (remaining <= 0) {
                return false;
            }
            if (process.waitFor(
                    Math.min(remaining, TimeUnit.MILLISECONDS.toNanos(50)), TimeUnit.NANOSECONDS)) {
                process.toHandle().descendants().forEach(descendants::add);
                return true;
            }
        }
    }

    private static CapturedOutput awaitCapture(Future<CapturedOutput> future, long deadlineNanos)
            throws InterruptedException, ExecutionException, TimeoutException {
        long remaining = deadlineNanos - System.nanoTime();
        if (remaining <= 0) {
            throw new TimeoutException("shell output drain exceeded command deadline");
        }
        return future.get(remaining, TimeUnit.NANOSECONDS);
    }

    private static void terminateProcessTree(
            Process process, Set<ProcessHandle> observedDescendants) {
        List<ProcessHandle> handles = new ArrayList<>(observedDescendants);
        process.toHandle().descendants().forEach(handles::add);
        handles.stream()
                .distinct()
                .sorted(Comparator.comparingLong(ProcessHandle::pid).reversed())
                .filter(ProcessHandle::isAlive)
                .forEach(ProcessHandle::destroy);
        if (process.isAlive()) {
            process.destroy();
        }
        try {
            process.waitFor(250, TimeUnit.MILLISECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        handles.stream().filter(ProcessHandle::isAlive).forEach(ProcessHandle::destroyForcibly);
        if (process.isAlive()) {
            process.destroyForcibly();
        }
    }

    private static void closeProcessStreams(Process process) {
        try {
            process.getInputStream().close();
        } catch (IOException ignored) {
            // Best effort during timeout cleanup.
        }
        try {
            process.getErrorStream().close();
        } catch (IOException ignored) {
            // Best effort during timeout cleanup.
        }
        try {
            process.getOutputStream().close();
        } catch (IOException ignored) {
            // Best effort during timeout cleanup.
        }
    }

    private static ExecuteResponse timeoutResponse(int timeoutSeconds, boolean customTimeout) {
        String suffix =
                customTimeout
                        ? " seconds (custom timeout). The command may be stuck or require more"
                                + " time."
                        : " seconds. For long-running commands, re-run using the timeout"
                                + " parameter.";
        return new ExecuteResponse(
                "Error: Command timed out after " + timeoutSeconds + suffix, 124, false);
    }

    private record CapturedOutput(String text, boolean truncated) {}

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
}
