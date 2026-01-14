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

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockConstruction;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.when;

import io.agentscope.core.rag.exception.ReaderException;
import io.agentscope.core.rag.model.Document;
import java.io.InputStream;
import java.util.List;
import java.util.TreeSet;
import org.apache.tika.exception.TikaException;
import org.apache.tika.metadata.Metadata;
import org.apache.tika.mime.MediaType;
import org.apache.tika.mime.MediaTypeRegistry;
import org.apache.tika.mime.MimeType;
import org.apache.tika.mime.MimeTypeException;
import org.apache.tika.mime.MimeTypes;
import org.apache.tika.parser.AutoDetectParser;
import org.apache.tika.parser.ParseContext;
import org.apache.tika.sax.ToXMLContentHandler;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.mockito.MockedConstruction;
import org.mockito.MockedStatic;
import org.xml.sax.ContentHandler;
import reactor.test.StepVerifier;

/**
 * Unit tests for {@link TikaReader}.
 */
@Tag("unit")
@DisplayName("TikaReader Unit Tests")
class TikaReaderTest {

    @Test
    @DisplayName("Should create TikaReader with default settings")
    void testDefaultConstructor() {
        TikaReader reader = new TikaReader();
        assertNotNull(reader);
        assertEquals(512, reader.getChunkSize());
        assertEquals(SplitStrategy.PARAGRAPH, reader.getSplitStrategy());
        assertEquals(50, reader.getOverlapSize());
    }

    @Test
    @DisplayName("Should create TikaReader with custom settings")
    void testCustomConstructor() {
        TikaReader reader =
                new TikaReader(1024, SplitStrategy.TOKEN, 100, new ToXMLContentHandler());
        assertNotNull(reader);
        assertEquals(1024, reader.getChunkSize());
        assertEquals(SplitStrategy.TOKEN, reader.getSplitStrategy());
        assertEquals(100, reader.getOverlapSize());
    }

    @Test
    @DisplayName("Should throw exception when content handler is null")
    void testNullContentHandler() {
        assertThrows(
                IllegalArgumentException.class,
                () -> new TikaReader(512, SplitStrategy.PARAGRAPH, 50, null));
    }

    @Test
    @DisplayName("Test read document")
    void testReadDocument() {
        TikaReader reader = new TikaReader();
        ReaderInput input = ReaderInput.fromPath("src/test/resources/rag-test.docx");
        StepVerifier.create(reader.read(input))
                .assertNext(
                        documents -> {
                            Assertions.assertNotNull(documents);
                            Assertions.assertFalse(documents.isEmpty());
                            for (Document doc : documents) {
                                Assertions.assertNotNull(doc);
                                Assertions.assertNotNull(doc.getMetadata());
                                Assertions.assertNotNull(doc.getMetadata().getContentText());
                            }
                        })
                .verifyComplete();
    }

    @Test
    @DisplayName("Should throw exception when input is null")
    void testReadNullInput() {
        TikaReader reader = new TikaReader();
        StepVerifier.create(reader.read(null)).expectError(ReaderException.class).verify();
    }

    @Test
    @DisplayName("Should throw ReaderException when occur error")
    void testReadOccurError() {
        TikaReader reader = new TikaReader();
        ReaderInput input = ReaderInput.fromPath("src/test/resources/rag-test.docx");

        MockedConstruction<AutoDetectParser> mockCtor =
                mockConstruction(
                        AutoDetectParser.class,
                        (parser, ctx) -> {
                            doThrow(new TikaException("Error parsing document"))
                                    .when(parser)
                                    .parse(
                                            any(InputStream.class),
                                            any(ContentHandler.class),
                                            any(Metadata.class),
                                            any(ParseContext.class));
                        });

        StepVerifier.create(reader.read(input)).expectError(ReaderException.class).verify();

        mockCtor.close();
    }

    @Test
    void testGetSupportedFormats() {
        TikaReader reader = new TikaReader();
        List<String> supportedFormats = reader.getSupportedFormats();
        assertNotNull(supportedFormats);
        assertTrue(supportedFormats.contains("pdf"));
        assertTrue(supportedFormats.contains("xls"));
        assertTrue(supportedFormats.contains("xlsx"));
        assertTrue(supportedFormats.contains("doc"));
        assertTrue(supportedFormats.contains("docx"));
        assertTrue(supportedFormats.contains("html"));
        assertTrue(supportedFormats.contains("txt"));
    }

    @Test
    @DisplayName("Should skip wrong mime type")
    void testGetSupportedFormatsWithWrongMimeType() throws MimeTypeException {
        TikaReader reader = new TikaReader();

        MockedStatic<MimeTypes> mockStatic = mockStatic(MimeTypes.class);
        MimeTypes mockMimeTypes = mock(MimeTypes.class);
        MediaTypeRegistry mockMediaTypeRegistry = mock(MediaTypeRegistry.class);
        MimeType mockJsonMimeType = mock(MimeType.class);
        MimeType mockHtmlMimeType = mock(MimeType.class);
        MimeType mockPngMimeType = mock(MimeType.class);
        TreeSet<MediaType> mediaTypes = new TreeSet<>();
        mediaTypes.add(MediaType.application("json"));
        mediaTypes.add(MediaType.text("html"));
        mediaTypes.add(MediaType.image("png"));

        mockStatic.when(MimeTypes::getDefaultMimeTypes).thenReturn(mockMimeTypes);
        when(mockMimeTypes.getMediaTypeRegistry()).thenReturn(mockMediaTypeRegistry);
        when(mockMediaTypeRegistry.getTypes()).thenReturn(mediaTypes);
        when(mockMimeTypes.forName("application/json")).thenReturn(mockJsonMimeType);
        when(mockMimeTypes.forName("text/html")).thenReturn(mockHtmlMimeType);
        when(mockMimeTypes.forName("image/png")).thenReturn(mockPngMimeType);
        when(mockMimeTypes.forName("application/error_type")).thenThrow(MimeTypeException.class);
        when(mockJsonMimeType.getExtensions()).thenReturn(List.of(".json"));
        when(mockHtmlMimeType.getExtensions()).thenReturn(List.of(".html"));
        when(mockPngMimeType.getExtensions()).thenReturn(List.of(".png"));

        List<String> supportedFormats = reader.getSupportedFormats();

        assertNotNull(supportedFormats);
        assertTrue(supportedFormats.contains("json"));
        assertTrue(supportedFormats.contains("html"));
        assertTrue(supportedFormats.contains("png"));
        assertFalse(supportedFormats.contains("error_type"));

        mockStatic.close();
    }
}
