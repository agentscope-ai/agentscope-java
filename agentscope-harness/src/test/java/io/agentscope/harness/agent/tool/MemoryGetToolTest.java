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
package io.agentscope.harness.agent.tool;

import static io.agentscope.harness.agent.tool.ToolResultAssertions.assertErrorKind;
import static io.agentscope.harness.agent.tool.ToolResultAssertions.assertNoErrorMetadata;
import static io.agentscope.harness.agent.tool.ToolResultAssertions.assertText;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.agentscope.core.agent.RuntimeContext;
import io.agentscope.core.message.ToolErrorKind;
import io.agentscope.core.message.ToolResultBlock;
import io.agentscope.core.message.ToolResultState;
import io.agentscope.core.message.ToolUseBlock;
import io.agentscope.core.tool.Tool;
import io.agentscope.core.tool.ToolCallParam;
import io.agentscope.core.tool.Toolkit;
import io.agentscope.harness.agent.filesystem.remote.RemoteFilesystem;
import io.agentscope.harness.agent.filesystem.remote.store.InMemoryStore;
import io.agentscope.harness.agent.workspace.WorkspaceManager;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import java.util.stream.IntStream;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class MemoryGetToolTest {
    private static final Pattern NEXT_START = Pattern.compile("startLine=(\\d+) endLine=");

    @TempDir Path workspace;

    private MemoryGetTool tool;
    private final RuntimeContext context = RuntimeContext.empty();

    @BeforeEach
    void setUp() {
        tool = new MemoryGetTool(new WorkspaceManager(workspace));
    }

    @Test
    void readsTheRequestedInclusiveRangeWithLineNumbers() throws Exception {
        Files.writeString(workspace.resolve("MEMORY.md"), "a\nb\nc\nd\ne\n");

        ToolResultBlock result = tool.memoryGet(context, "MEMORY.md", 2, 4);
        String text = assertText(result, ToolResultState.SUCCESS);

        assertNoErrorMetadata(result);
        assertTrue(text.contains("2|b"), text);
        assertTrue(text.contains("3|c"), text);
        assertTrue(text.contains("4|d"), text);
        assertTrue(!text.contains("1|a") && !text.contains("5|e"), text);
    }

    @Test
    void pageIsBoundedAndPointsAtTheNextStartLine() throws Exception {
        Files.writeString(workspace.resolve("MEMORY.md"), longLines(30, 1_000));

        String text =
                assertText(tool.memoryGet(context, "MEMORY.md", 1, 30), ToolResultState.SUCCESS);

        assertTrue(text.contains("Output truncated"), text);
        assertTrue(text.contains("Continue with memory_get path=MEMORY.md"), text);
        assertTrue(text.length() <= MemoryGetTool.MAX_OUTPUT_CHARS, text);
        Matcher matcher = NEXT_START.matcher(text);
        assertTrue(matcher.find(), text);
        int next = Integer.parseInt(matcher.group(1));
        assertTrue(next > 1 && next <= 30, text);

        // The next page must continue from where this one stopped, without duplicating lines.
        String second =
                assertText(tool.memoryGet(context, "MEMORY.md", next, 30), ToolResultState.SUCCESS);
        assertTrue(second.startsWith(next + "|line-" + next), second);
    }

    @Test
    void oversizedSingleLineKeepsHeadAndTail() throws Exception {
        String longLine = "line-start " + "x".repeat(5_000) + " line-END";
        Files.writeString(workspace.resolve("MEMORY.md"), longLine + "\n");

        String text =
                assertText(tool.memoryGet(context, "MEMORY.md", 1, 1), ToolResultState.SUCCESS);

        assertTrue(text.contains("line-start"), text);
        assertTrue(text.contains("line-END"), text);
        assertTrue(text.contains("[truncated]"), text);
        assertTrue(text.length() <= MemoryGetTool.MAX_OUTPUT_CHARS, text);
    }

    @Test
    void missingFileErrorExplainsHowToFindMemory() {
        ToolResultBlock result = tool.memoryGet(context, "memory/missing.md", 1, 10);
        String text = assertText(result, ToolResultState.ERROR);
        assertErrorKind(result, ToolErrorKind.NOT_FOUND, "memory/missing.md");
        assertTrue(text.contains("Memory file not found or empty: memory/missing.md"), text);
        assertTrue(text.contains("memory_search"), text);
    }

    @Test
    void traversalIsRejectedWithAnActionableMessage() {
        ToolResultBlock result = tool.memoryGet(context, "../MEMORY.md", 1, 10);
        String text = assertText(result, ToolResultState.ERROR);
        assertErrorKind(result, ToolErrorKind.PERMISSION_DENIED, "../MEMORY.md");
        assertTrue(text.contains("relative path inside the memory namespace"), text);
    }

    @Test
    void startLineBeyondTheFileSuggestsAValidLine() throws Exception {
        Files.writeString(workspace.resolve("MEMORY.md"), "a\nb\n");

        ToolResultBlock result = tool.memoryGet(context, "MEMORY.md", 9, 12);
        String text = assertText(result, ToolResultState.ERROR);
        assertErrorKind(result, ToolErrorKind.INVALID_ARGUMENT, "MEMORY.md");

        assertTrue(text.contains("exceeds file length 3"), text);
        assertTrue(text.contains("retry with startLine <= 3"), text);
    }

    @Test
    void reversedRangeIsRejected() throws Exception {
        Files.writeString(workspace.resolve("MEMORY.md"), "a\nb\nc\n");

        ToolResultBlock result = tool.memoryGet(context, "MEMORY.md", 3, 2);
        String text = assertText(result, ToolResultState.ERROR);
        assertErrorKind(result, ToolErrorKind.INVALID_ARGUMENT, "MEMORY.md");

        assertTrue(text.contains("must be >= startLine"), text);
    }

    @Test
    void nonPositiveStartLineIsRejected() throws Exception {
        Files.writeString(workspace.resolve("MEMORY.md"), "a\nb\n");

        ToolResultBlock result = tool.memoryGet(context, "MEMORY.md", 0, 2);
        String text = assertText(result, ToolResultState.ERROR);
        assertErrorKind(result, ToolErrorKind.INVALID_ARGUMENT, "MEMORY.md");

        assertEquals("Error: startLine must be >= 1 (1-based); got 0.", text);
    }

    @Test
    void codepointSegmentsRecoverTheWholeLongLineIncludingSupplementaryCharacters()
            throws Exception {
        String original = "prefix " + "a\uD83D\uDE80中".repeat(2_301) + " tail";
        Files.writeString(workspace.resolve("MEMORY.md"), original);
        String preview =
                assertText(tool.memoryGet(context, "MEMORY.md", 1, 1), ToolResultState.SUCCESS);
        assertTrue(preview.contains("prefix ") && preview.contains(" tail"));
        assertTrue(preview.contains("charOffset=0"));
        assertTrue(!preview.contains("read_file") && !preview.contains("shell"));
        Pattern nextPattern = Pattern.compile("nextCharOffset=(\\d+)");
        StringBuilder restored = new StringBuilder();
        int offset = 0;
        int pages = 0;
        while (true) {
            String page =
                    assertText(
                            tool.memoryGet(context, "MEMORY.md", 1, 1, offset),
                            ToolResultState.SUCCESS);
            assertTrue(page.length() <= MemoryGetTool.MAX_OUTPUT_CHARS);
            int newline = page.indexOf(System.lineSeparator());
            String segment = page.substring(2, newline);
            int points = segment.codePointCount(0, segment.length());
            assertTrue(points <= MemoryGetTool.MAX_LINE_CHARS);
            restored.append(segment);
            Matcher next =
                    nextPattern.matcher(page.substring(newline + System.lineSeparator().length()));
            pages++;
            if (!next.find()) break;
            int nextOffset = Integer.parseInt(next.group(1));
            assertEquals(offset + points, nextOffset);
            offset = nextOffset;
            assertTrue(pages < 10);
        }
        assertTrue(pages > 1);
        assertEquals(original, restored.toString());
        int total = original.codePointCount(0, original.length());
        assertEquals(
                "1|" + System.lineSeparator(),
                assertText(
                        tool.memoryGet(context, "MEMORY.md", 1, 1, total),
                        ToolResultState.SUCCESS));
        assertErrorKind(
                tool.memoryGet(context, "MEMORY.md", 1, 1, total + 1),
                ToolErrorKind.INVALID_ARGUMENT,
                "MEMORY.md");
        assertErrorKind(
                tool.memoryGet(context, "MEMORY.md", 1, 1, -1),
                ToolErrorKind.INVALID_ARGUMENT,
                "MEMORY.md");
        assertErrorKind(
                tool.memoryGet(context, "MEMORY.md", 1, 2, 0),
                ToolErrorKind.INVALID_ARGUMENT,
                "MEMORY.md");
    }

    @Test
    void utf16LinePrefixesAndBothTrailersStayInsideTheExistingPageBudget() throws Exception {
        String contents =
                IntStream.range(0, 20)
                        .mapToObj(i -> "\uD83D\uDE80".repeat(3_000))
                        .collect(Collectors.joining("\n"));
        Files.writeString(workspace.resolve("MEMORY.md"), contents);
        String result =
                assertText(tool.memoryGet(context, "MEMORY.md", 1, 20), ToolResultState.SUCCESS);
        assertTrue(result.length() <= MemoryGetTool.MAX_OUTPUT_CHARS);
        assertTrue(result.contains("Output truncated"));
        assertTrue(result.contains("nextCharOffset"));
    }

    @Test
    void uniqueSchemaAndReflectiveContinuationKeepTheCurrentMemoryNamespace() {
        var store = new InMemoryStore();
        var filesystem =
                new RemoteFilesystem(
                        store, rc -> List.of("memory-pages", rc.get("tenant", String.class)));
        RuntimeContext first =
                RuntimeContext.builder().userId("same-user").put("tenant", "first").build();
        RuntimeContext other = RuntimeContext.builder(first).put("tenant", "other").build();
        try (WorkspaceManager manager = new WorkspaceManager(workspace, filesystem)) {
            manager.writeMemoryFileUtf8(first, "MEMORY.md", "x".repeat(2_000) + "private-tail");
            manager.writeMemoryFileUtf8(other, "MEMORY.md", "other-namespace");
            MemoryGetTool memory = new MemoryGetTool(manager);
            Toolkit toolkit = new Toolkit();
            toolkit.registerTool(memory);
            assertEquals(
                    1,
                    Arrays.stream(MemoryGetTool.class.getDeclaredMethods())
                            .filter(m -> m.isAnnotationPresent(Tool.class))
                            .count());
            var schemas = toolkit.getToolSchemas();
            assertEquals(1, schemas.size());
            assertTrue(
                    ((Map<?, ?>) schemas.get(0).getParameters().get("properties"))
                            .containsKey("charOffset"));
            assertTrue(
                    !((List<?>) schemas.get(0).getParameters().get("required"))
                            .contains("charOffset"));
            Map<String, Object> oldInput =
                    Map.of("path", "MEMORY.md", "startLine", 1, "endLine", 1);
            assertEquals(
                    assertText(memory.memoryGet(first, "MEMORY.md", 1, 1), ToolResultState.SUCCESS),
                    invoke(toolkit, first, oldInput));
            assertTrue(
                    invoke(
                                    toolkit,
                                    first,
                                    Map.of(
                                            "path",
                                            "MEMORY.md",
                                            "startLine",
                                            1,
                                            "endLine",
                                            1,
                                            "charOffset",
                                            2_000))
                            .contains("private-tail"));
            assertTrue(
                    invoke(
                                    toolkit,
                                    other,
                                    Map.of(
                                            "path",
                                            "MEMORY.md",
                                            "startLine",
                                            1,
                                            "endLine",
                                            1,
                                            "charOffset",
                                            0))
                            .contains("other-namespace"));
            assertErrorKind(
                    memory.memoryGet(other, "MEMORY.md", 1, 1, 2_000),
                    ToolErrorKind.INVALID_ARGUMENT,
                    "MEMORY.md");
            assertErrorKind(
                    memory.memoryGet(first, "../MEMORY.md", 1, 1, 0),
                    ToolErrorKind.PERMISSION_DENIED,
                    "../MEMORY.md");
            assertEquals("other-namespace", manager.readMemoryMd(other));
        }
    }

    private static String invoke(
            Toolkit toolkit, RuntimeContext context, Map<String, Object> input) {
        return assertText(
                toolkit.callTool(
                                ToolCallParam.builder()
                                        .toolUseBlock(
                                                ToolUseBlock.builder()
                                                        .id("memory-page")
                                                        .name("memory_get")
                                                        .input(input)
                                                        .build())
                                        .input(input)
                                        .runtimeContext(context)
                                        .build())
                        .block(Duration.ofSeconds(10)),
                ToolResultState.SUCCESS);
    }

    private static String longLines(int count, int lineChars) {
        String filler = "x".repeat(lineChars - 10);
        return IntStream.rangeClosed(1, count)
                .mapToObj(i -> "line-" + i + " " + filler)
                .collect(Collectors.joining("\n"));
    }
}
