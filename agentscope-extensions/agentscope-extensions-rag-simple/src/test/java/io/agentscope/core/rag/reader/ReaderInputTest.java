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
package io.agentscope.core.rag.reader;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Unit tests for ReaderInput.
 */
@Tag("unit")
@DisplayName("ReaderInput Unit Tests")
class ReaderInputTest {

    @TempDir Path tempDir;

    @Test
    @DisplayName("Should create ReaderInput from string")
    void testFromString() {
        String text = "Test content";
        ReaderInput input = ReaderInput.fromString(text);

        assertEquals(text, input.asString());
        assertEquals(ReaderInput.InputType.STRING, input.getType());
    }

    @Test
    @DisplayName("Should throw exception when creating from null string")
    void testFromStringNull() {
        assertThrows(IllegalArgumentException.class, () -> ReaderInput.fromString(null));
    }

    @Test
    @DisplayName("Should create ReaderInput from file path")
    void testFromFilePathString() throws IOException {
        Path testFile = tempDir.resolve("test.txt");
        String content = "Test file content";
        Files.writeString(testFile, content);

        ReaderInput input = ReaderInput.fromFile(testFile.toString());

        assertEquals(content, input.asString());
        assertEquals(ReaderInput.InputType.FILE, input.getType());
    }

    @Test
    @DisplayName("Should create ReaderInput from Path")
    void testFromFilePath() throws IOException {
        Path testFile = tempDir.resolve("test.txt");
        String content = "Test file content";
        Files.writeString(testFile, content);

        ReaderInput input = ReaderInput.fromFile(testFile);

        assertEquals(content, input.asString());
        assertEquals(ReaderInput.InputType.FILE, input.getType());
    }

    @Test
    @DisplayName("Should create ReaderInput from File")
    void testFromFile() throws IOException {
        File testFile = tempDir.resolve("test.txt").toFile();
        String content = "Test file content";
        Files.writeString(testFile.toPath(), content);

        ReaderInput input = ReaderInput.fromFile(testFile);

        assertEquals(content, input.asString());
        assertEquals(ReaderInput.InputType.FILE, input.getType());
    }

    @Test
    @DisplayName("Should throw exception when file does not exist")
    void testFromFileNotExists() {
        Path nonExistentFile = tempDir.resolve("nonexistent.txt");
        assertThrows(IOException.class, () -> ReaderInput.fromFile(nonExistentFile));
    }

    @Test
    @DisplayName("Should throw exception when creating from null file path")
    void testFromFilePathNull() {
        assertThrows(IllegalArgumentException.class, () -> ReaderInput.fromFile((String) null));
        assertThrows(IllegalArgumentException.class, () -> ReaderInput.fromFile((Path) null));
        assertThrows(IllegalArgumentException.class, () -> ReaderInput.fromFile((File) null));
    }

    @Test
    @DisplayName("Should create ReaderInput from file path")
    void testFromPath() {
        ReaderInput input = ReaderInput.fromPath(Path.of("src/test/resources/rag-test.docx"));
        assertNotNull(input);
        assertEquals(ReaderInput.InputType.FILE, input.getType());
        assertTrue(input.asString().contains("src/test/resources/rag-test.docx"));
    }

    @Test
    @DisplayName("Should throw exception when path is invalid")
    void testFromPathWithInvalidPath() {
        assertThrows(IllegalArgumentException.class, () -> ReaderInput.fromPath((String) null));
        assertThrows(IllegalArgumentException.class, () -> ReaderInput.fromPath("/not/exist/path"));
        assertThrows(IllegalArgumentException.class, () -> ReaderInput.fromPath(""));
        assertThrows(IllegalArgumentException.class, () -> ReaderInput.fromPath("  "));
        assertThrows(IllegalArgumentException.class, () -> ReaderInput.fromPath((Path) null));
        assertThrows(
                IllegalArgumentException.class,
                () -> ReaderInput.fromPath(Path.of("/not/exist/path")));
    }

    @Test
    @DisplayName("Should create ReaderInput from file path string")
    void testFromPathString() {
        ReaderInput input = ReaderInput.fromPath("src/test/resources/rag-test.docx");
        assertNotNull(input);
        assertEquals(ReaderInput.InputType.FILE, input.getType());
        assertTrue(input.asString().contains("src/test/resources/rag-test.docx"));
    }
}
