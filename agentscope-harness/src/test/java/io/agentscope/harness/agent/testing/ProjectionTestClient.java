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
package io.agentscope.harness.agent.testing;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.agentscope.core.agent.RuntimeContext;
import io.agentscope.harness.agent.sandbox.AbstractBaseSandbox;
import io.agentscope.harness.agent.sandbox.ExecResult;
import io.agentscope.harness.agent.sandbox.Sandbox;
import io.agentscope.harness.agent.sandbox.SandboxClient;
import io.agentscope.harness.agent.sandbox.SandboxClientOptions;
import io.agentscope.harness.agent.sandbox.SandboxState;
import io.agentscope.harness.agent.sandbox.WorkspaceArchiveExtractor;
import io.agentscope.harness.agent.sandbox.WorkspaceSpec;
import io.agentscope.harness.agent.sandbox.snapshot.LocalSandboxSnapshot;
import io.agentscope.harness.agent.sandbox.snapshot.SandboxSnapshotSpec;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.file.FileSystems;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.PathMatcher;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.apache.commons.compress.archivers.tar.TarArchiveEntry;
import org.apache.commons.compress.archivers.tar.TarArchiveOutputStream;

/** Local, deterministic backend exercising the real projection/snapshot lifecycle without Docker. */
public final class ProjectionTestClient implements SandboxClient<SandboxClientOptions> {
    private final Path base;
    public LocalSandbox last;
    public int resumes;

    public ProjectionTestClient(Path base) {
        this.base = base;
    }

    @Override
    public Sandbox create(
            WorkspaceSpec spec, SandboxSnapshotSpec snapshot, SandboxClientOptions options) {
        Path dir = base.resolve(UUID.randomUUID().toString());
        TestState state = new TestState(dir.toString());
        state.setSessionId(dir.getFileName().toString());
        state.setWorkspaceSpec(spec.copy());
        state.setSnapshot(
                new LocalSandboxSnapshot(
                        base.resolve("snapshots").toString(), state.getSessionId()));
        return resumeLocal(state);
    }

    private LocalSandbox resumeLocal(TestState state) {
        state.getWorkspaceSpec().setRoot(state.directory);
        last = new LocalSandbox(state);
        return last;
    }

    @Override
    public Sandbox resume(SandboxState state) {
        resumes++;
        return resumeLocal((TestState) state);
    }

    @Override
    public void delete(Sandbox sandbox) {}

    @Override
    public String serializeState(SandboxState state) {
        try {
            TestState s = (TestState) state;
            return new ObjectMapper()
                    .writeValueAsString(
                            new Saved(
                                    s.directory, s.getSessionId(), s.getWorkspaceProjectionHash()));
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    @Override
    public SandboxState deserializeState(String json) {
        try {
            Saved saved = new ObjectMapper().readValue(json, Saved.class);
            TestState state = new TestState(saved.directory());
            state.setSessionId(saved.id());
            state.setWorkspaceSpec(new WorkspaceSpec());
            state.setWorkspaceProjectionHash(saved.hash());
            state.setWorkspaceRootReady(true);
            state.setSnapshot(
                    new LocalSandboxSnapshot(base.resolve("snapshots").toString(), saved.id()));
            return state;
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private record Saved(String directory, String id, String hash) {}

    private static final class TestState extends SandboxState {
        private final String directory;

        TestState(String directory) {
            this.directory = directory;
        }
    }

    public static final class LocalSandbox extends AbstractBaseSandbox {
        private final Path dir;
        public int hydrations;
        public boolean failVerification;

        public LocalSandbox(SandboxState state) {
            super(state);
            dir = Path.of(state.getWorkspaceSpec().getRoot());
        }

        public Path directory() {
            return dir;
        }

        @Override
        protected String getWorkspaceRoot() {
            return dir.toString();
        }

        @Override
        public void shutdown() {}

        @Override
        protected void doSetupWorkspace() throws Exception {
            Files.createDirectories(dir);
        }

        @Override
        protected void doDestroyWorkspace() {}

        @Override
        protected void doHydrateWorkspace(InputStream archive) throws Exception {
            hydrations++;
            WorkspaceArchiveExtractor.extractTarArchive(dir, archive);
        }

        @Override
        protected InputStream doPersistWorkspace() throws Exception {
            ByteArrayOutputStream bytes = new ByteArrayOutputStream();
            try (TarArchiveOutputStream tar = new TarArchiveOutputStream(bytes);
                    var paths = Files.walk(dir)) {
                for (Path path : paths.filter(Files::isRegularFile).toList()) {
                    TarArchiveEntry entry =
                            new TarArchiveEntry(dir.relativize(path).toString().replace('\\', '/'));
                    entry.setSize(Files.size(path));
                    tar.putArchiveEntry(entry);
                    Files.copy(path, tar);
                    tar.closeArchiveEntry();
                }
                tar.finish();
            }
            return new ByteArrayInputStream(bytes.toByteArray());
        }

        /** Interprets only the filesystem commands exercised by these tests; unknown commands fail. */
        @Override
        protected ExecResult doExec(RuntimeContext rc, String command, int timeout)
                throws Exception {
            if (command.startsWith("test -d ")) return result(Files.isDirectory(dir) ? 0 : 1, "");
            List<String> quoted = new ArrayList<>();
            Matcher tokens = Pattern.compile("'([^']*)'").matcher(command);
            while (tokens.find()) quoted.add(tokens.group(1));
            if (command.contains("sha256sum")) {
                if (failVerification) return result(127, "");
                StringBuilder out = new StringBuilder();
                Matcher files = Pattern.compile("sha256sum < '([^']*)'").matcher(command);
                while (files.find()) {
                    Path path = resolve(files.group(1));
                    if (!Files.isRegularFile(path) || Files.isSymbolicLink(path))
                        return result(1, "");
                    out.append(
                                    HexFormat.of()
                                            .formatHex(
                                                    MessageDigest.getInstance("SHA-256")
                                                            .digest(Files.readAllBytes(path))))
                            .append("  -\n");
                }
                return result(0, out.toString());
            }
            if (command.startsWith("printf '%s' ")) {
                Matcher writes =
                        Pattern.compile("printf '%s' '([^']*)' > '([^']*)'").matcher(command);
                boolean wrote = false;
                while (writes.find()) {
                    Path path = resolve(writes.group(2));
                    Files.createDirectories(path.getParent());
                    Files.writeString(path, writes.group(1));
                    wrote = true;
                }
                return result(wrote ? 0 : 1, "");
            }
            if (command.startsWith("if [ ! -f ")) {
                Path path = resolve(quoted.get(0));
                return result(
                        0,
                        !Files.isRegularFile(path)
                                ? "__NOT_FOUND__"
                                : Files.size(path) == 0 ? "__EMPTY__" : Files.readString(path));
            }
            if (command.startsWith("find ")) {
                Path start = resolve(quoted.get(0));
                String glob = quoted.get(1);
                if (!Files.isDirectory(start)) return result(0, "");
                StringBuilder out = new StringBuilder();
                PathMatcher matcher = FileSystems.getDefault().getPathMatcher("glob:" + glob);
                try (var files = Files.walk(start)) {
                    for (Path p : files.filter(Files::isRegularFile).sorted().toList()) {
                        if (matcher.matches(p.getFileName()))
                            out.append(dir.relativize(p).toString().replace('\\', '/'))
                                    .append("\t")
                                    .append(Files.size(p))
                                    .append("\t0\n");
                    }
                }
                return result(0, out.toString());
            }
            // Maintenance may probe for files that do not exist in this fixture.
            return result(1, "Unsupported fixture command: " + command);
        }

        private Path resolve(String path) {
            return dir.resolve(path).normalize();
        }

        private ExecResult result(int code, String out) {
            return new ExecResult(code, out, "", false);
        }
    }
}
