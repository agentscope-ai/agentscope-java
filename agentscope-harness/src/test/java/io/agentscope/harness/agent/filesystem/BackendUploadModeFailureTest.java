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
package io.agentscope.harness.agent.filesystem;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.agentscope.core.agent.RuntimeContext;
import io.agentscope.harness.agent.filesystem.local.LocalFilesystem;
import io.agentscope.harness.agent.filesystem.model.FileDownloadResponse;
import io.agentscope.harness.agent.filesystem.model.FileUploadResponse;
import io.agentscope.harness.agent.filesystem.model.UploadMode;
import io.agentscope.harness.agent.filesystem.remote.RemoteFilesystem;
import io.agentscope.harness.agent.filesystem.remote.store.InMemoryStore;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.AbstractMap.SimpleImmutableEntry;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/** Failed create-only uploads protect existing data and do not abort the remaining batch. */
class BackendUploadModeFailureTest {

    private static final RuntimeContext RT = RuntimeContext.empty();
    private static final byte[] CONTENT = new byte[] {0, (byte) 0xff, 1, (byte) 0xfe};

    @TempDir Path workspace;

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void nullContentPreservesExistingFileAndContinuesBatch(boolean remote) {
        AbstractFilesystem fs =
                remote ? new RemoteFilesystem(new InMemoryStore()) : local(workspace);
        byte[] original = "original".getBytes(StandardCharsets.UTF_8);
        assertTrue(fs.write(RT, "/existing.txt", "original").isSuccess());

        List<FileUploadResponse> responses =
                fs.uploadFiles(
                        RT,
                        List.of(
                                new SimpleImmutableEntry<String, byte[]>("/existing.txt", null),
                                Map.entry("/valid.bin", CONTENT)),
                        UploadMode.CREATE_NEW);

        assertEquals(2, responses.size());
        assertEquals("/existing.txt", responses.get(0).path());
        assertFalse(responses.get(0).isSuccess());
        assertTrue(responses.get(0).error().contains("content"));
        assertEquals("/valid.bin", responses.get(1).path());
        assertTrue(responses.get(1).isSuccess(), responses.get(1).error());
        assertArrayEquals(original, download(fs, "/existing.txt"));
        assertArrayEquals(CONTENT, download(fs, "/valid.bin"));
    }

    @ParameterizedTest
    @ValueSource(strings = {"/../outside.bin", "/invalid\0name.bin"})
    void localInvalidPathPreservesOutsideFileAndContinuesBatch(String invalidPath)
            throws Exception {
        Path root = Files.createDirectory(workspace.resolve("root"));
        Path outside = workspace.resolve("outside.bin");
        byte[] original = new byte[] {2, 3, 4};
        Files.write(outside, original);
        LocalFilesystem fs = local(root);

        List<FileUploadResponse> responses =
                fs.uploadFiles(
                        RT,
                        List.of(Map.entry(invalidPath, CONTENT), Map.entry("/valid.bin", CONTENT)),
                        UploadMode.CREATE_NEW);

        assertEquals(2, responses.size());
        assertEquals(invalidPath, responses.get(0).path());
        assertFalse(responses.get(0).isSuccess());
        assertEquals("permission_denied", responses.get(0).error());
        assertEquals("/valid.bin", responses.get(1).path());
        assertTrue(responses.get(1).isSuccess(), responses.get(1).error());
        assertArrayEquals(original, Files.readAllBytes(outside));
        assertArrayEquals(CONTENT, Files.readAllBytes(root.resolve("valid.bin")));
    }

    @Test
    void localFileAsParentPreservesParentAndContinuesBatch() throws Exception {
        Path blockedParent = workspace.resolve("blocked");
        byte[] original = "parent is a file".getBytes(StandardCharsets.UTF_8);
        Files.write(blockedParent, original);
        LocalFilesystem fs = local(workspace);

        List<FileUploadResponse> responses =
                fs.uploadFiles(
                        RT,
                        List.of(
                                Map.entry("/blocked/file.bin", CONTENT),
                                Map.entry("/valid.bin", CONTENT)),
                        UploadMode.CREATE_NEW);

        assertEquals(2, responses.size());
        assertEquals("/blocked/file.bin", responses.get(0).path());
        assertFalse(responses.get(0).isSuccess());
        assertEquals("/valid.bin", responses.get(1).path());
        assertTrue(responses.get(1).isSuccess(), responses.get(1).error());
        assertTrue(Files.isRegularFile(blockedParent));
        assertArrayEquals(original, Files.readAllBytes(blockedParent));
        assertArrayEquals(CONTENT, Files.readAllBytes(workspace.resolve("valid.bin")));
    }

    @Test
    void remoteStoreFailureIsReportedAndContinuesBatch() {
        InMemoryStore store =
                new InMemoryStore() {
                    @Override
                    public boolean putIfVersion(
                            List<String> namespace,
                            String key,
                            Map<String, Object> value,
                            long expectedVersion) {
                        if ("/unavailable.bin".equals(key)) {
                            throw new IllegalStateException("store unavailable");
                        }
                        return super.putIfVersion(namespace, key, value, expectedVersion);
                    }
                };
        RemoteFilesystem fs = new RemoteFilesystem(store);

        List<FileUploadResponse> responses =
                fs.uploadFiles(
                        RT,
                        List.of(
                                Map.entry("/unavailable.bin", CONTENT),
                                Map.entry("/valid.bin", CONTENT)),
                        UploadMode.CREATE_NEW);

        assertEquals(2, responses.size());
        assertEquals("/unavailable.bin", responses.get(0).path());
        assertFalse(responses.get(0).isSuccess());
        assertTrue(responses.get(0).error().contains("store unavailable"));
        assertEquals("/valid.bin", responses.get(1).path());
        assertTrue(responses.get(1).isSuccess(), responses.get(1).error());
        assertNull(store.get(List.of("filesystem"), "/unavailable.bin"));
        assertArrayEquals(CONTENT, download(fs, "/valid.bin"));
    }

    private static LocalFilesystem local(Path root) {
        return new LocalFilesystem(root, true, 10);
    }

    private static byte[] download(AbstractFilesystem fs, String path) {
        List<FileDownloadResponse> responses = fs.downloadFiles(RT, List.of(path));
        assertEquals(1, responses.size());
        FileDownloadResponse response = responses.get(0);
        assertTrue(response.isSuccess(), response.error());
        return response.content();
    }
}
