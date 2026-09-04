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
package io.agentscope.harness.agent.sandbox.impl.docker;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.agentscope.harness.agent.sandbox.WorkspaceSpec;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class DockerSandboxFileTransferTest {

    private DockerSandbox sandbox;

    @BeforeEach
    void setUp() {
        DockerSandboxState state = new DockerSandboxState();
        state.setContainerId("container-1");
        state.setWorkspaceRoot("/workspace");
        state.setWorkspaceSpec(new WorkspaceSpec());
        sandbox = new DockerSandbox(state);
    }

    @Test
    void supportsRelativeAndAbsolutePathsUnderWorkspace() {
        assertTrue(sandbox.supportsFileTransfer("notes/report.bin"));
        assertTrue(sandbox.supportsFileTransfer("/workspace/notes/report.bin"));
        assertFalse(sandbox.supportsFileTransfer("/workspace"));
        assertFalse(sandbox.supportsFileTransfer("/etc/passwd"));
    }

    @Test
    void rejectsTraversalAndUnavailableContainer() {
        assertFalse(sandbox.supportsFileTransfer("/workspace/../etc/passwd"));
        assertFalse(sandbox.supportsFileTransfer("/workspace/.."));
        assertFalse(sandbox.supportsFileTransfer("/workspace/../."));

        DockerSandboxState state = new DockerSandboxState();
        state.setWorkspaceRoot("/workspace");
        state.setWorkspaceSpec(new WorkspaceSpec());
        assertFalse(new DockerSandbox(state).supportsFileTransfer("/workspace/a.txt"));
    }

    @Test
    void uploadAndDownloadRejectPathsOutsideWorkspaceWithoutTouchingDocker() {
        DockerSandbox noTouch = new DockerSandbox(stateWithWorkspace("/workspace", "container-1"));
        assertThrows(
                IllegalArgumentException.class,
                () -> noTouch.uploadFile("/etc/cron.d", new byte[] {1}));
        assertThrows(
                IllegalArgumentException.class, () -> noTouch.downloadFile("/workspace/../secret"));
        assertThrows(
                IllegalArgumentException.class,
                () -> noTouch.uploadFile("/workspace", new byte[] {1}));
        assertThrows(IllegalArgumentException.class, () -> noTouch.downloadFile("/workspace"));
    }

    @Test
    void uploadRejectsNullContentEvenWhenContainerUnavailable() {
        DockerSandbox noContainer = new DockerSandbox(stateWithWorkspace("/workspace", null));
        assertThrows(
                IllegalArgumentException.class,
                () -> noContainer.uploadFile("/workspace/a.bin", null),
                "null content must be rejected before the container check");
    }

    @Test
    void uploadAndDownloadRejectBlankAndNullPaths() {
        assertThrows(IllegalArgumentException.class, () -> sandbox.uploadFile("", new byte[] {1}));
        assertThrows(
                IllegalArgumentException.class, () -> sandbox.uploadFile("   ", new byte[] {1}));
        assertThrows(
                IllegalArgumentException.class, () -> sandbox.uploadFile(null, new byte[] {1}));
        assertThrows(IllegalArgumentException.class, () -> sandbox.downloadFile(""));
        assertThrows(IllegalArgumentException.class, () -> sandbox.downloadFile(null));
    }

    @Test
    void supportsWorkspaceRootOfSlash() {
        DockerSandbox rootSandbox = new DockerSandbox(stateWithWorkspace("/", "container-root"));
        assertTrue(rootSandbox.supportsFileTransfer("/tmp/a.bin"));
        assertTrue(rootSandbox.supportsFileTransfer("/workspace/x.txt"));
        assertTrue(rootSandbox.supportsFileTransfer("relative.txt"));
        assertFalse(rootSandbox.supportsFileTransfer("/"));
        // The whole filesystem is in scope when the workspace root is "/".
    }

    @Test
    void supportsPathsWithSpacesAndQuotes() {
        assertTrue(sandbox.supportsFileTransfer("/workspace/my file.bin"));
        assertTrue(sandbox.supportsFileTransfer("/workspace/with\"quote\".bin"));
        assertTrue(sandbox.supportsFileTransfer("/workspace/a b/c \"d\"/e.txt"));
        assertTrue(sandbox.supportsFileTransfer("relative name with spaces.txt"));
    }

    @Test
    void whitespaceAndDotDotSegmentsInsideWorkspaceAreRejected() {
        assertFalse(sandbox.supportsFileTransfer("/workspace/a/../b"));
        assertFalse(sandbox.supportsFileTransfer("/workspace/a/./b"));
        assertFalse(sandbox.supportsFileTransfer("/workspace//b"));
    }

    @Test
    void supportsFileTransferNeverLeaksStateAcrossInstances() {
        // The no-container sandbox must not fall back to a previously seen container.
        DockerSandbox noContainer = new DockerSandbox(stateWithWorkspace("/workspace", null));
        assertFalse(noContainer.supportsFileTransfer("/workspace/a.txt"));
        assertThrows(
                IllegalArgumentException.class,
                () -> noContainer.uploadFile("/workspace/a.txt", new byte[] {1}));
    }

    @Test
    void uploadWritesContentThroughDockerCpAndCleansUpTemp() throws Exception {
        RecordingDockerSandbox recording =
                new RecordingDockerSandbox(stateWithWorkspace("/workspace", "container-1"));
        byte[] content = new byte[] {1, 2, 3, 4, 5};

        recording.uploadFile("notes/report.bin", content);

        // The file bytes reached docker cp intact (never through exec argv).
        assertArrayEquals(content, recording.uploadedContent);
        // Parent directory is created, then the temp file is copied to the resolved path.
        assertTrue(
                recording.commands.contains(
                        List.of(
                                "docker",
                                "exec",
                                "container-1",
                                "mkdir",
                                "-p",
                                "/workspace/notes")),
                recording.commands.toString());
        List<String> cp = recording.lastCpCommand();
        assertNotNull(cp);
        assertEquals("container-1:/workspace/notes/report.bin", cp.get(3));
        // Host temp file is removed after the round trip.
        assertNotNull(recording.lastCpSource);
        assertFalse(Files.exists(Path.of(recording.lastCpSource)));
    }

    @Test
    void uploadAtWorkspaceRootUsesSlashParent() throws Exception {
        RecordingDockerSandbox recording =
                new RecordingDockerSandbox(stateWithWorkspace("/", "container-root"));

        recording.uploadFile("/file.bin", new byte[] {9});

        assertTrue(
                recording.commands.contains(
                        List.of("docker", "exec", "container-root", "mkdir", "-p", "/")),
                recording.commands.toString());
        assertEquals("container-root:/file.bin", recording.lastCpCommand().get(3));
    }

    @Test
    void downloadReadsContentThroughDockerCpAndCleansUpTemp() throws Exception {
        RecordingDockerSandbox recording =
                new RecordingDockerSandbox(stateWithWorkspace("/workspace", "container-1"));
        recording.downloadPayload = new byte[] {7, 8, 9};

        byte[] out = recording.downloadFile("/workspace/out.bin");

        assertArrayEquals(recording.downloadPayload, out);
        List<String> cp = recording.lastCpCommand();
        assertEquals("container-1:/workspace/out.bin", cp.get(2));
        // Destination temp file is removed after the bytes are read back.
        assertNotNull(recording.lastCpDest);
        assertFalse(Files.exists(Path.of(recording.lastCpDest)));
    }

    @Test
    void uploadCleansUpTempFileWhenDockerCpFails() {
        RecordingDockerSandbox recording =
                new RecordingDockerSandbox(stateWithWorkspace("/workspace", "container-1"));
        recording.failCp = true;

        assertThrows(
                RuntimeException.class,
                () -> recording.uploadFile("/workspace/a.bin", new byte[] {1}));

        assertNotNull(recording.lastCpSource);
        assertFalse(Files.exists(Path.of(recording.lastCpSource)));
    }

    private static DockerSandboxState stateWithWorkspace(String root, String containerId) {
        DockerSandboxState state = new DockerSandboxState();
        state.setWorkspaceRoot(root);
        state.setContainerId(containerId);
        state.setWorkspaceSpec(new WorkspaceSpec());
        return state;
    }

    /**
     * Intercepts the docker CLI so the upload/download temp-file plumbing runs end to end without a
     * live Docker daemon: {@code docker cp} to a container captures the source bytes, and {@code
     * docker cp} from a container writes {@link #downloadPayload} into the host destination.
     */
    private static final class RecordingDockerSandbox extends DockerSandbox {

        final List<List<String>> commands = new ArrayList<>();
        byte[] uploadedContent;
        byte[] downloadPayload;
        String lastCpSource;
        String lastCpDest;
        boolean failCp;
        private final String containerPathPrefix;

        RecordingDockerSandbox(DockerSandboxState state) {
            super(state);
            containerPathPrefix = state.getContainerId() + ":";
        }

        List<String> lastCpCommand() {
            for (int i = commands.size() - 1; i >= 0; i--) {
                List<String> c = commands.get(i);
                if (c.size() >= 2 && "cp".equals(c.get(1))) {
                    return c;
                }
            }
            return null;
        }

        @Override
        protected void runDockerCliBlocking(int timeoutSeconds, String... command)
                throws Exception {
            commands.add(List.of(command));
            if (command.length >= 4 && "cp".equals(command[1])) {
                String src = command[2];
                String dst = command[3];
                boolean upload = dst.startsWith(containerPathPrefix);
                boolean download = src.startsWith(containerPathPrefix);
                if (upload == download) {
                    throw new AssertionError(
                            "Expected exactly one docker cp endpoint inside the container");
                }
                // Record paths before any simulated failure so cleanup can be asserted.
                if (upload) {
                    lastCpSource = src;
                } else {
                    lastCpDest = dst;
                }
                if (failCp) {
                    throw new RuntimeException("simulated docker cp failure");
                }
                if (upload) {
                    // upload: host temp -> container:path
                    uploadedContent = Files.readAllBytes(Path.of(src));
                } else {
                    // download: container:path -> host temp
                    Files.write(
                            Path.of(dst), downloadPayload == null ? new byte[0] : downloadPayload);
                }
            }
        }
    }
}
