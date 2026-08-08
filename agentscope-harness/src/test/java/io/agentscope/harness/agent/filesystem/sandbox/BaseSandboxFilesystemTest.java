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

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.agentscope.core.agent.RuntimeContext;
import io.agentscope.harness.agent.filesystem.model.EditResult;
import io.agentscope.harness.agent.filesystem.model.ExecuteResponse;
import io.agentscope.harness.agent.filesystem.model.FileDownloadResponse;
import io.agentscope.harness.agent.filesystem.model.FileInfo;
import io.agentscope.harness.agent.filesystem.model.FileUploadResponse;
import io.agentscope.harness.agent.filesystem.model.GlobResult;
import io.agentscope.harness.agent.filesystem.model.LsResult;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledOnOs;
import org.junit.jupiter.api.condition.OS;
import org.junit.jupiter.api.io.TempDir;

class BaseSandboxFilesystemTest {

    private static final RuntimeContext RT = RuntimeContext.empty();

    // ================================================================
    // Unit tests — canned responses, run on all platforms
    // ================================================================

    @Nested
    class CannedResponseTests {

        @Test
        void glob_recursivePattern_stripsDoubleStarPrefixBeforeFindName() {
            FakeSandboxFilesystem filesystem = new FakeSandboxFilesystem();

            GlobResult result = filesystem.glob(RT, "**/*.md", "/workspace");

            assertTrue(result.isSuccess());
            assertTrue(
                    filesystem.lastCommand.contains("stat -c"),
                    "glob should use stat for metadata");
            assertEquals(
                    List.of("/workspace/README.md", "/workspace/docs/guide.md"),
                    result.matches().stream().map(FileInfo::path).collect(Collectors.toList()));
        }

        @Test
        void glob_parsesSize() {
            FakeSandboxFilesystem filesystem = new FakeSandboxFilesystem();

            GlobResult result = filesystem.glob(RT, "*.md", "/workspace");

            assertTrue(result.isSuccess());
            assertEquals(
                    1024L,
                    result.matches().stream()
                            .filter(f -> f.path().equals("/workspace/README.md"))
                            .findFirst()
                            .orElseThrow()
                            .size());
        }

        @Test
        void glob_parsesModifiedAt() {
            FakeSandboxFilesystem filesystem = new FakeSandboxFilesystem();

            GlobResult result = filesystem.glob(RT, "*.md", "/workspace");

            assertTrue(result.isSuccess());
            String modifiedAt =
                    result.matches().stream()
                            .filter(f -> f.path().equals("/workspace/README.md"))
                            .findFirst()
                            .orElseThrow()
                            .modifiedAt();
            assertFalse(modifiedAt.isEmpty(), "modifiedAt should be populated");
        }

        @Test
        void ls_reportsFileSizeAndModifiedAt() {
            FakeSandboxFilesystem filesystem = new FakeSandboxFilesystem();

            LsResult result = filesystem.ls(RT, "/workspace");

            assertTrue(result.isSuccess());
            assertFalse(result.entries().isEmpty());

            FileInfo file =
                    result.entries().stream()
                            .filter(e -> e.path().equals("/workspace/readme.txt"))
                            .findFirst()
                            .orElseThrow();
            assertEquals(12L, file.size());
            assertFalse(file.modifiedAt().isEmpty(), "file modifiedAt should be populated");
        }

        @Test
        void ls_reportsDirModifiedAt() {
            FakeSandboxFilesystem filesystem = new FakeSandboxFilesystem();

            LsResult result = filesystem.ls(RT, "/workspace");

            assertTrue(result.isSuccess());
            FileInfo dir =
                    result.entries().stream()
                            .filter(e -> e.path().equals("/workspace/docs"))
                            .findFirst()
                            .orElseThrow();
            assertTrue(dir.isDirectory());
            assertFalse(dir.modifiedAt().isEmpty(), "dir modifiedAt should be populated");
        }

        @Test
        void edit_downloadFails_returnsFileNotFound() {
            EditSpyFilesystem fs = new EditSpyFilesystem();
            fs.withDownloadResult(List.of());

            EditResult result = fs.edit(RT, "/workspace/missing.txt", "old", "new", false);

            assertFalse(result.isSuccess());
            assertTrue(result.error().contains("not found"));
            assertTrue(fs.downloadedPaths.contains("/workspace/missing.txt"));
        }

        @Test
        void edit_downloadReturnsError_returnsFileNotFound() {
            EditSpyFilesystem fs = new EditSpyFilesystem();
            fs.withDownloadResult(
                    List.of(FileDownloadResponse.fail("/workspace/f.txt", "permission denied")));

            EditResult result = fs.edit(RT, "/workspace/f.txt", "old", "new", false);

            assertFalse(result.isSuccess());
            assertTrue(result.error().contains("not found"));
        }

        @Test
        void edit_emptyContent_returnsError() {
            EditSpyFilesystem fs = new EditSpyFilesystem();
            fs.withDownloadResult(List.of(FileDownloadResponse.success("/workspace/f.txt", null)));

            EditResult result = fs.edit(RT, "/workspace/f.txt", "old", "new", false);

            assertFalse(result.isSuccess());
            assertTrue(result.error().contains("empty"));
        }

        @Test
        void edit_stringNotFound_returnsError() {
            EditSpyFilesystem fs = new EditSpyFilesystem();
            fs.withDownloadResult(
                    List.of(
                            FileDownloadResponse.success(
                                    "/workspace/f.txt", "hello world".getBytes())));

            EditResult result = fs.edit(RT, "/workspace/f.txt", "nonexistent", "new", false);

            assertFalse(result.isSuccess());
            assertTrue(result.error().contains("String not found"));
        }

        @Test
        void edit_uploadFails_returnsError() {
            EditSpyFilesystem fs = new EditSpyFilesystem();
            fs.withDownloadResult(
                    List.of(
                            FileDownloadResponse.success(
                                    "/workspace/f.txt", "hello world".getBytes())));
            fs.withUploadResult(List.of(FileUploadResponse.fail("/workspace/f.txt", "disk full")));

            EditResult result = fs.edit(RT, "/workspace/f.txt", "world", "Java", false);

            assertFalse(result.isSuccess());
            assertTrue(result.error().contains("disk full"));
        }

        @Test
        void edit_success_downloadEditUploadFlow() {
            EditSpyFilesystem fs = new EditSpyFilesystem();
            fs.withDownloadResult(
                    List.of(
                            FileDownloadResponse.success(
                                    "/workspace/f.txt", "Hello World!".getBytes())));
            fs.withUploadResult(List.of(FileUploadResponse.success("/workspace/f.txt")));

            EditResult result = fs.edit(RT, "/workspace/f.txt", "World", "Java", false);

            assertTrue(result.isSuccess());
            assertEquals("/workspace/f.txt", result.path());
            assertEquals(1, result.occurrences());
            // verify upload contains edited content
            assertEquals(1, fs.uploadedFiles.size());
            String uploadedContent =
                    new String(fs.uploadedFiles.get(0).getValue(), StandardCharsets.UTF_8);
            assertEquals("Hello Java!", uploadedContent);
        }

        @Test
        void edit_replaceAll_replacesAllOccurrences() {
            EditSpyFilesystem fs = new EditSpyFilesystem();
            fs.withDownloadResult(
                    List.of(
                            FileDownloadResponse.success(
                                    "/workspace/f.txt", "a b a b a".getBytes())));
            fs.withUploadResult(List.of(FileUploadResponse.success("/workspace/f.txt")));

            EditResult result = fs.edit(RT, "/workspace/f.txt", "a", "x", true);

            assertTrue(result.isSuccess());
            assertEquals(3, result.occurrences());
            String uploadedContent =
                    new String(fs.uploadedFiles.get(0).getValue(), StandardCharsets.UTF_8);
            assertEquals("x b x b x", uploadedContent);
        }

        @Test
        void edit_withSpecialCharactersInStrings() {
            EditSpyFilesystem fs = new EditSpyFilesystem();
            String content = "line1\nline2\"quote\\backslash\nline3";
            fs.withDownloadResult(
                    List.of(FileDownloadResponse.success("/workspace/f.txt", content.getBytes())));
            fs.withUploadResult(List.of(FileUploadResponse.success("/workspace/f.txt")));

            EditResult result =
                    fs.edit(
                            RT,
                            "/workspace/f.txt",
                            "line2\"quote\\backslash",
                            "replaced\"line\\here",
                            false);

            assertTrue(result.isSuccess());
            assertEquals(1, result.occurrences());
            String uploadedContent =
                    new String(fs.uploadedFiles.get(0).getValue(), StandardCharsets.UTF_8);
            assertEquals("line1\nreplaced\"line\\here\nline3", uploadedContent);
        }
    }

    // ================================================================
    // Integration tests — real shell execution, Linux only
    // ================================================================

    @Nested
    @EnabledOnOs(OS.LINUX)
    class LocalShellIntegrationTests {

        @TempDir Path tmpDir;

        @Test
        void ls_returnsRealSizeAndModifiedAt() throws IOException {
            byte[] content = "hello world\n".getBytes(StandardCharsets.UTF_8);
            Files.write(tmpDir.resolve("file.txt"), content);
            Files.createDirectory(tmpDir.resolve("subdir"));

            LocalShellSandboxFilesystem fs = new LocalShellSandboxFilesystem();
            LsResult result = fs.ls(RT, tmpDir.toString());

            assertTrue(result.isSuccess());
            assertEquals(2, result.entries().size());

            FileInfo file =
                    result.entries().stream()
                            .filter(e -> e.path().endsWith("file.txt"))
                            .findFirst()
                            .orElseThrow();
            assertEquals(content.length, file.size());
            assertFalse(file.modifiedAt().isEmpty());

            FileInfo dir =
                    result.entries().stream()
                            .filter(FileInfo::isDirectory)
                            .findFirst()
                            .orElseThrow();
            assertFalse(dir.modifiedAt().isEmpty());
        }

        @Test
        void glob_returnsRealSizeAndModifiedAt() throws IOException {
            Files.write(tmpDir.resolve("a.md"), "aaa".getBytes(StandardCharsets.UTF_8));
            Path sub = Files.createDirectory(tmpDir.resolve("sub"));
            Files.write(sub.resolve("b.md"), "bbbbb".getBytes(StandardCharsets.UTF_8));

            LocalShellSandboxFilesystem fs = new LocalShellSandboxFilesystem();
            GlobResult result = fs.glob(RT, "**/*.md", tmpDir.toString());

            assertTrue(result.isSuccess());
            assertEquals(2, result.matches().size());

            FileInfo a =
                    result.matches().stream()
                            .filter(f -> f.path().endsWith("a.md"))
                            .findFirst()
                            .orElseThrow();
            assertEquals(3L, a.size());
            assertFalse(a.modifiedAt().isEmpty());

            FileInfo b =
                    result.matches().stream()
                            .filter(f -> f.path().endsWith("b.md"))
                            .findFirst()
                            .orElseThrow();
            assertEquals(5L, b.size());
            assertFalse(b.modifiedAt().isEmpty());
        }

        @Test
        void glob_emptyResultWhenNoMatch() {
            LocalShellSandboxFilesystem fs = new LocalShellSandboxFilesystem();
            GlobResult result = fs.glob(RT, "*.xyz", tmpDir.toString());
            assertTrue(result.isSuccess());
            assertTrue(result.matches().isEmpty());
        }

        @Test
        void edit_simpleReplacement() throws IOException {
            Path file = tmpDir.resolve("test.txt");
            Files.writeString(file, "Hello World");

            LocalShellSandboxFilesystem fs = new LocalShellSandboxFilesystem();
            EditResult result = fs.edit(RT, file.toString(), "World", "Java", false);

            assertTrue(result.isSuccess(), "edit should succeed: " + result.error());
            assertEquals("Hello Java", Files.readString(file));
            assertEquals(1, result.occurrences());
        }

        @Test
        void edit_withSpecialCharacters() throws IOException {
            Path file = tmpDir.resolve("special.txt");
            Files.writeString(file, "line1\nline2\"quote\\backslash\nline3");

            LocalShellSandboxFilesystem fs = new LocalShellSandboxFilesystem();
            EditResult result =
                    fs.edit(
                            RT,
                            file.toString(),
                            "line2\"quote\\backslash",
                            "replaced\"line\\here",
                            false);

            assertTrue(result.isSuccess(), "edit should succeed: " + result.error());
            assertEquals("line1\nreplaced\"line\\here\nline3", Files.readString(file));
            assertEquals(1, result.occurrences());
        }

        @Test
        void edit_replaceAll() throws IOException {
            Path file = tmpDir.resolve("replace.txt");
            Files.writeString(file, "a b a b a");

            LocalShellSandboxFilesystem fs = new LocalShellSandboxFilesystem();
            EditResult result = fs.edit(RT, file.toString(), "a", "x", true);

            assertTrue(result.isSuccess(), "edit should succeed: " + result.error());
            assertEquals("x b x b x", Files.readString(file));
            assertEquals(3, result.occurrences());
        }

        @Test
        void edit_fileNotFound() {
            LocalShellSandboxFilesystem fs = new LocalShellSandboxFilesystem();
            EditResult result =
                    fs.edit(RT, tmpDir.resolve("nonexistent.txt").toString(), "old", "new", false);

            assertFalse(result.isSuccess());
            assertTrue(result.error().contains("not found"));
        }

        @Test
        void edit_stringNotFound() throws IOException {
            Path file = tmpDir.resolve("missing.txt");
            Files.writeString(file, "Hello World");

            LocalShellSandboxFilesystem fs = new LocalShellSandboxFilesystem();
            EditResult result = fs.edit(RT, file.toString(), "Goodbye", "Hi", false);

            assertFalse(result.isSuccess());
            assertTrue(result.error().contains("String not found"));
        }

        @Test
        void edit_multipleOccurrencesWithoutReplaceAll() throws IOException {
            Path file = tmpDir.resolve("multi.txt");
            Files.writeString(file, "foo bar foo baz foo");

            LocalShellSandboxFilesystem fs = new LocalShellSandboxFilesystem();
            EditResult result = fs.edit(RT, file.toString(), "foo", "x", false);

            assertFalse(result.isSuccess());
            assertTrue(result.error().contains("appears"));
        }
    }

    // ================================================================
    // Test helpers
    // ================================================================

    private static final class EditSpyFilesystem extends BaseSandboxFilesystem {

        final List<String> downloadedPaths = new ArrayList<>();
        final List<Map.Entry<String, byte[]>> uploadedFiles = new ArrayList<>();
        private List<FileDownloadResponse> cannedDownload = List.of();
        private List<FileUploadResponse> cannedUpload = List.of();

        void withDownloadResult(List<FileDownloadResponse> responses) {
            this.cannedDownload = responses;
        }

        void withUploadResult(List<FileUploadResponse> responses) {
            this.cannedUpload = responses;
        }

        @Override
        public String id() {
            return "edit-spy";
        }

        @Override
        public ExecuteResponse execute(
                RuntimeContext runtimeContext, String command, Integer timeoutSeconds) {
            throw new UnsupportedOperationException();
        }

        @Override
        public List<FileUploadResponse> uploadFiles(
                RuntimeContext runtimeContext, List<Map.Entry<String, byte[]>> files) {
            uploadedFiles.addAll(files);
            return cannedUpload;
        }

        @Override
        public List<FileDownloadResponse> downloadFiles(
                RuntimeContext runtimeContext, List<String> paths) {
            downloadedPaths.addAll(paths);
            return cannedDownload;
        }
    }

    private static final class FakeSandboxFilesystem extends BaseSandboxFilesystem {

        String lastCommand;

        @Override
        public String id() {
            return "fake";
        }

        @Override
        public ExecuteResponse execute(
                RuntimeContext runtimeContext, String command, Integer timeoutSeconds) {
            lastCommand = command;
            if (command.startsWith("for f in ") && command.contains("stat -c")) {
                return new ExecuteResponse(
                        "DIR:/workspace/docs\t1719300000\n"
                                + "FILE:/workspace/readme.txt\t12\t1719300000\n",
                        0,
                        false);
            }
            if (command.startsWith("find ") && command.contains("while IFS=")) {
                return new ExecuteResponse(
                        "/workspace/README.md\t1024\t1719300000\n"
                                + "/workspace/docs/guide.md\t512\t1719300000\n",
                        0,
                        false);
            }
            return new ExecuteResponse("", 0, false);
        }

        @Override
        public List<FileUploadResponse> uploadFiles(
                RuntimeContext runtimeContext, List<Map.Entry<String, byte[]>> files) {
            return List.of();
        }

        @Override
        public List<FileDownloadResponse> downloadFiles(
                RuntimeContext runtimeContext, List<String> paths) {
            return List.of();
        }
    }

    private static final class LocalShellSandboxFilesystem extends BaseSandboxFilesystem {

        @Override
        public String id() {
            return "local-shell";
        }

        @Override
        public ExecuteResponse execute(
                RuntimeContext runtimeContext, String command, Integer timeoutSeconds) {
            try {
                Process p =
                        new ProcessBuilder("sh", "-c", command).redirectErrorStream(true).start();
                String output =
                        new String(p.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
                int exitCode = p.waitFor();
                return new ExecuteResponse(output, exitCode, false);
            } catch (Exception e) {
                return new ExecuteResponse("execute failed: " + e.getMessage(), 1, false);
            }
        }

        @Override
        public List<FileUploadResponse> uploadFiles(
                RuntimeContext runtimeContext, List<Map.Entry<String, byte[]>> files) {
            List<FileUploadResponse> results = new ArrayList<>();
            for (Map.Entry<String, byte[]> entry : files) {
                try {
                    Files.write(Path.of(entry.getKey()), entry.getValue());
                    results.add(FileUploadResponse.success(entry.getKey()));
                } catch (IOException e) {
                    results.add(FileUploadResponse.fail(entry.getKey(), e.getMessage()));
                }
            }
            return results;
        }

        @Override
        public List<FileDownloadResponse> downloadFiles(
                RuntimeContext runtimeContext, List<String> paths) {
            List<FileDownloadResponse> results = new ArrayList<>();
            for (String path : paths) {
                try {
                    byte[] content = Files.readAllBytes(Path.of(path));
                    results.add(FileDownloadResponse.success(path, content));
                } catch (IOException e) {
                    results.add(FileDownloadResponse.fail(path, e.getMessage()));
                }
            }
            return results;
        }
    }
}
