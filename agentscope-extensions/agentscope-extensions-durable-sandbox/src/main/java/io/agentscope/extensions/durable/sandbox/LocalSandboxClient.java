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
package io.agentscope.extensions.durable.sandbox;

import io.agentscope.core.agent.RuntimeContext;
import io.agentscope.durable.DurableJson;
import io.agentscope.harness.agent.sandbox.ExecResult;
import io.agentscope.harness.agent.sandbox.Sandbox;
import io.agentscope.harness.agent.sandbox.SandboxClient;
import io.agentscope.harness.agent.sandbox.SandboxClientOptions;
import io.agentscope.harness.agent.sandbox.SandboxState;
import io.agentscope.harness.agent.sandbox.WorkspaceSpec;
import io.agentscope.harness.agent.sandbox.layout.DirEntry;
import io.agentscope.harness.agent.sandbox.layout.FileEntry;
import io.agentscope.harness.agent.sandbox.layout.WorkspaceEntry;
import io.agentscope.harness.agent.sandbox.snapshot.SandboxSnapshotSpec;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

/**
 * Explicit local-development adapter for durable POSIX/Python 3 tools. Commands turn with the host's
 * permissions; this is not a security isolation boundary. Replicas must share this exact filesystem.
 * Initial inline files/directories are staged once. Resume never creates or restores a directory;
 * snapshot import/export is intentionally unsupported because it would copy execution evidence.
 */
public final class LocalSandboxClient implements SandboxClient<SandboxClientOptions> {
    private static final int OUTPUT_LIMIT = 96 * 1024 * 1024;
    private static final int ERROR_LIMIT = 64 * 1024;
    private final Path root;

    public LocalSandboxClient(Path root) {
        try {
            this.root = Files.createDirectories(root.toAbsolutePath().normalize()).toRealPath();
        } catch (IOException failure) {
            throw new UncheckedIOException("Local Sandbox root unavailable", failure);
        }
    }

    /** A ready-to-compose provider target; lifecycle and cleanup remain in SandboxBindings. */
    public SandboxBindings.Target target(WorkspaceSpec workspace) {
        return SandboxBindings.Target.using(this, workspace, null, this::identity)
                .withKeepAlive(
                        sandbox -> {
                            if (!sandbox.isRunning())
                                throw new IOException("Original local Sandbox is missing");
                        });
    }

    public String identity(Sandbox sandbox) {
        return state(sandbox.getState()).id;
    }

    @Override
    public Sandbox create(
            WorkspaceSpec workspace, SandboxSnapshotSpec snapshot, SandboxClientOptions options) {
        if (snapshot != null || options != null)
            throw new IllegalArgumentException(
                    "Local Durable Sandbox uses a root and inline workspace only");
        var spec = workspace == null ? new WorkspaceSpec() : workspace;
        var files = new LinkedHashMap<Path, byte[]>();
        var directories = new HashSet<Path>();
        collect(Path.of(""), spec.getEntries(), files, directories);
        var state =
                new State(
                        UUID.randomUUID().toString(),
                        root.toString(),
                        spec.getEnvironment(),
                        false);
        return new LocalSandbox(state, files, directories);
    }

    private static void collect(
            Path parent,
            Map<String, WorkspaceEntry> entries,
            Map<Path, byte[]> files,
            Set<Path> directories) {
        entries.forEach(
                (name, entry) -> {
                    Path relative = Path.of(name);
                    Path path = parent.resolve(relative).normalize();
                    if (relative.isAbsolute()
                            || path.toString().isBlank()
                            || path.startsWith("..")
                            || path.startsWith(".durable-executions")
                            || path.startsWith(".durable-sandbox-identity"))
                        throw new IllegalArgumentException("Invalid initial Sandbox path");
                    if (entry.isEphemeral())
                        throw new IllegalArgumentException(
                                "Durable local files are staged only once");
                    if (entry instanceof FileEntry file) {
                        var charset =
                                Charset.forName(
                                        file.getEncoding() == null ? "UTF-8" : file.getEncoding());
                        files.put(
                                path,
                                (file.getContent() == null ? "" : file.getContent())
                                        .getBytes(charset));
                    } else if (entry instanceof DirEntry directory) {
                        directories.add(path);
                        collect(path, directory.getChildren(), files, directories);
                    } else {
                        throw new IllegalArgumentException(
                                "Local Durable Sandbox supports inline files and directories");
                    }
                });
    }

    @Override
    public Sandbox resume(SandboxState state) {
        return new LocalSandbox(state(state), null, null);
    }

    @Override
    public void delete(Sandbox sandbox) {
        state(sandbox.getState());
        try {
            sandbox.shutdown();
        } catch (Exception failure) {
            throw new IllegalStateException("Local Sandbox deletion unconfirmed", failure);
        }
    }

    @Override
    public String serializeState(SandboxState value) {
        var state = state(value);
        return DurableJson.value(
                        Map.of(
                                "format",
                                1,
                                "id",
                                state.id,
                                "root",
                                state.root,
                                "environment",
                                state.environment,
                                "ready",
                                state.isWorkspaceRootReady()))
                .toString();
    }

    @Override
    public SandboxState deserializeState(String json) {
        var value = DurableJson.parse(json);
        if (value.path("format").asInt() != 1 || !value.path("environment").isObject())
            throw new IllegalArgumentException("Unknown local Sandbox state");
        var environment = new LinkedHashMap<String, String>();
        value.get("environment")
                .fields()
                .forEachRemaining(
                        entry -> {
                            if (!entry.getValue().isTextual())
                                throw new IllegalArgumentException("Invalid environment value");
                            environment.put(entry.getKey(), entry.getValue().textValue());
                        });
        return state(
                new State(
                        value.required("id").asText(),
                        value.required("root").asText(),
                        environment,
                        value.path("ready").asBoolean()));
    }

    private State state(SandboxState value) {
        if (!(value instanceof State state)
                || !root.toString().equals(state.root)
                || !state.id.matches(
                        "[a-f0-9]{8}-[a-f0-9]{4}-[a-f0-9]{4}-[a-f0-9]{4}-[a-f0-9]{12}"))
            throw new IllegalArgumentException("Original local Sandbox identity or root differs");
        return state;
    }

    private static final class State extends SandboxState {
        final String id;
        final String root;
        final Map<String, String> environment;

        State(String id, String root, Map<String, String> environment, boolean ready) {
            this.id = id;
            this.root = root;
            this.environment = Map.copyOf(environment);
            setWorkspaceRootReady(ready);
        }
    }

    private final class LocalSandbox implements Sandbox {
        private final State state;
        private final Map<Path, byte[]> files;
        private final Set<Path> directories;
        private boolean attempted;

        LocalSandbox(State state, Map<Path, byte[]> files, Set<Path> directories) {
            this.state = state;
            this.files = files;
            this.directories = directories;
        }

        private Path directory() {
            return root.resolve(state.id);
        }

        @Override
        public synchronized void start() throws IOException {
            if (files == null || attempted) {
                requireDirectory();
                if (!state.isWorkspaceRootReady())
                    throw new IOException("Local initialization unconfirmed");
                return;
            }
            attempted = true;
            Files.createDirectory(directory());
            for (var path : directories) Files.createDirectories(directory().resolve(path));
            for (var entry : files.entrySet()) {
                Path path = directory().resolve(entry.getKey());
                Files.createDirectories(path.getParent());
                Files.write(path, entry.getValue(), StandardOpenOption.CREATE_NEW);
            }
            state.setWorkspaceRootReady(true);
        }

        private void requireDirectory() throws IOException {
            if (!isRunning()) throw new IOException("Original local Sandbox directory is missing");
        }

        @Override
        public boolean isRunning() {
            return Files.isDirectory(directory(), LinkOption.NOFOLLOW_LINKS);
        }

        @Override
        public SandboxState getState() {
            return state;
        }

        @Override
        public ExecResult exec(RuntimeContext context, String command, Integer timeoutSeconds)
                throws Exception {
            requireDirectory();
            int timeout = timeoutSeconds == null ? 30 : timeoutSeconds;
            if (timeout < 1)
                throw new IllegalArgumentException("Positive command timeout required");
            Path output = Files.createTempFile("durable-local-control", ".out");
            Path errors = Files.createTempFile("durable-local-control", ".err");
            Process process = null;
            try {
                var builder =
                        new ProcessBuilder("sh", "-c", command)
                                .directory(directory().toFile())
                                .redirectOutput(output.toFile())
                                .redirectError(errors.toFile());
                builder.environment().putAll(state.environment);
                process = builder.start();
                process.getOutputStream().close();
                long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(timeout);
                boolean truncated = false;
                while (!process.waitFor(50, TimeUnit.MILLISECONDS)) {
                    if (Files.size(output) > OUTPUT_LIMIT || Files.size(errors) > ERROR_LIMIT) {
                        truncated = true;
                        break;
                    }
                    if (System.nanoTime() >= deadline)
                        throw new IOException("Local Sandbox control timed out");
                }
                truncated |= Files.size(output) > OUTPUT_LIMIT || Files.size(errors) > ERROR_LIMIT;
                return new ExecResult(
                        process.isAlive() ? -1 : process.exitValue(),
                        read(output, OUTPUT_LIMIT),
                        read(errors, ERROR_LIMIT),
                        truncated);
            } finally {
                // Only stop the control process. Detached durable operations have their own cancel
                // protocol.
                if (process != null && process.isAlive()) process.destroyForcibly();
                Files.deleteIfExists(output);
                Files.deleteIfExists(errors);
            }
        }

        @Override
        public void stop() {}

        @Override
        public void close() throws IOException {
            shutdown();
        }

        @Override
        public void shutdown() throws IOException {
            if (!Files.exists(directory(), LinkOption.NOFOLLOW_LINKS)) return;
            requireDirectory();
            try (var paths = Files.walk(directory())) {
                for (var path : paths.sorted(Comparator.reverseOrder()).toList())
                    Files.deleteIfExists(path);
            }
        }

        @Override
        public InputStream persistWorkspace() {
            throw new UnsupportedOperationException(
                    "Durable local workspaces retain their original directory");
        }

        @Override
        public void hydrateWorkspace(InputStream archive) {
            throw new UnsupportedOperationException(
                    "Durable execution evidence cannot be restored into a replacement");
        }
    }

    private static String read(Path path, int limit) throws IOException {
        try (var input = Files.newInputStream(path)) {
            return new String(input.readNBytes(limit), StandardCharsets.UTF_8);
        }
    }
}
