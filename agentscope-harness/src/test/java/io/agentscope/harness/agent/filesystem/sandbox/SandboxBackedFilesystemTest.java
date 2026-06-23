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
package io.agentscope.harness.agent.filesystem.sandbox;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.agentscope.core.agent.RuntimeContext;
import io.agentscope.harness.agent.filesystem.model.FileDownloadResponse;
import io.agentscope.harness.agent.filesystem.model.FileUploadResponse;
import io.agentscope.harness.agent.sandbox.ExecResult;
import io.agentscope.harness.agent.sandbox.Sandbox;
import io.agentscope.harness.agent.sandbox.SandboxException;
import io.agentscope.harness.agent.sandbox.SandboxState;
import io.agentscope.harness.agent.sandbox.WorkspaceSpec;
import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.AbstractMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.apache.commons.compress.archivers.tar.TarArchiveEntry;
import org.apache.commons.compress.archivers.tar.TarArchiveInputStream;
import org.junit.jupiter.api.Test;

class SandboxBackedFilesystemTest {

    private static final RuntimeContext RT = RuntimeContext.empty();

    @Test
    void downloadFiles_decodesBase64Output() {
        byte[] expected = "hello world".getBytes(StandardCharsets.UTF_8);
        SandboxBackedFilesystem fs = new SandboxBackedFilesystem();
        fs.setSandbox(new StubSandbox("aGVsbG8gd29ybGQ=", expected));

        List<FileDownloadResponse> responses = fs.downloadFiles(RT, List.of("/workspace/note.txt"));

        assertEquals(1, responses.size());
        assertTrue(responses.get(0).isSuccess());
        assertArrayEquals(expected, responses.get(0).content());
    }

    @Test
    void uploadFiles_streamsThroughTarHydration() throws Exception {
        byte[] content = "session-line\n".repeat(6000).getBytes(StandardCharsets.UTF_8);
        StubSandbox sandbox = new StubSandbox("", stateWithRoot("/workspace"));
        SandboxBackedFilesystem fs = new SandboxBackedFilesystem();
        fs.setSandbox(sandbox);

        List<FileUploadResponse> responses =
                fs.uploadFiles(
                        RT,
                        List.of(Map.entry("/workspace/agents/demo/sessions/large.jsonl", content)));

        assertEquals(1, responses.size());
        assertTrue(responses.get(0).isSuccess());
        assertEquals(0, sandbox.execCalls.get(), "upload must not shell out through exec()");
        assertEquals(1, sandbox.hydrateCalls.get(), "upload should hydrate exactly once");
        assertTarEntry(sandbox.archiveBytes.get(), "agents/demo/sessions/large.jsonl", content);
    }

    @Test
    void operationsRequireSandbox() {
        SandboxBackedFilesystem fs = new SandboxBackedFilesystem();

        assertThrows(
                SandboxException.SandboxConfigurationException.class,
                () -> fs.execute(RT, "echo hi", 1));
        assertThrows(
                SandboxException.SandboxConfigurationException.class,
                () -> fs.uploadFiles(RT, List.of(Map.entry("/workspace/note.txt", bytes("x")))));
        assertThrows(
                SandboxException.SandboxConfigurationException.class,
                () -> fs.downloadFiles(RT, List.of("/workspace/note.txt")));
    }

    @Test
    void execute_coversSuccessTimeoutAndFallbackBranches() {
        SandboxBackedFilesystem fs = new SandboxBackedFilesystem();

        fs.setSandbox(
                new StubSandbox(
                        (runtimeContext, command, timeoutSeconds) ->
                                new ExecResult(0, "stdout", "stderr", true),
                        stateWithRoot("/workspace")));
        var success = fs.execute(RT, "echo hi", 5);
        assertEquals("stdout\n[stderr] stderr", success.output());
        assertEquals(0, success.exitCode());
        assertTrue(success.truncated());

        fs.setSandbox(
                new StubSandbox(
                        (runtimeContext, command, timeoutSeconds) -> {
                            throw new SandboxException.ExecTimeoutException(
                                    command, timeoutSeconds != null ? timeoutSeconds : 0);
                        },
                        stateWithRoot("/workspace")));
        var timeout = fs.execute(RT, "sleep 99", 3);
        assertEquals("Command timed out after 3s: sleep 99", timeout.output());
        assertEquals(124, timeout.exitCode());
        assertFalse(timeout.truncated());

        fs.setSandbox(
                new StubSandbox(
                        (runtimeContext, command, timeoutSeconds) -> {
                            throw new SandboxException.ExecException(7, "stdout", "stderr");
                        },
                        stateWithRoot("/workspace")));
        var execFailure = fs.execute(RT, "cmd", null);
        assertEquals("stdout\nstderr", execFailure.output());
        assertEquals(7, execFailure.exitCode());
        assertFalse(execFailure.truncated());

        fs.setSandbox(
                new StubSandbox(
                        (runtimeContext, command, timeoutSeconds) -> {
                            throw new IllegalStateException("boom");
                        },
                        stateWithRoot("/workspace")));
        var internalFailure = fs.execute(RT, "cmd", null);
        assertEquals("Internal sandbox error: boom", internalFailure.output());
        assertEquals(-1, internalFailure.exitCode());
        assertFalse(internalFailure.truncated());
    }

    @Test
    void uploadFiles_coversPathNormalizationEdgeCases() throws Exception {
        byte[] content = "edge-case".getBytes(StandardCharsets.UTF_8);
        StubSandbox sandbox =
                new StubSandbox(
                        (runtimeContext, command, timeoutSeconds) ->
                                new ExecResult(0, "", "", false),
                        stateWithRoot("/workspace/"));
        SandboxBackedFilesystem fs = new SandboxBackedFilesystem();
        fs.setSandbox(sandbox);

        List<FileUploadResponse> responses =
                fs.uploadFiles(
                        RT,
                        List.of(
                                Map.entry("./relative/file.txt", content),
                                Map.entry("/workspace", content),
                                Map.entry("/other/location.txt", content),
                                Map.entry("../escape.txt", content),
                                new AbstractMap.SimpleEntry<>("/workspace/null.txt", null)));

        assertEquals(5, responses.size());
        assertTrue(responses.get(0).isSuccess());
        assertFalse(responses.get(1).isSuccess());
        assertFalse(responses.get(2).isSuccess());
        assertFalse(responses.get(3).isSuccess());
        assertFalse(responses.get(4).isSuccess());
        assertEquals(1, sandbox.hydrateCalls.get());
        assertTarEntry(sandbox.archiveBytes.get(), "relative/file.txt", content);
    }

    @Test
    void uploadFiles_usesRelativePathWhenWorkspaceLookupIsMissing() throws Exception {
        byte[] content = "orphan".getBytes(StandardCharsets.UTF_8);
        SandboxBackedFilesystem fs = new SandboxBackedFilesystem();

        StubSandbox missingState =
                new StubSandbox(
                        (runtimeContext, command, timeoutSeconds) ->
                                new ExecResult(0, "", "", false),
                        () -> null);
        fs.setSandbox(missingState);
        List<FileUploadResponse> missingStateResponses =
                fs.uploadFiles(RT, List.of(Map.entry("/orphan/null-state.txt", content)));
        assertEquals(1, missingStateResponses.size());
        assertTrue(missingStateResponses.get(0).isSuccess());
        assertTarEntry(missingState.archiveBytes.get(), "orphan/null-state.txt", content);

        StubSandbox throwingState =
                new StubSandbox(
                        (runtimeContext, command, timeoutSeconds) ->
                                new ExecResult(0, "", "", false),
                        () -> {
                            throw new IllegalStateException("state broken");
                        });
        fs.setSandbox(throwingState);
        List<FileUploadResponse> throwingStateResponses =
                fs.uploadFiles(RT, List.of(Map.entry("/orphan/throwing-state.txt", content)));
        assertEquals(1, throwingStateResponses.size());
        assertTrue(throwingStateResponses.get(0).isSuccess());
        assertTarEntry(throwingState.archiveBytes.get(), "orphan/throwing-state.txt", content);
    }

    @Test
    void downloadFiles_coversFailureBranches() {
        SandboxBackedFilesystem fs = new SandboxBackedFilesystem();

        fs.setSandbox(
                new StubSandbox(
                        (runtimeContext, command, timeoutSeconds) ->
                                new ExecResult(1, "stdout", "stderr", false),
                        stateWithRoot("/workspace")));
        List<FileDownloadResponse> responses =
                fs.downloadFiles(RT, List.of("/workspace/missing.txt"));
        assertEquals(1, responses.size());
        assertFalse(responses.get(0).isSuccess());
        assertEquals("stdout\n[stderr] stderr", responses.get(0).error());

        fs.setSandbox(
                new StubSandbox(
                        (runtimeContext, command, timeoutSeconds) -> {
                            throw new SandboxException.ExecException(9, "out", "err");
                        },
                        stateWithRoot("/workspace")));
        responses = fs.downloadFiles(RT, List.of("/workspace/missing.txt"));
        assertEquals(1, responses.size());
        assertFalse(responses.get(0).isSuccess());
        assertEquals("out\nerr", responses.get(0).error());

        fs.setSandbox(
                new StubSandbox(
                        (runtimeContext, command, timeoutSeconds) -> {
                            throw new IllegalStateException("boom");
                        },
                        stateWithRoot("/workspace")));
        responses = fs.downloadFiles(RT, List.of("/workspace/missing.txt"));
        assertEquals(1, responses.size());
        assertFalse(responses.get(0).isSuccess());
        assertEquals("boom", responses.get(0).error());
    }

    @FunctionalInterface
    private interface ExecBehavior {
        ExecResult exec(RuntimeContext runtimeContext, String command, Integer timeoutSeconds)
                throws Exception;
    }

    @FunctionalInterface
    private interface StateBehavior {
        SandboxState getState();
    }

    private static final class StubSandbox implements Sandbox {
        private final AtomicReference<String> command = new AtomicReference<>();
        private final ExecBehavior execBehavior;
        private final StateBehavior stateBehavior;
        private final AtomicInteger execCalls = new AtomicInteger();
        private final AtomicInteger hydrateCalls = new AtomicInteger();
        private final AtomicReference<byte[]> archiveBytes = new AtomicReference<>();

        private StubSandbox(String stdout, byte[] expected) {
            this(
                    (runtimeContext, command, timeoutSeconds) ->
                            new ExecResult(0, stdout, "", false),
                    stateWithRoot("/workspace"));
        }

        private StubSandbox(String stdout, StateBehavior stateBehavior) {
            this(
                    (runtimeContext, command, timeoutSeconds) ->
                            new ExecResult(0, stdout, "", false),
                    stateBehavior);
        }

        private StubSandbox(ExecBehavior execBehavior, StateBehavior stateBehavior) {
            this.execBehavior = execBehavior;
            this.stateBehavior = stateBehavior;
        }

        @Override
        public void start() {}

        @Override
        public void stop() {}

        @Override
        public void close() {}

        @Override
        public boolean isRunning() {
            return true;
        }

        @Override
        public SandboxState getState() {
            return stateBehavior.getState();
        }

        @Override
        public ExecResult exec(
                RuntimeContext runtimeContext, String command, Integer timeoutSeconds) {
            execCalls.incrementAndGet();
            this.command.set(command);
            try {
                return execBehavior.exec(runtimeContext, command, timeoutSeconds);
            } catch (RuntimeException e) {
                throw e;
            } catch (Exception e) {
                throw new RuntimeException(e);
            }
        }

        @Override
        public InputStream persistWorkspace() {
            return InputStream.nullInputStream();
        }

        @Override
        public void hydrateWorkspace(InputStream archive) throws Exception {
            hydrateCalls.incrementAndGet();
            archiveBytes.set(archive.readAllBytes());
        }
    }

    private static StateBehavior stateWithRoot(String workspaceRoot) {
        return () -> {
            SandboxState state = new SandboxState() {};
            WorkspaceSpec spec = new WorkspaceSpec();
            spec.setRoot(workspaceRoot);
            state.setWorkspaceSpec(spec);
            return state;
        };
    }

    private static byte[] bytes(String value) {
        return value.getBytes(StandardCharsets.UTF_8);
    }

    private static void assertTarEntry(byte[] archive, String expectedName, byte[] expectedContent)
            throws Exception {
        assertNotNull(archive);
        try (TarArchiveInputStream tar =
                new TarArchiveInputStream(new ByteArrayInputStream(archive))) {
            TarArchiveEntry entry = tar.getNextTarEntry();
            assertNotNull(entry);
            assertEquals(expectedName, entry.getName());
            assertArrayEquals(expectedContent, tar.readAllBytes());
            assertNull(tar.getNextTarEntry());
        }
    }
}
