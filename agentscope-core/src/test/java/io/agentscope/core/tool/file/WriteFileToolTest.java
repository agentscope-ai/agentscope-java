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
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.agentscope.core.message.ToolResultBlock;
import io.agentscope.core.message.ToolResultState;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class WriteFileToolTest {

    private static final String ORIGINAL_CONTENT = "one\ntwo\nthree\nfour\nfive\n";

    @TempDir Path tempDir;

    private WriteFileTool tool;
    private Path file;

    @BeforeEach
    void setUp() throws IOException {
        tool = new WriteFileTool(tempDir.toString());
        file = tempDir.resolve("sample.txt");
        Files.writeString(file, ORIGINAL_CONTENT, StandardCharsets.UTF_8);
    }

    @Test
    void replacesSpecifiedRangeAndPreservesTrailingNewline() {
        ToolResultBlock result = tool.writeTextFile(file.toString(), "NEW", "2,3").block();

        assertEquals(ToolResultState.RUNNING, result.getState());
        assertEquals("one\nNEW\nfour\nfive\n", readFile());
    }

    @Test
    void doesNotAddTrailingNewlineWhenOriginalHadNone() throws IOException {
        Files.writeString(file, "one\ntwo\nthree", StandardCharsets.UTF_8);

        ToolResultBlock result = tool.writeTextFile(file.toString(), "NEW", "1,1").block();

        assertEquals(ToolResultState.RUNNING, result.getState());
        assertEquals("NEW\ntwo\nthree", readFile());
    }

    @Test
    void stripsSingleTrailingNewlineFromReplacementContent() {
        ToolResultBlock result = tool.writeTextFile(file.toString(), "NEW\n", "2,2").block();

        assertEquals(ToolResultState.RUNNING, result.getState());
        assertEquals("one\nNEW\nthree\nfour\nfive\n", readFile());
    }

    @Test
    void clampsEndBeyondFileLengthToEndOfFile() {
        ToolResultBlock result = tool.writeTextFile(file.toString(), "NEW", "3,999").block();

        assertEquals(ToolResultState.RUNNING, result.getState());
        assertEquals("one\ntwo\nNEW\n", readFile());
    }

    @Test
    void rejectsReversedRangeWithoutModifyingFile() {
        ToolResultBlock result = tool.writeTextFile(file.toString(), "NEW", "5,2").block();

        assertEquals(ToolResultState.ERROR, result.getState());
        assertEquals(ORIGINAL_CONTENT, readFile());
    }

    @Test
    void rejectsZeroBasedStartWithoutModifyingFile() {
        ToolResultBlock result = tool.writeTextFile(file.toString(), "NEW", "0,2").block();

        assertEquals(ToolResultState.ERROR, result.getState());
        assertEquals(ORIGINAL_CONTENT, readFile());
    }

    @Test
    void rejectsNegativeRangeWithoutModifyingFile() {
        ToolResultBlock result = tool.writeTextFile(file.toString(), "NEW", "[-3,-1]").block();

        assertEquals(ToolResultState.ERROR, result.getState());
        assertEquals(ORIGINAL_CONTENT, readFile());
    }

    @Test
    void rejectsStartBeyondFileLengthWithoutModifyingFile() {
        ToolResultBlock result = tool.writeTextFile(file.toString(), "NEW", "10,20").block();

        assertEquals(ToolResultState.ERROR, result.getState());
        assertEquals(ORIGINAL_CONTENT, readFile());
    }

    @Test
    void createsNewFileWhenFileDoesNotExist() {
        Path newFile = tempDir.resolve("new.txt");

        ToolResultBlock result =
                tool.writeTextFile(newFile.toString(), "fresh content", null).block();

        assertEquals(ToolResultState.RUNNING, result.getState());
        assertTrue(Files.exists(newFile));
        assertEquals("fresh content", readFile(newFile));
    }

    private String readFile() {
        return readFile(file);
    }

    private String readFile(Path path) {
        try {
            return Files.readString(path, StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
    }
}
