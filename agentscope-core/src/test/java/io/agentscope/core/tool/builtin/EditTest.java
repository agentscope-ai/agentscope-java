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
import io.agentscope.core.state.ToolContext;
import io.agentscope.core.tool.ToolCallParam;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Locale;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class EditTest {

    @TempDir Path tempDir;
    private ToolContext context;
    private Read read;
    private Edit edit;
    private PermissionContext defaultCtx;

    @BeforeEach
    void setUp() {
        context = ToolContext.builder().build();
        read = new Read(context);
        edit = new Edit(context);
        defaultCtx = PermissionContext.builder().mode(PermissionMode.DEFAULT).build();
    }

    private ToolCallParam param(Map<String, Object> input, String toolName) {
        ToolUseBlock use = ToolUseBlock.builder().id("call-1").name(toolName).input(input).build();
        return ToolCallParam.builder().toolUseBlock(use).input(input).build();
    }

    private Path seed(String name, String content) throws IOException {
        Path p = tempDir.resolve(name);
        Files.writeString(p, content, StandardCharsets.UTF_8);
        return p;
    }

    private void preRead(Path p) {
        ToolResultBlock r =
                read.callAsync(param(Map.of("file_path", p.toString()), "Read")).block();
        assertNotNull(r);
    }

    @Nested
    class CheckPermissions {

        @Test
        void safePathPassthrough() {
            PermissionDecision d =
                    edit.checkPermissions(
                                    Map.of(
                                            "file_path",
                                            tempDir.resolve("ok.txt").toString(),
                                            "old_string",
                                            "a",
                                            "new_string",
                                            "b"),
                                    defaultCtx)
                            .block();
            assertEquals(PermissionBehavior.PASSTHROUGH, d.getBehavior());
        }

        @Test
        void dangerousPathAsksWithSafetyReason() {
            PermissionDecision d =
                    edit.checkPermissions(
                                    Map.of(
                                            "file_path",
                                            tempDir.resolve(".gitconfig").toString(),
                                            "old_string",
                                            "x",
                                            "new_string",
                                            "y"),
                                    defaultCtx)
                            .block();
            assertEquals(PermissionBehavior.ASK, d.getBehavior());
            assertTrue(d.getDecisionReason().toLowerCase(Locale.ROOT).contains("safety"));
        }
    }

    @Nested
    class Execution {

        @Test
        void requiresCacheHit() throws IOException {
            Path file = seed("uncached.txt", "hello world");
            ToolResultBlock r =
                    edit.callAsync(
                                    param(
                                            Map.of(
                                                    "file_path",
                                                    file.toString(),
                                                    "old_string",
                                                    "hello",
                                                    "new_string",
                                                    "goodbye"),
                                            "Edit"))
                            .block();
            TextBlock text = (TextBlock) r.getOutput().get(0);
            assertTrue(text.getText().startsWith("Error"));
            assertTrue(text.getText().toLowerCase(Locale.ROOT).contains("read"));
        }

        @Test
        void replacesUniqueOccurrence() throws IOException {
            Path file = seed("doc.txt", "hello world");
            preRead(file);
            ToolResultBlock r =
                    edit.callAsync(
                                    param(
                                            Map.of(
                                                    "file_path",
                                                    file.toString(),
                                                    "old_string",
                                                    "world",
                                                    "new_string",
                                                    "there"),
                                            "Edit"))
                            .block();
            TextBlock text = (TextBlock) r.getOutput().get(0);
            assertFalse(text.getText().startsWith("Error"));
            assertEquals("hello there", Files.readString(file, StandardCharsets.UTF_8));
        }

        @Test
        void rejectsAmbiguousMatchWithoutReplaceAll() throws IOException {
            Path file = seed("doc2.txt", "x x x");
            preRead(file);
            ToolResultBlock r =
                    edit.callAsync(
                                    param(
                                            Map.of(
                                                    "file_path",
                                                    file.toString(),
                                                    "old_string",
                                                    "x",
                                                    "new_string",
                                                    "y"),
                                            "Edit"))
                            .block();
            TextBlock text = (TextBlock) r.getOutput().get(0);
            assertTrue(text.getText().startsWith("Error"));
            assertTrue(text.getText().contains("3"));
        }

        @Test
        void replaceAllHandlesMultipleOccurrences() throws IOException {
            Path file = seed("doc3.txt", "x x x");
            preRead(file);
            ToolResultBlock r =
                    edit.callAsync(
                                    param(
                                            Map.of(
                                                    "file_path",
                                                    file.toString(),
                                                    "old_string",
                                                    "x",
                                                    "new_string",
                                                    "y",
                                                    "replace_all",
                                                    true),
                                            "Edit"))
                            .block();
            TextBlock text = (TextBlock) r.getOutput().get(0);
            assertFalse(text.getText().startsWith("Error"));
            assertEquals("y y y", Files.readString(file, StandardCharsets.UTF_8));
        }

        @Test
        void zeroMatchesReturnsError() throws IOException {
            Path file = seed("doc4.txt", "abc");
            preRead(file);
            ToolResultBlock r =
                    edit.callAsync(
                                    param(
                                            Map.of(
                                                    "file_path",
                                                    file.toString(),
                                                    "old_string",
                                                    "zzz",
                                                    "new_string",
                                                    "y"),
                                            "Edit"))
                            .block();
            TextBlock text = (TextBlock) r.getOutput().get(0);
            assertTrue(text.getText().startsWith("Error"));
            assertTrue(text.getText().toLowerCase(Locale.ROOT).contains("not found"));
        }

        @Test
        void emptyOldStringReturnsError() throws IOException {
            Path file = seed("doc5.txt", "anything");
            preRead(file);
            ToolResultBlock r =
                    edit.callAsync(
                                    param(
                                            Map.of(
                                                    "file_path",
                                                    file.toString(),
                                                    "old_string",
                                                    "",
                                                    "new_string",
                                                    "y"),
                                            "Edit"))
                            .block();
            TextBlock text = (TextBlock) r.getOutput().get(0);
            assertTrue(text.getText().startsWith("Error"));
        }

        @Test
        void refreshesCacheAfterEdit() throws IOException {
            Path file = seed("doc6.txt", "alpha");
            preRead(file);
            edit.callAsync(
                            param(
                                    Map.of(
                                            "file_path",
                                            file.toString(),
                                            "old_string",
                                            "alpha",
                                            "new_string",
                                            "beta"),
                                    "Edit"))
                    .block();
            // Second edit should also succeed because cache was refreshed
            ToolResultBlock r =
                    edit.callAsync(
                                    param(
                                            Map.of(
                                                    "file_path",
                                                    file.toString(),
                                                    "old_string",
                                                    "beta",
                                                    "new_string",
                                                    "gamma"),
                                            "Edit"))
                            .block();
            TextBlock text = (TextBlock) r.getOutput().get(0);
            assertFalse(text.getText().startsWith("Error"));
            assertEquals("gamma", Files.readString(file, StandardCharsets.UTF_8));
        }
    }

    @Nested
    class Configuration {

        @Test
        void exposesFlags() {
            assertEquals("Edit", edit.getName());
            assertFalse(edit.isReadOnly());
            assertFalse(edit.isConcurrencySafe());
        }
    }
}
