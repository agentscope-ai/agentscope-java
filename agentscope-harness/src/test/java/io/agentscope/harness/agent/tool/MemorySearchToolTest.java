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

import static org.junit.jupiter.api.Assertions.assertTrue;

import io.agentscope.harness.agent.workspace.WorkspaceManager;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** Tests for multi-keyword search in {@link MemorySearchTool}. */
class MemorySearchToolTest {

    @TempDir Path workspace;

    private MemorySearchTool tool;

    @BeforeEach
    void setUp() {
        tool = new MemorySearchTool(new WorkspaceManager(workspace));
    }

    @Test
    void singleKeywordMatches() throws IOException {
        Files.writeString(workspace.resolve("MEMORY.md"), "- Contains Markdown formatting\n");
        String result = tool.memorySearch(null, "Markdown");
        assertTrue(result.startsWith("Found "), result);
    }

    @Test
    void multiKeywordAndPassMatches() throws IOException {
        Files.writeString(
                workspace.resolve("MEMORY.md"), "- 用户要求创作诗歌并以 Markdown 文件形式交付，交付物为《秋日书怀》(七言律诗)\n");
        String result = tool.memorySearch(null, "秋日书怀 七言律诗");
        assertTrue(result.startsWith("Found "), result);
    }

    @Test
    void multiKeywordThreeTokensAndPass() throws IOException {
        Files.writeString(
                workspace.resolve("MEMORY.md"), "- 用户要求创作诗歌并以 Markdown 文件形式交付，交付物为《秋日书怀》(七言律诗)\n");
        String result = tool.memorySearch(null, "诗歌 Markdown 交付");
        assertTrue(result.startsWith("Found "), result);
    }

    @Test
    void orFallbackWhenNotAllTokensOnOneLine() throws IOException {
        Files.writeString(
                workspace.resolve("MEMORY.md"),
                "- line about 诗歌 creation\n- another line about 交付\n");
        String result = tool.memorySearch(null, "诗歌 交付");
        // No single line has both; OR fallback returns lines with any match
        assertTrue(result.startsWith("Found "), result);
    }

    @Test
    void noMatchReturnsMessage() throws IOException {
        Files.writeString(workspace.resolve("MEMORY.md"), "- some unrelated content\n");
        String result = tool.memorySearch(null, "absent keyword");
        assertTrue(result.startsWith("No matching"), result);
    }

    @Test
    void nullQueryReturnsNoQuery() {
        String result = tool.memorySearch(null, null);
        assertTrue(result.contains("No query"), result);
    }

    @Test
    void blankQueryReturnsNoQuery() {
        String result = tool.memorySearch(null, "   ");
        assertTrue(result.contains("No query"), result);
    }
}
