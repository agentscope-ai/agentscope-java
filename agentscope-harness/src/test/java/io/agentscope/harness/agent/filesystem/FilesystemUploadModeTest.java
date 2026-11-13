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
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.agentscope.core.agent.RuntimeContext;
import io.agentscope.harness.agent.filesystem.local.LocalFilesystem;
import io.agentscope.harness.agent.filesystem.local.LocalFilesystemWithShell;
import io.agentscope.harness.agent.filesystem.model.FileDownloadResponse;
import io.agentscope.harness.agent.filesystem.model.FileUploadResponse;
import io.agentscope.harness.agent.filesystem.model.UploadMode;
import io.agentscope.harness.agent.filesystem.model.WriteResult;
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

/** Upload modes preserve existing backend behavior through filesystem wrappers. */
class FilesystemUploadModeTest {

    private static final RuntimeContext RT = RuntimeContext.empty();

    @TempDir Path workspace;

    @ParameterizedTest
    @ValueSource(strings = {"local", "remote", "overlay"})
    void overwriteModeReplacesFilesCreatedByLegacyUpload(String backend) {
        AbstractFilesystem fs =
                switch (backend) {
                    case "remote" -> new RemoteFilesystem(new InMemoryStore());
                    case "overlay" ->
                            new OverlayFilesystem(
                                    local(workspace), new RemoteFilesystem(new InMemoryStore()));
                    default -> local(workspace);
                };
        byte[] original = "original".getBytes(StandardCharsets.UTF_8);
        byte[] replacement = "replacement\r\n".getBytes(StandardCharsets.UTF_8);
        FileUploadResponse initial =
                fs.uploadFiles(RT, List.of(Map.entry("/file.txt", original))).get(0);
        assertTrue(initial.isSuccess(), initial.error());

        FileUploadResponse result =
                fs.uploadFiles(
                                RT,
                                List.of(Map.entry("/file.txt", replacement)),
                                UploadMode.OVERWRITE)
                        .get(0);

        assertTrue(result.isSuccess(), result.error());
        assertArrayEquals(replacement, download(fs, RT, "/file.txt"));
    }

    @Test
    void defaultCreateNewRejectsNullContentAndContinuesBatch() throws Exception {
        OverlayFilesystem fs =
                new OverlayFilesystem(local(workspace), new RemoteFilesystem(new InMemoryStore()));
        byte[] content = "valid content\r\n".getBytes(StandardCharsets.UTF_8);

        List<FileUploadResponse> responses =
                fs.uploadFiles(
                        RT,
                        List.of(
                                new SimpleImmutableEntry<String, byte[]>("/missing.txt", null),
                                Map.entry("/valid.txt", content)),
                        UploadMode.CREATE_NEW);

        assertEquals(2, responses.size());
        assertEquals("/missing.txt", responses.get(0).path());
        assertFalse(responses.get(0).isSuccess());
        assertNotNull(responses.get(0).error());
        assertEquals("/valid.txt", responses.get(1).path());
        assertTrue(responses.get(1).isSuccess(), responses.get(1).error());
        assertFalse(Files.exists(workspace.resolve("missing.txt")));
        assertArrayEquals(content, Files.readAllBytes(workspace.resolve("valid.txt")));
    }

    @Test
    void defaultCreateNewCreatesUtf8InUpperWithoutChangingSharedLower() throws Exception {
        Path upperRoot = Files.createDirectory(workspace.resolve("upper"));
        Path lowerRoot = Files.createDirectory(workspace.resolve("lower"));
        byte[] shared = "shared original".getBytes(StandardCharsets.UTF_8);
        byte[] customized = " \t用户内容\r\n".getBytes(StandardCharsets.UTF_8);
        Files.write(lowerRoot.resolve("file.txt"), shared);
        OverlayFilesystem overlay = new OverlayFilesystem(local(upperRoot), local(lowerRoot));

        FileUploadResponse result =
                overlay.uploadFiles(
                                RT,
                                List.of(Map.entry("/file.txt", customized)),
                                UploadMode.CREATE_NEW)
                        .get(0);

        assertTrue(result.isSuccess(), result.error());
        assertArrayEquals(customized, Files.readAllBytes(upperRoot.resolve("file.txt")));
        assertArrayEquals(shared, Files.readAllBytes(lowerRoot.resolve("file.txt")));
        assertArrayEquals(customized, download(overlay, RT, "/file.txt"));
    }

    @Test
    void defaultCreateNewRejectsExistingUpperFile() throws Exception {
        Path upperRoot = Files.createDirectory(workspace.resolve("upper"));
        Path lowerRoot = Files.createDirectory(workspace.resolve("lower"));
        byte[] original = "existing customization".getBytes(StandardCharsets.UTF_8);
        Files.write(upperRoot.resolve("file.txt"), original);
        OverlayFilesystem overlay = new OverlayFilesystem(local(upperRoot), local(lowerRoot));

        FileUploadResponse result =
                overlay.uploadFiles(
                                RT,
                                List.of(
                                        Map.entry(
                                                "/file.txt",
                                                "replacement".getBytes(StandardCharsets.UTF_8))),
                                UploadMode.CREATE_NEW)
                        .get(0);

        assertFalse(result.isSuccess());
        assertArrayEquals(original, Files.readAllBytes(upperRoot.resolve("file.txt")));
        assertFalse(Files.exists(lowerRoot.resolve("file.txt")));
    }

    @Test
    void defaultCreateNewRejectsInvalidUtf8AndMoveRetainsSource() throws Exception {
        Path sourceRoot = Files.createDirectory(workspace.resolve("source"));
        Path upperRoot = Files.createDirectory(workspace.resolve("upper"));
        Path lowerRoot = Files.createDirectory(workspace.resolve("lower"));
        byte[] content = new byte[] {(byte) 0xff, 1, 2};
        Files.write(sourceRoot.resolve("file.bin"), content);
        OverlayFilesystem overlay = new OverlayFilesystem(local(upperRoot), local(lowerRoot));
        FileUploadResponse upload =
                overlay.uploadFiles(
                                RT, List.of(Map.entry("/file.bin", content)), UploadMode.CREATE_NEW)
                        .get(0);
        assertFalse(upload.isSuccess());
        CompositeFilesystem fs =
                new CompositeFilesystem(local(sourceRoot), Map.of("/target/", overlay));

        WriteResult result = fs.move(RT, "/file.bin", "/target/file.bin");

        assertFalse(result.isSuccess());
        assertArrayEquals(content, Files.readAllBytes(sourceRoot.resolve("file.bin")));
        assertFalse(Files.exists(upperRoot.resolve("file.bin")));
        assertFalse(Files.exists(lowerRoot.resolve("file.bin")));
    }

    @Test
    void binaryCreateNewUsesNestedRoutesAndBakedNamespace() throws Exception {
        RuntimeContext baked = RuntimeContext.builder().userId("alice").build();
        RuntimeContext caller = RuntimeContext.builder().userId("bob").build();
        InMemoryStore store = new InMemoryStore();
        RemoteFilesystem remote =
                new RemoteFilesystem(store, context -> List.of("files", context.getUserId()));
        Path innerRoot = Files.createDirectory(workspace.resolve("inner-default"));
        Path outerRoot = Files.createDirectory(workspace.resolve("outer-default"));
        CompositeFilesystem inner =
                new CompositeFilesystem(local(innerRoot), Map.of("/objects/", remote));
        CompositeFilesystem outer =
                new CompositeFilesystem(local(outerRoot), Map.of("/archive/", inner));
        BakedContextFilesystem fs = new BakedContextFilesystem(outer, baked);
        byte[] content = new byte[] {0, (byte) 0xff, 1, (byte) 0xfe};
        String path = "/archive/objects/file.bin";

        List<FileUploadResponse> responses =
                fs.uploadFiles(caller, List.of(Map.entry(path, content)), UploadMode.CREATE_NEW);

        assertEquals(1, responses.size());
        assertTrue(responses.get(0).isSuccess(), responses.get(0).error());
        assertEquals(path, responses.get(0).path());
        assertNotNull(store.get(List.of("files", "alice"), "/file.bin"));
        assertNull(store.get(List.of("files", "bob"), "/file.bin"));
        assertArrayEquals(content, download(remote, baked, "/file.bin"));
        assertFalse(Files.exists(innerRoot.resolve("objects/file.bin")));
        assertFalse(Files.exists(outerRoot.resolve("archive/objects/file.bin")));
    }

    @Test
    void routedSandboxForwardsBinaryCreateNewToMountedBackend() throws Exception {
        Path primaryRoot = Files.createDirectory(workspace.resolve("primary"));
        Path mountedRoot = Files.createDirectory(workspace.resolve("mounted"));
        LocalFilesystemWithShell primary =
                new LocalFilesystemWithShell(primaryRoot, true, 10, 1024, Map.of(), false);
        RoutedSandboxFilesystem fs =
                new RoutedSandboxFilesystem(primary, Map.of("/mounted/", local(mountedRoot)));
        byte[] content = new byte[] {(byte) 0xff, 0, (byte) 0xfe};

        List<FileUploadResponse> responses =
                fs.uploadFiles(
                        RT,
                        List.of(Map.entry("/mounted/file.bin", content)),
                        UploadMode.CREATE_NEW);

        assertEquals(1, responses.size());
        assertTrue(responses.get(0).isSuccess(), responses.get(0).error());
        assertEquals("/mounted/file.bin", responses.get(0).path());
        assertArrayEquals(content, Files.readAllBytes(mountedRoot.resolve("file.bin")));
        assertFalse(Files.exists(primaryRoot.resolve("mounted/file.bin")));
    }

    private static LocalFilesystem local(Path root) {
        return new LocalFilesystem(root, true, 10);
    }

    private static byte[] download(AbstractFilesystem fs, RuntimeContext context, String path) {
        List<FileDownloadResponse> responses = fs.downloadFiles(context, List.of(path));
        assertEquals(1, responses.size());
        FileDownloadResponse response = responses.get(0);
        assertTrue(response.isSuccess(), response.error());
        return response.content();
    }
}
