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

import io.agentscope.core.message.AudioBlock;
import io.agentscope.core.message.ContentBlock;
import io.agentscope.core.message.DocumentBlock;
import io.agentscope.core.message.ImageBlock;
import io.agentscope.core.message.URLSource;
import io.agentscope.core.message.VideoBlock;
import java.util.Base64;
import java.util.Map;
import org.a2aproject.sdk.spec.FileContent;
import org.a2aproject.sdk.spec.FilePart;
import org.a2aproject.sdk.spec.FileWithBytes;
import org.a2aproject.sdk.spec.FileWithUri;
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
 *   <li>Parsing a document FilePart to DocumentBlock</li>
 *   <li>Handling a missing mime type</li>
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
    @DisplayName("Should return null for empty primary mime type")
    void testParseEmptyPrimaryMimeType() {
        FileContent file =
                new FileWithBytes(
                        "", "test.pdf", Base64.getEncoder().encodeToString("test".getBytes()));
        FilePart part = new FilePart(file);
        ContentBlock result = parser.parse(part);
        assertNull(result);
    }

    @Test
    @DisplayName("Should parse a document FilePart to DocumentBlock")
    void testParseDocumentFilePart() {
        FileContent file =
                new FileWithBytes(
                        "application/pdf",
                        "test.pdf",
                        Base64.getEncoder().encodeToString("test".getBytes()));
        FilePart part = new FilePart(file);

        ContentBlock result = parser.parse(part);

        assertNotNull(result);
        assertEquals(DocumentBlock.class, result.getClass());
        DocumentBlock document = (DocumentBlock) result;
        assertEquals("test.pdf", document.getName());
        assertEquals("application/pdf", document.getMimeType());
    }

    @Test
    @DisplayName("Should parse a plain-text FilePart to DocumentBlock")
    void testParseTextDocumentFilePart() {
        FileContent file =
                new FileWithBytes(
                        "text/plain; charset=utf-8",
                        "notes.txt",
                        Base64.getEncoder().encodeToString("hello".getBytes()));
        FilePart part = new FilePart(file);

        ContentBlock result = parser.parse(part);

        assertNotNull(result);
        assertEquals(DocumentBlock.class, result.getClass());
    }

    @Test
    @DisplayName("Should return null for a binary file that is not a document")
    void testParseNonDocumentBinaryFilePart() {
        FileContent file =
                new FileWithBytes(
                        "application/zip",
                        "bundle.zip",
                        Base64.getEncoder().encodeToString("test".getBytes()));

        ContentBlock result = parser.parse(new FilePart(file));

        assertNull(result);
    }

    @Test
    @DisplayName("Should store a MIME type without its parameters")
    void testParseDocumentDropsMimeParameters() {
        FileContent file =
                new FileWithBytes(
                        "text/plain; charset=utf-8",
                        "notes.txt",
                        Base64.getEncoder().encodeToString("hello".getBytes()));
        FilePart part = new FilePart(file);

        ContentBlock result = parser.parse(part);

        assertNotNull(result);
        assertEquals("text/plain", ((DocumentBlock) result).getMimeType());
    }

    @Test
    @DisplayName("A scanned document marked as document round-trips as a DocumentBlock")
    void testExplicitDocumentMarkerBeatsImageMimeType() {
        // Outbound: a DocumentBlock whose payload is a JPEG scan leaves as a FilePart carrying
        // the AgentScope block-type marker next to its image MIME type.
        DocumentBlock scanned =
                DocumentBlock.builder()
                        .name("scan.jpg")
                        .source(
                                io.agentscope.core.message.Base64Source.builder()
                                        .mediaType("image/jpeg")
                                        .data(Base64.getEncoder().encodeToString("scan".getBytes()))
                                        .build())
                        .build();
        FilePart part = (FilePart) new DocumentBlockParser().parse(scanned);

        // Inbound: the marker must win over the image MIME type.
        ContentBlock result = parser.parse(part);

        assertNotNull(result, "the sender sent a document; it must not be dropped");
        assertEquals(DocumentBlock.class, result.getClass());
        assertEquals("scan.jpg", ((DocumentBlock) result).getName());
        assertEquals("image/jpeg", ((DocumentBlock) result).getMimeType());
    }

    @Test
    @DisplayName("User metadata survives a document round-trip through A2A")
    void testDocumentMetadataSurvivesRoundTrip() {
        DocumentBlock outgoing =
                DocumentBlock.builder()
                        .name("report.pdf")
                        .source(
                                io.agentscope.core.message.Base64Source.builder()
                                        .mediaType("application/pdf")
                                        .data(Base64.getEncoder().encodeToString("pdf".getBytes()))
                                        .build())
                        .metadata(Map.of("file_parsing_strategy", "text_only"))
                        .build();
        FilePart part = (FilePart) new DocumentBlockParser().parse(outgoing);

        ContentBlock result = parser.parse(part);

        assertNotNull(result);
        DocumentBlock imported = (DocumentBlock) result;
        // The framework marker is consumed by routing and must not leak into the block.
        assertNull(
                imported.getMetadata().get(MessageConstants.BLOCK_TYPE_METADATA_KEY),
                String.valueOf(imported.getMetadata()));
        assertEquals("text_only", imported.getMetadata().get("file_parsing_strategy"));
    }

    @Test
    @DisplayName("A spoofed block-type marker cannot override the framework's")
    void testUserMetadataCannotSpoofTheBlockTypeMarker() {
        DocumentBlock outgoing =
                DocumentBlock.builder()
                        .name("report.pdf")
                        .source(
                                io.agentscope.core.message.Base64Source.builder()
                                        .mediaType("application/pdf")
                                        .data(Base64.getEncoder().encodeToString("pdf".getBytes()))
                                        .build())
                        .metadata(
                                Map.of(
                                        MessageConstants.BLOCK_TYPE_METADATA_KEY,
                                        "image",
                                        "file_parsing_strategy",
                                        "auto"))
                        .build();
        FilePart part = (FilePart) new DocumentBlockParser().parse(outgoing);

        ContentBlock result = parser.parse(part);

        assertNotNull(result);
        // The framework's marker never survives the round trip: the sender cannot spoof it
        // outbound, and the receiver strips it after routing.
        assertNull(
                ((DocumentBlock) result)
                        .getMetadata()
                        .get(MessageConstants.BLOCK_TYPE_METADATA_KEY),
                String.valueOf(((DocumentBlock) result).getMetadata()));
        assertEquals("auto", ((DocumentBlock) result).getMetadata().get("file_parsing_strategy"));
    }

    @Test
    @DisplayName("Should keep the declared MIME type on a URL source")
    void testParseUriFilePartKeepsMimeType() {
        FilePart part =
                new FilePart(
                        new FileWithUri(
                                "application/pdf",
                                "report.pdf",
                                "https://example.test/report.pdf"));

        ContentBlock result = parser.parse(part);

        assertNotNull(result);
        DocumentBlock document = (DocumentBlock) result;
        assertEquals("application/pdf", document.getMimeType());
        assertEquals("application/pdf", ((URLSource) document.getSource()).getMimeType());
    }

    @Test
    @DisplayName("Should return null when the FilePart carries no mime type")
    void testParseFilePartWithoutMimeType() {
        FileContent file =
                new FileWithBytes(
                        "", "test.pdf", Base64.getEncoder().encodeToString("test".getBytes()));
        FilePart part = new FilePart(file);
        ContentBlock result = parser.parse(part);
        assertNull(result);
    }

    @Test
    @DisplayName("A document marker cannot bypass a null mime type")
    void testDocumentMarkerWithNullMimeTypeIsDropped() {
        // FileWithUri accepts a null MIME type. The marker used to win before the guard, so the
        // part became a DocumentBlock whose media type no provider could resolve.
        FileContent file = new FileWithUri(null, "test.pdf", "https://example.test/test.pdf");
        FilePart part =
                new FilePart(
                        file,
                        Map.of(
                                MessageConstants.BLOCK_TYPE_METADATA_KEY,
                                MessageConstants.BlockContent.TYPE_DOCUMENT));

        assertNull(parser.parse(part));
    }

    @Test
    @DisplayName("A document marker cannot bypass a blank mime type")
    void testDocumentMarkerWithBlankMimeTypeIsDropped() {
        FileContent file =
                new FileWithBytes(
                        "  ", "test.pdf", Base64.getEncoder().encodeToString("test".getBytes()));
        FilePart part =
                new FilePart(
                        file,
                        Map.of(
                                MessageConstants.BLOCK_TYPE_METADATA_KEY,
                                MessageConstants.BlockContent.TYPE_DOCUMENT));

        assertNull(parser.parse(part));
    }
}
