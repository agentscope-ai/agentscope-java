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
package io.agentscope.core.a2a.agent.message;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import io.a2a.spec.FilePart;
import io.a2a.spec.FileWithBytes;
import io.a2a.spec.FileWithUri;
import io.a2a.spec.Part;
import io.agentscope.core.message.Base64Source;
import io.agentscope.core.message.Source;
import io.agentscope.core.message.URLSource;
import java.util.Base64;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Unit tests for BaseMediaBlockParser.
 *
 * <p>Tests cover:
 * <ul>
 *   <li>Concrete implementation functionality</li>
 *   <li>Parsing Base64Source to FilePart</li>
 *   <li>Parsing URLSource to FilePart</li>
 *   <li>Handling unsupported source types</li>
 *   <li>Media type parsing from URL</li>
 * </ul>
 */
@DisplayName("BaseMediaBlockParser Tests")
class BaseMediaBlockParserTest {

    private AudioBlockParser parser;

    @BeforeEach
    void setUp() {
        parser = new AudioBlockParser();
    }

    @Test
    @DisplayName("Should parse Base64Source to FilePart")
    void testParseBase64Source() {
        Base64Source source = mock(Base64Source.class);
        when(source.getMediaType()).thenReturn("audio/mp3");
        when(source.getData()).thenReturn(Base64.getEncoder().encodeToString("test".getBytes()));

        Part<?> result = parser.parseSource(source);

        assertNotNull(result);
        assertInstanceOf(FilePart.class, result);
        FilePart filePart = (FilePart) result;
        assertEquals("audio/mp3", filePart.getFile().mimeType());
        assertInstanceOf(FileWithBytes.class, filePart.getFile());
        FileWithBytes file = (FileWithBytes) filePart.getFile();
        assertEquals(Base64.getEncoder().encodeToString("test".getBytes()), file.bytes());
    }

    @Test
    @DisplayName("Should parse URLSource to FilePart")
    void testParseURLSource() {
        URLSource source = mock(URLSource.class);
        when(source.getUrl()).thenReturn("https://example.com/test.mp3");

        Part<?> result = parser.parseSource(source);

        assertNotNull(result);
        assertInstanceOf(FilePart.class, result);
        FilePart filePart = (FilePart) result;
        assertEquals("audio/mpeg", filePart.getFile().mimeType());
        assertInstanceOf(FileWithUri.class, filePart.getFile());
        FileWithUri file = (FileWithUri) filePart.getFile();
        assertEquals("https://example.com/test.mp3", file.uri());
    }

    @Test
    @DisplayName("Should parse URL to unknown media type")
    void testParseURLWithUnknownMediaType() {
        URLSource source = mock(URLSource.class);
        when(source.getUrl()).thenReturn("https://example.com/test");
        Part<?> result = parser.parseSource(source);

        assertNotNull(result);
        assertInstanceOf(FilePart.class, result);
        FilePart filePart = (FilePart) result;
        assertEquals("audio", filePart.getFile().mimeType());
        assertInstanceOf(FileWithUri.class, filePart.getFile());
        FileWithUri file = (FileWithUri) filePart.getFile();
        assertEquals("https://example.com/test", file.uri());
    }

    @Test
    @DisplayName("Should parse URL to non equal media type")
    void testParseURLWithNonEqualMediaType() {
        URLSource source = mock(URLSource.class);
        when(source.getUrl()).thenReturn("https://example.com/test.pdf");
        Part<?> result = parser.parseSource(source);

        assertNotNull(result);
        assertInstanceOf(FilePart.class, result);
        FilePart filePart = (FilePart) result;
        assertEquals("audio", filePart.getFile().mimeType());
        assertInstanceOf(FileWithUri.class, filePart.getFile());
        FileWithUri file = (FileWithUri) filePart.getFile();
        assertEquals("https://example.com/test.pdf", file.uri());
    }

    @Test
    @DisplayName("Should parse URL for illegal URL")
    void testParseURLForIllegalURL() {
        URLSource source = mock(URLSource.class);
        when(source.getUrl()).thenReturn("example.com/test.mp3");
        Part<?> result = parser.parseSource(source);

        assertNotNull(result);
        assertInstanceOf(FilePart.class, result);
        FilePart filePart = (FilePart) result;
        assertEquals("audio", filePart.getFile().mimeType());
        assertInstanceOf(FileWithUri.class, filePart.getFile());
        FileWithUri file = (FileWithUri) filePart.getFile();
        assertEquals("example.com/test.mp3", file.uri());
    }

    @Test
    @DisplayName("Should return null for unsupported source types")
    void testParseUnsupportedSource() {
        Source source = mock(Source.class);

        Part<?> result = parser.parseSource(source);
        assertNull(result);
    }
}
