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
import io.agentscope.harness.agent.filesystem.model.ExecuteResponse;
import io.agentscope.harness.agent.filesystem.model.FileDownloadResponse;
import io.agentscope.harness.agent.filesystem.model.FileInfo;
import io.agentscope.harness.agent.filesystem.model.FileUploadResponse;
import io.agentscope.harness.agent.filesystem.model.GlobResult;
import io.agentscope.harness.agent.filesystem.model.GrepResult;
import io.agentscope.harness.agent.filesystem.model.LsResult;
import io.agentscope.harness.agent.filesystem.model.ReadResult;
import io.agentscope.harness.agent.filesystem.model.WriteResult;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermission;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledOnOs;
import org.junit.jupiter.api.condition.OS;
import org.junit.jupiter.api.io.TempDir;

class BaseSandboxFilesystemTest {

    private static final RuntimeContext RT = RuntimeContext.empty();

    /**
     * The response {@code SandboxBackedFilesystem.execute} produces when the sandbox backend
     * call itself fails (e.g. HTTP 504): the command never ran.
     */
    private static ExecuteResponse sandboxRequestFailed() {
        return new ExecuteResponse(
                "Internal sandbox error: Execute failed (status=504)", -1, false);
    }

    // ================================================================
    // Unit tests — canned responses, run on all platforms
    // ================================================================

    @Nested
    class CannedResponseTests {

        @Test
        void writeClaimsPathExclusivelyBeforeUpload() {
            SuccessfulWriteFilesystem filesystem = new SuccessfulWriteFilesystem();

            WriteResult result = filesystem.write(RT, "/workspace/claim", "owner");

            assertTrue(result.isSuccess());
            assertEquals("owner", filesystem.uploadedContent);
        }

        @Test
        void writeFailsWhenExclusiveClaimAlreadyExists() {
            FixedResponseFilesystem filesystem =
                    new FixedResponseFilesystem(new ExecuteResponse("EXISTS", 1, false));

            WriteResult result = filesystem.write(RT, "/workspace/claim", "owner");

            assertFalse(result.isSuccess());
            assertTrue(result.error().contains("already exists"));
        }

        @Test
        void writeDoesNotTreatCommandErrorPathContainingExistsAsCollision() {
            FixedResponseFilesystem filesystem =
                    new FixedResponseFilesystem(
                            new ExecuteResponse(
                                    "mkdir: cannot create directory '/tmp/EXISTS-parent':"
                                            + " Permission denied",
                                    1,
                                    false));

            WriteResult result = filesystem.write(RT, "/tmp/EXISTS-parent/claim", "owner");

            assertFalse(result.isSuccess());
            assertFalse(result.error().contains("already exists"));
        }

        @Test
        void writePreservesCreateFailureDiagnostic() {
            FixedResponseFilesystem filesystem =
                    new FixedResponseFilesystem(
                            new ExecuteResponse("CREATE_FAILED: permission denied", 1, false));

            WriteResult result = filesystem.write(RT, "/workspace/claim", "owner");

            assertFalse(result.isSuccess());
            assertTrue(result.error().contains("permission denied"));
        }

        @Test
        void emptyWriteDoesNotAssumeUnknownCreateOutcomeSucceeded() {
            FixedResponseFilesystem filesystem =
                    new FixedResponseFilesystem(new ExecuteResponse("unknown state", null, false));

            WriteResult result = filesystem.write(RT, "/workspace/claim", "");

            assertFalse(result.isSuccess());
            assertFalse(result.isAlreadyExists());
        }

        @Test
        void writeReportsPlaceholderCleanupFailureAlongsideUploadFailure() {
            FailedUploadCleanupFilesystem filesystem = new FailedUploadCleanupFilesystem();

            WriteResult result = filesystem.write(RT, "/workspace/claim", "owner");

            assertFalse(result.isSuccess());
            assertTrue(result.error().contains("upload denied"));
            assertTrue(result.error().contains("cleanup denied"));
        }

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

        // ==================== Bug reproduction: execute failures masked as results (#2961)
        // ====================

        @Test
        void ls_executeFailure_negativeExit_shouldFailWithCause() {
            LsResult result =
                    new FixedResponseFilesystem(sandboxRequestFailed()).ls(RT, "/workspace");

            assertFalse(result.isSuccess(), "ls should fail when the command never ran");
            assertTrue(result.error().contains("/workspace"), "error should locate the target");
            assertTrue(result.error().contains("status=504"), "error should carry the cause");
        }

        @Test
        void ls_executeFailure_nullOutput_shouldFailWithExitCodeFallback() {
            LsResult result =
                    new FixedResponseFilesystem(new ExecuteResponse(null, -1, false))
                            .ls(RT, "/workspace");

            assertFalse(result.isSuccess(), "a null-output failure must not collapse into success");
            assertTrue(
                    result.error().contains("exit code -1"),
                    "error should fall back to the exit code when no diagnostic output exists");
        }

        @Test
        void ls_executeFailure_unknownExitCode_shouldFail() {
            LsResult result =
                    new FixedResponseFilesystem(new ExecuteResponse("unknown state", null, false))
                            .ls(RT, "/workspace");

            assertFalse(result.isSuccess(), "ls should fail when the exit code is unknown");
        }

        @Test
        void ls_executeFailure_timeout_shouldFailWithMessage() {
            LsResult result =
                    new FixedResponseFilesystem(
                                    new ExecuteResponse("Command timed out after 30s", 124, false))
                            .ls(RT, "/workspace");

            assertFalse(result.isSuccess(), "ls should fail when execution times out");
            assertTrue(result.error().contains("timed out"), "error should carry the cause");
        }

        @Test
        void ls_commandFailure_positiveExit_shouldFailWithOutput() {
            LsResult result =
                    new FixedResponseFilesystem(
                                    new ExecuteResponse("sh: stat: not found", 127, false))
                            .ls(RT, "/workspace");

            assertFalse(result.isSuccess(), "ls should fail when the command itself fails");
            assertTrue(result.error().contains("stat"), "error should carry the command output");
        }

        @Test
        void read_text_executeFailure_shouldFailInsteadOfErrorAsContent() {
            ReadResult result =
                    new FixedResponseFilesystem(sandboxRequestFailed())
                            .read(RT, "/workspace/notes.txt", 0, 10);

            assertFalse(result.isSuccess(), "read should fail when the command never ran");
            assertTrue(result.error().contains("status=504"), "error should carry the cause");
        }

        @Test
        void read_binary_executeFailure_shouldFailInsteadOfFileNotFound() {
            ReadResult result =
                    new FixedResponseFilesystem(sandboxRequestFailed())
                            .read(RT, "/workspace/logo.png", 0, 10);

            assertFalse(result.isSuccess(), "read should fail when the command never ran");
            assertTrue(
                    result.error().contains("status=504"),
                    "execution failure must not be mislabeled as file_not_found");
        }

        @Test
        void read_binary_commandFailure_shouldKeepFileNotFoundSignal() {
            ReadResult result =
                    new FixedResponseFilesystem(new ExecuteResponse("", 1, false))
                            .read(RT, "/workspace/logo.png", 0, 10);

            assertFalse(result.isSuccess(), "a missing file is still a failure");
            assertTrue(
                    result.error().contains("file_not_found"),
                    "a real command failure keeps the designed signal");
        }

        @Test
        void read_binary_timeout_shouldFailWithMessageInsteadOfFileNotFound() {
            ReadResult result =
                    new FixedResponseFilesystem(
                                    new ExecuteResponse("Command timed out after 30s", 124, false))
                            .read(RT, "/workspace/logo.png", 0, 10);

            assertFalse(result.isSuccess(), "read should fail when execution times out");
            assertTrue(
                    result.error().contains("timed out"),
                    "a timeout must not be mislabeled as file_not_found");
        }

        @Test
        void grep_executeFailure_shouldFailInsteadOfEmptySuccess() {
            GrepResult result =
                    new FixedResponseFilesystem(sandboxRequestFailed())
                            .grep(RT, "pattern", "/workspace", null);

            assertFalse(result.isSuccess(), "grep should fail when the command never ran");
            assertTrue(result.error().contains("status=504"), "error should carry the cause");
        }

        @Test
        void glob_executeFailure_shouldFailInsteadOfErrorAsPaths() {
            GlobResult result =
                    new FixedResponseFilesystem(sandboxRequestFailed())
                            .glob(RT, "*.md", "/workspace");

            assertFalse(result.isSuccess(), "glob should fail when the command never ran");
            assertTrue(result.error().contains("status=504"), "error should carry the cause");
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

        // ==================== Bug reproduction: ls shell swallows errors ====================

        @Test
        void ls_nonExistentPath_shouldReturnFail() {
            LocalShellSandboxFilesystem fs = new LocalShellSandboxFilesystem();
            LsResult r = fs.ls(RT, "/this/path/does/not/exist/at/all");
            assertFalse(r.isSuccess(), "ls on non-existent path should fail");
        }

        @Test
        void ls_filePath_shouldReturnFail() throws IOException {
            Path file = tmpDir.resolve("file.txt");
            Files.writeString(file, "content");
            LocalShellSandboxFilesystem fs = new LocalShellSandboxFilesystem();
            LsResult r = fs.ls(RT, file.toAbsolutePath().toString());
            assertFalse(r.isSuccess(), "ls on a file path should fail");
        }
    }

    @Nested
    @EnabledOnOs({OS.LINUX, OS.MAC})
    class LocalShellWriteIntegrationTests {

        @TempDir Path tmpDir;

        @Test
        void writeUploadsOverExclusivePlaceholderAndRejectsSecondWrite() throws IOException {
            LocalShellSandboxFilesystem fs = new LocalShellSandboxFilesystem();
            Path path = tmpDir.resolve("nested/claim.txt");

            WriteResult first = fs.write(RT, path.toString(), "first");
            WriteResult second = fs.write(RT, path.toString(), "second");

            assertTrue(first.isSuccess());
            assertFalse(second.isSuccess());
            assertEquals("first", Files.readString(path));
        }

        @Test
        void emptyWriteKeepsExclusivePlaceholderWithoutUploading() throws IOException {
            LocalShellSandboxFilesystem fs = new LocalShellSandboxFilesystem(true);
            Path path = tmpDir.resolve("claim.marker");

            WriteResult first = fs.write(RT, path.toString(), "");

            assertTrue(first.isSuccess());
            assertTrue(Files.exists(path));
            assertEquals(0L, Files.size(path));
            assertTrue(fs.write(RT, path.toString(), "").isAlreadyExists());
        }

        @Test
        void writeFailureForNonWritableDirectoryIsNotReportedAsAlreadyExists() throws IOException {
            LocalShellSandboxFilesystem fs = new LocalShellSandboxFilesystem();
            Path directory = Files.createDirectory(tmpDir.resolve("read-only"));
            Files.setPosixFilePermissions(
                    directory,
                    Set.of(PosixFilePermission.OWNER_READ, PosixFilePermission.OWNER_EXECUTE));
            Assumptions.assumeFalse(
                    Files.isWritable(directory),
                    "POSIX permissions are not enforced for this user");

            try {
                WriteResult result =
                        fs.write(RT, directory.resolve("new-file").toString(), "content");

                assertFalse(result.isSuccess());
                assertFalse(result.error().contains("already exists"));
            } finally {
                Files.setPosixFilePermissions(
                        directory,
                        Set.of(
                                PosixFilePermission.OWNER_READ,
                                PosixFilePermission.OWNER_WRITE,
                                PosixFilePermission.OWNER_EXECUTE));
            }
        }
    }

    // ================================================================
    // Test helpers
    // ================================================================

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
            if (command.startsWith("if [ ! -e ") && command.contains("stat -c")) {
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

    private static final class SuccessfulWriteFilesystem extends BaseSandboxFilesystem {

        private String uploadedContent;

        @Override
        public String id() {
            return "successful-write";
        }

        @Override
        public ExecuteResponse execute(
                RuntimeContext runtimeContext, String command, Integer timeoutSeconds) {
            return new ExecuteResponse("", 0, false);
        }

        @Override
        public List<FileUploadResponse> uploadFiles(
                RuntimeContext runtimeContext, List<Map.Entry<String, byte[]>> files) {
            Map.Entry<String, byte[]> file = files.get(0);
            uploadedContent = new String(file.getValue(), StandardCharsets.UTF_8);
            return List.of(FileUploadResponse.success(file.getKey()));
        }

        @Override
        public List<FileDownloadResponse> downloadFiles(
                RuntimeContext runtimeContext, List<String> paths) {
            return List.of();
        }
    }

    private static final class FailedUploadCleanupFilesystem extends BaseSandboxFilesystem {

        @Override
        public String id() {
            return "failed-upload-cleanup";
        }

        @Override
        public ExecuteResponse execute(
                RuntimeContext runtimeContext, String command, Integer timeoutSeconds) {
            return command.startsWith("mkdir -p ")
                    ? new ExecuteResponse("", 0, false)
                    : new ExecuteResponse("cleanup denied", 1, false);
        }

        @Override
        public List<FileUploadResponse> uploadFiles(
                RuntimeContext runtimeContext, List<Map.Entry<String, byte[]>> files) {
            return List.of(FileUploadResponse.fail(files.get(0).getKey(), "upload denied"));
        }

        @Override
        public List<FileDownloadResponse> downloadFiles(
                RuntimeContext runtimeContext, List<String> paths) {
            return List.of();
        }
    }

    /**
     * execute() always returns the fixed response, standing in for any execution-layer outcome
     * (successful or failing) without a live sandbox.
     */
    private static final class FixedResponseFilesystem extends BaseSandboxFilesystem {

        private final ExecuteResponse response;

        FixedResponseFilesystem(ExecuteResponse response) {
            this.response = response;
        }

        @Override
        public String id() {
            return "fixed-response";
        }

        @Override
        public ExecuteResponse execute(
                RuntimeContext runtimeContext, String command, Integer timeoutSeconds) {
            return response;
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

        private final boolean failUploads;

        private LocalShellSandboxFilesystem() {
            this(false);
        }

        private LocalShellSandboxFilesystem(boolean failUploads) {
            this.failUploads = failUploads;
        }

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
            if (failUploads) {
                return List.of(FileUploadResponse.fail(files.get(0).getKey(), "unexpected upload"));
            }
            return files.stream()
                    .map(
                            file -> {
                                try {
                                    Files.write(Path.of(file.getKey()), file.getValue());
                                    return FileUploadResponse.success(file.getKey());
                                } catch (IOException e) {
                                    return FileUploadResponse.fail(file.getKey(), e.getMessage());
                                }
                            })
                    .toList();
        }

        @Override
        public List<FileDownloadResponse> downloadFiles(
                RuntimeContext runtimeContext, List<String> paths) {
            return List.of();
        }
    }
}
