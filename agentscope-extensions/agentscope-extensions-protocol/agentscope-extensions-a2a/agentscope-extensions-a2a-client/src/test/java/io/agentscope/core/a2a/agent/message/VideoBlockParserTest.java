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
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import io.a2a.spec.FilePart;
import io.a2a.spec.FileWithBytes;
import io.a2a.spec.FileWithUri;
import io.a2a.spec.Part;
import io.agentscope.core.message.Base64Source;
import io.agentscope.core.message.URLSource;
import io.agentscope.core.message.VideoBlock;
import java.util.Base64;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Unit tests for VideoBlockParser.
 *
 * <p>Tests cover:
 * <ul>
 *   <li>Media type correctness</li>
 *   <li>Parsing VideoBlock with Base64Source</li>
 *   <li>Parsing VideoBlock with UrlSource</li>
 * </ul>
 */
@DisplayName("VideoBlockParser Tests")
class VideoBlockParserTest {

    private VideoBlockParser parser;

    @BeforeEach
    void setUp() {
        parser = new VideoBlockParser();
    }

    @Test
    @DisplayName("Should have correct media type")
    void testMediaType() {
        assertEquals("video", parser.getMediaType());
    }

    @Test
    @DisplayName("Should parse VideoBlock with Base64Source")
    void testParseVideoBlockWithBase64Source() {
        Base64Source source = mock(Base64Source.class);
        when(source.getMediaType()).thenReturn("video/mp4");
        when(source.getData()).thenReturn(Base64.getEncoder().encodeToString("test".getBytes()));
        VideoBlock block = VideoBlock.builder().source(source).build();

        Part<?> result = parser.parse(block);

        assertNotNull(result);
        assertInstanceOf(FilePart.class, result);
        FilePart filePart = (FilePart) result;
        assertEquals("video/mp4", filePart.getFile().mimeType());
        assertInstanceOf(FileWithBytes.class, filePart.getFile());
        FileWithBytes file = (FileWithBytes) filePart.getFile();
        assertEquals(Base64.getEncoder().encodeToString("test".getBytes()), file.bytes());
    }

    @Test
    @DisplayName("Should parse VideoBlock with UrlSource")
    void testParseVideoBlockWithUrlSource() {
        URLSource source = mock(URLSource.class);
        when(source.getUrl()).thenReturn("https://example.com/test.mp4");
        VideoBlock block = VideoBlock.builder().source(source).build();

        Part<?> result = parser.parse(block);

        assertNotNull(result);
        assertInstanceOf(FilePart.class, result);
        FilePart filePart = (FilePart) result;
        assertEquals("video/mp4", filePart.getFile().mimeType());
        assertInstanceOf(FileWithUri.class, filePart.getFile());
        FileWithUri file = (FileWithUri) filePart.getFile();
        assertEquals("https://example.com/test.mp4", file.uri());
    }
}
