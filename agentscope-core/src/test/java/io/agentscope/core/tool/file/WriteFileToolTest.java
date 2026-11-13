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
package io.agentscope.core.tool.file;

import static org.junit.jupiter.api.Assertions.assertEquals;

import io.agentscope.core.message.TextBlock;
import io.agentscope.core.message.ToolResultBlock;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class WriteFileToolTest {

    @TempDir Path tempDir;

    private WriteFileTool tool;

    @BeforeEach
    void setUp() throws IOException {
        tool = new WriteFileTool(tempDir.toString());
        Files.writeString(
                tempDir.resolve("sample.txt"), "one\ntwo\nthree\n", StandardCharsets.UTF_8);
    }

    @Test
    void insertShowsSnippetForPathRelativeToBaseDir() {
        assertEquals(
                "Insert content into sample.txt at line 2 successfully. The new content between"
                        + " lines 1-4 is:\n```\n1: one\n2: inserted\n3: two\n4: three\n```",
                resultText(tool.insertTextFile("sample.txt", "inserted", 2).block()));
    }

    @Test
    void writeRangeShowsSnippetForPathRelativeToBaseDir() {
        assertEquals(
                "Write sample.txt successfully. The new content snippet:\n"
                        + "```\n1: one\n2: replaced\n3: three\n```",
                resultText(tool.writeTextFile("sample.txt", "replaced", "2,2").block()));
    }

    private String resultText(ToolResultBlock result) {
        return ((TextBlock) result.getOutput().get(0)).getText();
    }
}
