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
import io.agentscope.harness.agent.filesystem.model.GrepResult;
import io.agentscope.harness.agent.filesystem.model.LsResult;
import io.agentscope.harness.agent.filesystem.model.ReadResult;
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

    /** Mirrors how {@code edit()} hands user content to the sandbox. */
    private static String base64(String value) {
        return java.util.Base64.getEncoder().encodeToString(value.getBytes(StandardCharsets.UTF_8));
    }

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

        @Test
        void edit_native_success_encodesParamsAndRunsInlineScript() {
            EditSpyFilesystem fs = new EditSpyFilesystem();
            fs.withExecuteResult(new ExecuteResponse("__RESULT__{\"count\": 1}\n", 0, false));

            EditResult result = fs.edit(RT, "/workspace/f.txt", "World", "Java", false);

            assertTrue(result.isSuccess());
            assertEquals("/workspace/f.txt", result.path());
            assertEquals(1, result.occurrences());
            // no staging directory and no file transfer at all
            assertTrue(fs.uploadedFiles.isEmpty());
            assertTrue(fs.lastCommand.contains("python3 - "));
            assertTrue(fs.lastCommand.contains("'/workspace/f.txt'"));
            assertTrue(fs.lastCommand.contains("'" + base64("World") + "'"));
            assertTrue(fs.lastCommand.contains("'" + base64("Java") + "'"));
            assertTrue(fs.lastCommand.contains("__AGENTSCOPE_EDIT_PY__"));
            // python's exit status must reach Java (else 127 never surfaces as a fallback)
            assertTrue(
                    fs.lastCommand.contains("exit $__ec"),
                    "exit code passthrough required: " + fs.lastCommand);
        }

        // ==================== Bug reproduction: edit program collapsed into one line
        // (#2571) ====================

        @Test
        void edit_generatedPythonProgram_isSeparatedByRealLineFeeds() {
            FakeSandboxFilesystem filesystem = new FakeSandboxFilesystem();

            filesystem.edit(RT, "/workspace/file.txt", "old", "new", false);

            String cmd = filesystem.lastCommand;
            assertTrue(
                    cmd.startsWith("python3 - "),
                    "edit should drive the replacement through a python3 program");
            // The regression was a program collapsed onto one line, so the invariant is that
            // statements are separated by real line feeds. A backslash-n may still appear inside
            // the program's own string literals (the CRLF normalizer), which is harmless.
            assertTrue(
                    cmd.contains("import base64, os, sys, json, uuid\ntarget"),
                    "the python program must be separated by real line feeds");
            assertTrue(
                    cmd.contains("\ncount = text.count(old)"),
                    "every statement must be on its own line, not joined by an escape");
        }

        @Test
        void edit_executeFailure_shouldFailWithCause() {
            EditResult result =
                    new FixedResponseFilesystem(sandboxRequestFailed())
                            .edit(RT, "/workspace/file.txt", "old", "new", false);

            assertFalse(result.isSuccess(), "edit should fail when the command never ran");
            assertTrue(result.error().contains("status=504"), "error should carry the cause");
        }

        @Test
        void edit_executeFailure_nullOutput_shouldFailWithExitCodeFallback() {
            EditResult result =
                    new FixedResponseFilesystem(new ExecuteResponse(null, -1, false))
                            .edit(RT, "/workspace/file.txt", "old", "new", false);

            assertFalse(result.isSuccess(), "edit should fail when the command never ran");
            assertTrue(
                    result.error().contains("exit code -1"),
                    "error should fall back to the exit code when no diagnostic output exists");
        }

        @Test
        void edit_native_writeFailedDetailWithErrorLikePath_notMisclassified() {
            EditSpyFilesystem fs = new EditSpyFilesystem();
            fs.withExecuteResult(
                    new ExecuteResponse(
                            "__RESULT__{\"error\": \"write_failed\", \"detail\": \"[Errno 13]"
                                    + " '/ws/string_not_found/x'\"}\n",
                            0,
                            false));
            // write_failed now degrades to transfer; the path name must not be mistaken
            // for a string_not_found error, so the fallback must still run and report
            // its own (transfer) outcome.
            fs.withDownloadResult(
                    List.of(FileDownloadResponse.fail("/ws/string_not_found/x", "nope")));

            EditResult result = fs.edit(RT, "/ws/string_not_found/x", "a", "b", false);

            assertFalse(result.isSuccess());
            assertFalse(result.error().contains("String not found in file"));
            assertTrue(fs.downloadedPaths.contains("/ws/string_not_found/x"));
        }

        @Test
        void edit_native_writeFailed_fallsBackToTransfer() {
            EditSpyFilesystem fs = new EditSpyFilesystem();
            fs.withExecuteResult(
                    new ExecuteResponse(
                            "__RESULT__{\"error\": \"write_failed\", \"detail\": \"[Errno 13]\"}\n",
                            0,
                            false));
            fs.withDownloadResult(
                    List.of(
                            FileDownloadResponse.success(
                                    "/workspace/f.txt", "Hello World!".getBytes())));

            EditResult result = fs.edit(RT, "/workspace/f.txt", "World", "Java", false);

            assertTrue(result.isSuccess(), "write_failed should degrade: " + result.error());
            assertEquals(1, result.occurrences());
            assertTrue(fs.downloadedPaths.contains("/workspace/f.txt"));
            assertEquals(
                    "Hello Java!",
                    new String(fs.uploadedFiles.get(0).getValue(), StandardCharsets.UTF_8));
        }

        @Test
        void edit_native_multilineParams_enterCommandOnlyBase64Encoded() {
            EditSpyFilesystem fs = new EditSpyFilesystem();
            fs.withExecuteResult(new ExecuteResponse("__RESULT__{\"count\": 1}\n", 0, false));
            String oldStr = "line2\"quote\\backslash\nline2b";
            String newStr = "replaced\"line\\here\nnewline";

            EditResult result = fs.edit(RT, "/workspace/f.txt", oldStr, newStr, false);

            assertTrue(result.isSuccess());
            assertTrue(
                    fs.lastCommand.contains("'" + base64(oldStr) + "'"),
                    "old should be present base64-encoded");
            assertTrue(
                    fs.lastCommand.contains("'" + base64(newStr) + "'"),
                    "new should be present base64-encoded");
            // raw multiline/quoted content must not appear: it would break the single-quoted
            // argument, and a newline would split the command into extra lines.
            assertFalse(
                    fs.lastCommand.contains(oldStr),
                    "raw user content must not leak into the command string");
            assertFalse(fs.lastCommand.contains("\n" + newStr));
        }

        @Test
        void edit_argumentsTooLarge_isRejectedWithoutExecuting() {
            EditSpyFilesystem fs = new EditSpyFilesystem();
            fs.withExecuteResult(new ExecuteResponse("__RESULT__{\"count\": 1}\n", 0, false));
            // 100 KiB of text base64-encodes to ~137 KiB, past the 112 KiB command budget and
            // also past the kernel's 128 KiB single-argument limit.
            String huge = "a".repeat(100 * 1024);

            EditResult result = fs.edit(RT, "/workspace/f.txt", "World", huge, false);

            assertFalse(result.isSuccess());
            assertTrue(result.error().contains("too large"));
            assertTrue(result.error().contains("Split the edit"));
            assertEquals(0, fs.executeCalls, "oversized arguments must not reach the sandbox");
            assertTrue(fs.uploadedFiles.isEmpty());
        }

        @Test
        void edit_native_stringNotFound_mapsError() {
            EditSpyFilesystem fs = new EditSpyFilesystem();
            fs.withExecuteResult(
                    new ExecuteResponse("__RESULT__{\"error\": \"string_not_found\"}\n", 0, false));

            EditResult result = fs.edit(RT, "/workspace/f.txt", "nonexistent", "new", false);

            assertFalse(result.isSuccess());
            assertTrue(result.error().contains("String not found"));
        }

        @Test
        void edit_native_multipleOccurrences_mapsCount() {
            EditSpyFilesystem fs = new EditSpyFilesystem();
            fs.withExecuteResult(
                    new ExecuteResponse(
                            "__RESULT__{\"error\": \"multiple_occurrences\", \"count\": 3}\n",
                            0,
                            false));

            EditResult result = fs.edit(RT, "/workspace/f.txt", "foo", "x", false);

            assertFalse(result.isSuccess());
            assertTrue(result.error().contains("3 times"));
        }

        @Test
        void edit_transferPathReuploaded_contentAfterFallback() {
            EditSpyFilesystem fs = new EditSpyFilesystem();
            fs.withExecuteResult(new ExecuteResponse("sh: 1: python3: not found\n", 127, false));
            fs.withDownloadResult(
                    List.of(
                            FileDownloadResponse.success(
                                    "/workspace/f.txt", "Hello World!".getBytes())));

            EditResult result = fs.edit(RT, "/workspace/f.txt", "World", "Java", false);

            assertTrue(result.isSuccess(), "fallback should succeed: " + result.error());
            // the fallback re-uploads exactly one file: the edited content
            assertEquals(1, fs.uploadedFiles.size());
            assertEquals(
                    "Hello Java!",
                    new String(fs.uploadedFiles.get(0).getValue(), StandardCharsets.UTF_8));
        }

        @Test
        void edit_emptyOldString_failsFast() {
            EditSpyFilesystem fs = new EditSpyFilesystem();

            EditResult result = fs.edit(RT, "/workspace/f.txt", "", "new", false);

            assertFalse(result.isSuccess());
            assertTrue(result.error().contains("oldString must not be null or empty"));
            assertTrue(fs.uploadedFiles.isEmpty());
        }

        @Test
        void edit_pythonMissing_fallsBackToTransfer() {
            EditSpyFilesystem fs = new EditSpyFilesystem();
            fs.withExecuteResult(new ExecuteResponse("sh: 1: python3: not found\n", 127, false));
            fs.withDownloadResult(
                    List.of(
                            FileDownloadResponse.success(
                                    "/workspace/f.txt", "Hello World!".getBytes())));

            EditResult result = fs.edit(RT, "/workspace/f.txt", "World", "Java", false);

            assertTrue(result.isSuccess(), "fallback should succeed: " + result.error());
            assertEquals(1, result.occurrences());
            assertTrue(fs.downloadedPaths.contains("/workspace/f.txt"));
            assertEquals(
                    "Hello Java!",
                    new String(fs.uploadedFiles.get(0).getValue(), StandardCharsets.UTF_8));
        }

        @Test
        void edit_pythonMissing_transferDownloadFails_reportsReadFailureNotMissingFile() {
            EditSpyFilesystem fs = new EditSpyFilesystem();
            fs.withExecuteResult(new ExecuteResponse("python3: command not found", 127, false));
            fs.withDownloadResult(List.of());

            EditResult result = fs.edit(RT, "/workspace/missing.txt", "old", "new", false);

            assertFalse(result.isSuccess());
            // a failed download is not evidence that the file is absent
            assertFalse(result.error().contains("not found"));
            assertTrue(result.error().contains("Could not read file"));
        }

        @Test
        void edit_pythonMissing_transferDownloadError_carriesCause() {
            EditSpyFilesystem fs = new EditSpyFilesystem();
            fs.withExecuteResult(new ExecuteResponse("python3: command not found", 127, false));
            fs.withDownloadResult(
                    List.of(FileDownloadResponse.fail("/workspace/f.txt", "permission denied")));

            EditResult result = fs.edit(RT, "/workspace/f.txt", "old", "new", false);

            assertFalse(result.isSuccess());
            assertTrue(result.error().contains("Could not read file"));
            assertTrue(
                    result.error().contains("permission denied"), "underlying cause must survive");
        }

        @Test
        void edit_native_statFailure_mapsToWriteFailedNotRawTraceback() {
            // A target that vanishes between the script's read and its stat used to raise an
            // uncaught OSError, so no __RESULT__ line was printed and Java reported
            // "unexpected server response" instead of a mapped error.
            EditSpyFilesystem fs = new EditSpyFilesystem();
            fs.withExecuteResult(
                    new ExecuteResponse(
                            "Traceback (most recent call last):\n  FileNotFoundError: '/gone.txt'"
                                    + "\n__RESULT__{\"error\": \"write_failed\", \"detail\":"
                                    + " \"[Errno 2] No such file or directory\"}\n",
                            0,
                            false));
            fs.withDownloadResult(
                    List.of(FileDownloadResponse.success("/gone.txt", "Hello".getBytes())));

            EditResult result = fs.edit(RT, "/gone.txt", "Hello", "Hi", false);

            // write_failed is treated as "target untouched", so the transfer path retries
            assertTrue(result.isSuccess(), "write_failed should degrade: " + result.error());
            assertEquals(1, result.occurrences());
        }

        @Test
        void edit_pythonMissing_transferStringNotFound() {
            EditSpyFilesystem fs = new EditSpyFilesystem();
            fs.withExecuteResult(new ExecuteResponse("sh: 1: python3: not found\n", 127, false));
            fs.withDownloadResult(
                    List.of(
                            FileDownloadResponse.success(
                                    "/workspace/f.txt", "hello world".getBytes())));

            EditResult result = fs.edit(RT, "/workspace/f.txt", "nonexistent", "new", false);

            assertFalse(result.isSuccess());
            assertTrue(result.error().contains("String not found"));
            // transfer downloaded and found no match, so nothing was re-uploaded
            assertTrue(fs.uploadedFiles.isEmpty());
        }

        @Test
        void edit_pythonMissing_transferMultipleOccurrences() {
            EditSpyFilesystem fs = new EditSpyFilesystem();
            fs.withExecuteResult(new ExecuteResponse("python3: command not found", 127, false));
            fs.withDownloadResult(
                    List.of(
                            FileDownloadResponse.success(
                                    "/workspace/f.txt", "foo bar foo".getBytes())));

            EditResult result = fs.edit(RT, "/workspace/f.txt", "foo", "x", false);

            assertFalse(result.isSuccess());
            assertTrue(result.error().contains("appears"));
        }

        @Test
        void edit_pythonMissing_transferReplaceAll() {
            EditSpyFilesystem fs = new EditSpyFilesystem();
            fs.withExecuteResult(new ExecuteResponse("sh: 1: python3: not found\n", 127, false));
            fs.withDownloadResult(
                    List.of(
                            FileDownloadResponse.success(
                                    "/workspace/f.txt", "a b a b a".getBytes())));

            EditResult result = fs.edit(RT, "/workspace/f.txt", "a", "x", true);

            assertTrue(result.isSuccess());
            assertEquals(3, result.occurrences());
            assertEquals(
                    "x b x b x",
                    new String(fs.uploadedFiles.get(0).getValue(), StandardCharsets.UTF_8));
        }

        @Test
        void edit_pythonMissing_transferEmptyFile() {
            EditSpyFilesystem fs = new EditSpyFilesystem();
            fs.withExecuteResult(new ExecuteResponse("sh: 1: python3: not found\n", 127, false));
            fs.withDownloadResult(
                    List.of(FileDownloadResponse.success("/workspace/f.txt", new byte[0])));

            EditResult result = fs.edit(RT, "/workspace/f.txt", "old", "new", false);

            assertFalse(result.isSuccess());
            assertTrue(result.error().contains("empty"));
        }

        @Test
        void edit_pythonMissing_transferUploadFails() {
            EditSpyFilesystem fs = new EditSpyFilesystem();
            fs.withExecuteResult(new ExecuteResponse("sh: 1: python3: not found\n", 127, false));
            fs.withDownloadResult(
                    List.of(
                            FileDownloadResponse.success(
                                    "/workspace/f.txt", "hello world".getBytes())));
            fs.withTransferUploadFailure("disk full");

            EditResult result = fs.edit(RT, "/workspace/f.txt", "world", "Java", false);

            assertFalse(result.isSuccess());
            assertTrue(result.error().contains("disk full"));
        }

        @Test
        void edit_pythonMissing_transferInvalidUtf8_failsInsteadOfReencoding() {
            EditSpyFilesystem fs = new EditSpyFilesystem();
            fs.withExecuteResult(new ExecuteResponse("sh: 1: python3: not found\n", 127, false));
            // 0xFF is never valid UTF-8 (a legacy-encoding byte); the fallback must
            // refuse rather than replace it and silently rewrite the file.
            fs.withDownloadResult(
                    List.of(
                            FileDownloadResponse.success(
                                    "/workspace/f.txt", new byte[] {'a', (byte) 0xFF, 'b'})));

            EditResult result = fs.edit(RT, "/workspace/f.txt", "a", "x", false);

            assertFalse(result.isSuccess());
            assertTrue(result.error().contains("not valid UTF-8"));
            // nothing was re-uploaded
            assertTrue(fs.uploadedFiles.isEmpty());
        }

        @Test
        void edit_pythonMissing_stderrOnly_exitZero_fallsBack() {
            // Backends that report "python3: not found" on stderr while the trailing
            // shell commands keep the overall exit code at 0 (before exit passthrough,
            // or when stderr is the only signal available).
            EditSpyFilesystem fs = new EditSpyFilesystem();
            fs.withExecuteResult(new ExecuteResponse("sh: 1: python3: not found\n", 0, false));
            fs.withDownloadResult(
                    List.of(
                            FileDownloadResponse.success(
                                    "/workspace/f.txt", "Hello World!".getBytes())));

            EditResult result = fs.edit(RT, "/workspace/f.txt", "World", "Java", false);

            assertTrue(
                    result.isSuccess(),
                    "stderr-based detection should fall back: " + result.error());
            assertEquals(1, result.occurrences());
        }

        @Test
        void edit_nullNewString_isRejected_notTreatedAsDeletion() {
            EditSpyFilesystem fs = new EditSpyFilesystem();

            EditResult result = fs.edit(RT, "/workspace/f.txt", "World", null, false);

            assertFalse(result.isSuccess());
            assertTrue(result.error().contains("must not be null"));
            assertTrue(fs.uploadedFiles.isEmpty(), "nothing should be staged for a rejected call");
        }

        @Test
        void edit_emptyNewString_deletesWithNormalSemantics() {
            EditSpyFilesystem fs = new EditSpyFilesystem();
            fs.withExecuteResult(new ExecuteResponse("__RESULT__{\"count\": 2}\n", 0, false));

            EditResult result = fs.edit(RT, "/workspace/f.txt", "World", "", false);

            assertTrue(result.isSuccess());
            assertEquals(2, result.occurrences());
            // an empty newString encodes to an empty argv entry: a real deletion
            assertTrue(fs.lastCommand.contains("''"));
        }

        @Test
        void edit_pythonNotExecutable_exit126_fallsBackToTransfer() {
            // 126 = python3 found but not executable; without this the call would surface as
            // "unexpected server response" instead of degrading to the transfer path.
            EditSpyFilesystem fs = new EditSpyFilesystem();
            fs.withExecuteResult(
                    new ExecuteResponse("sh: 1: python3: Permission denied\n", 126, false));
            fs.withDownloadResult(
                    List.of(
                            FileDownloadResponse.success(
                                    "/workspace/f.txt", "Hello World!".getBytes())));

            EditResult result = fs.edit(RT, "/workspace/f.txt", "World", "Java", false);

            assertTrue(result.isSuccess(), "126 should fall back: " + result.error());
            assertEquals(1, result.occurrences());
        }

        @Test
        void edit_malformedResultJson_failsWithRawPayload() {
            EditSpyFilesystem fs = new EditSpyFilesystem();
            fs.withExecuteResult(new ExecuteResponse("__RESULT__not-json{{", 0, false));

            EditResult result = fs.edit(RT, "/workspace/f.txt", "World", "Java", false);

            assertFalse(result.isSuccess());
            assertTrue(result.error().contains("not-json{{"));
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
            // an ambiguous replacement must leave the file untouched
            assertEquals("foo bar foo baz foo", Files.readString(file));
        }

        @Test
        void edit_pathContainingHeredocDelimiter_doesNotTruncateCommand() throws IOException {
            // A newline inside the single-quoted target argument stays part of the argument: it
            // does not terminate the command line, and the heredoc body is only read afterwards.
            // So a path whose own line equals the delimiter cannot close the heredoc early.
            Path dir = Files.createDirectory(tmpDir.resolve("dir__AGENTSCOPE_EDIT_PY__"));
            Path file = dir.resolve("f.txt");
            Files.writeString(file, "Hello World");

            LocalShellSandboxFilesystem fs = new LocalShellSandboxFilesystem();
            EditResult result = fs.edit(RT, file.toString(), "World", "Java", false);

            assertTrue(
                    result.isSuccess(),
                    "delimiter-shaped path should still edit: " + result.error());
            assertEquals("Hello Java", Files.readString(file));
        }

        @Test
        void edit_crlfFile_matchesLfPattern() throws IOException {
            Path file = tmpDir.resolve("crlf.txt");
            Files.writeString(file, "alpha\r\nbeta\r\ngamma\r\n");

            LocalShellSandboxFilesystem fs = new LocalShellSandboxFilesystem();
            EditResult result = fs.edit(RT, file.toString(), "beta", "BETA", false);

            assertTrue(result.isSuccess(), "CRLF file must match an LF pattern: " + result.error());
            assertEquals(1, result.occurrences());
            assertEquals("alpha\nBETA\ngamma\n", Files.readString(file));
        }

        @Test
        void edit_crlf_parityBetweenNativeAndFallback() throws IOException {
            // The native path and the transfer fallback must accept and reject the same edits;
            // otherwise an edit succeeds only when python3 happens to be present.
            Path nativeFile = tmpDir.resolve("crlf-native.txt");
            Files.writeString(nativeFile, "one\r\ntwo\r\n");
            EditResult nativeResult =
                    new LocalShellSandboxFilesystem()
                            .edit(RT, nativeFile.toString(), "two", "2", false);

            Path fallbackFile = tmpDir.resolve("crlf-fallback.txt");
            Files.writeString(fallbackFile, "one\r\ntwo\r\n");
            EditResult fallbackResult =
                    new LocalShellFallbackFilesystem(false)
                            .edit(RT, fallbackFile.toString(), "two", "2", false);

            assertTrue(nativeResult.isSuccess(), "native: " + nativeResult.error());
            assertTrue(fallbackResult.isSuccess(), "fallback: " + fallbackResult.error());
            assertEquals(nativeResult.occurrences(), fallbackResult.occurrences());
            assertEquals(Files.readString(nativeFile), Files.readString(fallbackFile));
        }

        @Test
        void edit_crlf_ambiguousOccurrenceStillRejected() throws IOException {
            // The >1 guard must fire on CRLF content on the native path too, matching the
            // shared replacement semantics.
            Path file = tmpDir.resolve("crlf-multi.txt");
            Files.writeString(file, "a\r\na\r\n");

            LocalShellSandboxFilesystem fs = new LocalShellSandboxFilesystem();
            EditResult result = fs.edit(RT, file.toString(), "a", "x", false);

            assertFalse(result.isSuccess());
            assertTrue(result.error().contains("2 times"));
            assertEquals("a\r\na\r\n", Files.readString(file), "file must be untouched");
        }

        @Test
        void edit_symlinkTarget_preservesLinkAndWritesThrough() throws IOException {
            Path real = tmpDir.resolve("real.txt");
            Files.writeString(real, "Hello World");
            Path link = tmpDir.resolve("link.txt");
            Files.createSymbolicLink(link, real.getFileName());

            LocalShellSandboxFilesystem fs = new LocalShellSandboxFilesystem();
            EditResult result = fs.edit(RT, link.toString(), "World", "Java", false);

            assertTrue(result.isSuccess(), "edit through link should succeed: " + result.error());
            // the link itself is still a symlink (os.replace must not replace the link)
            assertTrue(Files.isSymbolicLink(link), "symlink must survive the edit");
            // and the content landed in the real target
            assertEquals("Hello Java", Files.readString(real));
        }

        @Test
        void edit_fallback_writeThroughUploadPreservesSymlink() throws IOException {
            // Forced transfer fallback (python3 "missing"): an exec-style backend whose
            // upload opens the path (shell redirection semantics) writes through the link.
            Path real = tmpDir.resolve("real2.txt");
            Files.writeString(real, "Hello World");
            Path link = tmpDir.resolve("link2.txt");
            Files.createSymbolicLink(link, real.getFileName());

            LocalShellFallbackFilesystem fs = new LocalShellFallbackFilesystem(false);
            EditResult result = fs.edit(RT, link.toString(), "World", "Java", false);

            assertTrue(result.isSuccess(), "fallback should succeed: " + result.error());
            assertEquals(1, result.occurrences());
            assertTrue(Files.isSymbolicLink(link), "write-through upload preserves the link");
            assertEquals("Hello Java", Files.readString(real));
        }

        @Test
        void edit_fallback_replacingUploadReplacesSymlink() throws IOException {
            // A backend whose upload replaces the path (temp+rename at filePath) breaks the
            // link — the documented divergence from the native path, owned by the backend.
            Path real = tmpDir.resolve("real3.txt");
            Files.writeString(real, "Hello World");
            Path link = tmpDir.resolve("link3.txt");
            Files.createSymbolicLink(link, real.getFileName());

            LocalShellFallbackFilesystem fs = new LocalShellFallbackFilesystem(true);
            EditResult result = fs.edit(RT, link.toString(), "World", "Java", false);

            assertTrue(result.isSuccess(), "fallback should succeed: " + result.error());
            assertFalse(Files.isSymbolicLink(link), "replacing upload replaces the link");
            assertEquals("Hello Java", Files.readString(link));
            assertEquals("Hello World", Files.readString(real), "original target untouched");
        }

        @Test
        void edit_replaceAll_occurrencesParityBetweenNativeAndFallback() throws IOException {
            // Same input through both paths must report the same count and final content,
            // so the two implementations cannot drift.
            Path nativeFile = tmpDir.resolve("parity-native.txt");
            Files.writeString(nativeFile, "a b a b a");
            EditResult nativeResult =
                    new LocalShellSandboxFilesystem()
                            .edit(RT, nativeFile.toString(), "a", "x", true);

            Path fallbackFile = tmpDir.resolve("parity-fallback.txt");
            Files.writeString(fallbackFile, "a b a b a");
            EditResult fallbackResult =
                    new LocalShellFallbackFilesystem(false)
                            .edit(RT, fallbackFile.toString(), "a", "x", true);

            assertTrue(nativeResult.isSuccess(), "native: " + nativeResult.error());
            assertTrue(fallbackResult.isSuccess(), "fallback: " + fallbackResult.error());
            assertEquals(nativeResult.occurrences(), fallbackResult.occurrences());
            assertEquals(3, fallbackResult.occurrences());
            assertEquals(Files.readString(nativeFile), Files.readString(fallbackFile));
            assertEquals("x b x b x", Files.readString(fallbackFile));
        }
    }

    // ================================================================
    // Test helpers
    // ================================================================

    private static final class EditSpyFilesystem extends BaseSandboxFilesystem {

        final List<String> downloadedPaths = new ArrayList<>();
        final List<Map.Entry<String, byte[]>> uploadedFiles = new ArrayList<>();
        String lastCommand;
        int executeCalls;
        private List<FileDownloadResponse> cannedDownload = List.of();
        private ExecuteResponse cannedExecute =
                new ExecuteResponse("__RESULT__{\"count\": 1}\n", 0, false);
        private String uploadFailure;
        private String transferUploadFailure;

        void withDownloadResult(List<FileDownloadResponse> responses) {
            this.cannedDownload = responses;
        }

        void withExecuteResult(ExecuteResponse response) {
            this.cannedExecute = response;
        }

        void withUploadFailure(String error) {
            this.uploadFailure = error;
        }

        /** Fail only non-param uploads (i.e. the transfer-phase re-upload of edited content). */
        void withTransferUploadFailure(String error) {
            this.transferUploadFailure = error;
        }

        @Override
        public String id() {
            return "edit-spy";
        }

        @Override
        public ExecuteResponse execute(
                RuntimeContext runtimeContext, String command, Integer timeoutSeconds) {
            executeCalls++;
            lastCommand = command;
            return cannedExecute;
        }

        @Override
        public List<FileUploadResponse> uploadFiles(
                RuntimeContext runtimeContext, List<Map.Entry<String, byte[]>> files) {
            uploadedFiles.addAll(files);
            List<FileUploadResponse> results = new ArrayList<>();
            for (Map.Entry<String, byte[]> entry : files) {
                if (uploadFailure != null || transferUploadFailure != null) {
                    String err = uploadFailure != null ? uploadFailure : transferUploadFailure;
                    results.add(FileUploadResponse.fail(entry.getKey(), err));
                } else {
                    results.add(FileUploadResponse.success(entry.getKey()));
                }
            }
            return results;
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
                    Path dest = Path.of(entry.getKey());
                    if (dest.getParent() != null) {
                        Files.createDirectories(dest.getParent());
                    }
                    Files.write(dest, entry.getValue());
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

    /**
     * Real-file backend that forces the transfer fallback (python3 "missing") and lets the test
     * choose the upload semantics: write-through (open the path, following a symlink) or
     * replace (remove the path first, breaking a symlink).
     */
    private static final class LocalShellFallbackFilesystem extends BaseSandboxFilesystem {

        private final boolean replaceOnUpload;

        LocalShellFallbackFilesystem(boolean replaceOnUpload) {
            this.replaceOnUpload = replaceOnUpload;
        }

        @Override
        public String id() {
            return "local-shell-no-python";
        }

        @Override
        public ExecuteResponse execute(
                RuntimeContext runtimeContext, String command, Integer timeoutSeconds) {
            if (command.startsWith("python3 ")) {
                return new ExecuteResponse("python3: command not found", 127, false);
            }
            try {
                Process p =
                        new ProcessBuilder("sh", "-c", command).redirectErrorStream(true).start();
                String output =
                        new String(p.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
                return new ExecuteResponse(output, p.waitFor(), false);
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
                    Path dest = Path.of(entry.getKey());
                    if (dest.getParent() != null) {
                        Files.createDirectories(dest.getParent());
                    }
                    if (replaceOnUpload) {
                        Files.deleteIfExists(dest);
                    }
                    Files.write(dest, entry.getValue());
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
                    results.add(
                            FileDownloadResponse.success(path, Files.readAllBytes(Path.of(path))));
                } catch (IOException e) {
                    results.add(FileDownloadResponse.fail(path, e.getMessage()));
                }
            }
            return results;
        }
    }
}
