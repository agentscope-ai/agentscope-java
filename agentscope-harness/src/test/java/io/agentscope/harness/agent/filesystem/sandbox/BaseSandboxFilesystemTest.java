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
import io.agentscope.harness.agent.filesystem.model.LsResult;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
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
            return List.of();
        }

        @Override
        public List<FileDownloadResponse> downloadFiles(
                RuntimeContext runtimeContext, List<String> paths) {
            return List.of();
        }
    }
}
