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

import io.agentscope.core.rag.model.Document;
import java.util.List;
import reactor.core.publisher.Mono;

/**
 * Interface for document readers.
 *
 * <p>This interface provides a unified API for reading various types of documents
 * and chunking them into Document objects. Implementations should support different
 * file formats and input sources.
 */
public interface Reader {

    /**
     * Reads a document and returns a list of chunked Document objects.
     *
     * <p>Errors during reading are propagated through the Mono via {@code Mono.error()}.
     *
     * @param input the input to read (text, file path, URL, etc.)
     * @return a Mono that emits a list of Document objects, or an error signal on failure
     */
    Mono<List<Document>> read(ReaderInput input);

    /**
     * Gets the list of file formats supported by this reader.
     *
     * @return a list of supported file extensions (e.g., "txt", "md", "pdf")
     */
    List<String> getSupportedFormats();
}
