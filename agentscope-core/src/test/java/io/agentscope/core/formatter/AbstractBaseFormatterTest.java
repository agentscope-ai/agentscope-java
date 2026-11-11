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
import io.agentscope.core.message.ContentBlock;
import io.agentscope.core.message.DataBlock;
import io.agentscope.core.message.DocumentBlock;
import io.agentscope.core.message.FileIdSource;
import io.agentscope.core.message.Msg;
import io.agentscope.core.message.TextBlock;
import io.agentscope.core.message.URLSource;
import io.agentscope.core.model.ChatResponse;
import io.agentscope.core.model.GenerateOptions;
import io.agentscope.core.model.ToolSchema;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/**
 * Unit tests for {@link AbstractBaseFormatter}.
 *
 * <p>These tests verify the block dispatch shared by every provider formatter:
 * <ul>
 *   <li>Tool-result text fallback renders a reference for documents</li>
 *   <li>Text blocks keep rendering verbatim</li>
 * </ul>
 */
@Tag("unit")
@DisplayName("AbstractBaseFormatter Unit Tests")
class AbstractBaseFormatterTest {

    private final TestFormatter formatter = new TestFormatter();

    @Test
    @DisplayName("Should render a URL document reference in tool result text")
    void documentBlockProducesAReferenceInToolResultText() {
        DocumentBlock document =
                DocumentBlock.builder()
                        .source(
                                URLSource.builder()
                                        .url("https://example.com/report.pdf")
                                        .mimeType("application/pdf")
                                        .build())
                        .name("report.pdf")
                        .build();

        String text = formatter.toolResult(List.<ContentBlock>of(document));

        assertTrue(text.contains("document"), text);
        assertTrue(text.contains("https://example.com/report.pdf"), text);
    }

    @Test
    @DisplayName("Should render a provider file id document reference in tool result text")
    void providerFileIdDocumentProducesAReferenceInToolResultText() {
        DocumentBlock document =
                DocumentBlock.builder()
                        .source(new FileIdSource("dashscope", "file-123"))
                        .name("report.pdf")
                        .build();

        String text = formatter.toolResult(List.<ContentBlock>of(document));

        assertTrue(text.contains("file id"), text);
        assertTrue(text.contains("file-123"), text);
    }

    @Test
    @DisplayName("Should describe an inline document by size, not by a local path")
    void inlineDocumentDoesNotLeakATempPath() {
        DocumentBlock document =
                DocumentBlock.builder()
                        .source(new Base64Source("application/pdf", "JVBERi0xLjQK"))
                        .name("report.pdf")
                        .build();

        String text = formatter.toolResult(List.<ContentBlock>of(document));

        assertTrue(text.contains("inline base64"), text);
        assertFalse(text.contains("agentscope_"), text);
        assertFalse(text.contains("JVBERi0xLjQK"), text);
    }

    @Test
    @DisplayName("Should render text blocks verbatim")
    void textBlockStillRendersVerbatim() {
        String text =
                formatter.toolResult(
                        List.<ContentBlock>of(TextBlock.builder().text("plain").build()));

        assertEquals("plain", text);
    }

    @Test
    @DisplayName("Should render a DataBlock URL reference in tool result text")
    void dataBlockProducesAReferenceInToolResultText() {
        DataBlock data =
                DataBlock.builder()
                        .source(
                                URLSource.builder()
                                        .url("https://example.com/asset.bin")
                                        .mimeType("application/octet-stream")
                                        .build())
                        .build();

        String text = formatter.toolResult(List.<ContentBlock>of(data));

        assertEquals("The returned data can be found at: https://example.com/asset.bin", text);
    }

    private static final class TestFormatter extends AbstractBaseFormatter<Object, Object, Object> {

        @Override
        protected List<Object> doFormat(List<Msg> msgs) {
            return List.of();
        }

        @Override
        public void applyTools(Object paramsBuilder, List<ToolSchema> tools) {
            // No provider-specific parameters in this test formatter.
        }

        @Override
        public void applyOptions(
                Object paramsBuilder, GenerateOptions options, GenerateOptions defaultOptions) {
            // No provider-specific options in this test formatter.
        }

        @Override
        public ChatResponse parseResponse(Object response, Instant startTime) {
            return null;
        }

        String toolResult(List<ContentBlock> blocks) {
            return convertToolResultToString(blocks);
        }
    }
}
