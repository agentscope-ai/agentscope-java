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
import java.util.concurrent.ThreadFactory;
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

    private static final int OUTPUT_DRAIN_TIMEOUT_SECONDS = 5;

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

        Process proc = null;
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
            Process startedProcess = proc;
            ExecutorService streamReaders =
                    Executors.newFixedThreadPool(2, newStreamReaderThreadFactory());
            try {
                Future<CapturedOutput> stdoutFuture =
                        streamReaders.submit(
                                () ->
                                        readCapturedOutput(
                                                startedProcess.getInputStream(), maxOutputBytes));
                Future<CapturedOutput> stderrFuture =
                        streamReaders.submit(
                                () ->
                                        readCapturedOutput(
                                                startedProcess.getErrorStream(), maxOutputBytes));

                boolean finished = proc.waitFor(effectiveTimeout, TimeUnit.SECONDS);
                if (!finished) {
                    proc.destroyForcibly();
                    proc.waitFor(OUTPUT_DRAIN_TIMEOUT_SECONDS, TimeUnit.SECONDS);
                }

                CapturedOutput stdout = getOutput(stdoutFuture);
                CapturedOutput stderr = getOutput(stderrFuture);
                if (!finished) {
                    return timeoutResponse(effectiveTimeout, timeoutSeconds != null);
                }

                Charset outputCharset = outputCharset(osName);
                String outputStr =
                        formatOutput(
                                new String(stdout.bytes(), outputCharset),
                                new String(stderr.bytes(), outputCharset));
                boolean truncated = stdout.truncated() || stderr.truncated();
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
            } finally {
                streamReaders.shutdownNow();
            }

        } catch (IOException | InterruptedException e) {
            if (e instanceof InterruptedException) {
                if (proc != null && proc.isAlive()) {
                    proc.destroyForcibly();
                }
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

    private static ThreadFactory newStreamReaderThreadFactory() {
        return runnable -> {
            Thread thread = new Thread(runnable, "LocalFilesystemWithShell-StreamReader");
            thread.setDaemon(true);
            return thread;
        };
    }

    private ExecuteResponse timeoutResponse(int effectiveTimeout, boolean customTimeout) {
        String msg;
        if (customTimeout) {
            msg =
                    "Error: Command timed out after "
                            + effectiveTimeout
                            + " seconds (custom timeout). The command may be stuck or require more"
                            + " time.";
        } else {
            msg =
                    "Error: Command timed out after "
                            + effectiveTimeout
                            + " seconds. For long-running commands, re-run using the timeout"
                            + " parameter.";
        }
        return new ExecuteResponse(msg, 124, false);
    }

    private static String formatOutput(String stdout, String stderr) {
        StringBuilder output = new StringBuilder();
        if (!stdout.isEmpty()) {
            output.append(stdout);
        }
        if (!stderr.isBlank()) {
            for (String line : stderr.strip().split("\n")) {
                if (!output.isEmpty()) {
                    output.append('\n');
                }
                output.append("[stderr] ").append(line);
            }
        }
        return output.isEmpty() ? "<no output>" : output.toString();
    }

    static CapturedOutput readCapturedOutput(InputStream stream, int maxBytes) throws IOException {
        ByteArrayOutputStream captured = new ByteArrayOutputStream(Math.min(maxBytes, 8_192));
        byte[] buffer = new byte[8_192];
        boolean truncated = false;
        int read;
        while ((read = stream.read(buffer)) != -1) {
            int writable = Math.min(read, maxBytes - captured.size());
            if (writable > 0) {
                captured.write(buffer, 0, writable);
            }
            truncated |= writable < read;
        }
        return new CapturedOutput(captured.toByteArray(), truncated);
    }

    private static CapturedOutput getOutput(Future<CapturedOutput> future)
            throws IOException, InterruptedException {
        try {
            return future.get(OUTPUT_DRAIN_TIMEOUT_SECONDS, TimeUnit.SECONDS);
        } catch (ExecutionException e) {
            Throwable cause = e.getCause();
            if (cause instanceof IOException) {
                throw (IOException) cause;
            }
            throw new IOException("Failed to collect shell output", cause);
        } catch (TimeoutException e) {
            future.cancel(true);
            throw new IOException("Timed out while collecting shell output", e);
        }
    }

    record CapturedOutput(byte[] bytes, boolean truncated) {}

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
