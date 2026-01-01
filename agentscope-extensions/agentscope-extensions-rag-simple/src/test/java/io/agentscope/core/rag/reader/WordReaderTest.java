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
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.agentscope.core.rag.exception.ReaderException;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import reactor.test.StepVerifier;

/**
 * Unit tests for WordReader.
 */
@Tag("unit")
@DisplayName("WordReader Unit Tests")
class WordReaderTest {

    @Test
    @DisplayName("Should create WordReader with default settings")
    void testDefaultConstructor() {
        WordReader reader = new WordReader();
        assertEquals(512, reader.getChunkSize());
        assertEquals(SplitStrategy.PARAGRAPH, reader.getSplitStrategy());
        assertEquals(50, reader.getOverlapSize());
        assertTrue(reader.isIncludeImage());
        assertFalse(reader.isSeparateTable());
        assertEquals(TableFormat.MARKDOWN, reader.getTableFormat());
    }

    @Test
    @DisplayName("Should create WordReader with custom settings")
    void testConstructorWithSettings() {
        WordReader reader =
                new WordReader(1024, SplitStrategy.TOKEN, 100, false, true, TableFormat.JSON);
        assertEquals(1024, reader.getChunkSize());
        assertEquals(SplitStrategy.TOKEN, reader.getSplitStrategy());
        assertEquals(100, reader.getOverlapSize());
        assertFalse(reader.isIncludeImage());
        assertTrue(reader.isSeparateTable());
        assertEquals(TableFormat.JSON, reader.getTableFormat());
    }

    @Test
    @DisplayName("Should throw exception for invalid chunk size")
    void testInvalidChunkSize() {
        assertThrows(
                IllegalArgumentException.class,
                () ->
                        new WordReader(
                                0, SplitStrategy.PARAGRAPH, 50, true, false, TableFormat.MARKDOWN));
        assertThrows(
                IllegalArgumentException.class,
                () ->
                        new WordReader(
                                -1,
                                SplitStrategy.PARAGRAPH,
                                50,
                                true,
                                false,
                                TableFormat.MARKDOWN));
    }

    @Test
    @DisplayName("Should throw exception for null split strategy")
    void testNullSplitStrategy() {
        assertThrows(
                IllegalArgumentException.class,
                () -> new WordReader(512, null, 50, true, false, TableFormat.MARKDOWN));
    }

    @Test
    @DisplayName("Should throw exception for negative overlap size")
    void testNegativeOverlapSize() {
        assertThrows(
                IllegalArgumentException.class,
                () ->
                        new WordReader(
                                512,
                                SplitStrategy.PARAGRAPH,
                                -1,
                                true,
                                false,
                                TableFormat.MARKDOWN));
    }

    @Test
    @DisplayName("Should throw exception for null table format")
    void testNullTableFormat() {
        assertThrows(
                IllegalArgumentException.class,
                () -> new WordReader(512, SplitStrategy.PARAGRAPH, 50, true, false, null));
    }

    @Test
    @DisplayName("Should return supported Word formats")
    void testGetSupportedFormats() {
        WordReader reader = new WordReader();
        assertEquals(List.of("doc", "docx"), reader.getSupportedFormats());
    }

    @Test
    @DisplayName("Should handle null input")
    void testNullInput() throws ReaderException {
        WordReader reader = new WordReader();

        StepVerifier.create(reader.read(null)).expectError(ReaderException.class).verify();
    }

    @Test
    @DisplayName("Should throw exception when Word file does not exist")
    void testNonExistentWordFile() throws ReaderException {
        WordReader reader = new WordReader();
        ReaderInput input = ReaderInput.fromString("/non/existent/file.docx");

        StepVerifier.create(reader.read(input)).expectError(ReaderException.class).verify();
    }
}
