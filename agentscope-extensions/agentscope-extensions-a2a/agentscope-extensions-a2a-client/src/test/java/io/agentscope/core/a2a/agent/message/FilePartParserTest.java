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
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;

import io.a2a.spec.FileContent;
import io.a2a.spec.FilePart;
import io.a2a.spec.FileWithBytes;
import io.a2a.spec.FileWithUri;
import io.agentscope.core.message.AudioBlock;
import io.agentscope.core.message.ContentBlock;
import io.agentscope.core.message.ImageBlock;
import io.agentscope.core.message.VideoBlock;
import java.util.Base64;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Unit tests for FilePartParser.
 *
 * <p>Tests cover:
 * <ul>
 *   <li>Parsing image FilePart to ImageBlock</li>
 *   <li>Parsing audio FilePart to AudioBlock</li>
 *   <li>Parsing video FilePart to VideoBlock</li>
 *   <li>Handling unsupported mime types</li>
 * </ul>
 */
@DisplayName("FilePartParser Tests")
class FilePartParserTest {

    private FilePartParser parser;

    @BeforeEach
    void setUp() {
        parser = new FilePartParser();
    }

    @Test
    @DisplayName("Should parse image FilePart to ImageBlock")
    void testParseImageFilePart() {
        FileContent file =
                new FileWithBytes(
                        "image/png",
                        "test.png",
                        Base64.getEncoder().encodeToString("test".getBytes()));
        FilePart part = new FilePart(file);

        ContentBlock result = parser.parse(part);

        assertNotNull(result);
        assertEquals(ImageBlock.class, result.getClass());
    }

    @Test
    @DisplayName("Should parse audio FilePart to AudioBlock")
    void testParseAudioFilePart() {
        FileContent file =
                new FileWithBytes(
                        "audio/mp3",
                        "test.mp3",
                        Base64.getEncoder().encodeToString("test".getBytes()));
        FilePart part = new FilePart(file);

        ContentBlock result = parser.parse(part);

        assertNotNull(result);
        assertEquals(AudioBlock.class, result.getClass());
    }

    @Test
    @DisplayName("Should parse video FilePart to VideoBlock")
    void testParseVideoFilePart() {
        FileContent file = new FileWithUri("video/mp4", "test.mp4", "https://exmaple.com/test.mp4");
        FilePart part = new FilePart(file);

        ContentBlock result = parser.parse(part);

        assertNotNull(result);
        assertEquals(VideoBlock.class, result.getClass());
    }

    @Test
    @DisplayName("Should parse video FilePart to VideoBlock with only primary mime type")
    void testParseVideoFilePartOnlyMimeType() {
        FileContent file = new FileWithUri("video", "test.mp4", "https://exmaple.com/test.mp4");
        FilePart part = new FilePart(file);

        ContentBlock result = parser.parse(part);

        assertNotNull(result);
        assertEquals(VideoBlock.class, result.getClass());
    }

    @Test
    @DisplayName("Should return null for unsupported mime types")
    void testParseUnsupportedFilePart() {
        FileContent file =
                new FileWithBytes(
                        "application/pdf",
                        "test.pdf",
                        Base64.getEncoder().encodeToString("test".getBytes()));
        FilePart part = new FilePart(file);

        ContentBlock result = parser.parse(part);

        assertNull(result);
    }

    @Test
    @DisplayName("Should return null for empty primary mime type")
    void testParseEmptyPrimaryMimeType() {
        FileContent file =
                new FileWithBytes(
                        "", "test.pdf", Base64.getEncoder().encodeToString("test".getBytes()));
        FilePart part = new FilePart(file);
        ContentBlock result = parser.parse(part);
        assertNull(result);

        file =
                new FileWithBytes(
                        null, "test.pdf", Base64.getEncoder().encodeToString("test".getBytes()));
        part = new FilePart(file);
        result = parser.parse(part);
        assertNull(result);
    }

    @Test
    @DisplayName("Should return null for unsupported primary mime type")
    void testParseUnsupportedPrimaryMimeType() {
        FileContent file =
                new FileWithBytes(
                        "application/pdf",
                        "test.pdf",
                        Base64.getEncoder().encodeToString("test".getBytes()));
        FilePart part = new FilePart(file);
        ContentBlock result = parser.parse(part);
        assertNull(result);
    }
}
