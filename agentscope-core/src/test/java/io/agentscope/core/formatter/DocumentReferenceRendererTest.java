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
package io.agentscope.core.formatter;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.agentscope.core.message.Base64Source;
import io.agentscope.core.message.DocumentBlock;
import io.agentscope.core.message.FileIdSource;
import io.agentscope.core.message.Msg;
import io.agentscope.core.message.MsgRole;
import io.agentscope.core.message.TextBlock;
import io.agentscope.core.message.ToolResultBlock;
import io.agentscope.core.message.URLSource;
import java.util.List;
import org.junit.jupiter.api.Test;

class DocumentReferenceRendererTest {

    @Test
    void rendersAProviderFileIdWithItsProvider() {
        DocumentBlock document =
                DocumentBlock.builder()
                        .source(new FileIdSource("dashscope", "file-42"))
                        .name("report.pdf")
                        .mimeType("application/pdf")
                        .build();

        assertEquals(
                "[document: report.pdf, application/pdf, provider file id: file-42 (dashscope)]",
                DocumentReferenceRenderer.render(document));
    }

    @Test
    void rendersAUrlInsteadOfTheDocumentBody() {
        DocumentBlock document =
                DocumentBlock.builder()
                        .source(new URLSource("https://example.test/report.pdf"))
                        .name("report.pdf")
                        .mimeType("application/pdf")
                        .build();

        assertEquals(
                "[document: report.pdf, application/pdf, url: https://example.test/report.pdf]",
                DocumentReferenceRenderer.render(document));
    }

    @Test
    void fallsBackToTheGeneratedIdWhenNameAndIdAreUnset() {
        DocumentBlock document =
                DocumentBlock.builder()
                        .source(new URLSource("https://example.test/report.pdf"))
                        .build();

        assertEquals(
                "[document: " + document.getId() + ", url: https://example.test/report.pdf]",
                DocumentReferenceRenderer.render(document));
    }

    @Test
    void fallsBackToUnnamedWhenNameAndIdAreBlank() {
        DocumentBlock document =
                DocumentBlock.builder()
                        .source(new URLSource("https://example.test/report.pdf"))
                        .name(" \n")
                        .id("\t\r\n")
                        .build();

        assertEquals(
                "[document: unnamed, url: https://example.test/report.pdf]",
                DocumentReferenceRenderer.render(document));
    }

    @Test
    void fallsBackToTheSourceMediaTypeWhenTheBlockHasNone() {
        DocumentBlock document =
                DocumentBlock.builder()
                        .source(new Base64Source("application/pdf", "JVBERi0xLjQK"))
                        .name("report.pdf")
                        .build();

        String reference = DocumentReferenceRenderer.render(document);

        assertEquals(
                "[document: report.pdf, application/pdf, inline base64 (~9 bytes)]", reference);
        assertFalse(reference.contains("JVBERi0xLjQK"), "the payload must never be inlined");
    }

    @Test
    void prefersTheDeclaredSizeOverThePayloadEstimate() {
        DocumentBlock document =
                DocumentBlock.builder()
                        .source(new Base64Source("application/pdf", "JVBERi0xLjQK"))
                        .name("report.pdf")
                        .sizeBytes(8_388_608L)
                        .build();

        assertEquals(
                "[document: report.pdf, application/pdf, inline base64 (~8388608 bytes)]",
                DocumentReferenceRenderer.render(document));
    }

    @Test
    void rendersAMessageAsTextPlusOneReferencePerDocument() {
        Msg msg =
                Msg.builder()
                        .name("user")
                        .role(MsgRole.USER)
                        .content(
                                TextBlock.builder().text("Please review both files.").build(),
                                DocumentBlock.builder()
                                        .source(new URLSource("https://example.test/a.pdf"))
                                        .name("a.pdf")
                                        .mimeType("application/pdf")
                                        .build(),
                                DocumentBlock.builder()
                                        .source(new FileIdSource("dashscope", "file-7"))
                                        .name("b.docx")
                                        .mimeType(
                                                "application/vnd.openxmlformats-officedocument"
                                                        + ".wordprocessingml.document")
                                        .build())
                        .build();

        assertEquals(
                "Please review both files.\n"
                        + "[document: a.pdf, application/pdf, url: https://example.test/a.pdf]\n"
                        + "[document: b.docx, application/vnd.openxmlformats-officedocument"
                        + ".wordprocessingml.document, provider file id: file-7 (dashscope)]",
                DocumentReferenceRenderer.renderMessageText(msg));
    }

    @Test
    void aDocumentOnlyMessageRendersWithoutALeadingBlankLine() {
        Msg msg =
                Msg.builder()
                        .name("user")
                        .role(MsgRole.USER)
                        .content(
                                DocumentBlock.builder()
                                        .source(new URLSource("https://example.test/a.pdf"))
                                        .name("a.pdf")
                                        .mimeType("application/pdf")
                                        .build())
                        .build();

        assertEquals(
                "[document: a.pdf, application/pdf, url: https://example.test/a.pdf]",
                DocumentReferenceRenderer.renderMessageText(msg));
    }

    @Test
    void takesTheMimeTypeFromTheUrlSourceWhenTheBlockHasNone() {
        DocumentBlock document =
                DocumentBlock.builder()
                        .source(
                                URLSource.builder()
                                        .url("https://example.test/report.pdf")
                                        .mimeType("application/pdf")
                                        .build())
                        .name("report.pdf")
                        .build();

        assertEquals(
                "[document: report.pdf, application/pdf,"
                        + " url: https://example.test/report.pdf]",
                DocumentReferenceRenderer.render(document));
    }

    @Test
    void subtractsBase64PaddingFromTheSizeEstimate() {
        DocumentBlock document =
                DocumentBlock.builder()
                        .source(new Base64Source("text/plain", "aGVsbG8="))
                        .name("notes.txt")
                        .build();

        assertTrue(
                DocumentReferenceRenderer.render(document).contains("inline base64 (~5 bytes)"),
                DocumentReferenceRenderer.render(document));
    }

    @Test
    void rendersADocumentCarriedByAToolResult() {
        DocumentBlock document =
                DocumentBlock.builder()
                        .source(new URLSource("https://example.test/report.pdf"))
                        .name("report.pdf")
                        .mimeType("application/pdf")
                        .build();
        Msg msg =
                Msg.builder()
                        .role(MsgRole.TOOL)
                        .content(
                                List.of(
                                        ToolResultBlock.of(
                                                List.of(
                                                        TextBlock.builder().text("done").build(),
                                                        document))))
                        .build();

        assertEquals(
                "[document: report.pdf, application/pdf,"
                        + " url: https://example.test/report.pdf]",
                DocumentReferenceRenderer.renderMessageText(msg));
    }

    @Test
    void aPlainTextMessageIsReturnedUnchanged() {
        Msg msg =
                Msg.builder()
                        .name("user")
                        .role(MsgRole.USER)
                        .content(List.of(TextBlock.builder().text("hello").build()))
                        .build();

        assertEquals("hello", DocumentReferenceRenderer.renderMessageText(msg));
        assertEquals("", DocumentReferenceRenderer.renderMessageText(null));
    }

    @Test
    void aReferenceStaysOnOneLineWhenFieldsCarryLineBreaks() {
        DocumentBlock document =
                DocumentBlock.builder()
                        .name("evil\nreport.pdf")
                        .source(URLSource.builder().url("https://example.test/a?b=1\rc=2").build())
                        .build();

        String reference = DocumentReferenceRenderer.render(document);

        assertFalse(
                reference.contains("\n") || reference.contains("\r"),
                "the renderer promises a single line: " + reference);
    }
}
