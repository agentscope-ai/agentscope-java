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
import io.agentscope.harness.agent.filesystem.model.WriteResult;
import io.agentscope.harness.agent.filesystem.remote.RemoteFilesystem;
import io.agentscope.harness.agent.filesystem.remote.store.InMemoryStore;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Base64;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.stream.Stream;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;

/** Cross-backend moves must preserve file bytes and report incomplete moves. */
class CompositeFilesystemMoveTest {

    private static final RuntimeContext RT = RuntimeContext.empty();
    private static final List<String> NAMESPACE = List.of("move-test");

    @TempDir Path workspace;

    static Stream<Arguments> fileContents() {
        byte[] allBytes = new byte[256];
        for (int i = 0; i < allBytes.length; i++) {
            allBytes[i] = (byte) i;
        }
        return Stream.of(
                Arguments.of("invalid-utf8.bin", new byte[] {(byte) 0xff}),
                Arguments.of(
                        "image.png",
                        Base64.getDecoder()
                                .decode(
                                        "iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAQAAAC1HAwCAAAAC0lEQVR42mP8/x8AAwMCAO+jRZkAAAAASUVORK5CYII=")),
                Arguments.of("empty.txt", new byte[0]),
                Arguments.of("whitespace.txt", " \t\r\n".getBytes(StandardCharsets.UTF_8)),
                Arguments.of(
                        "utf8-crlf.txt",
                        "hello 世界\r\nlast line\r\n".getBytes(StandardCharsets.UTF_8)),
                Arguments.of("all-bytes.bin", allBytes));
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("fileContents")
    void moveBetweenLocalBackendsPreservesBytes(String fileName, byte[] content) throws Exception {
        Path sourceRoot = Files.createDirectory(workspace.resolve("source"));
        Path targetRoot = Files.createDirectory(workspace.resolve("target"));
        Files.write(sourceRoot.resolve(fileName), content);
        CompositeFilesystem fs =
                new CompositeFilesystem(local(sourceRoot), Map.of("/target/", local(targetRoot)));

        WriteResult result = fs.move(RT, "/" + fileName, "/target/" + fileName);

        assertTrue(result.isSuccess(), result.error());
        assertEquals("/target/" + fileName, result.path());
        assertArrayEquals(content, Files.readAllBytes(targetRoot.resolve(fileName)));
        assertFalse(Files.exists(sourceRoot.resolve(fileName)));
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("fileContents")
    void moveFromLocalToRemotePreservesBytes(String fileName, byte[] content) throws Exception {
        Files.write(workspace.resolve(fileName), content);
        RemoteFilesystem remote = new RemoteFilesystem(new InMemoryStore(), NAMESPACE);
        CompositeFilesystem fs =
                new CompositeFilesystem(local(workspace), Map.of("/target/", remote));

        WriteResult result = fs.move(RT, "/" + fileName, "/target/" + fileName);

        assertTrue(result.isSuccess(), result.error());
        assertArrayEquals(content, download(remote, "/" + fileName));
        assertFalse(Files.exists(workspace.resolve(fileName)));
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("fileContents")
    void moveFromRemoteToLocalPreservesBytes(String fileName, byte[] content) throws Exception {
        InMemoryStore store = new InMemoryStore();
        putBytes(store, "/" + fileName, content);
        RemoteFilesystem remote = new RemoteFilesystem(store, NAMESPACE);
        CompositeFilesystem fs =
                new CompositeFilesystem(remote, Map.of("/target/", local(workspace)));

        WriteResult result = fs.move(RT, "/" + fileName, "/target/" + fileName);

        assertTrue(result.isSuccess(), result.error());
        assertArrayEquals(content, Files.readAllBytes(workspace.resolve(fileName)));
        assertNull(store.get(NAMESPACE, "/" + fileName));
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("fileContents")
    void moveWithinSameBackendPreservesBytes(String fileName, byte[] content) throws Exception {
        Files.write(workspace.resolve(fileName), content);
        LocalFilesystem backend = local(workspace);
        CompositeFilesystem fs = new CompositeFilesystem(backend, Map.of("/target/", backend));

        WriteResult result = fs.move(RT, "/" + fileName, "/target/moved-" + fileName);

        assertTrue(result.isSuccess(), result.error());
        assertArrayEquals(content, Files.readAllBytes(workspace.resolve("moved-" + fileName)));
        assertFalse(Files.exists(workspace.resolve(fileName)));
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void moveDoesNotOverwriteExistingTarget(boolean remoteTarget) throws Exception {
        byte[] source = new byte[] {(byte) 0xff, 1, 2};
        byte[] existing = new byte[] {3, 4, (byte) 0xfe};
        Path sourceRoot = Files.createDirectory(workspace.resolve("source"));
        Files.write(sourceRoot.resolve("file.bin"), source);
        AbstractFilesystem target;
        if (remoteTarget) {
            InMemoryStore store = new InMemoryStore();
            putBytes(store, "/file.bin", existing);
            target = new RemoteFilesystem(store, NAMESPACE);
        } else {
            Path targetRoot = Files.createDirectory(workspace.resolve("target"));
            Files.write(targetRoot.resolve("file.bin"), existing);
            target = local(targetRoot);
        }
        CompositeFilesystem fs =
                new CompositeFilesystem(local(sourceRoot), Map.of("/target/", target));

        WriteResult result = fs.move(RT, "/file.bin", "/target/file.bin");

        assertFalse(result.isSuccess());
        assertArrayEquals(source, Files.readAllBytes(sourceRoot.resolve("file.bin")));
        assertArrayEquals(existing, download(target, "/file.bin"));
    }

    @ParameterizedTest
    @ValueSource(strings = {"failure", "exception", "missing-response"})
    void moveReportsSourceDeletionFailureAndPreservesBothCopies(String failure) throws Exception {
        byte[] content = "preserve both copies\r\n".getBytes(StandardCharsets.UTF_8);
        Path sourceRoot = Files.createDirectory(workspace.resolve("source"));
        Path targetRoot = Files.createDirectory(workspace.resolve("target"));
        Files.write(sourceRoot.resolve("file.txt"), content);
        LocalFilesystem source =
                new LocalFilesystem(sourceRoot, true, 10) {
                    @Override
                    public WriteResult delete(RuntimeContext runtimeContext, String path) {
                        if (failure.equals("exception")) {
                            throw new IllegalStateException("source deletion denied");
                        }
                        if (failure.equals("missing-response")) {
                            return null;
                        }
                        return WriteResult.fail("source deletion denied");
                    }
                };
        CompositeFilesystem fs =
                new CompositeFilesystem(source, Map.of("/target/", local(targetRoot)));

        WriteResult result = fs.move(RT, "/file.txt", "/target/file.txt");

        assertFalse(result.isSuccess(), "a copy without source deletion is not a successful move");
        assertTrue(result.error().contains("could not delete source"));
        assertTrue(
                result.error()
                        .contains(
                                failure.equals("missing-response")
                                        ? "missing delete response"
                                        : "source deletion denied"));
        assertArrayEquals(content, Files.readAllBytes(sourceRoot.resolve("file.txt")));
        assertArrayEquals(content, Files.readAllBytes(targetRoot.resolve("file.txt")));
    }

    @ParameterizedTest
    @ValueSource(
            strings = {
                "unsupported",
                "null-content",
                "missing-response",
                "null-list",
                "null-entry",
                "failure"
            })
    void movePreservesSourceWhenDownloadCannotProvideBytes(String failure) throws Exception {
        byte[] content = new byte[] {(byte) 0xff, 1, 2};
        Path sourceRoot = Files.createDirectory(workspace.resolve("source"));
        Path targetRoot = Files.createDirectory(workspace.resolve("target"));
        Files.write(sourceRoot.resolve("file.bin"), content);
        LocalFilesystem source =
                new LocalFilesystem(sourceRoot, true, 10) {
                    @Override
                    public List<FileDownloadResponse> downloadFiles(
                            RuntimeContext runtimeContext, List<String> paths) {
                        return switch (failure) {
                            case "unsupported" ->
                                    throw new UnsupportedOperationException(
                                            "binary download is unavailable");
                            case "null-content" ->
                                    List.of(FileDownloadResponse.success(paths.get(0), null));
                            case "null-list" -> null;
                            case "null-entry" -> Collections.singletonList(null);
                            case "failure" ->
                                    List.of(
                                            FileDownloadResponse.fail(
                                                    paths.get(0), "download denied"));
                            default -> List.of();
                        };
                    }
                };
        CompositeFilesystem fs =
                new CompositeFilesystem(source, Map.of("/target/", local(targetRoot)));

        WriteResult result = fs.move(RT, "/file.bin", "/target/file.bin");

        assertFalse(result.isSuccess());
        assertTrue(result.error().contains("/file.bin"));
        if (failure.equals("failure")) {
            assertTrue(result.error().contains("download denied"));
        }
        assertArrayEquals(content, Files.readAllBytes(sourceRoot.resolve("file.bin")));
        assertFalse(Files.exists(targetRoot.resolve("file.bin")));
    }

    @ParameterizedTest
    @ValueSource(strings = {"failure", "exception", "null-list", "null-entry", "missing-response"})
    void movePreservesSourceWhenTargetUploadFails(String failure) throws Exception {
        byte[] content = new byte[] {(byte) 0xff, 1, 2};
        Path sourceRoot = Files.createDirectory(workspace.resolve("source"));
        Path targetRoot = Files.createDirectory(workspace.resolve("target"));
        Files.write(sourceRoot.resolve("file.bin"), content);
        LocalFilesystem target =
                new LocalFilesystem(targetRoot, true, 10) {
                    @Override
                    public List<FileUploadResponse> uploadFiles(
                            RuntimeContext runtimeContext,
                            List<Map.Entry<String, byte[]>> files,
                            UploadMode mode) {
                        return switch (failure) {
                            case "exception" -> throw new IllegalStateException("upload denied");
                            case "null-list" -> null;
                            case "null-entry" -> Collections.singletonList(null);
                            case "missing-response" -> List.of();
                            default ->
                                    List.of(
                                            FileUploadResponse.fail(
                                                    files.get(0).getKey(), "upload denied"));
                        };
                    }
                };
        CompositeFilesystem fs =
                new CompositeFilesystem(local(sourceRoot), Map.of("/target/", target));

        WriteResult result = fs.move(RT, "/file.bin", "/target/file.bin");

        assertFalse(result.isSuccess());
        assertTrue(result.error().contains("upload"));
        assertTrue(result.error().contains("/target/file.bin"));
        assertArrayEquals(content, Files.readAllBytes(sourceRoot.resolve("file.bin")));
        assertFalse(Files.exists(targetRoot.resolve("file.bin")));
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void concurrentMovesOnlyDeleteTheWinningSource(boolean remoteTarget) throws Exception {
        byte[] firstContent = new byte[] {(byte) 0xff, 1, 2};
        byte[] secondContent = new byte[] {3, 4, (byte) 0xfe};
        Path firstRoot = Files.createDirectory(workspace.resolve("first"));
        Path secondRoot = Files.createDirectory(workspace.resolve("second"));
        Files.write(firstRoot.resolve("file.bin"), firstContent);
        Files.write(secondRoot.resolve("file.bin"), secondContent);
        CountDownLatch uploadsReady = new CountDownLatch(2);
        AbstractFilesystem target;
        if (remoteTarget) {
            target =
                    new RemoteFilesystem(new InMemoryStore(), NAMESPACE) {
                        @Override
                        public List<FileUploadResponse> uploadFiles(
                                RuntimeContext runtimeContext,
                                List<Map.Entry<String, byte[]>> files,
                                UploadMode mode) {
                            awaitUploads(uploadsReady);
                            return super.uploadFiles(runtimeContext, files, mode);
                        }
                    };
        } else {
            Path targetRoot = Files.createDirectory(workspace.resolve("target"));
            target =
                    new LocalFilesystem(targetRoot, true, 10) {
                        @Override
                        public List<FileUploadResponse> uploadFiles(
                                RuntimeContext runtimeContext,
                                List<Map.Entry<String, byte[]>> files,
                                UploadMode mode) {
                            awaitUploads(uploadsReady);
                            return super.uploadFiles(runtimeContext, files, mode);
                        }
                    };
        }
        CompositeFilesystem first =
                new CompositeFilesystem(local(firstRoot), Map.of("/target/", target));
        CompositeFilesystem second =
                new CompositeFilesystem(local(secondRoot), Map.of("/target/", target));
        ExecutorService executor = Executors.newFixedThreadPool(2);
        try {
            Future<WriteResult> firstMove =
                    executor.submit(() -> first.move(RT, "/file.bin", "/target/file.bin"));
            Future<WriteResult> secondMove =
                    executor.submit(() -> second.move(RT, "/file.bin", "/target/file.bin"));
            WriteResult firstResult = firstMove.get(15, TimeUnit.SECONDS);
            WriteResult secondResult = secondMove.get(15, TimeUnit.SECONDS);

            assertEquals(
                    1,
                    (firstResult.isSuccess() ? 1 : 0) + (secondResult.isSuccess() ? 1 : 0),
                    "exactly one move may create the destination");
            Path winningRoot = firstResult.isSuccess() ? firstRoot : secondRoot;
            Path losingRoot = firstResult.isSuccess() ? secondRoot : firstRoot;
            byte[] winningContent = firstResult.isSuccess() ? firstContent : secondContent;
            byte[] losingContent = firstResult.isSuccess() ? secondContent : firstContent;
            assertFalse(Files.exists(winningRoot.resolve("file.bin")));
            assertArrayEquals(losingContent, Files.readAllBytes(losingRoot.resolve("file.bin")));
            assertArrayEquals(winningContent, download(target, "/file.bin"));
        } finally {
            executor.shutdownNow();
            assertTrue(executor.awaitTermination(5, TimeUnit.SECONDS));
        }
    }

    private static void awaitUploads(CountDownLatch uploadsReady) {
        uploadsReady.countDown();
        try {
            assertTrue(
                    uploadsReady.await(10, TimeUnit.SECONDS),
                    "both moves should reach the destination before either upload begins");
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new AssertionError("interrupted while waiting for concurrent uploads", e);
        }
    }

    private static LocalFilesystem local(Path root) {
        return new LocalFilesystem(root, true, 10);
    }

    private static void putBytes(InMemoryStore store, String path, byte[] content) {
        store.put(
                NAMESPACE,
                path,
                Map.of(
                        "content",
                        Base64.getEncoder().encodeToString(content),
                        "encoding",
                        "base64"));
    }

    private static byte[] download(AbstractFilesystem fs, String path) {
        List<FileDownloadResponse> responses = fs.downloadFiles(RT, List.of(path));
        assertEquals(1, responses.size());
        FileDownloadResponse response = responses.get(0);
        assertTrue(response.isSuccess(), response.error());
        return response.content();
    }
}
