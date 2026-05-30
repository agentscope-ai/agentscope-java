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
package io.agentscope.harness.agent.example.support;

import io.agentscope.core.agent.RuntimeContext;
import io.agentscope.harness.agent.sandbox.ExecResult;
import io.agentscope.harness.agent.sandbox.Sandbox;
import io.agentscope.harness.agent.sandbox.SandboxState;
import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.PathMatcher;
import java.util.Comparator;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Test-only {@link Sandbox} that uses a local temp directory as the workspace.
 */
public class InMemorySandbox implements Sandbox {

    private static final Pattern FIND_FILES_PATTERN =
            Pattern.compile("^find\\s+'([^']*)'\\s+-type\\s+f\\s+-name\\s+'([^']*)'.*");

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
        state.setWorkspaceRootReady(true);
        running.set(true);
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
    public ExecResult exec(RuntimeContext runtimeContext, String command, Integer timeoutSeconds)
            throws Exception {
        ExecResult handled = executeBuiltIn(command);
        if (handled != null) {
            return handled;
        }

        int timeout = timeoutSeconds != null ? timeoutSeconds : defaultTimeoutSeconds;
        ProcessBuilder pb = shell(command);
        pb.directory(workspaceDir.toFile());
        pb.redirectErrorStream(false);
        Process process = pb.start();

        boolean finished = process.waitFor(timeout, TimeUnit.SECONDS);
        if (!finished) {
            process.destroyForcibly();
            return new ExecResult(124, "", "Command timed out after " + timeout + "s", false);
        }

        String stdout = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        String stderr = new String(process.getErrorStream().readAllBytes(), StandardCharsets.UTF_8);
        return new ExecResult(process.exitValue(), stdout, stderr, false);
    }

    private ExecResult executeBuiltIn(String command) throws Exception {
        Matcher matcher = FIND_FILES_PATTERN.matcher(command != null ? command.trim() : "");
        if (!matcher.matches()) {
            return null;
        }

        String relativePath = matcher.group(1);
        String glob = matcher.group(2);
        Path root =
                workspaceDir
                        .resolve(
                                relativePath == null || relativePath.isBlank() ? "." : relativePath)
                        .normalize();
        if (!root.startsWith(workspaceDir) || !Files.exists(root)) {
            return new ExecResult(0, "", "", false);
        }

        PathMatcher pathMatcher = root.getFileSystem().getPathMatcher("glob:" + glob);
        List<String> files;
        try (var walk = Files.walk(root)) {
            files =
                    walk.filter(Files::isRegularFile)
                            .filter(path -> pathMatcher.matches(path.getFileName()))
                            .map(workspaceDir::relativize)
                            .map(path -> path.toString().replace('\\', '/'))
                            .sorted(Comparator.naturalOrder())
                            .toList();
        }
        return new ExecResult(0, String.join("\n", files), "", false);
    }

    private ProcessBuilder shell(String command) {
        if (System.getProperty("os.name", "").toLowerCase().contains("win")) {
            return new ProcessBuilder("cmd.exe", "/c", command);
        }
        return new ProcessBuilder("sh", "-c", command);
    }

    @Override
    public InputStream persistWorkspace() throws Exception {
        return new ByteArrayInputStream(new byte[1024]);
    }

    @Override
    public void hydrateWorkspace(InputStream archive) throws Exception {
        // no-op
    }

    public Path getWorkspaceDir() {
        return workspaceDir;
    }
}
