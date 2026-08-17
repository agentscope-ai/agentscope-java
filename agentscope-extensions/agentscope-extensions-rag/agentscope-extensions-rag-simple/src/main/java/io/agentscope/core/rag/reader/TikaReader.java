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

import io.agentscope.core.formatter.MediaUtils;
import io.agentscope.core.message.TextBlock;
import io.agentscope.core.rag.exception.ReaderException;
import io.agentscope.core.rag.model.Document;
import io.agentscope.core.rag.model.DocumentMetadata;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;
import org.apache.tika.exception.TikaException;
import org.apache.tika.metadata.Metadata;
import org.apache.tika.mime.MediaType;
import org.apache.tika.mime.MimeType;
import org.apache.tika.mime.MimeTypeException;
import org.apache.tika.mime.MimeTypes;
import org.apache.tika.parser.AutoDetectParser;
import org.apache.tika.parser.ParseContext;
import org.apache.tika.sax.BodyContentHandler;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.xml.sax.ContentHandler;
import org.xml.sax.SAXException;
import reactor.core.publisher.Mono;

/**
 * TikaReader is a document reader that uses Apache Tika to extract text from various document formats.
 *
 * <p>It supports various document formats (e.g. DOC, XSL, PDF, PPT ...) and splitting strategies.
 *
 * <p>Example usage:
 * <pre>{@code
 * TikaReader reader = new TikaReader();
 * ReaderInput input = ReaderInput.fromPath("/path/to/file");
 * List<Document> documents = reader.read(input).block();
 * }</pre>
 */
public class TikaReader extends AbstractChunkingReader {

    private static final Logger log = LoggerFactory.getLogger(TikaReader.class);

    /**
     * Handler to manage content extraction.
     */
    private final ContentHandler handler;

    /**
     * Creates a new TikaReader with the specified configuration.
     *
     * @param chunkSize     the target size for each chunk (interpreted based on strategy)
     * @param splitStrategy the strategy for splitting text
     * @param overlapSize   the number of characters/tokens to overlap between chunks
     * @param handler       the content handler to use for text extraction
     * @throws IllegalArgumentException if parameters are invalid
     */
    public TikaReader(
            int chunkSize, SplitStrategy splitStrategy, int overlapSize, ContentHandler handler) {
        super(chunkSize, splitStrategy, overlapSize);
        if (handler == null) {
            throw new IllegalArgumentException("content handler cannot be null");
        }
        this.handler = handler;
    }

    /**
     * Creates a new TikaReader with default settings.
     *
     * <p>Defaults: chunkSize=512, strategy=PARAGRAPH, overlapSize=50, handler=BodyContentHandler(-1)
     */
    public TikaReader() {
        this(512, SplitStrategy.PARAGRAPH, 50, new BodyContentHandler(-1));
    }

    @Override
    public Mono<List<Document>> read(ReaderInput input) {
        if (input == null) {
            return Mono.error(new ReaderException("Input cannot be null"));
        }

        return Mono.fromCallable(
                        () -> {
                            try {
                                String path = input.asString();
                                String text = extractTextFromTika(path);
                                List<String> chunks =
                                        TextChunker.chunkText(
                                                text, chunkSize, splitStrategy, overlapSize);
                                return createDocuments(chunks, path);
                            } catch (Exception e) {
                                throw new ReaderException(
                                        "Failed to read document from: " + input, e);
                            }
                        })
                .onErrorMap(ReaderException.class, e -> e);
    }

    @Override
    public List<String> getSupportedFormats() {
        Set<String> extensions = new HashSet<>();
        MimeTypes mimeTypes = MimeTypes.getDefaultMimeTypes();

        for (MediaType mediaType : mimeTypes.getMediaTypeRegistry().getTypes()) {
            MimeType mimeType;
            try {
                mimeType = mimeTypes.forName(mediaType.toString());
                extensions.addAll(mimeType.getExtensions());
            } catch (MimeTypeException e) {
                log.warn("Failed to parse mime type for " + mediaType.toString(), e);
            }
        }

        // Remove dot char
        Set<String> formats =
                extensions.stream()
                        .map(MediaUtils::getExtension)
                        .filter(s -> !s.isBlank())
                        .collect(Collectors.toSet());
        return List.copyOf(formats);
    }

    private String extractTextFromTika(String path)
            throws IOException, SAXException, TikaException {
        try (InputStream is = Files.newInputStream(Path.of(path))) {
            AutoDetectParser parser = new AutoDetectParser();
            Metadata metadata = new Metadata();
            ParseContext context = new ParseContext();
            parser.parse(is, this.handler, metadata, context);
            return this.handler.toString();
        }
    }

    /**
     * Creates Document objects from text chunks with deterministic doc_id.
     *
     * @param chunks the list of text chunks
     * @param path   the document path (used to generate doc_id)
     * @return a list of Document objects
     */
    private List<Document> createDocuments(List<String> chunks, String path) {
        // Generate deterministic doc_id using SHA256 of document path
        String docId = ReaderUtils.generateDocIdSHA256(path);
        List<Document> documents = new ArrayList<>();

        for (int i = 0; i < chunks.size(); i++) {
            TextBlock content = TextBlock.builder().text(chunks.get(i)).build();
            DocumentMetadata metadata = new DocumentMetadata(content, docId, String.valueOf(i));
            documents.add(new Document(metadata));
        }

        return documents;
    }
}
