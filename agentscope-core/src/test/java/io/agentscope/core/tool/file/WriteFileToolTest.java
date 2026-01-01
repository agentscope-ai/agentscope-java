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
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.agentscope.core.message.ToolResultBlock;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;

/**
 * Unit tests for WriteFileTool.
 *
 * <p>Tests file writing and insertion functionality including content insertion,
 * file creation, range-based replacement, and error handling.
 *
 * <p>Tagged as "unit" - fast running tests without external dependencies.
 */
@Tag("unit")
@DisplayName("WriteFileTool Unit Tests")
class WriteFileToolTest {

    @TempDir Path tempDir;

    private WriteFileTool writeFileTool;
    private Path testFile;

    @BeforeEach
    void setUp() throws IOException {
        writeFileTool = new WriteFileTool();

        // Create a test file with sample content
        testFile = tempDir.resolve("test.txt");
        List<String> lines = Arrays.asList("Line 1", "Line 2", "Line 3", "Line 4", "Line 5");
        Files.write(testFile, lines);
    }

    @AfterEach
    void tearDown() {
        // Cleanup is handled by @TempDir
    }

    // ==================== insertTextFile Tests ====================

    @Test
    @DisplayName("Should insert content at the beginning of file")
    void testInsertTextFile_AtBeginning() throws IOException {
        Mono<ToolResultBlock> result =
                writeFileTool.insertTextFile(testFile.toString(), "New Line 0", 1);

        StepVerifier.create(result)
                .assertNext(
                        block -> {
                            assertNotNull(block);
                            String text = extractText(block);
                            assertTrue(
                                    text.contains("Insert content")
                                            && text.contains("successfully"));
                        })
                .verifyComplete();

        // Verify file content
        List<String> lines = Files.readAllLines(testFile);
        assertEquals(6, lines.size());
        assertEquals("New Line 0", lines.get(0));
        assertEquals("Line 1", lines.get(1));
    }

    @Test
    @DisplayName("Should insert content in the middle of file")
    void testInsertTextFile_InMiddle() throws IOException {
        Mono<ToolResultBlock> result =
                writeFileTool.insertTextFile(testFile.toString(), "New Line 2.5", 3);

        StepVerifier.create(result)
                .assertNext(
                        block -> {
                            assertNotNull(block);
                            String text = extractText(block);
                            assertTrue(
                                    text.contains("Insert content")
                                            && text.contains("successfully"));
                        })
                .verifyComplete();

        // Verify file content
        List<String> lines = Files.readAllLines(testFile);
        assertEquals(6, lines.size());
        assertEquals("Line 2", lines.get(1));
        assertEquals("New Line 2.5", lines.get(2));
        assertEquals("Line 3", lines.get(3));
    }

    @Test
    @DisplayName("Should append content at the end of file")
    void testInsertTextFile_AtEnd() throws IOException {
        Mono<ToolResultBlock> result =
                writeFileTool.insertTextFile(testFile.toString(), "New Line 6", 6);

        StepVerifier.create(result)
                .assertNext(
                        block -> {
                            assertNotNull(block);
                            String text = extractText(block);
                            assertTrue(
                                    text.contains("Insert content")
                                            && text.contains("successfully"));
                        })
                .verifyComplete();

        // Verify file content
        List<String> lines = Files.readAllLines(testFile);
        assertEquals(6, lines.size());
        assertEquals("Line 5", lines.get(4));
        assertEquals("New Line 6", lines.get(5));
    }

    @Test
    @DisplayName("Should return error for invalid line number - zero")
    void testInsertTextFile_InvalidLineNumber_Zero() {
        Mono<ToolResultBlock> result =
                writeFileTool.insertTextFile(testFile.toString(), "content", 0);

        StepVerifier.create(result)
                .assertNext(
                        block -> {
                            assertNotNull(block);
                            String text = extractText(block);
                            assertTrue(text.contains("Error") || text.contains("invalid"));
                        })
                .verifyComplete();
    }

    @Test
    @DisplayName("Should return error for invalid line number - negative")
    void testInsertTextFile_InvalidLineNumber_Negative() {
        Mono<ToolResultBlock> result =
                writeFileTool.insertTextFile(testFile.toString(), "content", -1);

        StepVerifier.create(result)
                .assertNext(
                        block -> {
                            assertNotNull(block);
                            String text = extractText(block);
                            assertTrue(text.contains("Error") || text.contains("invalid"));
                        })
                .verifyComplete();
    }

    @Test
    @DisplayName("Should return error for line number exceeding valid range")
    void testInsertTextFile_LineNumberExceedsRange() {
        Mono<ToolResultBlock> result =
                writeFileTool.insertTextFile(
                        testFile.toString(), "content", 10 // File has only 5 lines, max valid is 6
                        );

        StepVerifier.create(result)
                .assertNext(
                        block -> {
                            assertNotNull(block);
                            String text = extractText(block);
                            assertTrue(
                                    text.contains("Error")
                                            || text.contains("not in the valid range"));
                        })
                .verifyComplete();
    }

    @Test
    @DisplayName("Should return error for non-existent file")
    void testInsertTextFile_NonExistentFile() {
        Mono<ToolResultBlock> result =
                writeFileTool.insertTextFile(
                        tempDir.resolve("nonexistent.txt").toString(), "content", 1);

        StepVerifier.create(result)
                .assertNext(
                        block -> {
                            assertNotNull(block);
                            String text = extractText(block);
                            assertTrue(text.contains("Error") || text.contains("does not exist"));
                        })
                .verifyComplete();
    }

    // ==================== writeTextFile Tests - Create New File ====================

    @Test
    @DisplayName("Should create new file with content")
    void testWriteTextFile_CreateNewFile() throws IOException {
        Path newFile = tempDir.resolve("new.txt");

        Mono<ToolResultBlock> result =
                writeFileTool.writeTextFile(newFile.toString(), "New file content", null);

        StepVerifier.create(result)
                .assertNext(
                        block -> {
                            assertNotNull(block);
                            String text = extractText(block);
                            assertTrue(
                                    text.contains("Create and write")
                                            && text.contains("successfully"));
                        })
                .verifyComplete();

        // Verify file content
        assertTrue(Files.exists(newFile));
        String content = Files.readString(newFile);
        assertEquals("New file content", content);
    }

    @Test
    @DisplayName("Should create new file and ignore ranges parameter")
    void testWriteTextFile_CreateNewFile_IgnoreRanges() throws IOException {
        Path newFile = tempDir.resolve("new2.txt");

        Mono<ToolResultBlock> result =
                writeFileTool.writeTextFile(
                        newFile.toString(), "New file content", "1,5" // Should be ignored
                        );

        StepVerifier.create(result)
                .assertNext(
                        block -> {
                            assertNotNull(block);
                            String text = extractText(block);
                            assertTrue(
                                    text.contains("Create and write")
                                            && text.contains("successfully"));
                            assertTrue(text.contains("ignored"));
                        })
                .verifyComplete();

        // Verify file content
        assertTrue(Files.exists(newFile));
        String content = Files.readString(newFile);
        assertEquals("New file content", content);
    }

    // ==================== writeTextFile Tests - Overwrite Entire File ====================

    @Test
    @DisplayName("Should overwrite entire file when ranges is null")
    void testWriteTextFile_OverwriteEntireFile_NullRanges() throws IOException {
        Mono<ToolResultBlock> result =
                writeFileTool.writeTextFile(testFile.toString(), "Completely new content", null);

        StepVerifier.create(result)
                .assertNext(
                        block -> {
                            assertNotNull(block);
                            String text = extractText(block);
                            assertTrue(text.contains("Overwrite") && text.contains("successfully"));
                        })
                .verifyComplete();

        // Verify file content
        String content = Files.readString(testFile);
        assertEquals("Completely new content", content);
    }

    @Test
    @DisplayName("Should overwrite entire file when ranges is empty")
    void testWriteTextFile_OverwriteEntireFile_EmptyRanges() throws IOException {
        Mono<ToolResultBlock> result =
                writeFileTool.writeTextFile(testFile.toString(), "Completely new content", "");

        StepVerifier.create(result)
                .assertNext(
                        block -> {
                            assertNotNull(block);
                            String text = extractText(block);
                            assertTrue(text.contains("Overwrite") && text.contains("successfully"));
                        })
                .verifyComplete();

        // Verify file content
        String content = Files.readString(testFile);
        assertEquals("Completely new content", content);
    }

    // ==================== writeTextFile Tests - Replace Range ====================

    @Test
    @DisplayName("Should replace lines in specified range")
    void testWriteTextFile_ReplaceRange() throws IOException {
        Mono<ToolResultBlock> result =
                writeFileTool.writeTextFile(testFile.toString(), "Replaced Lines 2-3", "2,3");

        StepVerifier.create(result)
                .assertNext(
                        block -> {
                            assertNotNull(block);
                            String text = extractText(block);
                            assertTrue(text.contains("Write") && text.contains("successfully"));
                        })
                .verifyComplete();

        // Verify file content
        List<String> lines = Files.readAllLines(testFile);
        assertEquals("Line 1", lines.get(0));
        assertTrue(lines.get(1).contains("Replaced Lines 2-3"));
        assertEquals("Line 4", lines.get(2));
    }

    @Test
    @DisplayName("Should replace range with bracket format")
    void testWriteTextFile_ReplaceRange_BracketFormat() throws IOException {
        Mono<ToolResultBlock> result =
                writeFileTool.writeTextFile(testFile.toString(), "Replaced Content", "[1,2]");

        StepVerifier.create(result)
                .assertNext(
                        block -> {
                            assertNotNull(block);
                            String text = extractText(block);
                            assertTrue(text.contains("Write") && text.contains("successfully"));
                        })
                .verifyComplete();

        // Verify file content
        List<String> lines = Files.readAllLines(testFile);
        assertTrue(lines.get(0).contains("Replaced Content"));
        assertEquals("Line 3", lines.get(1));
    }

    @Test
    @DisplayName("Should replace from beginning to middle")
    void testWriteTextFile_ReplaceFromBeginning() throws IOException {
        Mono<ToolResultBlock> result =
                writeFileTool.writeTextFile(testFile.toString(), "New Beginning", "1,3");

        StepVerifier.create(result)
                .assertNext(
                        block -> {
                            assertNotNull(block);
                            String text = extractText(block);
                            assertTrue(text.contains("Write") && text.contains("successfully"));
                        })
                .verifyComplete();

        // Verify file content
        List<String> lines = Files.readAllLines(testFile);
        assertTrue(lines.get(0).contains("New Beginning"));
        assertEquals("Line 4", lines.get(1));
        assertEquals("Line 5", lines.get(2));
    }

    @Test
    @DisplayName("Should replace from middle to end")
    void testWriteTextFile_ReplaceToEnd() throws IOException {
        Mono<ToolResultBlock> result =
                writeFileTool.writeTextFile(testFile.toString(), "New Ending", "3,5");

        StepVerifier.create(result)
                .assertNext(
                        block -> {
                            assertNotNull(block);
                            String text = extractText(block);
                            assertTrue(text.contains("Write") && text.contains("successfully"));
                        })
                .verifyComplete();

        // Verify file content
        List<String> lines = Files.readAllLines(testFile);
        assertEquals("Line 1", lines.get(0));
        assertEquals("Line 2", lines.get(1));
        assertTrue(lines.get(2).contains("New Ending"));
    }

    @Test
    @DisplayName("Should return error for invalid range format")
    void testWriteTextFile_InvalidRangeFormat() {
        Mono<ToolResultBlock> result =
                writeFileTool.writeTextFile(testFile.toString(), "content", "invalid");

        StepVerifier.create(result)
                .assertNext(
                        block -> {
                            assertNotNull(block);
                            String text = extractText(block);
                            assertTrue(
                                    text.contains("Error")
                                            || text.contains("Invalid range format"));
                        })
                .verifyComplete();
    }

    @Test
    @DisplayName("Should return error when start line exceeds file length")
    void testWriteTextFile_StartLineExceedsLength() {
        Mono<ToolResultBlock> result =
                writeFileTool.writeTextFile(
                        testFile.toString(), "content", "10,15" // File has only 5 lines
                        );

        StepVerifier.create(result)
                .assertNext(
                        block -> {
                            assertNotNull(block);
                            String text = extractText(block);
                            assertTrue(text.contains("Error") || text.contains("invalid"));
                        })
                .verifyComplete();
    }

    // ==================== Edge Cases ====================

    @Test
    @DisplayName("Should handle empty content insertion")
    void testInsertTextFile_EmptyContent() throws IOException {
        Mono<ToolResultBlock> result = writeFileTool.insertTextFile(testFile.toString(), "", 3);

        StepVerifier.create(result)
                .assertNext(
                        block -> {
                            assertNotNull(block);
                            String text = extractText(block);
                            assertTrue(
                                    text.contains("Insert content")
                                            && text.contains("successfully"));
                        })
                .verifyComplete();

        // Verify file content
        List<String> lines = Files.readAllLines(testFile);
        assertEquals(6, lines.size());
        assertEquals("", lines.get(2));
    }

    @Test
    @DisplayName("Should handle multiline content insertion")
    void testInsertTextFile_MultilineContent() throws IOException {
        Mono<ToolResultBlock> result =
                writeFileTool.insertTextFile(testFile.toString(), "Line A\nLine B\nLine C", 3);

        StepVerifier.create(result)
                .assertNext(
                        block -> {
                            assertNotNull(block);
                            String text = extractText(block);
                            assertTrue(
                                    text.contains("Insert content")
                                            && text.contains("successfully"));
                        })
                .verifyComplete();

        // Verify file was modified
        List<String> lines = Files.readAllLines(testFile);
        assertTrue(lines.size() > 5);
    }

    @Test
    @DisplayName("Should handle special characters in content")
    void testWriteTextFile_SpecialCharacters() throws IOException {
        String specialContent = "Content with 中文, émojis 😊, and symbols !@#$%";

        Mono<ToolResultBlock> result =
                writeFileTool.writeTextFile(testFile.toString(), specialContent, null);

        StepVerifier.create(result)
                .assertNext(
                        block -> {
                            assertNotNull(block);
                            String text = extractText(block);
                            assertTrue(text.contains("Overwrite") && text.contains("successfully"));
                        })
                .verifyComplete();

        // Verify file content
        String content = Files.readString(testFile);
        assertEquals(specialContent, content);
    }

    // ==================== baseDir Security Tests ====================

    @Test
    @DisplayName("Should allow file write without baseDir restriction")
    void testWriteTextFile_NoBaseDirRestriction() throws IOException {
        WriteFileTool tool = new WriteFileTool(); // No baseDir
        Mono<ToolResultBlock> result = tool.writeTextFile(testFile.toString(), "New content", null);

        StepVerifier.create(result)
                .assertNext(
                        block -> {
                            assertNotNull(block);
                            String text = extractText(block);
                            assertTrue(text.contains("Overwrite") && text.contains("successfully"));
                        })
                .verifyComplete();

        // Verify file was written
        String content = Files.readString(testFile);
        assertEquals("New content", content);
    }

    @Test
    @DisplayName("Should allow file write within baseDir")
    void testWriteTextFile_WithinBaseDir() throws IOException {
        WriteFileTool tool = new WriteFileTool(tempDir.toString());
        Mono<ToolResultBlock> result =
                tool.writeTextFile(testFile.toString(), "Allowed content", null);

        StepVerifier.create(result)
                .assertNext(
                        block -> {
                            assertNotNull(block);
                            String text = extractText(block);
                            assertTrue(text.contains("Overwrite") && text.contains("successfully"));
                        })
                .verifyComplete();

        // Verify file was written
        String content = Files.readString(testFile);
        assertEquals("Allowed content", content);
    }

    @Test
    @DisplayName("Should deny file write outside baseDir")
    void testWriteTextFile_OutsideBaseDir() throws IOException {
        // Create a subdirectory
        Path subDir = tempDir.resolve("subdir");
        Files.createDirectory(subDir);

        // Create file in subdirectory
        Path subFile = subDir.resolve("sub.txt");
        Files.write(subFile, List.of("Original content"));

        // Restrict tool to subdirectory
        WriteFileTool tool = new WriteFileTool(subDir.toString());

        // Try to write file outside the baseDir
        Mono<ToolResultBlock> result =
                tool.writeTextFile(testFile.toString(), "Malicious content", null);

        StepVerifier.create(result)
                .assertNext(
                        block -> {
                            assertNotNull(block);
                            String text = extractText(block);
                            assertTrue(
                                    text.contains("Error")
                                            || text.contains("Access denied")
                                            || text.contains("outside"));
                        })
                .verifyComplete();

        // Verify original file was not modified
        List<String> lines = Files.readAllLines(testFile);
        assertEquals("Line 1", lines.get(0));
    }

    @Test
    @DisplayName("Should prevent path traversal attacks on write")
    void testWriteTextFile_PathTraversalAttack() throws IOException {
        // Create a subdirectory with baseDir restriction
        Path subDir = tempDir.resolve("restricted");
        Files.createDirectory(subDir);

        WriteFileTool tool = new WriteFileTool(subDir.toString());

        // Try to write to parent directory using path traversal
        String maliciousPath = subDir.resolve("../malicious.txt").toString();
        Mono<ToolResultBlock> result = tool.writeTextFile(maliciousPath, "Malicious content", null);

        StepVerifier.create(result)
                .assertNext(
                        block -> {
                            assertNotNull(block);
                            String text = extractText(block);
                            assertTrue(
                                    text.contains("Error")
                                            || text.contains("Access denied")
                                            || text.contains("outside"));
                        })
                .verifyComplete();

        // Verify malicious file was not created
        Path maliciousFile = tempDir.resolve("malicious.txt");
        assertFalse(Files.exists(maliciousFile));
    }

    @Test
    @DisplayName("Should prevent path traversal attacks on insert")
    void testInsertTextFile_PathTraversalAttack() throws IOException {
        // Create a subdirectory with baseDir restriction
        Path subDir = tempDir.resolve("restricted");
        Files.createDirectory(subDir);

        // Create a file in the restricted directory
        Path restrictedFile = subDir.resolve("allowed.txt");
        Files.write(restrictedFile, List.of("Line 1", "Line 2"));

        WriteFileTool tool = new WriteFileTool(subDir.toString());

        // Try to insert into parent directory using path traversal
        String maliciousPath = subDir.resolve("../test.txt").toString();
        Mono<ToolResultBlock> result = tool.insertTextFile(maliciousPath, "Malicious content", 1);

        StepVerifier.create(result)
                .assertNext(
                        block -> {
                            assertNotNull(block);
                            String text = extractText(block);
                            assertTrue(
                                    text.contains("Error")
                                            || text.contains("Access denied")
                                            || text.contains("outside"));
                        })
                .verifyComplete();

        // Verify original file was not modified
        List<String> lines = Files.readAllLines(testFile);
        assertEquals("Line 1", lines.get(0));
    }

    @Test
    @DisplayName("Should allow creating new files within baseDir")
    void testWriteTextFile_CreateNewFileWithinBaseDir() throws IOException {
        WriteFileTool tool = new WriteFileTool(tempDir.toString());
        Path newFile = tempDir.resolve("newfile.txt");

        Mono<ToolResultBlock> result =
                tool.writeTextFile(newFile.toString(), "New file content", null);

        StepVerifier.create(result)
                .assertNext(
                        block -> {
                            assertNotNull(block);
                            String text = extractText(block);
                            assertTrue(
                                    text.contains("Create and write")
                                            && text.contains("successfully"));
                        })
                .verifyComplete();

        // Verify new file was created
        assertTrue(Files.exists(newFile));
        String content = Files.readString(newFile);
        assertEquals("New file content", content);
    }

    /**
     * Extract text content from ToolResultBlock for assertion.
     */
    private String extractText(ToolResultBlock block) {
        if (block.getOutput() != null && !block.getOutput().isEmpty()) {
            return block.getOutput().get(0).toString();
        }
        return "";
    }
}
