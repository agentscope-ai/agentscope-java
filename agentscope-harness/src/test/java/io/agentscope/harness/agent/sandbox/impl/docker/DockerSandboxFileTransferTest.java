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

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.agentscope.harness.agent.sandbox.WorkspaceSpec;
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

    private static DockerSandboxState stateWithWorkspace(String root, String containerId) {
        DockerSandboxState state = new DockerSandboxState();
        state.setWorkspaceRoot(root);
        state.setContainerId(containerId);
        state.setWorkspaceSpec(new WorkspaceSpec());
        return state;
    }
}
