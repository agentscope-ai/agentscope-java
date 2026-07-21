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
package io.agentscope.extensions.sandbox.kubernetes.client;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.mock;

import io.agentscope.extensions.sandbox.kubernetes.client.exceptions.SandboxRequestException;
import io.agentscope.extensions.sandbox.kubernetes.client.internal.SandboxConnector;
import java.net.http.HttpResponse;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class FilesystemTest {

    @Mock SandboxConnector connector;

    Filesystem filesystem;

    @BeforeEach
    void setUp() {
        filesystem = new Filesystem(connector);
    }

    @Test
    void safeUploadPath_normalizesRelative() {
        assertEquals("foo/bar.txt", Filesystem.safeUploadPath("foo/bar.txt"));
        assertEquals("foo/bar.txt", Filesystem.safeUploadPath("/foo/bar.txt"));
        assertEquals("foo/bar.txt", Filesystem.safeUploadPath("./foo/bar.txt"));
        assertEquals("foo/baz", Filesystem.safeUploadPath("foo/bar/../baz"));
    }

    @Test
    void safeUploadPath_rejectsEscape() {
        assertThrows(
                IllegalArgumentException.class, () -> Filesystem.safeUploadPath("../etc/passwd"));
        assertThrows(IllegalArgumentException.class, () -> Filesystem.safeUploadPath(""));
        assertThrows(IllegalArgumentException.class, () -> Filesystem.safeUploadPath("."));
        assertThrows(
                IllegalArgumentException.class,
                () -> Filesystem.safeUploadPath("foo\u0000../etc/passwd"));
    }

    @Test
    void exists_true() throws Exception {
        HttpResponse<String> resp = mock(HttpResponse.class);
        doReturn(200).when(resp).statusCode();
        doReturn("{\"exists\":true}").when(resp).body();

        doReturn(resp).when(connector).sendRequest(eq("GET"), anyString(), any(), any(), anyInt());

        assertTrue(filesystem.exists("workspace/file.txt"));
    }

    @Test
    void exists_false() throws Exception {
        HttpResponse<String> resp = mock(HttpResponse.class);
        doReturn(200).when(resp).statusCode();
        doReturn("{\"exists\":false}").when(resp).body();

        doReturn(resp).when(connector).sendRequest(eq("GET"), anyString(), any(), any(), anyInt());

        assertFalse(filesystem.exists("workspace/missing.txt"));
    }

    @Test
    void read_success() throws Exception {
        HttpResponse<byte[]> resp = mock(HttpResponse.class);
        doReturn(200).when(resp).statusCode();
        doReturn("file content".getBytes()).when(resp).body();

        doReturn(resp).when(connector).sendRequestForBytes(eq("GET"), anyString(), any(), any());

        byte[] data = filesystem.read("workspace/file.txt");
        assertEquals("file content", new String(data));
    }

    @Test
    void read_notFound() throws Exception {
        HttpResponse<byte[]> resp = mock(HttpResponse.class);
        doReturn(404).when(resp).statusCode();

        doReturn(resp).when(connector).sendRequestForBytes(eq("GET"), anyString(), any(), any());

        assertThrows(SandboxRequestException.class, () -> filesystem.read("workspace/missing.txt"));
    }
}
