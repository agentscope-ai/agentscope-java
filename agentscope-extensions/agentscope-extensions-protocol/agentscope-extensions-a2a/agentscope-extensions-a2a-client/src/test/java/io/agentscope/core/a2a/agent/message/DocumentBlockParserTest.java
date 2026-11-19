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
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.agentscope.core.message.Base64Source;
import io.agentscope.core.message.DocumentBlock;
import io.agentscope.core.message.URLSource;
import java.util.Base64;
import org.a2aproject.sdk.spec.FilePart;
import org.a2aproject.sdk.spec.Part;
import org.a2aproject.sdk.spec.TextPart;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

@DisplayName("DocumentBlockParser Tests")
class DocumentBlockParserTest {

    private final DocumentBlockParser parser = new DocumentBlockParser();

    @Test
    @DisplayName("An untyped inline document travels as a text reference")
    void testUntypedBase64DocumentFallsBackToTextReference() {
        DocumentBlock block =
                DocumentBlock.builder()
                        .name("report.pdf")
                        .source(
                                Base64Source.builder()
                                        .mediaType("")
                                        .data(Base64.getEncoder().encodeToString("pdf".getBytes()))
                                        .build())
                        .build();

        Part<?> part = parser.parse(block);

        // A file part without a MIME type is dropped by the receiving parser, which would lose
        // the document silently.
        assertInstanceOf(TextPart.class, part);
        assertTrue(((TextPart) part).text().contains("[document: report.pdf"));
    }

    @Test
    @DisplayName("The source MIME type is used when the block does not repeat it")
    void testBase64DocumentUsesSourceMimeType() {
        DocumentBlock block =
                DocumentBlock.builder()
                        .name("report.pdf")
                        .source(
                                Base64Source.builder()
                                        .mediaType("application/pdf")
                                        .data(Base64.getEncoder().encodeToString("pdf".getBytes()))
                                        .build())
                        .build();

        Part<?> part = parser.parse(block);

        FilePart filePart = assertInstanceOf(FilePart.class, part);
        assertEquals("application/pdf", filePart.file().mimeType());
    }

    @Test
    @DisplayName("A URL document with no resolvable MIME type travels as a text reference")
    void testUntypedUrlDocumentFallsBackToTextReference() {
        DocumentBlock block =
                DocumentBlock.builder()
                        .name("download")
                        .source(new URLSource("https://example.test/download"))
                        .build();

        Part<?> part = parser.parse(block);

        assertInstanceOf(TextPart.class, part);
    }
}
