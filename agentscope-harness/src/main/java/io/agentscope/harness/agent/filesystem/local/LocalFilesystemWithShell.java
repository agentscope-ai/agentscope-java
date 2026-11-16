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
import io.agentscope.harness.agent.filesystem.model.WriteResult;
import io.agentscope.harness.agent.filesystem.remote.store.NamespaceFactory;
import io.agentscope.harness.agent.filesystem.sandbox.AbstractSandboxFilesystem;
import io.agentscope.harness.agent.memory.compaction.ToolResultEvictionConfig;
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
import java.util.concurrent.TimeUnit;
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

    /**
     * Safety net (millis) for joining drainer threads after the process exits or is destroyed.
     * Never reached in practice: process exit closes the streams and ends the drainers at once.
     */
    private static final long DRAIN_JOIN_TIMEOUT_MILLIS = 5000;

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
     * @param maxOutputBytes maximum UTF-8 bytes retained from output, excluding notices
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
     * @param maxOutputBytes maximum UTF-8 bytes retained from output, excluding notices
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
     * @param maxOutputBytes maximum UTF-8 bytes retained from output, excluding notices
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
     * @param maxOutputBytes maximum UTF-8 bytes retained from output, excluding notices
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

            // stdout/stderr must be drained concurrently with waitFor: if the child writes
            // more than the OS pipe buffer (~4 KB on Windows, 64 KB default on Linux) while
            // the parent blocks in waitFor, both sides deadlock and every such command is
            // misreported as a timeout (exit 124).
            ByteArrayOutputStream stdoutBuf = new ByteArrayOutputStream();
            ByteArrayOutputStream stderrBuf = new ByteArrayOutputStream();
            Thread stdoutDrainer = drainAsync(proc.getInputStream(), stdoutBuf);
            Thread stderrDrainer = drainAsync(proc.getErrorStream(), stderrBuf);

            boolean finished = proc.waitFor(effectiveTimeout, TimeUnit.SECONDS);
            if (!finished) {
                proc.destroyForcibly();
            }
            joinQuietly(stdoutDrainer);
            joinQuietly(stderrDrainer);

            Charset outputCharset = outputCharset(osName);
            String stdout = stdoutBuf.toString(outputCharset);
            String stderr = stderrBuf.toString(outputCharset);

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

            String completeOutput = output.isEmpty() ? "<no output>" : output.toString();
            byte[] outputBytes = completeOutput.getBytes(StandardCharsets.UTF_8);
            boolean truncated = outputBytes.length > Math.max(0, maxOutputBytes);
            String outputStr = completeOutput;
            if (truncated) {
                outputStr =
                        previewOutput(outputBytes, Math.max(0, maxOutputBytes))
                                + "\n\n"
                                + saveOutput(runtimeContext, completeOutput, !finished);
            }

            int exitCode = finished ? proc.exitValue() : 124;
            if (!finished) {
                outputStr =
                        "Error: Command timed out after "
                                + effectiveTimeout
                                + " seconds"
                                + (timeoutSeconds != null ? " (custom timeout)" : "")
                                + ". Only partial output was captured before timeout:\n\n"
                                + outputStr;
            }
            if (exitCode != 0) {
                outputStr = outputStr.stripTrailing() + "\n\nExit code: " + exitCode;
            }

            return new ExecuteResponse(outputStr, exitCode, truncated, !finished);

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
        }
    }

    /** The byte budget covers retained output; omission and recovery notices are additional. */
    static String previewOutput(byte[] output, int byteBudget) {
        byteBudget = Math.max(0, byteBudget);
        if (output.length <= byteBudget) {
            return new String(output, StandardCharsets.UTF_8);
        }
        int headEnd = byteBudget / 2;
        int tailStart = output.length - (byteBudget - headEnd);
        // Do not split a UTF-8 code point at either end of the omitted region.
        while (headEnd > 0 && (output[headEnd] & 0xc0) == 0x80) {
            headEnd--;
        }
        while (tailStart < output.length && (output[tailStart] & 0xc0) == 0x80) {
            tailStart++;
        }
        return new String(output, 0, headEnd, StandardCharsets.UTF_8)
                + "\n\n... "
                + (tailStart - headEnd)
                + " UTF-8 bytes omitted ...\n\n"
                + new String(output, tailStart, output.length - tailStart, StandardCharsets.UTF_8);
    }

    private String saveOutput(RuntimeContext rc, String completeOutput, boolean partial) {
        String path =
                ToolResultEvictionConfig.DEFAULT_EVICTION_PATH
                        + "/execute/"
                        + UUID.randomUUID()
                        + ".txt";
        try {
            WriteResult result =
                    write(
                            rc,
                            path,
                            partial
                                    ? "Only partial output was captured before timeout:\n\n"
                                            + completeOutput
                                    : completeOutput);
            if (result.isSuccess() && result.path() != null && !result.path().isBlank()) {
                return (partial ? "Captured partial output" : "Full output")
                        + " saved. Use `read_file` with path `"
                        + result.path()
                        + "` to read it.";
            }
            log.warn("Failed to save shell output to {}: {}", path, result.error());
        } catch (RuntimeException e) {
            // Path-policy denial or storage failure must not change the command's outcome.
            log.warn("Failed to save shell output to {}: {}", path, e.getMessage());
        }
        return "Full output unavailable: the captured output could not be saved.";
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

    /**
     * Continuously copies a subprocess stream into {@code buf} on a daemon thread so the child
     * never blocks on a full OS pipe buffer. Read errors (e.g. the stream closing when the
     * process is destroyed on timeout) end the drainer quietly.
     */
    private static Thread drainAsync(InputStream in, ByteArrayOutputStream buf) {
        Thread t =
                new Thread(
                        () -> {
                            byte[] chunk = new byte[DRAIN_CHUNK_BYTES];
                            int n;
                            try {
                                while ((n = in.read(chunk)) != -1) {
                                    buf.write(chunk, 0, n);
                                }
                            } catch (IOException ignored) {
                                // Stream closed because the process was destroyed; nothing to do.
                            }
                        });
        t.setDaemon(true);
        t.start();
        return t;
    }

    private static void joinQuietly(Thread t) {
        try {
            t.join(DRAIN_JOIN_TIMEOUT_MILLIS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
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
