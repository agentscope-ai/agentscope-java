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
package io.agentscope.harness.agent.filesystem;

import io.agentscope.harness.agent.filesystem.model.ExecuteResponse;
import io.agentscope.harness.agent.store.NamespaceFactory;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
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

    private final String sandboxId;
    private final int defaultTimeout;
    private final int maxOutputBytes;
    private final Map<String, String> env;

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
        super(rootDir, virtualMode, 10, namespaceFactory);

        if (timeout <= 0) {
            throw new IllegalArgumentException("timeout must be positive, got " + timeout);
        }

        this.defaultTimeout = timeout;
        this.maxOutputBytes = maxOutputBytes;
        this.sandboxId = "local-" + UUID.randomUUID().toString().substring(0, 8);

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

    @Override
    public ExecuteResponse execute(String command, Integer timeoutSeconds) {
        if (command == null || command.isBlank()) {
            return new ExecuteResponse("Error: Command must be a non-empty string.", 1, false);
        }

        int effectiveTimeout = timeoutSeconds != null ? timeoutSeconds : defaultTimeout;
        if (effectiveTimeout <= 0) {
            throw new IllegalArgumentException("timeout must be positive, got " + effectiveTimeout);
        }

        try {
            Path workDir = resolveExecuteCwd();
            ProcessBuilder pb =
                    new ProcessBuilder("sh", "-c", command)
                            .directory(workDir.toFile())
                            .redirectErrorStream(false);

            if (!env.isEmpty()) {
                pb.environment().clear();
                pb.environment().putAll(env);
            }

            Process proc = pb.start();

            boolean finished = proc.waitFor(effectiveTimeout, TimeUnit.SECONDS);

            String stdout =
                    new String(proc.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
            String stderr =
                    new String(proc.getErrorStream().readAllBytes(), StandardCharsets.UTF_8);

            if (!finished) {
                proc.destroyForcibly();
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

            boolean truncated = false;
            if (outputStr.length() > maxOutputBytes) {
                outputStr =
                        outputStr.substring(0, maxOutputBytes)
                                + "\n\n... Output truncated at "
                                + maxOutputBytes
                                + " bytes.";
                truncated = true;
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
        }
    }

    private Path resolveExecuteCwd() {
        NamespaceFactory nsf = getNamespaceFactory();
        if (nsf == null) {
            return getCwd();
        }
        List<String> ns = nsf.getNamespace();
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
