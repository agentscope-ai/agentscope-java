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
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class GlobTest {

    @TempDir Path tempDir;
    private Glob glob;
    private PermissionContext defaultCtx;

    @BeforeEach
    void setUp() {
        glob = new Glob();
        defaultCtx = PermissionContext.builder().mode(PermissionMode.DEFAULT).build();
    }

    private ToolCallParam param(Map<String, Object> input) {
        ToolUseBlock use = ToolUseBlock.builder().id("call-1").name("Glob").input(input).build();
        return ToolCallParam.builder().toolUseBlock(use).input(input).build();
    }

    private Path mk(String relative) throws IOException {
        Path p = tempDir.resolve(relative);
        Files.createDirectories(p.getParent());
        Files.writeString(p, "content", StandardCharsets.UTF_8);
        return p;
    }

    @Nested
    class CheckPermissions {

        @Test
        void alwaysPassthrough() {
            PermissionDecision d =
                    glob.checkPermissions(Map.of("pattern", "*.java"), defaultCtx).block();
            assertEquals(PermissionBehavior.PASSTHROUGH, d.getBehavior());
        }
    }

    @Nested
    class Execution {

        @Test
        void flatStarPattern() throws IOException {
            mk("a.java");
            mk("b.java");
            mk("c.txt");
            ToolResultBlock r =
                    glob.callAsync(param(Map.of("pattern", "*.java", "path", tempDir.toString())))
                            .block();
            TextBlock text = (TextBlock) r.getOutput().get(0);
            assertTrue(text.getText().contains("a.java"));
            assertTrue(text.getText().contains("b.java"));
            assertFalse(text.getText().contains("c.txt"));
        }

        @Test
        void recursiveDoubleStarPattern() throws IOException {
            mk("src/main/java/A.java");
            mk("src/test/java/B.java");
            mk("src/main/resources/x.txt");
            ToolResultBlock r =
                    glob.callAsync(
                                    param(
                                            Map.of(
                                                    "pattern",
                                                    "**/*.java",
                                                    "path",
                                                    tempDir.toString())))
                            .block();
            TextBlock text = (TextBlock) r.getOutput().get(0);
            assertTrue(text.getText().contains("A.java"));
            assertTrue(text.getText().contains("B.java"));
            assertFalse(text.getText().contains("x.txt"));
        }

        @Test
        void multiSegmentGlob() throws IOException {
            mk("src/main/java/Foo.java");
            mk("src/test/java/Bar.java");
            ToolResultBlock r =
                    glob.callAsync(
                                    param(
                                            Map.of(
                                                    "pattern",
                                                    "src/main/java/*.java",
                                                    "path",
                                                    tempDir.toString())))
                            .block();
            TextBlock text = (TextBlock) r.getOutput().get(0);
            assertTrue(text.getText().contains("Foo.java"));
            assertFalse(text.getText().contains("Bar.java"));
        }

        @Test
        void noMatchesReturnsFriendlyMessage() throws IOException {
            mk("only.txt");
            ToolResultBlock r =
                    glob.callAsync(param(Map.of("pattern", "*.java", "path", tempDir.toString())))
                            .block();
            TextBlock text = (TextBlock) r.getOutput().get(0);
            assertTrue(text.getText().contains("No files found"));
        }

        @Test
        void mtimeDescendingSort() throws IOException {
            Path older = mk("old.txt");
            Path newer = mk("new.txt");
            Files.setLastModifiedTime(
                    older, java.nio.file.attribute.FileTime.fromMillis(1_000_000));
            Files.setLastModifiedTime(
                    newer, java.nio.file.attribute.FileTime.fromMillis(2_000_000));

            ToolResultBlock r =
                    glob.callAsync(param(Map.of("pattern", "*.txt", "path", tempDir.toString())))
                            .block();
            TextBlock text = (TextBlock) r.getOutput().get(0);
            int newIdx = text.getText().indexOf("new.txt");
            int oldIdx = text.getText().indexOf("old.txt");
            assertTrue(newIdx >= 0 && oldIdx >= 0);
            assertTrue(newIdx < oldIdx, "newer file must appear before older one");
        }

        @Test
        void rejectsBlankPattern() {
            ToolResultBlock r = glob.callAsync(param(Map.of("pattern", ""))).block();
            TextBlock text = (TextBlock) r.getOutput().get(0);
            assertTrue(text.getText().startsWith("Error"));
        }

        @Test
        void rejectsNonDirectoryPath() throws IOException {
            Path file = mk("solo.txt");
            ToolResultBlock r =
                    glob.callAsync(param(Map.of("pattern", "*.txt", "path", file.toString())))
                            .block();
            TextBlock text = (TextBlock) r.getOutput().get(0);
            assertTrue(text.getText().startsWith("Error"));
        }
    }

    @Nested
    class Configuration {

        @Test
        void exposesNameAndSafetyFlags() {
            assertNotNull(glob.getName());
            assertEquals("Glob", glob.getName());
            assertTrue(glob.isReadOnly());
            assertTrue(glob.isConcurrencySafe());
        }
    }
}
