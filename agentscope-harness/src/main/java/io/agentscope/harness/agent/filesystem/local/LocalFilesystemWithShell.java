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
import java.io.BufferedOutputStream;
import java.io.Closeable;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.io.Reader;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.security.DigestInputStream;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ExecutionException;
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

    /** Read buffer size (bytes) for the stream drainer threads. */
    private static final int DRAIN_CHUNK_BYTES = 8192;

    /**
     * Bounded wait (millis) for process cleanup and drainer termination. Descendants can keep
     * inherited pipes open after their parent exits; EOF is not implied by parent exit.
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

        Process proc = null;
        StreamCapture stdout = null;
        StreamCapture stderr = null;
        Thread stdoutDrainer = null;
        Thread stderrDrainer = null;
        List<ProcessHandle> descendants = new ArrayList<>();
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

            stdout = new StreamCapture(runtimeContext, false);
            stderr = new StreamCapture(runtimeContext, true);
            proc = pb.start();
            // Synchronous spool writes apply disk backpressure, without a growing write queue.
            stdoutDrainer = drainAsync(proc.getInputStream(), stdout, outputCharset(osName));
            stderrDrainer = drainAsync(proc.getErrorStream(), stderr, outputCharset(osName));

            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(effectiveTimeout);
            boolean finished;
            do {
                observeDescendants(proc, descendants);
                long remaining = deadline - System.nanoTime();
                finished =
                        proc.waitFor(
                                Math.max(
                                        0,
                                        Math.min(
                                                remaining,
                                                TimeUnit.MILLISECONDS.toNanos(
                                                        DRAIN_JOIN_TIMEOUT_MILLIS))),
                                TimeUnit.NANOSECONDS);
            } while (!finished && System.nanoTime() < deadline);
            if (!finished) {
                terminateTree(proc, descendants);
            }
            stdoutDrainer.join(DRAIN_JOIN_TIMEOUT_MILLIS);
            stderrDrainer.join(DRAIN_JOIN_TIMEOUT_MILLIS);
            boolean drained = !stdoutDrainer.isAlive() && !stderrDrainer.isAlive();
            if (!drained) {
                terminateTree(proc, descendants);
                closeQuietly(proc.getInputStream());
                closeQuietly(proc.getErrorStream());
                stdoutDrainer.interrupt();
                stderrDrainer.interrupt();
                stdoutDrainer.join(DRAIN_JOIN_TIMEOUT_MILLIS);
                stderrDrainer.join(DRAIN_JOIN_TIMEOUT_MILLIS);
            }

            BoundedOutput combined = new BoundedOutput(Math.max(0, maxOutputBytes));
            combined.append(stdout.snapshot());
            BoundedOutput stderrOutput = stderr.snapshot();
            if (stderrOutput.size > 0) {
                if (combined.size > 0) {
                    combined.append(new byte[] {'\n'});
                }
                combined.append(stderrOutput);
            }
            if (combined.size == 0) {
                combined.append("<no output>".getBytes(StandardCharsets.UTF_8));
            }
            boolean truncated = combined.size > Math.max(0, maxOutputBytes);
            String outputStr = combined.preview();
            if (truncated) {
                outputStr +=
                        "\n\n" + saveOutput(runtimeContext, stdout, stderr, !finished, drained);
            }
            if (!drained || !stdout.complete || !stderr.complete) {
                outputStr +=
                        "\nOnly partial output was captured: output draining did not complete.";
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

            // Ordered, fixed-length stream hashes avoid stdout/stderr boundary ambiguity.
            MessageDigest digest = sha256();
            String outputDigest = null;
            if (drained && stdout.complete && stderr.complete) {
                digest.update(stdout.digest.digest());
                digest.update(stderr.digest.digest());
                outputDigest = HexFormat.of().formatHex(digest.digest());
            }
            return new ExecuteResponse(outputStr, exitCode, truncated, !finished, outputDigest);

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
            // Reactor's interrupt-based cancellation follows the same path as Java interruption.
            // Clear the flag only during bounded cleanup, then restore it for the caller.
            boolean interrupted = Thread.interrupted();
            try {
                if (proc != null) {
                    terminateTree(proc, descendants);
                    closeQuietly(proc.getOutputStream());
                    closeQuietly(proc.getInputStream());
                    closeQuietly(proc.getErrorStream());
                }
                stopDrainer(stdoutDrainer);
                stopDrainer(stderrDrainer);
                if (stdout != null) {
                    stdout.cleanup();
                }
                if (stderr != null) {
                    stderr.cleanup();
                }
            } finally {
                if (interrupted) {
                    Thread.currentThread().interrupt();
                }
            }
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

    private String saveOutput(
            RuntimeContext rc,
            StreamCapture stdout,
            StreamCapture stderr,
            boolean partial,
            boolean drained) {
        String path =
                ToolResultEvictionConfig.DEFAULT_EVICTION_PATH
                        + "/execute/"
                        + UUID.randomUUID()
                        + ".txt";
        Path artifact = null;
        boolean saved = false;
        try {
            // Preserve virtual write policy and subclass storage-failure contracts. Never hand
            // write() a full-output String; only publish the reference after the stream closes.
            WriteResult result = write(rc, path, "");
            if (!result.isSuccess()) {
                log.warn("Failed to save shell output to {}: {}", path, result.error());
                return unavailableOutput();
            }
            artifact = resolvePath(rc, path);
            if (!drained
                    || !stdout.saved()
                    || !stderr.saved()
                    || result.path() == null
                    || result.path().isBlank()
                    || !resolvePath(rc, result.path()).equals(artifact)) {
                return unavailableOutput();
            }
            try (OutputStream out =
                    new BufferedOutputStream(
                            Files.newOutputStream(
                                    artifact,
                                    StandardOpenOption.WRITE,
                                    StandardOpenOption.TRUNCATE_EXISTING),
                            DRAIN_CHUNK_BYTES)) {
                if (partial) {
                    out.write(
                            "Only partial output was captured before timeout:\n\n"
                                    .getBytes(StandardCharsets.UTF_8));
                }
                stdout.copyTo(out);
                if (stderr.output.size > 0) {
                    if (stdout.output.size > 0) {
                        out.write('\n');
                    }
                    stderr.copyTo(out);
                }
                if (stdout.output.size == 0 && stderr.output.size == 0) {
                    out.write("<no output>".getBytes(StandardCharsets.UTF_8));
                }
            }
            if (!Files.isRegularFile(artifact) || !Files.isReadable(artifact)) {
                return unavailableOutput();
            }
            saved = true;
            return (partial ? "Captured partial output" : "Full output")
                    + " saved. Use `read_file` with path `"
                    + result.path()
                    + "` to read it.";
        } catch (IOException | RuntimeException e) {
            // Disk capacity is finite. A failed spool/archive never changes the process outcome.
            log.warn("Failed to save shell output to {}: {}", path, e.getMessage());
            return unavailableOutput();
        } finally {
            if (!saved && artifact != null) {
                deleteQuietly(artifact);
            }
        }
    }

    private static String unavailableOutput() {
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

    private static Thread drainAsync(InputStream in, StreamCapture capture, Charset charset) {
        Thread thread =
                new Thread(
                        () -> {
                            // Readers retain incomplete encoded characters across reads. Carry a
                            // high surrogate too, so UTF-8 re-encoding cannot split a code point.
                            char[] chunk = new char[DRAIN_CHUNK_BYTES + 1];
                            int carry = 0;
                            try (Reader reader =
                                    new InputStreamReader(
                                            new DigestInputStream(in, capture.digest), charset)) {
                                int count;
                                while ((count = reader.read(chunk, carry, DRAIN_CHUNK_BYTES))
                                        != -1) {
                                    count += carry;
                                    carry = Character.isHighSurrogate(chunk[count - 1]) ? 1 : 0;
                                    capture.accept(chunk, count - carry);
                                    if (carry != 0) {
                                        chunk[0] = chunk[count - 1];
                                    }
                                }
                                if (carry != 0) {
                                    capture.accept(chunk, carry);
                                }
                                capture.complete = true;
                            } catch (IOException e) {
                                log.debug(
                                        "Shell output stream ended before EOF: {}", e.getMessage());
                            } finally {
                                capture.finish();
                            }
                        },
                        "agentscope-shell-drain-" + UUID.randomUUID());
        thread.setDaemon(true);
        thread.start();
        return thread;
    }

    private static MessageDigest sha256() {
        try {
            return MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("Java runtime does not support SHA-256", e);
        }
    }

    private static void observeDescendants(Process process, List<ProcessHandle> children) {
        children.removeIf(child -> !child.isAlive());
        try (var descendants = process.descendants()) {
            descendants.forEach(
                    child -> {
                        if (!children.contains(child)) {
                            children.add(child);
                        }
                    });
        } catch (UnsupportedOperationException | SecurityException e) {
            log.debug("Cannot enumerate shell descendants: {}", e.getMessage());
        }
    }

    private static void terminateTree(Process process, List<ProcessHandle> children) {
        // Snapshot before destroying the parent: reparented/escaped descendants may no longer
        // be discoverable afterwards. This is not an OS process-group isolation guarantee.
        observeDescendants(process, children);
        Map<ProcessHandle, Integer> depths = new java.util.HashMap<>();
        children.forEach(child -> depths.put(child, processDepth(child)));
        children.sort(
                java.util.Comparator.comparingInt((ProcessHandle child) -> depths.get(child))
                        .reversed());
        long deadline =
                System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(DRAIN_JOIN_TIMEOUT_MILLIS);
        // Kill deepest first, leaving each parent a bounded opportunity to reap its child.
        for (ProcessHandle child : children) {
            destroyQuietly(child);
            awaitExit(child, deadline);
        }
        process.destroyForcibly();
        awaitExit(
                process.toHandle(),
                System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(DRAIN_JOIN_TIMEOUT_MILLIS));
    }

    private static int processDepth(ProcessHandle process) {
        int depth = 0;
        for (var parent = process.parent(); parent.isPresent(); parent = parent.get().parent()) {
            depth++;
        }
        return depth;
    }

    private static void destroyQuietly(ProcessHandle process) {
        try {
            if (process.isAlive()) {
                process.destroyForcibly();
            }
        } catch (UnsupportedOperationException | SecurityException e) {
            log.warn("Cannot terminate shell descendant {}: {}", process.pid(), e.getMessage());
        }
    }

    private static void awaitExit(ProcessHandle process, long deadline) {
        long remaining = deadline - System.nanoTime();
        if (remaining <= 0 || !process.isAlive()) {
            return;
        }
        try {
            process.onExit().get(remaining, TimeUnit.NANOSECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        } catch (ExecutionException | TimeoutException | UnsupportedOperationException e) {
            log.debug("Shell process {} exit not confirmed: {}", process.pid(), e.getMessage());
        }
    }

    private static void stopDrainer(Thread thread) {
        if (thread == null || !thread.isAlive()) {
            return;
        }
        thread.interrupt();
        try {
            thread.join(DRAIN_JOIN_TIMEOUT_MILLIS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        if (thread.isAlive()) {
            log.warn("Shell drainer {} did not terminate within cleanup timeout", thread.getName());
        }
    }

    private static void closeQuietly(Closeable stream) {
        if (stream != null) {
            try {
                stream.close();
            } catch (IOException | RuntimeException e) {
                log.debug("Failed to close shell capture stream: {}", e.getMessage());
            }
        }
    }

    private static void deleteQuietly(Path file) {
        try {
            Files.deleteIfExists(file);
        } catch (IOException | RuntimeException e) {
            log.warn("Failed to delete shell capture {}: {}", file, e.getMessage());
        }
    }

    /**
     * Decoded UTF-8 spool plus bounded preview. stderr trailing whitespace is speculative:
     * it is written to disk but committed to the preview only when followed by non-whitespace.
     * Thus strip()/line prefix compatibility does not require buffering an entire line or run.
     */
    private final class StreamCapture {
        private final RuntimeContext rc;
        private final String key = ".shell-capture-" + UUID.randomUUID() + ".tmp";
        private final boolean stderr;
        private final MessageDigest digest = sha256();
        private final BoundedOutput output = new BoundedOutput(Math.max(0, maxOutputBytes));
        private final BoundedOutput pending = new BoundedOutput(Math.max(0, maxOutputBytes));
        private Path spool;
        private OutputStream sink;
        private boolean storageFailed;
        private volatile boolean complete;
        private volatile boolean closed;

        private StreamCapture(RuntimeContext rc, boolean stderr) {
            this.rc = rc;
            this.stderr = stderr;
            try {
                spool = resolvePath(rc, key);
                Files.createDirectories(spool.getParent());
                sink =
                        new BufferedOutputStream(
                                Files.newOutputStream(
                                        spool,
                                        StandardOpenOption.CREATE_NEW,
                                        StandardOpenOption.WRITE),
                                DRAIN_CHUNK_BYTES);
            } catch (IOException | RuntimeException e) {
                storageFailed = true;
                log.warn("Shell output spool unavailable: {}", e.getMessage());
            }
        }

        private void accept(char[] chars, int count) {
            if (!stderr) {
                emit(new String(chars, 0, count).getBytes(StandardCharsets.UTF_8), false);
                return;
            }
            int start = 0;
            while (start < count) {
                boolean whitespace = Character.isWhitespace(chars[start]);
                int end = start + 1;
                while (end < count
                        && chars[end] != '\n'
                        && Character.isWhitespace(chars[end]) == whitespace) {
                    end++;
                }
                if (output.size == 0 && whitespace) {
                    start = end;
                    continue;
                }
                if (!whitespace) {
                    if (output.size == 0) {
                        emit("[stderr] ".getBytes(StandardCharsets.UTF_8), false);
                    }
                    output.append(pending);
                    pending.reset();
                }
                if (chars[start] == '\n') {
                    // Each LF is prefixed, including interior blank lines, but not stripped LFs.
                    emit("\n[stderr] ".getBytes(StandardCharsets.UTF_8), true);
                    start++;
                } else {
                    emit(
                            new String(chars, start, end - start).getBytes(StandardCharsets.UTF_8),
                            whitespace);
                    start = end;
                }
            }
        }

        private void emit(byte[] bytes, boolean speculative) {
            (speculative ? pending : output).append(bytes);
            if (sink != null) {
                try {
                    sink.write(bytes);
                } catch (IOException e) {
                    storageFailed = true;
                    closeQuietly(sink);
                    sink = null;
                    log.warn("Shell output spool write failed: {}", e.getMessage());
                }
            }
        }

        private void finish() {
            if (sink != null) {
                try {
                    sink.close();
                    // Discard stderr.stripTrailing() without reading the spool into memory.
                    try (var channel =
                            Files.newByteChannel(resolvePath(rc, key), StandardOpenOption.WRITE)) {
                        channel.truncate(output.size);
                    }
                } catch (IOException | RuntimeException e) {
                    storageFailed = true;
                    log.warn("Shell output spool completion failed: {}", e.getMessage());
                } finally {
                    sink = null;
                }
            }
            closed = true;
        }

        private BoundedOutput snapshot() {
            return output.snapshot();
        }

        private boolean saved() {
            return complete && closed && !storageFailed && spool != null;
        }

        private void copyTo(OutputStream out) throws IOException {
            try (InputStream in = Files.newInputStream(resolvePath(rc, key))) {
                byte[] chunk = new byte[DRAIN_CHUNK_BYTES];
                int n;
                long copied = 0;
                while ((n = in.read(chunk)) != -1) {
                    out.write(chunk, 0, n);
                    copied += n;
                }
                if (copied != output.size) {
                    throw new IOException("Shell output spool is incomplete");
                }
            }
        }

        private void cleanup() {
            closeQuietly(sink);
            if (spool != null) {
                deleteQuietly(spool);
            }
        }
    }

    /** Two byte-budget arrays and a long count, independent of total output size. */
    private static final class BoundedOutput {
        private final byte[] head;
        private final byte[] tail;
        private long size;
        private int tailPosition;

        private BoundedOutput(int budget) {
            head = new byte[budget];
            tail = new byte[budget];
        }

        private synchronized void append(byte[] bytes) {
            int headLength = (int) Math.min(size, head.length);
            int headCopy = Math.min(bytes.length, head.length - headLength);
            System.arraycopy(bytes, 0, head, headLength, headCopy);
            if (tail.length > 0) {
                int length = Math.min(bytes.length, tail.length);
                int offset = bytes.length - length;
                int first = Math.min(length, tail.length - tailPosition);
                System.arraycopy(bytes, offset, tail, tailPosition, first);
                System.arraycopy(bytes, offset + first, tail, 0, length - first);
                tailPosition = (tailPosition + length) % tail.length;
            }
            size += bytes.length;
        }

        private synchronized byte[] tailBytes() {
            int length = (int) Math.min(size, tail.length);
            byte[] bytes = new byte[length];
            int start = size < tail.length ? 0 : tailPosition;
            int first = Math.min(length, tail.length - start);
            System.arraycopy(tail, start, bytes, 0, first);
            System.arraycopy(tail, 0, bytes, first, length - first);
            return bytes;
        }

        private synchronized void append(BoundedOutput other) {
            int headLength = (int) Math.min(other.size, other.head.length);
            append(java.util.Arrays.copyOf(other.head, headLength));
            long remaining = other.size - headLength;
            if (remaining > 0) {
                byte[] suffix = other.tailBytes();
                int length = (int) Math.min(remaining, suffix.length);
                size += remaining - length;
                append(java.util.Arrays.copyOfRange(suffix, suffix.length - length, suffix.length));
            }
        }

        private synchronized BoundedOutput snapshot() {
            BoundedOutput copy = new BoundedOutput(head.length);
            copy.append(this);
            return copy;
        }

        private synchronized void reset() {
            size = 0;
            tailPosition = 0;
        }

        private String preview() {
            if (size <= head.length) {
                return new String(head, 0, (int) size, StandardCharsets.UTF_8);
            }
            int headEnd = head.length / 2;
            while (headEnd > 0 && (head[headEnd] & 0xc0) == 0x80) {
                headEnd--;
            }
            byte[] suffix = tailBytes();
            int tailStart = suffix.length - (head.length - head.length / 2);
            while (tailStart < suffix.length && (suffix[tailStart] & 0xc0) == 0x80) {
                tailStart++;
            }
            return new String(head, 0, headEnd, StandardCharsets.UTF_8)
                    + "\n\n... "
                    + (size - headEnd - suffix.length + tailStart)
                    + " UTF-8 bytes omitted ...\n\n"
                    + new String(
                            suffix, tailStart, suffix.length - tailStart, StandardCharsets.UTF_8);
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
