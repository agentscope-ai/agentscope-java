/*
 * Copyright 2024-2026 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      https://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package io.agentscope.core.formatter.ollama;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

import io.agentscope.core.message.Base64Source;
import io.agentscope.core.message.ImageBlock;
import io.agentscope.core.message.URLSource;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Unit tests for OllamaMediaConverter.
 */
@DisplayName("OllamaMediaConverter Unit Tests")
class OllamaMediaConverterTest {

    private OllamaMediaConverter converter;

    @BeforeEach
    void setUp() {
        converter = new OllamaMediaConverter();
    }

    @Test
    @DisplayName("Should create converter successfully")
    void testConstructor() {
        assertNotNull(converter);
    }

    @Test
    @DisplayName("Should convert Base64Source to base64 string")
    void testConvertBase64Source() throws Exception {
        // Arrange
        String base64Data =
                "iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAYAAAAfFcSJAAAADUlEQVR42mP8/5+hHgAHggJ/PchI7wAAAABJRU5ErkJggg==";
        Base64Source source =
                Base64Source.builder().data(base64Data).mediaType("image/png").build();
        ImageBlock imageBlock = ImageBlock.builder().source(source).build();

        // Act
        String result = converter.convertImageBlockToBase64(imageBlock);

        // Assert
        assertEquals(base64Data, result);
    }

    @Test
    @DisplayName("Should handle URLSource with local file")
    void testConvertURLSourceWithLocalFile() {
        // Arrange
        URLSource source = URLSource.builder().url("file:///nonexistent/path/image.jpg").build();
        ImageBlock imageBlock = ImageBlock.builder().source(source).build();

        // Act & Assert
        // This should throw an exception because the file doesn't actually exist
        assertThrows(
                Exception.class,
                () -> {
                    converter.convertImageBlockToBase64(imageBlock);
                });
    }

    @Test
    @DisplayName("Should handle URLSource with remote URL")
    void testConvertURLSourceWithRemoteURL() {
        // Arrange
        URLSource source =
                URLSource.builder().url("https://nonexistentdomain12345.com/image.jpg").build();
        ImageBlock imageBlock = ImageBlock.builder().source(source).build();

        // Act & Assert
        // This should throw an exception because the URL doesn't actually exist
        assertThrows(
                Exception.class,
                () -> {
                    converter.convertImageBlockToBase64(imageBlock);
                });
    }
}
