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
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.agentscope.core.message.TextBlock;
import io.agentscope.core.message.ToolResultBlock;
import io.agentscope.core.message.ToolUseBlock;
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
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class ReadTest {

    @TempDir Path tempDir;
    private ToolContext context;
    private Read read;
    private PermissionContext defaultCtx;

    @BeforeEach
    void setUp() {
        context = ToolContext.builder().build();
        read = new Read(context);
        defaultCtx = PermissionContext.builder().mode(PermissionMode.DEFAULT).build();
    }

    private ToolCallParam param(Map<String, Object> input) {
        ToolUseBlock use = ToolUseBlock.builder().id("call-1").name("Read").input(input).build();
        return ToolCallParam.builder().toolUseBlock(use).input(input).build();
    }

    private Path writeFile(String name, String content) throws IOException {
        Path p = tempDir.resolve(name);
        Files.writeString(p, content, StandardCharsets.UTF_8);
        return p;
    }

    @Nested
    class CheckPermissions {

        @Test
        void alwaysPassthrough() {
            PermissionDecision d =
                    read.checkPermissions(Map.of("file_path", "/tmp/foo"), defaultCtx).block();
            assertNotNull(d);
            assertEquals(PermissionBehavior.PASSTHROUGH, d.getBehavior());
        }

        @Test
        void missingFilePathStillPassthrough() {
            PermissionDecision d = read.checkPermissions(Map.of(), defaultCtx).block();
            assertEquals(PermissionBehavior.PASSTHROUGH, d.getBehavior());
        }
    }

    @Nested
    class MatchRule {

        @Test
        void nullRuleMatches() {
            assertTrue(read.matchRule(null, Map.of("file_path", "/a/b/c.txt")));
        }

        @Test
        void globMatchesSamePath() {
            assertTrue(read.matchRule("/tmp/*.txt", Map.of("file_path", "/tmp/foo.txt")));
        }

        @Test
        void globRejectsMismatch() {
            assertFalse(read.matchRule("/tmp/*.txt", Map.of("file_path", "/etc/foo.txt")));
        }

        @Test
        void missingFilePathReturnsFalse() {
            assertFalse(read.matchRule("/tmp/*.txt", Map.of()));
        }
    }

    @Nested
    class GenerateSuggestions {

        @Test
        void emitsParentGlobAndToolLevelRule() {
            List<PermissionRule> rules =
                    read.generateSuggestions(Map.of("file_path", "/var/log/app.log"));
            assertTrue(rules.stream().anyMatch(r -> "/var/log/*".equals(r.ruleContent())));
            assertTrue(rules.stream().anyMatch(r -> r.ruleContent() == null));
            for (PermissionRule rule : rules) {
                assertEquals(PermissionBehavior.ALLOW, rule.behavior());
                assertEquals("suggested", rule.source());
            }
        }

        @Test
        void noFilePathEmitsToolLevelOnly() {
            List<PermissionRule> rules = read.generateSuggestions(Map.of());
            assertEquals(1, rules.size());
            assertNotNull(rules.get(0));
        }
    }

    @Nested
    class Execution {

        @Test
        void readsEntireFileAndCachesIt() throws IOException {
            Path file = writeFile("hello.txt", "line1\nline2\nline3\n");
            ToolResultBlock result =
                    read.callAsync(param(Map.of("file_path", file.toString()))).block();
            assertNotNull(result);
            TextBlock text = (TextBlock) result.getOutput().get(0);
            assertTrue(text.getText().contains("line1"));
            assertTrue(text.getText().contains("line2"));
            assertTrue(text.getText().contains("line3"));
            // cat -n style numbering with leading spaces and tab
            assertTrue(text.getText().contains("\tline1"));

            Optional<?> cached = context.getCache(file.toString()).block();
            assertNotNull(cached);
            assertTrue(cached.isPresent());
        }

        @Test
        void respectsOffsetAndLimit() throws IOException {
            Path file = writeFile("page.txt", "a\nb\nc\nd\ne\n");
            ToolResultBlock result =
                    read.callAsync(
                                    param(
                                            Map.of(
                                                    "file_path",
                                                    file.toString(),
                                                    "offset",
                                                    2,
                                                    "limit",
                                                    2)))
                            .block();
            TextBlock text = (TextBlock) result.getOutput().get(0);
            assertTrue(text.getText().contains("\tb"));
            assertTrue(text.getText().contains("\tc"));
            assertFalse(text.getText().contains("\ta"));
            assertFalse(text.getText().contains("\td"));
        }

        @Test
        void truncatesLongLines() throws IOException {
            String longLine = "x".repeat(50);
            Path file = writeFile("long.txt", longLine + "\n");
            Read narrow = new Read(context, 10);
            ToolResultBlock result =
                    narrow.callAsync(param(Map.of("file_path", file.toString()))).block();
            TextBlock text = (TextBlock) result.getOutput().get(0);
            assertTrue(text.getText().contains("[truncated]"));
        }

        @Test
        void rejectsBlankFilePath() {
            ToolResultBlock result = read.callAsync(param(Map.of("file_path", ""))).block();
            TextBlock text = (TextBlock) result.getOutput().get(0);
            assertTrue(text.getText().startsWith("Error"));
        }

        @Test
        void rejectsZeroOffset() throws IOException {
            Path file = writeFile("any.txt", "data\n");
            ToolResultBlock result =
                    read.callAsync(param(Map.of("file_path", file.toString(), "offset", 0)))
                            .block();
            TextBlock text = (TextBlock) result.getOutput().get(0);
            assertTrue(text.getText().startsWith("Error"));
        }

        @Test
        void missingFileReturnsErrorMessage() {
            ToolResultBlock result =
                    read.callAsync(param(Map.of("file_path", tempDir.resolve("nope").toString())))
                            .block();
            TextBlock text = (TextBlock) result.getOutput().get(0);
            assertTrue(text.getText().contains("not found") || text.getText().contains("File"));
        }
    }

    @Nested
    class Configuration {

        @Test
        void exposesReadOnlyAndConcurrencySafeFlags() {
            assertEquals("Read", read.getName());
            assertTrue(read.isReadOnly());
            assertTrue(read.isConcurrencySafe());
        }

        @Test
        void rejectsNonPositiveMaxLineCharacters() {
            assertThrows(IllegalArgumentException.class, () -> new Read(context, 0));
            assertThrows(IllegalArgumentException.class, () -> new Read(context, -1));
        }
    }
}
