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
import io.agentscope.core.permission.AdditionalWorkingDirectory;
import io.agentscope.core.permission.PermissionBehavior;
import io.agentscope.core.permission.PermissionContext;
import io.agentscope.core.permission.PermissionDecision;
import io.agentscope.core.permission.PermissionMode;
import io.agentscope.core.permission.PermissionRule;
import io.agentscope.core.state.ToolContext;
import io.agentscope.core.tool.ToolCallParam;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class WriteTest {

    @TempDir Path tempDir;
    private ToolContext context;
    private Read read;
    private Write write;
    private PermissionContext defaultCtx;

    @BeforeEach
    void setUp() {
        context = ToolContext.builder().build();
        read = new Read(context);
        write = new Write(context);
        defaultCtx = PermissionContext.builder().mode(PermissionMode.DEFAULT).build();
    }

    private ToolCallParam param(Map<String, Object> input, String toolName) {
        ToolUseBlock use = ToolUseBlock.builder().id("call-1").name(toolName).input(input).build();
        return ToolCallParam.builder().toolUseBlock(use).input(input).build();
    }

    @Nested
    class CheckPermissions {

        @Test
        void safePathPassthrough() {
            PermissionDecision d =
                    write.checkPermissions(
                                    Map.of(
                                            "file_path",
                                            tempDir.resolve("safe.txt").toString(),
                                            "content",
                                            "x"),
                                    defaultCtx)
                            .block();
            assertEquals(PermissionBehavior.PASSTHROUGH, d.getBehavior());
        }

        @Test
        void dangerousPathAsksWithSafetyReason() {
            PermissionDecision d =
                    write.checkPermissions(
                                    Map.of(
                                            "file_path",
                                            tempDir.resolve(".bashrc").toString(),
                                            "content",
                                            "x"),
                                    defaultCtx)
                            .block();
            assertEquals(PermissionBehavior.ASK, d.getBehavior());
            assertTrue(d.getDecisionReason().toLowerCase(Locale.ROOT).contains("safety"));
        }

        @Test
        void acceptEditsAllowsInsideWorkingDirectory() {
            PermissionContext ctx =
                    PermissionContext.builder()
                            .mode(PermissionMode.ACCEPT_EDITS)
                            .addWorkingDirectory(
                                    tempDir.toString(),
                                    new AdditionalWorkingDirectory(tempDir.toString(), "test"))
                            .build();
            PermissionDecision d =
                    write.checkPermissions(
                                    Map.of(
                                            "file_path",
                                            tempDir.resolve("inside.txt").toString(),
                                            "content",
                                            "x"),
                                    ctx)
                            .block();
            assertEquals(PermissionBehavior.ALLOW, d.getBehavior());
        }

        @Test
        void acceptEditsOutsideWorkingDirectoryIsPassthrough() {
            PermissionContext ctx =
                    PermissionContext.builder()
                            .mode(PermissionMode.ACCEPT_EDITS)
                            .addWorkingDirectory(
                                    tempDir.toString(),
                                    new AdditionalWorkingDirectory(tempDir.toString(), "test"))
                            .build();
            PermissionDecision d =
                    write.checkPermissions(
                                    Map.of("file_path", "/elsewhere/output.txt", "content", "x"),
                                    ctx)
                            .block();
            assertEquals(PermissionBehavior.PASSTHROUGH, d.getBehavior());
        }

        @Test
        void missingFilePathIsPassthrough() {
            PermissionDecision d =
                    write.checkPermissions(Map.of("content", "x"), defaultCtx).block();
            assertEquals(PermissionBehavior.PASSTHROUGH, d.getBehavior());
        }
    }

    @Nested
    class MatchRuleAndSuggestions {

        @Test
        void nullRuleMatches() {
            assertTrue(write.matchRule(null, Map.of("file_path", "/x/y.txt")));
        }

        @Test
        void globMatchesPath() {
            assertTrue(write.matchRule("/x/*.txt", Map.of("file_path", "/x/y.txt")));
            assertFalse(write.matchRule("/x/*.txt", Map.of("file_path", "/z/y.txt")));
        }

        @Test
        void emitsParentDirAndToolLevelSuggestions() {
            List<PermissionRule> rules =
                    write.generateSuggestions(Map.of("file_path", "/etc/app/conf.yaml"));
            assertTrue(rules.stream().anyMatch(r -> "/etc/app/*".equals(r.ruleContent())));
            assertTrue(rules.stream().anyMatch(r -> r.ruleContent() == null));
        }
    }

    @Nested
    class Execution {

        @Test
        void writesNewFileWithoutCachePreread() {
            Path target = tempDir.resolve("new.txt");
            ToolResultBlock result =
                    write.callAsync(
                                    param(
                                            Map.of(
                                                    "file_path",
                                                    target.toString(),
                                                    "content",
                                                    "hello"),
                                            "Write"))
                            .block();
            TextBlock text = (TextBlock) result.getOutput().get(0);
            assertFalse(text.getText().startsWith("Error"));
            assertTrue(Files.exists(target));
        }

        @Test
        void rejectsOverwriteWithoutPriorRead() throws IOException {
            Path target = tempDir.resolve("existing.txt");
            Files.writeString(target, "original");
            ToolResultBlock result =
                    write.callAsync(
                                    param(
                                            Map.of(
                                                    "file_path",
                                                    target.toString(),
                                                    "content",
                                                    "new"),
                                            "Write"))
                            .block();
            TextBlock text = (TextBlock) result.getOutput().get(0);
            assertTrue(text.getText().startsWith("Error"));
            assertTrue(text.getText().toLowerCase(Locale.ROOT).contains("read"));
        }

        @Test
        void allowsOverwriteAfterRead() throws IOException {
            Path target = tempDir.resolve("readable.txt");
            Files.writeString(target, "first");
            ToolCallParam readParam = param(Map.of("file_path", target.toString()), "Read");
            assertNotNull(read.callAsync(readParam).block());

            ToolResultBlock writeResult =
                    write.callAsync(
                                    param(
                                            Map.of(
                                                    "file_path",
                                                    target.toString(),
                                                    "content",
                                                    "second"),
                                            "Write"))
                            .block();
            TextBlock text = (TextBlock) writeResult.getOutput().get(0);
            assertFalse(text.getText().startsWith("Error"));
            assertEquals("second", Files.readString(target, StandardCharsets.UTF_8));
        }

        @Test
        void rejectsMissingContent() {
            ToolResultBlock result =
                    write.callAsync(param(Map.of("file_path", "/tmp/x"), "Write")).block();
            TextBlock text = (TextBlock) result.getOutput().get(0);
            assertTrue(text.getText().startsWith("Error"));
        }
    }

    @Nested
    class Configuration {

        @Test
        void exposesFlags() {
            assertEquals("Write", write.getName());
            assertFalse(write.isReadOnly());
            assertFalse(write.isConcurrencySafe());
        }

        @Test
        void additionalDangerousFilesFlagged() {
            Write custom = new Write(context, List.of("secret.key"), List.of());
            PermissionDecision d =
                    custom.checkPermissions(
                                    Map.of(
                                            "file_path",
                                            tempDir.resolve("secret.key").toString(),
                                            "content",
                                            "x"),
                                    defaultCtx)
                            .block();
            assertEquals(PermissionBehavior.ASK, d.getBehavior());
        }
    }
}
