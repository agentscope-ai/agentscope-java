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
 * Unit tests for PDFReader.
 */
@Tag("unit")
@DisplayName("PDFReader Unit Tests")
class PDFReaderTest {

    @Test
    @DisplayName("Should create PDFReader with default settings")
    void testDefaultConstructor() {
        PDFReader reader = new PDFReader();
        assertEquals(512, reader.getChunkSize());
        assertEquals(SplitStrategy.PARAGRAPH, reader.getSplitStrategy());
        assertEquals(50, reader.getOverlapSize());
        assertFalse(reader.isExtractImages());
    }

    @Test
    @DisplayName("Should create PDFReader with custom settings")
    void testConstructorWithSettings() {
        PDFReader reader = new PDFReader(1024, SplitStrategy.TOKEN, 100, true);
        assertEquals(1024, reader.getChunkSize());
        assertEquals(SplitStrategy.TOKEN, reader.getSplitStrategy());
        assertEquals(100, reader.getOverlapSize());
        assertTrue(reader.isExtractImages());
    }

    @Test
    @DisplayName("Should throw exception for invalid chunk size")
    void testInvalidChunkSize() {
        assertThrows(
                IllegalArgumentException.class,
                () -> new PDFReader(0, SplitStrategy.PARAGRAPH, 50));
        assertThrows(
                IllegalArgumentException.class,
                () -> new PDFReader(-1, SplitStrategy.PARAGRAPH, 50));
    }

    @Test
    @DisplayName("Should throw exception for null split strategy")
    void testNullSplitStrategy() {
        assertThrows(IllegalArgumentException.class, () -> new PDFReader(512, null, 50));
    }

    @Test
    @DisplayName("Should throw exception for negative overlap size")
    void testNegativeOverlapSize() {
        assertThrows(
                IllegalArgumentException.class,
                () -> new PDFReader(512, SplitStrategy.PARAGRAPH, -1));
    }

    @Test
    @DisplayName("Should return supported PDF format")
    void testGetSupportedFormats() {
        PDFReader reader = new PDFReader();
        assertEquals(List.of("pdf"), reader.getSupportedFormats());
    }

    @Test
    @DisplayName("Should handle null input")
    void testNullInput() throws ReaderException {
        PDFReader reader = new PDFReader();

        StepVerifier.create(reader.read(null)).expectError(ReaderException.class).verify();
    }

    @Test
    @DisplayName("Should throw exception when PDF file does not exist")
    void testNonExistentPDFFile() throws ReaderException {
        PDFReader reader = new PDFReader();
        ReaderInput input = ReaderInput.fromString("/non/existent/file.pdf");

        StepVerifier.create(reader.read(input)).expectError(ReaderException.class).verify();
    }
}
