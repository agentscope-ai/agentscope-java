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
package io.agentscope.core.tool.builtin;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.agentscope.core.message.TextBlock;
import io.agentscope.core.message.ToolResultBlock;
import io.agentscope.core.message.ToolUseBlock;
import io.agentscope.core.permission.PermissionBehavior;
import io.agentscope.core.permission.PermissionContext;
import io.agentscope.core.permission.PermissionDecision;
import io.agentscope.core.permission.PermissionMode;
import io.agentscope.core.tool.ToolCallParam;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIf;
import org.junit.jupiter.api.io.TempDir;

class GrepTest {

    @TempDir Path tempDir;
    private Grep grep;
    private PermissionContext defaultCtx;

    @BeforeAll
    static void announce() {
        if (!ripgrepAvailable()) {
            System.err.println(
                    "[GrepTest] ripgrep ('rg') is not on PATH; execution tests will be skipped.");
        }
    }

    @BeforeEach
    void setUp() {
        grep = new Grep();
        defaultCtx = PermissionContext.builder().mode(PermissionMode.DEFAULT).build();
    }

    static boolean ripgrepAvailable() {
        try {
            Process p = new ProcessBuilder("rg", "--version").redirectErrorStream(true).start();
            return p.waitFor() == 0;
        } catch (IOException | InterruptedException e) {
            if (e instanceof InterruptedException) {
                Thread.currentThread().interrupt();
            }
            return false;
        }
    }

    private ToolCallParam param(Map<String, Object> input) {
        ToolUseBlock use = ToolUseBlock.builder().id("call-1").name("Grep").input(input).build();
        return ToolCallParam.builder().toolUseBlock(use).input(input).build();
    }

    private Path seed(String relative, String content) throws IOException {
        Path p = tempDir.resolve(relative);
        Files.createDirectories(p.getParent());
        Files.writeString(p, content, StandardCharsets.UTF_8);
        return p;
    }

    @Nested
    class CheckPermissions {

        @Test
        void alwaysPassthrough() {
            PermissionDecision d =
                    grep.checkPermissions(Map.of("pattern", "foo"), defaultCtx).block();
            assertEquals(PermissionBehavior.PASSTHROUGH, d.getBehavior());
        }
    }

    @Nested
    class Configuration {

        @Test
        void exposesNameAndSafetyFlags() {
            assertEquals("Grep", grep.getName());
            assertTrue(grep.isReadOnly());
            assertTrue(grep.isConcurrencySafe());
        }
    }

    @Nested
    class Validation {

        @Test
        void rejectsBlankPattern() {
            ToolResultBlock r = grep.callAsync(param(Map.of("pattern", ""))).block();
            TextBlock text = (TextBlock) r.getOutput().get(0);
            assertTrue(text.getText().startsWith("Error"));
        }

        @Test
        void rejectsInvalidOutputMode() {
            ToolResultBlock r =
                    grep.callAsync(param(Map.of("pattern", "foo", "output_mode", "unknown")))
                            .block();
            TextBlock text = (TextBlock) r.getOutput().get(0);
            assertTrue(text.getText().startsWith("Error"));
        }

        @Test
        void rejectsZeroHeadLimit() {
            ToolResultBlock r =
                    grep.callAsync(param(Map.of("pattern", "foo", "head_limit", 0))).block();
            TextBlock text = (TextBlock) r.getOutput().get(0);
            assertTrue(text.getText().startsWith("Error"));
        }

        @Test
        void rejectsNegativeOffset() {
            ToolResultBlock r =
                    grep.callAsync(param(Map.of("pattern", "foo", "offset", -1))).block();
            TextBlock text = (TextBlock) r.getOutput().get(0);
            assertTrue(text.getText().startsWith("Error"));
        }
    }

    @Nested
    @EnabledIf("io.agentscope.core.tool.builtin.GrepTest#ripgrepAvailable")
    class Execution {

        @Test
        void contentModeFindsMatches() throws IOException {
            seed("a.txt", "alpha\nbeta\ngamma\n");
            seed("b.txt", "delta\nbeta two\n");
            ToolResultBlock r =
                    grep.callAsync(param(Map.of("pattern", "beta", "path", tempDir.toString())))
                            .block();
            TextBlock text = (TextBlock) r.getOutput().get(0);
            assertFalse(text.getText().startsWith("Error"));
            assertTrue(text.getText().contains("beta"));
        }

        @Test
        void filesWithMatchesMode() throws IOException {
            seed("c.txt", "needle here\n");
            seed("d.txt", "nothing\n");
            ToolResultBlock r =
                    grep.callAsync(
                                    param(
                                            Map.of(
                                                    "pattern",
                                                    "needle",
                                                    "path",
                                                    tempDir.toString(),
                                                    "output_mode",
                                                    "files_with_matches")))
                            .block();
            TextBlock text = (TextBlock) r.getOutput().get(0);
            assertTrue(text.getText().contains("c.txt"));
            assertFalse(text.getText().contains("d.txt"));
        }

        @Test
        void countMode() throws IOException {
            seed("e.txt", "x\nx\nx\n");
            ToolResultBlock r =
                    grep.callAsync(
                                    param(
                                            Map.of(
                                                    "pattern",
                                                    "x",
                                                    "path",
                                                    tempDir.toString(),
                                                    "output_mode",
                                                    "count")))
                            .block();
            TextBlock text = (TextBlock) r.getOutput().get(0);
            assertTrue(text.getText().contains("3"));
        }

        @Test
        void caseInsensitiveFlag() throws IOException {
            seed("f.txt", "Hello World\n");
            ToolResultBlock r =
                    grep.callAsync(
                                    param(
                                            Map.of(
                                                    "pattern",
                                                    "hello",
                                                    "path",
                                                    tempDir.toString(),
                                                    "-i",
                                                    true)))
                            .block();
            TextBlock text = (TextBlock) r.getOutput().get(0);
            assertTrue(text.getText().toLowerCase().contains("hello"));
        }

        @Test
        void emptyResultMessage() throws IOException {
            seed("g.txt", "nothing relevant\n");
            ToolResultBlock r =
                    grep.callAsync(
                                    param(
                                            Map.of(
                                                    "pattern",
                                                    "zzzzunlikelyzzzz",
                                                    "path",
                                                    tempDir.toString())))
                            .block();
            TextBlock text = (TextBlock) r.getOutput().get(0);
            assertTrue(text.getText().contains("No matches"));
        }

        @Test
        void respectsGlobFilter() throws IOException {
            seed("only.java", "needle\n");
            seed("skip.txt", "needle\n");
            ToolResultBlock r =
                    grep.callAsync(
                                    param(
                                            Map.of(
                                                    "pattern",
                                                    "needle",
                                                    "path",
                                                    tempDir.toString(),
                                                    "glob",
                                                    "*.java",
                                                    "output_mode",
                                                    "files_with_matches")))
                            .block();
            TextBlock text = (TextBlock) r.getOutput().get(0);
            assertTrue(text.getText().contains("only.java"));
            assertFalse(text.getText().contains("skip.txt"));
        }
    }

    @Nested
    class Smoke {

        @Test
        void grepInstanceConstructibleWithoutRipgrep() {
            // Whether or not rg is installed, instantiation must succeed and metadata exposed.
            assertNotNull(new Grep());
        }
    }
}
