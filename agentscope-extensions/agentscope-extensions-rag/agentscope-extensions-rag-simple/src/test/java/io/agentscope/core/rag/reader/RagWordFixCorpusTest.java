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

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.agentscope.core.rag.model.Document;
import java.net.URL;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.List;
import java.util.stream.Collectors;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * @Description: Unit tests for WordReader.
 **/
@DisplayName("WordReader Test Unit")
public class RagWordFixCorpusTest {
    @Test
    @DisplayName("Fix the deleted blank paragraphs")
    void shouldParsePolicyDocxWithKnownKeyword() {
        assertDocxReadable("/rag-test.docx", "This technical report");
    }

    private void assertDocxReadable(String classpathLocation, String expectedKeyword) {
        Path docxPath = resolveClasspathFile(classpathLocation);
        List<Document> docs = new WordReader().read(ReaderInput.fromPath(docxPath)).block();
        assertNotNull(docs, classpathLocation + " parse result is null");
        assertFalse(docs.isEmpty(), classpathLocation + " parsed 0 chunks, corpus may be empty");

        for (Document doc : docs) {
            String text = doc.getMetadata().getContentText();
            assertTrue(
                    text != null && !text.isBlank(),
                    classpathLocation
                            + " contains a blank chunk (docId="
                            + doc.getMetadata().getDocId()
                            + ")");
        }

        // Concatenate all chunks in order before searching the keyword, to avoid false negatives
        // when the keyword is split across chunk boundaries
        String fullText =
                docs.stream()
                        .map(doc -> doc.getMetadata().getContentText())
                        .collect(Collectors.joining());
        assertTrue(
                fullText.contains(expectedKeyword),
                String.format(
                        "%s full text (%d chunks, %d chars) does not contain keyword '%s'",
                        classpathLocation, docs.size(), fullText.length(), expectedKeyword));
    }

    private Path resolveClasspathFile(String classpathLocation) {
        URL url = getClass().getResource(classpathLocation);
        assertNotNull(
                url,
                "Resource not found: "
                        + classpathLocation
                        + ", ensure the file exists under src/test/resources/");
        try {
            return Paths.get(url.toURI());
        } catch (Exception e) {
            throw new IllegalStateException("Failed to resolve " + classpathLocation + " path", e);
        }
    }
}
