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
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

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

/**
 * Unit tests for FileToolUtils.
 *
 * <p>Tests utility methods for file operations including view range calculation,
 * range parsing, and file content viewing.
 *
 * <p>Tagged as "unit" - fast running tests without external dependencies.
 */
@Tag("unit")
@DisplayName("FileToolUtils Unit Tests")
class FileToolUtilsTest {

    @TempDir Path tempDir;

    private Path testFile;

    @BeforeEach
    void setUp() throws IOException {
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
    @DisplayName("Should calculate view ranges correctly for insertion")
    void testCalculateViewRanges_Insertion() {
        // Insert at line 5, original 10 lines, new 11 lines
        int[] range = FileToolUtils.calculateViewRanges(10, 11, 5, 5, 2);

        // Should show lines [3, 8] (5-2 to 5+2+1)
        assertEquals(3, range[0], "Start line should be 3");
        assertEquals(8, range[1], "End line should be 8");
    }

    @Test
    @DisplayName("Should calculate view ranges with boundary at start")
    void testCalculateViewRanges_StartBoundary() {
        // Insert at line 1, with extra lines
        int[] range = FileToolUtils.calculateViewRanges(10, 11, 1, 1, 5);

        // Should start from line 1 (can't go below 1)
        assertEquals(1, range[0], "Start line should be 1");
        assertTrue(range[1] > 1, "End line should be greater than 1");
    }

    @Test
    @DisplayName("Should calculate view ranges with boundary at end")
    void testCalculateViewRanges_EndBoundary() {
        // Insert at line 10, original 10 lines, new 11 lines
        int[] range = FileToolUtils.calculateViewRanges(10, 11, 10, 10, 5);

        // Should not exceed new line count
        assertTrue(range[0] >= 1, "Start should be at least 1");
        assertEquals(11, range[1], "End should not exceed new line count");
    }

    @Test
    @DisplayName("Should calculate view ranges for replacement")
    void testCalculateViewRanges_Replacement() {
        // Replace lines 3-7 (5 lines), original 10 lines, new 10 lines
        int[] range = FileToolUtils.calculateViewRanges(10, 10, 3, 7, 2);

        // Should show context around the replacement
        assertEquals(1, range[0], "Start line should be 1");
        assertTrue(range[1] <= 10, "End should not exceed file length");
    }

    @Test
    @DisplayName("Should view entire file content")
    void testViewTextFile_EntireFile() {
        String content = FileToolUtils.viewTextFile(testFile.toString(), 1, 10);

        assertNotNull(content);
        assertTrue(content.contains("1: Line 1"), "Should contain line 1");
        assertTrue(content.contains("10: Line 10"), "Should contain line 10");
        assertEquals(10, content.split("\n").length, "Should have 10 lines");
    }

    @Test
    @DisplayName("Should view specific range of file")
    void testViewTextFile_SpecificRange() {
        String content = FileToolUtils.viewTextFile(testFile.toString(), 3, 5);

        assertNotNull(content);
        assertTrue(content.contains("3: Line 3"), "Should contain line 3");
        assertTrue(content.contains("4: Line 4"), "Should contain line 4");
        assertTrue(content.contains("5: Line 5"), "Should contain line 5");
        assertFalse(content.contains("Line 2"), "Should not contain line 2");
        assertFalse(content.contains("Line 6"), "Should not contain line 6");
        assertEquals(3, content.split("\n").length, "Should have 3 lines");
    }

    @Test
    @DisplayName("Should handle view range beyond file length")
    void testViewTextFile_RangeBeyondFile() {
        String content = FileToolUtils.viewTextFile(testFile.toString(), 5, 20);

        assertNotNull(content);
        assertTrue(content.contains("5: Line 5"), "Should contain line 5");
        assertTrue(content.contains("10: Line 10"), "Should contain line 10");
        assertFalse(content.contains("Line 11"), "Should not have line 11");
    }

    @Test
    @DisplayName("Should handle view with invalid start line")
    void testViewTextFile_InvalidStartLine() {
        String content = FileToolUtils.viewTextFile(testFile.toString(), 0, 5);

        assertNotNull(content);
        assertTrue(content.contains("1: Line 1"), "Should start from line 1");
        assertTrue(content.contains("5: Line 5"), "Should contain line 5");
    }

    @Test
    @DisplayName("Should handle non-existent file")
    void testViewTextFile_NonExistentFile() {
        String content = FileToolUtils.viewTextFile("non_existent.txt", 1, 10);

        assertNotNull(content);
        assertTrue(content.contains("Error reading file"), "Should contain error message");
    }

    @Test
    @DisplayName("Should parse simple range format")
    void testParseRanges_SimpleFormat() {
        int[] range = FileToolUtils.parseRanges("1,5");

        assertNotNull(range);
        assertEquals(2, range.length);
        assertEquals(1, range[0]);
        assertEquals(5, range[1]);
    }

    @Test
    @DisplayName("Should parse bracket range format")
    void testParseRanges_BracketFormat() {
        int[] range = FileToolUtils.parseRanges("[1,5]");

        assertNotNull(range);
        assertEquals(2, range.length);
        assertEquals(1, range[0]);
        assertEquals(5, range[1]);
    }

    @Test
    @DisplayName("Should parse range with spaces")
    void testParseRanges_WithSpaces() {
        int[] range = FileToolUtils.parseRanges("  [ 10 , 20 ]  ");

        assertNotNull(range);
        assertEquals(2, range.length);
        assertEquals(10, range[0]);
        assertEquals(20, range[1]);
    }

    @Test
    @DisplayName("Should parse negative range")
    void testParseRanges_NegativeNumbers() {
        int[] range = FileToolUtils.parseRanges("-100,-1");

        assertNotNull(range);
        assertEquals(2, range.length);
        assertEquals(-100, range[0]);
        assertEquals(-1, range[1]);
    }

    @Test
    @DisplayName("Should return null for invalid format - single number")
    void testParseRanges_InvalidFormat_SingleNumber() {
        int[] range = FileToolUtils.parseRanges("5");

        assertNull(range, "Should return null for invalid format");
    }

    @Test
    @DisplayName("Should return null for invalid format - three numbers")
    void testParseRanges_InvalidFormat_ThreeNumbers() {
        int[] range = FileToolUtils.parseRanges("1,5,10");

        assertNull(range, "Should return null for invalid format");
    }

    @Test
    @DisplayName("Should return null for invalid format - non-numeric")
    void testParseRanges_InvalidFormat_NonNumeric() {
        int[] range = FileToolUtils.parseRanges("abc,def");

        assertNull(range, "Should return null for non-numeric values");
    }

    @Test
    @DisplayName("Should return null for empty string")
    void testParseRanges_EmptyString() {
        int[] range = FileToolUtils.parseRanges("");

        assertNull(range, "Should return null for empty string");
    }

    @Test
    @DisplayName("Should return null for null input")
    void testParseRanges_NullInput() {
        assertThrows(
                NullPointerException.class,
                () -> {
                    FileToolUtils.parseRanges(null);
                },
                "Should throw NullPointerException for null input");
    }
}
