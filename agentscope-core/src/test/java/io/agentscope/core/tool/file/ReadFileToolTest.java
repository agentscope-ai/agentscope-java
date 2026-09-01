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

class ReadFileToolTest {

    @TempDir Path tempDir;

    private ReadFileTool tool;
    private Path file;

    @BeforeEach
    void setUp() throws IOException {
        tool = new ReadFileTool(tempDir.toString());
        file = tempDir.resolve("sample.txt");
        Files.writeString(file, "one\ntwo\nthree\nfour\nfive\n", StandardCharsets.UTF_8);
    }

    @Test
    void readsPositiveRange() {
        assertEquals(
                expected("The content of %s in lines [2, 3]:\n```\n2: two\n3: three\n```"),
                resultText(tool.viewTextFile(file.toString(), "2,3").block()));
    }

    @Test
    void clampsPositiveRangeToEndOfFile() {
        assertEquals(
                expected("The content of %s in lines [4, 5]:\n```\n4: four\n5: five\n```"),
                resultText(tool.viewTextFile(file.toString(), "4,20").block()));
    }

    @Test
    void readsRangeRelativeToEndOfFile() {
        assertEquals(
                expected(
                        "The content of %s in lines [3, 5]:\n```\n3: three\n4: four\n5: five\n```"),
                resultText(tool.viewTextFile(file.toString(), "-3,-1").block()));
    }

    @Test
    void readsEntireFileWithoutRange() {
        assertEquals(
                expected(
                        "The content of %s:\n```\n1: one\n2: two\n3: three\n4: four\n5: five\n```"),
                resultText(tool.viewTextFile(file.toString(), null).block()));
    }

    @Test
    void reportsRangeStartingAfterEndOfFile() {
        assertEquals(
                "Error: Invalid range: start line 10 is greater than end line 5.",
                resultText(tool.viewTextFile(file.toString(), "10,20").block()));
    }

    private String expected(String template) {
        return String.format(template, file);
    }

    private String resultText(ToolResultBlock result) {
        return ((TextBlock) result.getOutput().get(0)).getText();
    }
}
