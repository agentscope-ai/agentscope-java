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
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.agentscope.core.agent.RuntimeContext;
import io.agentscope.harness.agent.filesystem.model.FileDownloadResponse;
import io.agentscope.harness.agent.filesystem.model.FileUploadResponse;
import io.agentscope.harness.agent.sandbox.ExecResult;
import io.agentscope.harness.agent.sandbox.Sandbox;
import io.agentscope.harness.agent.sandbox.SandboxState;
import io.agentscope.harness.agent.sandbox.WorkspaceSpec;
import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
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
        StubSandbox sandbox = new StubSandbox("", content, "/workspace");
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

    private static final class StubSandbox implements Sandbox {
        private final AtomicReference<String> command = new AtomicReference<>();
        private final String stdout;
        private final byte[] expected;
        private final AtomicInteger execCalls = new AtomicInteger();
        private final AtomicInteger hydrateCalls = new AtomicInteger();
        private final AtomicReference<byte[]> archiveBytes = new AtomicReference<>();
        private final SandboxState state;

        private StubSandbox(String stdout, byte[] expected) {
            this(stdout, expected, "/workspace");
        }

        private StubSandbox(String stdout, byte[] expected, String workspaceRoot) {
            this.stdout = stdout;
            this.expected = expected;
            this.state = new SandboxState() {};
            WorkspaceSpec spec = new WorkspaceSpec();
            spec.setRoot(workspaceRoot);
            this.state.setWorkspaceSpec(spec);
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
            return state;
        }

        @Override
        public ExecResult exec(
                RuntimeContext runtimeContext, String command, Integer timeoutSeconds) {
            execCalls.incrementAndGet();
            this.command.set(command);
            return new ExecResult(0, stdout, "", false);
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
