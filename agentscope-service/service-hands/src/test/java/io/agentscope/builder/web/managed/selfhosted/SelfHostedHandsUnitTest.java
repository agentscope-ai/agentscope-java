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
package io.agentscope.builder.web.managed.selfhosted;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Hands-plane slice of the self-hosted hands tests: the worker-side tool executor plus the
 * shared input stager. The data-plane pieces (pending tool tracking, tool-result mapping, tool
 * schemas) are covered by {@code SelfHostedHandsDataPlaneTest} in {@code service-dataplane}.
 */
class SelfHostedHandsUnitTest {

    @TempDir Path tempDir;

    @Test
    void localExecutorRunsShellAndFilesystemTools() throws Exception {
        LocalHandsToolExecutor executor = new LocalHandsToolExecutor(tempDir);
        LocalHandsToolExecutor.ToolExecResult write =
                executor.execute(
                        "write_file", Map.of("path", "hello.txt", "content", "hello world"));
        assertThat(write.error()).isFalse();

        LocalHandsToolExecutor.ToolExecResult read =
                executor.execute("read_file", Map.of("path", "hello.txt"));
        assertThat(read.error()).isFalse();
        assertThat(read.content()).contains("hello world");

        LocalHandsToolExecutor.ToolExecResult shell =
                executor.execute("execute", Map.of("command", "echo from-shell"));
        assertThat(shell.error()).isFalse();
        assertThat(shell.content()).contains("from-shell");
    }

    @Test
    void grepOutputIsBoundedEvenWhenMatchingLinesAreLarge() throws Exception {
        Files.writeString(
                tempDir.resolve("large.txt"), ("match" + "x".repeat(5000) + "\n").repeat(200));
        var result =
                new LocalHandsToolExecutor(tempDir)
                        .execute("grep_files", Map.of("pattern", "match"));
        assertThat(result.error()).isFalse();
        assertThat(result.content().length()).isLessThanOrEqualTo(200000);
        assertThat(result.content()).contains("line truncated");
    }

    @Test
    void fileToolsRejectSymbolicLinksOutsideTheirWorkspace() throws Exception {
        Path workspace = Files.createDirectory(tempDir.resolve("workspace"));
        Path outside = tempDir.resolve("outside.txt");
        Files.writeString(outside, "outside-content");
        try {
            Files.createSymbolicLink(workspace.resolve("link"), outside);
        } catch (UnsupportedOperationException | IOException unavailable) {
            Assumptions.assumeTrue(false, "Filesystem cannot create symbolic links");
        }
        var result =
                new LocalHandsToolExecutor(workspace).execute("read_file", Map.of("path", "link"));
        assertThat(result.error()).isTrue();
        assertThat(result.content()).contains("symbolic link").doesNotContain("outside-content");
    }

    @Test
    void sessionInputStagerCopiesLocalFiles() throws Exception {
        Path src = tempDir.resolve("src.txt");
        Files.writeString(src, "staged-content", StandardCharsets.UTF_8);
        Path work = tempDir.resolve("work");
        Files.createDirectories(work);

        Map<String, Object> metadata =
                SessionInputStager.metadataFromResources(
                        List.of(Map.of("type", "file", "path", src.toString())));
        SessionInputStager.stage(metadata, work);

        Path staged = work.resolve("inputs").resolve("src.txt");
        assertThat(staged).exists();
        assertThat(Files.readString(staged)).isEqualTo("staged-content");
    }
}
