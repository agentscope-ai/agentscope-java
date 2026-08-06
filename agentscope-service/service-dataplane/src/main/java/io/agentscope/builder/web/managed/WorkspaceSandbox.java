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
package io.agentscope.builder.web.managed;

import io.agentscope.core.agent.RuntimeContext;
import io.agentscope.harness.agent.sandbox.ExecResult;
import io.agentscope.harness.agent.sandbox.Sandbox;
import io.agentscope.harness.agent.sandbox.SandboxState;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Docker-less {@link Sandbox} backed by a plain host directory, registered by an Environment
 * Worker (via the worker REST API) as the
 * Priority-1 {@code externalSandbox} for a {@code self_hosted} managed session.
 *
 * <p>The harness never calls {@link #stop()}/{@link #shutdown()} on a Priority-1 sandbox (see
 * {@code SandboxManager.release}) — the registering worker owns the full lifecycle, including
 * deleting {@link #workDir()} once the session is done with it. {@link #start()} is called once
 * per turn by {@code SandboxLifecycleMiddleware} and is idempotent.
 */
public final class WorkspaceSandbox implements Sandbox {

    private static final int DEFAULT_EXEC_TIMEOUT_SECONDS = 120;
    private static final int MAX_CAPTURED_BYTES = 200_000;

    private final Path workDir;
    private final SandboxState state;
    private final AtomicBoolean running = new AtomicBoolean(false);

    public WorkspaceSandbox(String sessionId, Path workDir) {
        this.workDir = workDir;
        this.state = new WorkspaceSandboxState();
        this.state.setSessionId(sessionId);
    }

    /** Host directory backing this sandbox's shell execution and (indirectly) its filesystem. */
    public Path workDir() {
        return workDir;
    }

    @Override
    public void start() throws Exception {
        Files.createDirectories(workDir);
        running.set(true);
    }

    @Override
    public void stop() throws Exception {
        // No-op: the worker owns persistence/cleanup of workDir, not the harness.
    }

    @Override
    public void shutdown() throws Exception {
        // No-op: the harness never destroys a Priority-1 externalSandbox's backend resources.
    }

    @Override
    public void close() throws Exception {
        stop();
    }

    @Override
    public boolean isRunning() {
        return running.get();
    }

    @Override
    public SandboxState getState() {
        return state;
    }

    @Override
    public ExecResult exec(RuntimeContext runtimeContext, String command, Integer timeoutSeconds)
            throws Exception {
        int timeout = timeoutSeconds != null ? timeoutSeconds : DEFAULT_EXEC_TIMEOUT_SECONDS;
        Files.createDirectories(workDir);
        ProcessBuilder pb = new ProcessBuilder("bash", "-lc", command);
        pb.directory(workDir.toFile());
        Process process = pb.start();

        ByteArrayOutputStream stdoutBuf = new ByteArrayOutputStream();
        ByteArrayOutputStream stderrBuf = new ByteArrayOutputStream();
        Thread stdoutReader = drainAsync(process.getInputStream(), stdoutBuf);
        Thread stderrReader = drainAsync(process.getErrorStream(), stderrBuf);

        boolean finished = process.waitFor(timeout, TimeUnit.SECONDS);
        if (!finished) {
            process.destroyForcibly();
        }
        stdoutReader.join(1000);
        stderrReader.join(1000);

        String stdout = stdoutBuf.toString(StandardCharsets.UTF_8);
        String stderr = stderrBuf.toString(StandardCharsets.UTF_8);
        boolean truncated =
                stdoutBuf.size() > MAX_CAPTURED_BYTES || stderrBuf.size() > MAX_CAPTURED_BYTES;
        int exitCode = finished ? process.exitValue() : 124;
        if (!finished) {
            stderr =
                    stderr
                            + (stderr.isBlank() ? "" : "\n")
                            + "[timeout] command exceeded "
                            + timeout
                            + "s and was killed";
        }
        return new ExecResult(exitCode, stdout, stderr, truncated);
    }

    private static Thread drainAsync(InputStream in, ByteArrayOutputStream out) {
        Thread t =
                new Thread(
                        () -> {
                            try {
                                byte[] buf = new byte[8192];
                                int n;
                                int total = 0;
                                while ((n = in.read(buf)) != -1) {
                                    if (total < MAX_CAPTURED_BYTES) {
                                        out.write(buf, 0, n);
                                    }
                                    total += n;
                                }
                            } catch (Exception ignored) {
                                // stream closed on process termination
                            }
                        },
                        "workspace-sandbox-drain");
        t.setDaemon(true);
        t.start();
        return t;
    }

    @Override
    public InputStream persistWorkspace() throws Exception {
        throw new UnsupportedOperationException(
                "WorkspaceSandbox does not support snapshot persistence; the worker owns"
                        + " workspace durability");
    }

    @Override
    public void hydrateWorkspace(InputStream archive) throws Exception {
        throw new UnsupportedOperationException(
                "WorkspaceSandbox does not support snapshot hydration; the worker owns"
                        + " workspace durability");
    }

    /** Minimal {@link SandboxState}: this sandbox is never persisted/resumed by the harness. */
    private static final class WorkspaceSandboxState extends SandboxState {}
}
