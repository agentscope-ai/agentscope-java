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
package io.agentscope.core.message;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Behaviour spec for {@link DocumentBlock} and {@link FileIdSource} — document input that may
 * reference a provider-side file id instead of carrying the bytes.
 */
class DocumentBlockTest {

    private final ObjectMapper mapper = new ObjectMapper();

    @Test
    @DisplayName("Builder constructs a DocumentBlock with FileIdSource")
    void testBuildWithFileIdSource() {
        DocumentBlock block =
                DocumentBlock.builder()
                        .source(
                                FileIdSource.builder()
                                        .provider("dashscope")
                                        .fileId("file-abc")
                                        .build())
                        .name("report.pdf")
                        .mimeType("application/pdf")
                        .sizeBytes(123L)
                        .build();

        assertInstanceOf(FileIdSource.class, block.getSource());
        assertEquals("dashscope", ((FileIdSource) block.getSource()).getProvider());
        assertEquals("file-abc", ((FileIdSource) block.getSource()).getFileId());
        assertEquals("report.pdf", block.getName());
        assertEquals("application/pdf", block.getMimeType());
        assertEquals(123L, block.getSizeBytes());
        assertNotNull(block.getId());
        assertFalse(block.getId().isEmpty());
        assertNull(block.getMetadata());
    }

    @Test
    @DisplayName("JSON round-trip preserves source/id/name/mime_type and emits type=\"document\"")
    void testJsonRoundTrip() throws Exception {
        DocumentBlock original =
                DocumentBlock.builder()
                        .source(
                                FileIdSource.builder()
                                        .provider("dashscope")
                                        .fileId("file-abc")
                                        .build())
                        .id("doc-1")
                        .name("report.pdf")
                        .mimeType("application/pdf")
                        .sizeBytes(123L)
                        .build();

        String json = mapper.writeValueAsString(original);
        assertTrue(json.contains("\"type\":\"document\""), "missing type discriminator: " + json);
        assertTrue(json.contains("\"type\":\"file_id\""), "missing source discriminator: " + json);
        assertTrue(json.contains("\"file_id\":\"file-abc\""), "missing file id: " + json);
        assertTrue(json.contains("\"provider\":\"dashscope\""), "missing provider: " + json);
        assertTrue(
                json.contains("\"mime_type\":\"application/pdf\""), "missing mime type: " + json);
        assertTrue(json.contains("\"size_bytes\":123"), "missing size: " + json);

        DocumentBlock parsed = mapper.readValue(json, DocumentBlock.class);
        assertEquals("doc-1", parsed.getId());
        assertEquals("report.pdf", parsed.getName());
        assertEquals("application/pdf", parsed.getMimeType());
        assertEquals(123L, parsed.getSizeBytes());
        assertInstanceOf(FileIdSource.class, parsed.getSource());
        assertEquals("dashscope", ((FileIdSource) parsed.getSource()).getProvider());
        assertEquals("file-abc", ((FileIdSource) parsed.getSource()).getFileId());
        assertEquals(original, parsed);
    }

    @Test
    @DisplayName("ContentBlock polymorphic deserialization resolves type=\"document\"")
    void testJsonDeserializationViaContentBlock() throws Exception {
        String json =
                "{\"type\":\"document\","
                        + "\"source\":{\"type\":\"file_id\",\"provider\":\"dashscope\","
                        + "\"file_id\":\"file-xyz\"},"
                        + "\"id\":\"doc-2\",\"name\":\"spec.docx\",\"size_bytes\":456}";
        ContentBlock block = mapper.readValue(json, ContentBlock.class);

        assertInstanceOf(DocumentBlock.class, block);
        DocumentBlock document = (DocumentBlock) block;
        assertEquals("doc-2", document.getId());
        assertEquals("spec.docx", document.getName());
        assertEquals(456L, document.getSizeBytes());
        assertEquals("file-xyz", ((FileIdSource) document.getSource()).getFileId());
        assertEquals("dashscope", ((FileIdSource) document.getSource()).getProvider());
    }

    @Test
    @DisplayName("URL and Base64 sources are valid document sources")
    void testNonFileIdSources() {
        DocumentBlock urlDocument =
                DocumentBlock.builder()
                        .source(URLSource.builder().url("https://example.com/a.pdf").build())
                        .build();
        DocumentBlock base64Document =
                DocumentBlock.builder()
                        .source(
                                Base64Source.builder()
                                        .mediaType("application/pdf")
                                        .data("JVBERi0=")
                                        .build())
                        .build();

        assertInstanceOf(URLSource.class, urlDocument.getSource());
        assertInstanceOf(Base64Source.class, base64Document.getSource());
    }

    @Test
    @DisplayName("Provider metadata survives the JSON round-trip")
    void testMetadataRoundTrip() throws Exception {
        DocumentBlock original =
                DocumentBlock.builder()
                        .source(
                                FileIdSource.builder()
                                        .provider("dashscope")
                                        .fileId("file-abc")
                                        .build())
                        .metadata(Map.of("provider", "dashscope"))
                        .build();

        DocumentBlock parsed =
                mapper.readValue(mapper.writeValueAsString(original), DocumentBlock.class);

        assertEquals(Map.of("provider", "dashscope"), parsed.getMetadata());
        assertEquals(original, parsed);
    }

    @Test
    @DisplayName("Provider metadata with binary values survives the JSON round-trip")
    void testBinaryMetadataRoundTrip() throws Exception {
        DocumentBlock original =
                DocumentBlock.builder()
                        .source(
                                FileIdSource.builder()
                                        .provider("dashscope")
                                        .fileId("file-abc")
                                        .build())
                        .metadata(Map.of("signature", new byte[] {1, 2, 3}))
                        .build();

        DocumentBlock parsed =
                mapper.readValue(mapper.writeValueAsString(original), DocumentBlock.class);

        assertEquals(original, parsed);
    }

    @Test
    @DisplayName("Provider metadata is defensively copied and immutable")
    void testMetadataIsDefensivelyCopied() {
        Map<String, Object> metadata = new HashMap<>();
        metadata.put("provider", "dashscope");
        DocumentBlock block =
                DocumentBlock.builder()
                        .source(
                                FileIdSource.builder()
                                        .provider("dashscope")
                                        .fileId("file-abc")
                                        .build())
                        .metadata(metadata)
                        .build();

        metadata.put("mutable", "value");

        assertFalse(block.getMetadata().containsKey("mutable"));
        assertThrows(UnsupportedOperationException.class, () -> block.getMetadata().put("x", "y"));
    }

    @Test
    @DisplayName("id is auto-generated when omitted")
    void testIdAutoGenerated() {
        DocumentBlock a =
                DocumentBlock.builder()
                        .source(
                                FileIdSource.builder()
                                        .provider("dashscope")
                                        .fileId("file-a")
                                        .build())
                        .build();
        DocumentBlock b =
                DocumentBlock.builder()
                        .source(
                                FileIdSource.builder()
                                        .provider("dashscope")
                                        .fileId("file-b")
                                        .build())
                        .build();

        assertNotNull(a.getId());
        assertFalse(a.getId().isEmpty());
        assertFalse(a.getId().equals(b.getId()), "auto-generated ids should be unique");
    }

    @Test
    @DisplayName("source is required")
    void testSourceRequired() {
        assertThrows(NullPointerException.class, () -> DocumentBlock.builder().build());
    }

    @Test
    @DisplayName("USER messages accept a document block, SYSTEM messages do not")
    void testRoleValidation() {
        DocumentBlock document =
                DocumentBlock.builder()
                        .source(
                                FileIdSource.builder()
                                        .provider("dashscope")
                                        .fileId("file-abc")
                                        .build())
                        .build();

        Msg user =
                Msg.builder()
                        .role(MsgRole.USER)
                        .content(
                                List.of(
                                        TextBlock.builder().text("summarise this").build(),
                                        document))
                        .build();
        assertTrue(user.hasContentBlocks(DocumentBlock.class));

        assertThrows(
                IllegalArgumentException.class,
                () -> Msg.builder().role(MsgRole.SYSTEM).content(List.of(document)).build());
    }
}
