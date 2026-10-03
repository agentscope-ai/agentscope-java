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
package io.agentscope.extensions.sandbox.e2b;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import io.agentscope.core.agent.RuntimeContext;
import io.agentscope.harness.agent.filesystem.model.FileDownloadResponse;
import io.agentscope.harness.agent.filesystem.model.FileUploadResponse;
import io.agentscope.harness.agent.filesystem.sandbox.SandboxBackedFilesystem;
import io.agentscope.harness.agent.sandbox.ExecResult;
import io.agentscope.harness.agent.sandbox.SandboxState;
import io.agentscope.harness.agent.sandbox.WorkspaceSpec;
import java.io.ByteArrayInputStream;
import java.lang.reflect.Field;
import java.util.AbstractMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

class E2bSandboxFileTransferTest {

    private static final RuntimeContext RT = RuntimeContext.empty();

    private static E2bSandbox sandbox() {
        E2bSandboxState state = new E2bSandboxState();
        WorkspaceSpec workspaceSpec = new WorkspaceSpec();
        workspaceSpec.setRoot("/home/user/workspace");
        state.setWorkspaceSpec(workspaceSpec);
        return new E2bSandbox(state, new E2bSandboxClientOptions());
    }

    @Test
    void supportsFileTransferAcceptsAbsolutePaths() {
        E2bSandbox sandbox = sandbox();
        assertTrue(sandbox.supportsFileTransfer("/tmp/a.txt"));
        assertTrue(sandbox.supportsFileTransfer("/home/user/workspace/MEMORY.md"));
    }

    @Test
    void supportsFileTransferRejectsNonAbsolutePaths() {
        E2bSandbox sandbox = sandbox();
        assertFalse(sandbox.supportsFileTransfer("MEMORY.md"));
        assertFalse(sandbox.supportsFileTransfer(""));
        assertFalse(sandbox.supportsFileTransfer(null));
    }

    @Test
    void nativeUploadIsPreferredBySandboxBackedFilesystem() throws Exception {
        E2bSandbox sandbox = mock(E2bSandbox.class);
        when(sandbox.supportsFileTransfer("/tmp/a.txt")).thenReturn(true);
        SandboxBackedFilesystem fs = new SandboxBackedFilesystem();
        fs.setSandbox(sandbox);

        List<Map.Entry<String, byte[]>> files =
                List.of(new AbstractMap.SimpleEntry<>("/tmp/a.txt", "data".getBytes()));

        List<FileUploadResponse> results = fs.uploadFiles(RT, files);

        verify(sandbox).uploadFile("/tmp/a.txt", "data".getBytes());
        assertEquals(1, results.size());
        assertTrue(results.get(0).isSuccess());
    }

    @Test
    void nativeUploadFailureIsReported() throws Exception {
        E2bSandbox sandbox = mock(E2bSandbox.class);
        when(sandbox.supportsFileTransfer("/tmp/a.txt")).thenReturn(true);
        doThrow(new RuntimeException("upload failed"))
                .when(sandbox)
                .uploadFile("/tmp/a.txt", "data".getBytes());
        SandboxBackedFilesystem fs = new SandboxBackedFilesystem();
        fs.setSandbox(sandbox);

        List<Map.Entry<String, byte[]>> files =
                List.of(new AbstractMap.SimpleEntry<>("/tmp/a.txt", "data".getBytes()));

        List<FileUploadResponse> results = fs.uploadFiles(RT, files);

        assertEquals(1, results.size());
        assertFalse(results.get(0).isSuccess());
        assertEquals("upload failed", results.get(0).error());
    }

    @Test
    void nativeDownloadIsPreferredBySandboxBackedFilesystem() throws Exception {
        E2bSandbox sandbox = mock(E2bSandbox.class);
        when(sandbox.supportsFileTransfer("/tmp/a.txt")).thenReturn(true);
        when(sandbox.downloadFile("/tmp/a.txt")).thenReturn("hello".getBytes());
        SandboxBackedFilesystem fs = new SandboxBackedFilesystem();
        fs.setSandbox(sandbox);

        List<FileDownloadResponse> results = fs.downloadFiles(RT, List.of("/tmp/a.txt"));

        verify(sandbox).downloadFile("/tmp/a.txt");
        assertEquals(1, results.size());
        assertArrayEquals("hello".getBytes(), results.get(0).content());
    }

    @Test
    void relativePathFallsBackToExecStrategy() throws Exception {
        SandboxState state = mock(SandboxState.class);
        WorkspaceSpec workspaceSpec = new WorkspaceSpec();
        workspaceSpec.setRoot("/home/user/workspace");
        when(state.getWorkspaceSpec()).thenReturn(workspaceSpec);
        E2bSandbox sandbox = mock(E2bSandbox.class);
        when(sandbox.supportsFileTransfer("MEMORY.md")).thenReturn(false);
        when(sandbox.getState()).thenReturn(state);
        SandboxBackedFilesystem fs = new SandboxBackedFilesystem();
        fs.setSandbox(sandbox);

        List<Map.Entry<String, byte[]>> files =
                List.of(new AbstractMap.SimpleEntry<>("MEMORY.md", "data".getBytes()));

        List<FileUploadResponse> results = fs.uploadFiles(RT, files);

        verify(sandbox, never()).uploadFile(any(), any());
        assertEquals(1, results.size());
        assertTrue(results.get(0).isSuccess());
    }

    @Test
    void persistWorkspaceDownloadsTarAndCleansUpTempFile() throws Exception {
        E2bEnvdProcessClient envd = mock(E2bEnvdProcessClient.class);
        E2bSandbox sandbox = sandboxWithMockEnvd(envd);
        byte[] tar = "fake-tar-bytes".getBytes();
        when(envd.runShell(any(), any(), any(), anyInt()))
                .thenReturn(new ExecResult(0, "", "", false));
        when(envd.downloadFile(any(), any())).thenReturn(tar);

        byte[] result = sandbox.persistWorkspace().readAllBytes();

        assertArrayEquals(tar, result);
        ArgumentCaptor<String> downloadPath = ArgumentCaptor.forClass(String.class);
        verify(envd).downloadFile(any(), downloadPath.capture());
        String tarPath = downloadPath.getValue();
        assertTrue(tarPath.startsWith("/tmp/agentscope-ws-"));
        ArgumentCaptor<String> commands = ArgumentCaptor.forClass(String.class);
        verify(envd, times(2)).runShell(any(), any(), commands.capture(), anyInt());
        assertTrue(commands.getAllValues().get(0).contains("-cf " + tarPath));
        assertEquals("rm -f " + tarPath, commands.getAllValues().get(1));
    }

    @Test
    void persistWorkspaceCleansUpTempFileOnDownloadFailure() throws Exception {
        E2bEnvdProcessClient envd = mock(E2bEnvdProcessClient.class);
        E2bSandbox sandbox = sandboxWithMockEnvd(envd);
        when(envd.runShell(any(), any(), any(), anyInt()))
                .thenReturn(new ExecResult(0, "", "", false));
        when(envd.downloadFile(any(), any())).thenThrow(new RuntimeException("download failed"));

        assertThrows(RuntimeException.class, sandbox::persistWorkspace);

        ArgumentCaptor<String> commands = ArgumentCaptor.forClass(String.class);
        verify(envd, times(2)).runShell(any(), any(), commands.capture(), anyInt());
        assertTrue(commands.getAllValues().get(1).startsWith("rm -f /tmp/agentscope-ws-"));
    }

    @Test
    void hydrateWorkspaceUploadsExtractsAndCleansUpTempFile() throws Exception {
        E2bEnvdProcessClient envd = mock(E2bEnvdProcessClient.class);
        E2bSandbox sandbox = sandboxWithMockEnvd(envd);
        byte[] archive = "fake-tar-bytes".getBytes();
        when(envd.runShell(any(), any(), any(), anyInt()))
                .thenReturn(new ExecResult(0, "", "", false));

        sandbox.hydrateWorkspace(new ByteArrayInputStream(archive));

        ArgumentCaptor<String> uploadPath = ArgumentCaptor.forClass(String.class);
        ArgumentCaptor<byte[]> uploadContent = ArgumentCaptor.forClass(byte[].class);
        verify(envd).uploadFile(any(), uploadPath.capture(), uploadContent.capture());
        String tarPath = uploadPath.getValue();
        assertTrue(tarPath.startsWith("/tmp/agentscope-ws-"));
        assertArrayEquals(archive, uploadContent.getValue());
        ArgumentCaptor<String> commands = ArgumentCaptor.forClass(String.class);
        verify(envd, times(2)).runShell(any(), any(), commands.capture(), anyInt());
        assertTrue(commands.getAllValues().get(0).contains("xf " + tarPath));
        assertEquals("rm -f " + tarPath, commands.getAllValues().get(1));
    }

    @Test
    void hydrateWorkspaceCleansUpTempFileOnExtractFailure() throws Exception {
        E2bEnvdProcessClient envd = mock(E2bEnvdProcessClient.class);
        E2bSandbox sandbox = sandboxWithMockEnvd(envd);
        when(envd.runShell(any(), any(), any(), anyInt()))
                .thenAnswer(
                        invocation -> {
                            String command = invocation.getArgument(2);
                            if (command.startsWith("rm -f ")) {
                                return new ExecResult(0, "", "", false);
                            }
                            throw new RuntimeException("tar failed");
                        });

        assertThrows(
                RuntimeException.class,
                () -> sandbox.hydrateWorkspace(new ByteArrayInputStream("data".getBytes())));

        verify(envd).uploadFile(any(), any(), any());
        ArgumentCaptor<String> commands = ArgumentCaptor.forClass(String.class);
        verify(envd, times(2)).runShell(any(), any(), commands.capture(), anyInt());
        assertTrue(commands.getAllValues().get(1).startsWith("rm -f /tmp/agentscope-ws-"));
    }

    private static E2bSandbox sandboxWithMockEnvd(E2bEnvdProcessClient envd) throws Exception {
        E2bSandbox sandbox = sandbox();
        Field field = E2bSandbox.class.getDeclaredField("envd");
        field.setAccessible(true);
        field.set(sandbox, envd);
        return sandbox;
    }
}
