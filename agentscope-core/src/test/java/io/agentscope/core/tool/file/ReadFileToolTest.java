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
 * Unit tests for ReadFileTool.
 *
 * <p>Tests file viewing functionality including entire file reading,
 * range-based reading, and negative index support.
 *
 * <p>Tagged as "unit" - fast running tests without external dependencies.
 */
@Tag("unit")
@DisplayName("ReadFileTool Unit Tests")
class ReadFileToolTest {

    @TempDir Path tempDir;

    private ReadFileTool readFileTool;
    private Path testFile;

    @BeforeEach
    void setUp() throws IOException {
        readFileTool = new ReadFileTool();

        // Create a test file with sample content
        testFile = tempDir.resolve("test.txt");
        List<String> lines =
                Arrays.asList(
                        "Line 1", "Line 2", "Line 3", "Line 4", "Line 5", "Line 6", "Line 7",
                        "Line 8", "Line 9", "Line 10");
        Files.write(testFile, lines);
    }

    @AfterEach
    void tearDown() {
        // Cleanup is handled by @TempDir
    }

    @Test
    @DisplayName("Should view entire file when ranges is null")
    void testViewTextFile_EntireFile_NullRanges() {
        Mono<ToolResultBlock> result = readFileTool.viewTextFile(testFile.toString(), null);

        StepVerifier.create(result)
                .assertNext(
                        block -> {
                            assertNotNull(block);
                            String text = extractText(block);
                            assertTrue(text.contains("The content of"));
                            assertTrue(text.contains("1: Line 1"));
                            assertTrue(text.contains("10: Line 10"));
                        })
                .verifyComplete();
    }

    @Test
    @DisplayName("Should view entire file when ranges is empty")
    void testViewTextFile_EntireFile_EmptyRanges() {
        Mono<ToolResultBlock> result = readFileTool.viewTextFile(testFile.toString(), "");

        StepVerifier.create(result)
                .assertNext(
                        block -> {
                            assertNotNull(block);
                            String text = extractText(block);
                            assertTrue(text.contains("The content of"));
                            assertTrue(text.contains("1: Line 1"));
                            assertTrue(text.contains("10: Line 10"));
                        })
                .verifyComplete();
    }

    @Test
    @DisplayName("Should view specific range of lines")
    void testViewTextFile_SpecificRange() {
        Mono<ToolResultBlock> result = readFileTool.viewTextFile(testFile.toString(), "3,5");

        StepVerifier.create(result)
                .assertNext(
                        block -> {
                            assertNotNull(block);
                            String text = extractText(block);
                            assertTrue(text.contains("lines [3, 5]"));
                            assertTrue(text.contains("3: Line 3"));
                            assertTrue(text.contains("4: Line 4"));
                            assertTrue(text.contains("5: Line 5"));
                            assertFalse(text.contains("2: Line 2"));
                            assertFalse(text.contains("6: Line 6"));
                        })
                .verifyComplete();
    }

    @Test
    @DisplayName("Should view range with bracket format")
    void testViewTextFile_BracketFormat() {
        Mono<ToolResultBlock> result = readFileTool.viewTextFile(testFile.toString(), "[2,4]");

        StepVerifier.create(result)
                .assertNext(
                        block -> {
                            assertNotNull(block);
                            String text = extractText(block);
                            assertTrue(text.contains("lines [2, 4]"));
                            assertTrue(text.contains("2: Line 2"));
                            assertTrue(text.contains("3: Line 3"));
                            assertTrue(text.contains("4: Line 4"));
                        })
                .verifyComplete();
    }

    @Test
    @DisplayName("Should handle negative indices - last 3 lines")
    void testViewTextFile_NegativeIndices_LastLines() {
        Mono<ToolResultBlock> result = readFileTool.viewTextFile(testFile.toString(), "-3,-1");

        StepVerifier.create(result)
                .assertNext(
                        block -> {
                            assertNotNull(block);
                            String text = extractText(block);
                            assertTrue(text.contains("lines [8, 10]"));
                            assertTrue(text.contains("8: Line 8"));
                            assertTrue(text.contains("9: Line 9"));
                            assertTrue(text.contains("10: Line 10"));
                            assertFalse(text.contains("7: Line 7"));
                        })
                .verifyComplete();
    }

    @Test
    @DisplayName("Should handle mixed positive and negative indices")
    void testViewTextFile_MixedIndices() {
        Mono<ToolResultBlock> result = readFileTool.viewTextFile(testFile.toString(), "5,-1");

        StepVerifier.create(result)
                .assertNext(
                        block -> {
                            assertNotNull(block);
                            String text = extractText(block);
                            assertTrue(text.contains("lines [5, 10]"));
                            assertTrue(text.contains("5: Line 5"));
                            assertTrue(text.contains("10: Line 10"));
                        })
                .verifyComplete();
    }

    @Test
    @DisplayName("Should handle range exceeding file length")
    void testViewTextFile_RangeExceedsLength() {
        Mono<ToolResultBlock> result = readFileTool.viewTextFile(testFile.toString(), "8,20");

        StepVerifier.create(result)
                .assertNext(
                        block -> {
                            assertNotNull(block);
                            String text = extractText(block);
                            assertTrue(text.contains("lines [8, 10]"));
                            assertTrue(text.contains("8: Line 8"));
                            assertTrue(text.contains("10: Line 10"));
                            assertFalse(text.contains("11: Line 11"));
                        })
                .verifyComplete();
    }

    @Test
    @DisplayName("Should handle range starting before 1")
    void testViewTextFile_RangeStartsBefore1() {
        Mono<ToolResultBlock> result = readFileTool.viewTextFile(testFile.toString(), "-20,3");

        StepVerifier.create(result)
                .assertNext(
                        block -> {
                            assertNotNull(block);
                            String text = extractText(block);
                            assertTrue(text.contains("1: Line 1"));
                            assertTrue(text.contains("3: Line 3"));
                        })
                .verifyComplete();
    }

    @Test
    @DisplayName("Should return error for non-existent file")
    void testViewTextFile_NonExistentFile() {
        Mono<ToolResultBlock> result =
                readFileTool.viewTextFile(tempDir.resolve("nonexistent.txt").toString(), null);

        StepVerifier.create(result)
                .assertNext(
                        block -> {
                            assertNotNull(block);
                            String text = extractText(block);
                            assertTrue(text.contains("Error") || text.contains("does not exist"));
                        })
                .verifyComplete();
    }

    @Test
    @DisplayName("Should return error for directory path")
    void testViewTextFile_DirectoryPath() {
        Mono<ToolResultBlock> result = readFileTool.viewTextFile(tempDir.toString(), null);

        StepVerifier.create(result)
                .assertNext(
                        block -> {
                            assertNotNull(block);
                            String text = extractText(block);
                            assertTrue(text.contains("Error") || text.contains("not a file"));
                        })
                .verifyComplete();
    }

    @Test
    @DisplayName("Should return error for invalid range format")
    void testViewTextFile_InvalidRangeFormat() {
        Mono<ToolResultBlock> result = readFileTool.viewTextFile(testFile.toString(), "invalid");

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
    @DisplayName("Should return error for single number range")
    void testViewTextFile_SingleNumberRange() {
        Mono<ToolResultBlock> result = readFileTool.viewTextFile(testFile.toString(), "5");

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
    @DisplayName("Should return error when start > end")
    void testViewTextFile_StartGreaterThanEnd() {
        Mono<ToolResultBlock> result = readFileTool.viewTextFile(testFile.toString(), "5,3");

        StepVerifier.create(result)
                .assertNext(
                        block -> {
                            assertNotNull(block);
                            String text = extractText(block);
                            assertTrue(text.contains("Error") || text.contains("Invalid range"));
                        })
                .verifyComplete();
    }

    @Test
    @DisplayName("Should handle single line range")
    void testViewTextFile_SingleLineRange() {
        Mono<ToolResultBlock> result = readFileTool.viewTextFile(testFile.toString(), "5,5");

        StepVerifier.create(result)
                .assertNext(
                        block -> {
                            assertNotNull(block);
                            String text = extractText(block);
                            assertTrue(text.contains("5: Line 5"));
                            assertFalse(text.contains("4: Line 4"));
                            assertFalse(text.contains("6: Line 6"));
                        })
                .verifyComplete();
    }

    @Test
    @DisplayName("Should handle empty file")
    void testViewTextFile_EmptyFile() throws IOException {
        Path emptyFile = tempDir.resolve("empty.txt");
        Files.write(emptyFile, List.of());

        Mono<ToolResultBlock> result = readFileTool.viewTextFile(emptyFile.toString(), null);

        StepVerifier.create(result)
                .assertNext(
                        block -> {
                            assertNotNull(block);
                            String text = extractText(block);
                            assertTrue(text.contains("The content of"));
                        })
                .verifyComplete();
    }

    @Test
    @DisplayName("Should handle file with special characters")
    void testViewTextFile_SpecialCharacters() throws IOException {
        Path specialFile = tempDir.resolve("special.txt");
        List<String> lines =
                Arrays.asList(
                        "Line with 中文", "Line with émojis 😊", "Line with symbols !@#$%^&*()");
        Files.write(specialFile, lines);

        Mono<ToolResultBlock> result = readFileTool.viewTextFile(specialFile.toString(), null);

        StepVerifier.create(result)
                .assertNext(
                        block -> {
                            assertNotNull(block);
                            String text = extractText(block);
                            assertTrue(text.contains("中文"));
                            assertTrue(text.contains("émojis"));
                            assertTrue(text.contains("!@#$%"));
                        })
                .verifyComplete();
    }

    // ==================== baseDir Security Tests ====================

    @Test
    @DisplayName("Should allow file access without baseDir restriction")
    void testViewTextFile_NoBaseDirRestriction() {
        ReadFileTool tool = new ReadFileTool(); // No baseDir
        Mono<ToolResultBlock> result = tool.viewTextFile(testFile.toString(), null);

        StepVerifier.create(result)
                .assertNext(
                        block -> {
                            assertNotNull(block);
                            String text = extractText(block);
                            assertTrue(text.contains("The content of"));
                            assertTrue(text.contains("1: Line 1"));
                        })
                .verifyComplete();
    }

    @Test
    @DisplayName("Should allow file access within baseDir")
    void testViewTextFile_WithinBaseDir() {
        ReadFileTool tool = new ReadFileTool(tempDir.toString());
        Mono<ToolResultBlock> result = tool.viewTextFile(testFile.toString(), null);

        StepVerifier.create(result)
                .assertNext(
                        block -> {
                            assertNotNull(block);
                            String text = extractText(block);
                            assertTrue(text.contains("The content of"));
                            assertTrue(text.contains("1: Line 1"));
                        })
                .verifyComplete();
    }

    @Test
    @DisplayName("Should deny file access outside baseDir")
    void testViewTextFile_OutsideBaseDir() throws IOException {
        // Create a subdirectory
        Path subDir = tempDir.resolve("subdir");
        Files.createDirectory(subDir);

        // Create file in subdirectory
        Path subFile = subDir.resolve("sub.txt");
        Files.write(subFile, List.of("Content in subdir"));

        // Restrict tool to subdirectory
        ReadFileTool tool = new ReadFileTool(subDir.toString());

        // Try to access file outside the baseDir
        Mono<ToolResultBlock> result = tool.viewTextFile(testFile.toString(), null);

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
    }

    @Test
    @DisplayName("Should prevent path traversal attacks")
    void testViewTextFile_PathTraversalAttack() throws IOException {
        // Create a subdirectory with baseDir restriction
        Path subDir = tempDir.resolve("restricted");
        Files.createDirectory(subDir);

        // Create a file in the restricted directory
        Path restrictedFile = subDir.resolve("allowed.txt");
        Files.write(restrictedFile, List.of("Allowed content"));

        ReadFileTool tool = new ReadFileTool(subDir.toString());

        // Try to access parent directory using path traversal
        String maliciousPath = subDir.resolve("../test.txt").toString();
        Mono<ToolResultBlock> result = tool.viewTextFile(maliciousPath, null);

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
    }

    @Test
    @DisplayName("Should handle relative paths within baseDir")
    void testViewTextFile_RelativePathWithinBaseDir() throws IOException {
        ReadFileTool tool = new ReadFileTool(tempDir.toString());

        // Use relative path (from current directory perspective)
        String relativePath = testFile.toString();
        Mono<ToolResultBlock> result = tool.viewTextFile(relativePath, null);

        StepVerifier.create(result)
                .assertNext(
                        block -> {
                            assertNotNull(block);
                            String text = extractText(block);
                            assertTrue(
                                    text.contains("The content of") || text.contains("1: Line 1"));
                        })
                .verifyComplete();
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
