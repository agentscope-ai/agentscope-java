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
