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
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.TreeSet;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Collectors;
import java.util.stream.IntStream;
import org.apache.tika.exception.TikaException;
import org.apache.tika.metadata.Metadata;
import org.apache.tika.mime.MediaType;
import org.apache.tika.mime.MediaTypeRegistry;
import org.apache.tika.mime.MimeType;
import org.apache.tika.mime.MimeTypeException;
import org.apache.tika.mime.MimeTypes;
import org.apache.tika.parser.AutoDetectParser;
import org.apache.tika.parser.ParseContext;
import org.apache.tika.sax.BodyContentHandler;
import org.apache.tika.sax.ToXMLContentHandler;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
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
    @DisplayName("Should throw exception when per-read handler factory is null")
    void testNullPerReadHandlerFactory() {
        assertThrows(
                IllegalArgumentException.class,
                () -> TikaReader.perReadHandler(512, SplitStrategy.PARAGRAPH, 50, null));
    }

    @Test
    @DisplayName("A factory that returns null must surface as a configuration error")
    void testNullFromPerReadHandlerFactory() {
        TikaReader reader = TikaReader.perReadHandler(512, SplitStrategy.PARAGRAPH, 50, () -> null);
        ReaderInput input = ReaderInput.fromPath("src/test/resources/rag-test.docx");

        StepVerifier.create(reader.read(input)).expectError(IllegalStateException.class).verify();
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

    @Test
    @DisplayName("A reused reader must not see content from earlier reads")
    void testReusedReaderDoesNotAccumulateEarlierContent(@TempDir Path tempDir) throws Exception {
        Path first = Files.writeString(tempDir.resolve("first.txt"), "ALPHA");
        Path second = Files.writeString(tempDir.resolve("second.txt"), "BETA");

        TikaReader reader = new TikaReader();

        assertEquals("first read must return the first document", "ALPHA", readAll(reader, first));
        assertEquals(
                "reading the same document twice must be idempotent",
                "ALPHA",
                readAll(reader, first));
        assertEquals(
                "the second document must not carry over text from the first",
                "BETA",
                readAll(reader, second));
    }

    @Test
    @DisplayName("A caller-supplied content handler is still honoured")
    void testCallerSuppliedHandlerIsUsed(@TempDir Path tempDir) throws Exception {
        Path file = Files.writeString(tempDir.resolve("document.txt"), "GAMMA");
        TikaReader reader =
                new TikaReader(1024, SplitStrategy.PARAGRAPH, 50, new ToXMLContentHandler());

        String text = readAll(reader, file);

        assertTrue(
                "expected markup from the supplied XHTML handler, got: " + text,
                text.contains("<"));
        assertTrue("expected the document text, got: " + text, text.contains("GAMMA"));
    }

    @Test
    @DisplayName("The per-read handler factory is consulted once per read")
    void testPerReadHandlerFactoryIsConsultedPerRead(@TempDir Path tempDir) throws Exception {
        Path file = Files.writeString(tempDir.resolve("document.txt"), "DELTA");
        AtomicInteger handlerCount = new AtomicInteger();
        TikaReader reader =
                TikaReader.perReadHandler(
                        512,
                        SplitStrategy.PARAGRAPH,
                        50,
                        () -> {
                            handlerCount.incrementAndGet();
                            return new BodyContentHandler(-1);
                        });

        assertEquals("DELTA", readAll(reader, file));
        assertEquals("DELTA", readAll(reader, file));
        assertEquals("DELTA", readAll(reader, file));
        assertEquals("the factory must be consulted once per read", 3, handlerCount.get());
    }

    @Test
    @DisplayName("Concurrent reads of one reader must not share handler state")
    void testConcurrentReadsStayIsolated(@TempDir Path tempDir) throws Exception {
        Path first = Files.writeString(tempDir.resolve("first.txt"), "ALPHA");
        Path second = Files.writeString(tempDir.resolve("second.txt"), "BETA");
        TikaReader reader = new TikaReader();
        ExecutorService executor = Executors.newFixedThreadPool(4);
        try {
            List<Future<String>> futures =
                    IntStream.range(0, 8)
                            .mapToObj(
                                    i ->
                                            executor.submit(
                                                    () ->
                                                            readAll(
                                                                    reader,
                                                                    i % 2 == 0 ? first : second)))
                            .collect(Collectors.toList());
            for (int i = 0; i < futures.size(); i++) {
                String expected = i % 2 == 0 ? "ALPHA" : "BETA";
                assertEquals(expected, futures.get(i).get(30, TimeUnit.SECONDS));
            }
        } finally {
            executor.shutdownNow();
        }
    }

    /** Reads a document and joins every chunk so assertions see the whole extracted text. */
    private static String readAll(TikaReader reader, Path path) {
        List<Document> documents = reader.read(ReaderInput.fromPath(path)).block();
        assertNotNull(documents);
        return documents.stream()
                .map(document -> document.getMetadata().getContentText())
                .collect(Collectors.joining("\n"));
    }
}
