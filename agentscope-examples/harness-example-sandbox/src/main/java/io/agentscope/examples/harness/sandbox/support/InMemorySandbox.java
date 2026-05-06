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
package io.agentscope.examples.harness.sandbox.support;

import io.agentscope.harness.agent.sandbox.ExecResult;
import io.agentscope.harness.agent.sandbox.Sandbox;
import io.agentscope.harness.agent.sandbox.SandboxState;
import io.agentscope.harness.agent.sandbox.WorkspaceProjectionApplier;
import io.agentscope.harness.agent.sandbox.WorkspaceSpec;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Objects;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import org.apache.commons.compress.archivers.tar.TarArchiveEntry;
import org.apache.commons.compress.archivers.tar.TarArchiveInputStream;

/**
 * In-process {@link Sandbox} that uses a local temp directory as the workspace (for examples).
 *
 * <p>Applies {@link WorkspaceProjectionApplier} payloads on {@link #start} and extracts tar
 * archives in {@link #hydrateWorkspace} so host-projected skills match production behaviour.
 */
public class InMemorySandbox implements Sandbox {

    private final InMemorySandboxState state;
    private final Path workspaceDir;
    private final AtomicBoolean running = new AtomicBoolean(false);
    private final int defaultTimeoutSeconds;

    public InMemorySandbox(InMemorySandboxState state, int defaultTimeoutSeconds) {
        this.state = state;
        this.workspaceDir = Path.of(state.getWorkspaceRoot());
        this.defaultTimeoutSeconds = defaultTimeoutSeconds;
    }

    @Override
    public void start() throws Exception {
        if (!Files.exists(workspaceDir)) {
            Files.createDirectories(workspaceDir);
        }
        applyWorkspaceProjectionIfChanged(state.getWorkspaceSpec());
        state.setWorkspaceRootReady(true);
        running.set(true);
    }

    private void applyWorkspaceProjectionIfChanged(WorkspaceSpec spec) throws Exception {
        WorkspaceProjectionApplier.ProjectionPayload payload =
                WorkspaceProjectionApplier.build(spec);
        if (payload == null) {
            return;
        }
        if (Objects.equals(payload.hash(), state.getWorkspaceProjectionHash())) {
            return;
        }
        if (payload.fileCount() > 0) {
            try (InputStream archive = new ByteArrayInputStream(payload.tarBytes())) {
                hydrateWorkspace(archive);
            }
        }
        state.setWorkspaceProjectionHash(payload.hash());
    }

    @Override
    public void stop() throws Exception {
        state.setWorkspaceRootReady(true);
        running.set(false);
    }

    @Override
    public void shutdown() throws Exception {
        // Leave workspace dir in place for resume in tests
    }

    @Override
    public void close() throws Exception {
        try {
            stop();
        } catch (Exception e) {
            // best-effort
        }
        shutdown();
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
    public ExecResult exec(String command, Integer timeoutSeconds) throws Exception {
        int timeout = timeoutSeconds != null ? timeoutSeconds : defaultTimeoutSeconds;
        ProcessBuilder pb = new ProcessBuilder("sh", "-c", command);
        pb.directory(workspaceDir.toFile());
        pb.redirectErrorStream(false);
        Process process = pb.start();

        boolean finished = process.waitFor(timeout, TimeUnit.SECONDS);
        if (!finished) {
            process.destroyForcibly();
            return new ExecResult(124, "", "Command timed out after " + timeout + "s", false);
        }

        String stdout = new String(process.getInputStream().readAllBytes());
        String stderr = new String(process.getErrorStream().readAllBytes());
        return new ExecResult(process.exitValue(), stdout, stderr, false);
    }

    @Override
    public InputStream persistWorkspace() throws Exception {
        return new ByteArrayInputStream(new byte[1024]);
    }

    @Override
    public void hydrateWorkspace(InputStream archive) throws Exception {
        if (archive == null) {
            return;
        }
        Path root = workspaceDir.normalize();
        try (TarArchiveInputStream tar = new TarArchiveInputStream(archive)) {
            TarArchiveEntry entry;
            while ((entry = tar.getNextEntry()) != null) {
                if (entry.isDirectory()) {
                    continue;
                }
                String name = entry.getName();
                if (name.startsWith("/")) {
                    name = name.substring(1);
                }
                if (name.isBlank()) {
                    continue;
                }
                Path dest = root.resolve(name).normalize();
                if (!dest.startsWith(root)) {
                    throw new IOException("Tar entry escapes workspace: " + name);
                }
                Files.createDirectories(dest.getParent());
                try (OutputStream out = Files.newOutputStream(dest)) {
                    tar.transferTo(out);
                }
            }
        }
    }

    public Path getWorkspaceDir() {
        return workspaceDir;
    }
}
