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

import io.agentscope.core.message.TextBlock;
import io.agentscope.core.rag.exception.ReaderException;
import io.agentscope.core.rag.model.Document;
import io.agentscope.core.rag.model.DocumentMetadata;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import reactor.core.publisher.Mono;

/**
 * Text reader implementation that reads and chunks text documents.
 *
 * <p>This reader supports various text formats and splitting strategies. It can
 * read text from strings or files and split them into chunks according to the
 * specified strategy.
 *
 * <p>Example usage:
 * <pre>{@code
 * TextReader reader = new TextReader(512, SplitStrategy.PARAGRAPH, 50);
 * ReaderInput input = ReaderInput.fromString("Long text content...");
 * List<Document> documents = reader.read(input).block();
 * }</pre>
 */
public class TextReader extends AbstractChunkingReader {

    /**
     * Creates a new TextReader with the specified configuration.
     *
     * @param chunkSize the target size for each chunk (interpreted based on strategy)
     * @param splitStrategy the strategy for splitting text
     * @param overlapSize the number of characters/tokens to overlap between chunks
     * @throws IllegalArgumentException if parameters are invalid
     */
    public TextReader(int chunkSize, SplitStrategy splitStrategy, int overlapSize) {
        super(chunkSize, splitStrategy, overlapSize);
    }

    /**
     * Creates a new TextReader with default settings.
     *
     * <p>Defaults: chunkSize=512, strategy=PARAGRAPH, overlapSize=50
     */
    public TextReader() {
        this(512, SplitStrategy.PARAGRAPH, 50);
    }

    @Override
    public Mono<List<Document>> read(ReaderInput input) {
        if (input == null) {
            return Mono.error(new ReaderException("Input cannot be null"));
        }

        return Mono.fromCallable(
                        () -> {
                            try {
                                String text = input.asString();
                                List<String> chunks =
                                        TextChunker.chunkText(
                                                text, chunkSize, splitStrategy, overlapSize);
                                return createDocuments(chunks);
                            } catch (Exception e) {
                                throw new ReaderException("Failed to read text input", e);
                            }
                        })
                .onErrorMap(ReaderException.class, e -> e); // Re-throw ReaderException as-is
    }

    @Override
    public List<String> getSupportedFormats() {
        return List.of("txt", "md", "rst");
    }

    /**
     * Creates Document objects from text chunks.
     *
     * @param chunks the list of text chunks
     * @return a list of Document objects
     */
    private List<Document> createDocuments(List<String> chunks) {
        String docId = UUID.randomUUID().toString();
        List<Document> documents = new ArrayList<>();

        for (int i = 0; i < chunks.size(); i++) {
            TextBlock content = TextBlock.builder().text(chunks.get(i)).build();
            DocumentMetadata metadata = new DocumentMetadata(content, docId, String.valueOf(i));
            documents.add(new Document(metadata));
        }

        return documents;
    }
}
