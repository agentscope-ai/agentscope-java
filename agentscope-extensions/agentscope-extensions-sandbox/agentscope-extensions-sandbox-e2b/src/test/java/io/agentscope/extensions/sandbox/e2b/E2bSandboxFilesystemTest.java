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
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import io.agentscope.core.agent.RuntimeContext;
import io.agentscope.harness.agent.filesystem.model.FileDownloadResponse;
import io.agentscope.harness.agent.filesystem.model.FileUploadResponse;
import io.agentscope.harness.agent.sandbox.ExecResult;
import io.agentscope.harness.agent.sandbox.Sandbox;
import java.util.AbstractMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class E2bSandboxFilesystemTest {

    private static final RuntimeContext RT = RuntimeContext.empty();

    @Test
    void uploadFiles_delegatesToE2bSandbox() throws Exception {
        E2bSandbox sandbox = mock(E2bSandbox.class);
        E2bSandboxFilesystem fs = new E2bSandboxFilesystem();
        fs.setSandbox(sandbox);

        List<Map.Entry<String, byte[]>> files =
                List.of(
                        new AbstractMap.SimpleEntry<>("/tmp/a.txt", "hello".getBytes()),
                        new AbstractMap.SimpleEntry<>("/tmp/b.txt", "world".getBytes()));

        List<FileUploadResponse> results = fs.uploadFiles(RT, files);

        verify(sandbox).uploadFile("/tmp/a.txt", "hello".getBytes());
        verify(sandbox).uploadFile("/tmp/b.txt", "world".getBytes());
        assertEquals(2, results.size());
        assertTrue(results.get(0).isSuccess());
        assertTrue(results.get(1).isSuccess());
    }

    @Test
    void uploadFiles_returnsFailureOnException() throws Exception {
        E2bSandbox sandbox = mock(E2bSandbox.class);
        doThrow(new RuntimeException("upload failed"))
                .when(sandbox)
                .uploadFile("/tmp/a.txt", "data".getBytes());
        E2bSandboxFilesystem fs = new E2bSandboxFilesystem();
        fs.setSandbox(sandbox);

        List<Map.Entry<String, byte[]>> files =
                List.of(new AbstractMap.SimpleEntry<>("/tmp/a.txt", "data".getBytes()));

        List<FileUploadResponse> results = fs.uploadFiles(RT, files);

        assertEquals(1, results.size());
        assertFalse(results.get(0).isSuccess());
        assertEquals("upload failed", results.get(0).error());
    }

    @Test
    void downloadFiles_delegatesToE2bSandbox() throws Exception {
        E2bSandbox sandbox = mock(E2bSandbox.class);
        when(sandbox.downloadFile("/tmp/a.txt")).thenReturn("hello".getBytes());
        when(sandbox.downloadFile("/tmp/b.txt")).thenReturn("world".getBytes());
        E2bSandboxFilesystem fs = new E2bSandboxFilesystem();
        fs.setSandbox(sandbox);

        List<FileDownloadResponse> results =
                fs.downloadFiles(RT, List.of("/tmp/a.txt", "/tmp/b.txt"));

        verify(sandbox).downloadFile("/tmp/a.txt");
        verify(sandbox).downloadFile("/tmp/b.txt");
        assertEquals(2, results.size());
        assertArrayEquals("hello".getBytes(), results.get(0).content());
        assertArrayEquals("world".getBytes(), results.get(1).content());
    }

    @Test
    void downloadFiles_returnsFailureOnException() throws Exception {
        E2bSandbox sandbox = mock(E2bSandbox.class);
        when(sandbox.downloadFile("/tmp/a.txt")).thenThrow(new RuntimeException("not found"));
        E2bSandboxFilesystem fs = new E2bSandboxFilesystem();
        fs.setSandbox(sandbox);

        List<FileDownloadResponse> results = fs.downloadFiles(RT, List.of("/tmp/a.txt"));

        assertEquals(1, results.size());
        assertFalse(results.get(0).isSuccess());
        assertEquals("not found", results.get(0).error());
    }

    @Test
    void uploadFiles_fallsBackToSuperWhenNotE2bSandbox() throws Exception {
        Sandbox sandbox = mock(Sandbox.class);
        when(sandbox.exec(any(), any(), any())).thenReturn(new ExecResult(0, "", "", false));
        E2bSandboxFilesystem fs = new E2bSandboxFilesystem();
        fs.setSandbox(sandbox);

        List<Map.Entry<String, byte[]>> files =
                List.of(new AbstractMap.SimpleEntry<>("/tmp/a.txt", "data".getBytes()));

        List<FileUploadResponse> results = fs.uploadFiles(RT, files);

        assertEquals(1, results.size());
        assertTrue(results.get(0).isSuccess());
    }

    @Test
    void downloadFiles_fallsBackToSuperWhenNotE2bSandbox() throws Exception {
        Sandbox sandbox = mock(Sandbox.class);
        when(sandbox.exec(any(), any(), any()))
                .thenReturn(new ExecResult(0, "ZGF0YQ==", "", false));
        E2bSandboxFilesystem fs = new E2bSandboxFilesystem();
        fs.setSandbox(sandbox);

        List<FileDownloadResponse> results = fs.downloadFiles(RT, List.of("/tmp/a.txt"));

        assertEquals(1, results.size());
        assertTrue(results.get(0).isSuccess());
        assertArrayEquals("data".getBytes(), results.get(0).content());
    }

    @Test
    void uploadFiles_resolvesRelativePath() throws Exception {
        E2bSandbox sandbox = mock(E2bSandbox.class);
        when(sandbox.getWorkspaceRoot()).thenReturn("/home/user/workspace");
        E2bSandboxFilesystem fs = new E2bSandboxFilesystem();
        fs.setSandbox(sandbox);
        List<Map.Entry<String, byte[]>> files =
                List.of(new AbstractMap.SimpleEntry<>("MEMORY.md", "data".getBytes()));
        fs.uploadFiles(RT, files);
        verify(sandbox).uploadFile("/home/user/workspace/MEMORY.md", "data".getBytes());
    }

    @Test
    void downloadFiles_resolvesRelativePath() throws Exception {
        E2bSandbox sandbox = mock(E2bSandbox.class);
        when(sandbox.getWorkspaceRoot()).thenReturn("/home/user/workspace");
        when(sandbox.downloadFile("/home/user/workspace/MEMORY.md")).thenReturn("data".getBytes());
        E2bSandboxFilesystem fs = new E2bSandboxFilesystem();
        fs.setSandbox(sandbox);
        List<FileDownloadResponse> results = fs.downloadFiles(RT, List.of("MEMORY.md"));
        verify(sandbox).downloadFile("/home/user/workspace/MEMORY.md");
        assertArrayEquals("data".getBytes(), results.get(0).content());
    }
}
