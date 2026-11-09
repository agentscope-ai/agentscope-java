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
package io.agentscope.core.tracing.telemetry;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.agentscope.core.formatter.AbstractBaseFormatter;
import io.agentscope.core.message.Base64Source;
import io.agentscope.core.message.DocumentBlock;
import io.agentscope.core.message.Msg;
import io.agentscope.core.message.MsgRole;
import io.agentscope.core.message.TextBlock;
import io.agentscope.core.message.ToolResultBlock;
import io.agentscope.core.model.ChatResponse;
import io.agentscope.core.model.ChatUsage;
import io.agentscope.core.model.GenerateOptions;
import io.agentscope.core.model.ToolSchema;
import io.opentelemetry.api.common.AttributeKey;
import io.opentelemetry.api.common.Attributes;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

@DisplayName("AttributesExtractors Tests")
class AttributesExtractorsTest {

    @Test
    @DisplayName("LLM response attributes include detailed token usage")
    void llmResponseAttributesIncludeDetailedTokenUsage() {
        ChatUsage usage =
                ChatUsage.builder()
                        .inputTokens(100)
                        .outputTokens(50)
                        .cachedTokens(30)
                        .cacheCreationTokens(10)
                        .reasoningTokens(20)
                        .toolUsePromptTokens(15)
                        .build();
        ChatResponse response =
                ChatResponse.builder()
                        .id("response-1")
                        .content(List.of(TextBlock.builder().text("answer").build()))
                        .usage(usage)
                        .finishReason("stop")
                        .build();

        Attributes attributes = AttributesExtractors.getLLMResponseAttributes(response);

        assertEquals(100L, attributes.get(AttributeKey.longKey("gen_ai.usage.input_tokens")));
        assertEquals(50L, attributes.get(AttributeKey.longKey("gen_ai.usage.output_tokens")));
        assertEquals(
                30L, attributes.get(AttributeKey.longKey("gen_ai.usage.cache_read.input_tokens")));
        assertEquals(
                10L,
                attributes.get(AttributeKey.longKey("gen_ai.usage.cache_creation.input_tokens")));
        assertEquals(
                20L, attributes.get(AttributeKey.longKey("gen_ai.usage.reasoning.output_tokens")));
        assertEquals(
                15L,
                attributes.get(AttributeKey.longKey("agentscope.usage.tool_use_prompt_tokens")));
    }

    @Test
    @DisplayName("LLM response without usage does not fail")
    void llmResponseWithoutUsageDoesNotFail() {
        ChatResponse response =
                ChatResponse.builder()
                        .id("response-no-usage")
                        .content(List.of(TextBlock.builder().text("answer").build()))
                        .finishReason("stop")
                        .build();

        Attributes attributes = AttributesExtractors.getLLMResponseAttributes(response);

        assertNull(attributes.get(AttributeKey.longKey("gen_ai.usage.input_tokens")));
        assertNull(attributes.get(AttributeKey.longKey("gen_ai.usage.output_tokens")));
    }

    @Test
    @DisplayName("Tool call result keeps a document reference but drops the payload bytes")
    void toolCallResultReplacesDocumentAndDataPayloadsWithReferences() {
        DocumentBlock document =
                DocumentBlock.builder()
                        .source(
                                Base64Source.builder()
                                        .mediaType("application/pdf")
                                        .data("aGVsbG8gd29ybGQ=")
                                        .build())
                        .name("report.pdf")
                        .mimeType("application/pdf")
                        .build();
        ToolResultBlock result = ToolResultBlock.of(List.of(document));

        Attributes attributes = AttributesExtractors.getToolResponseAttributes(result);

        String toolResult = attributes.get(AttributeKey.stringKey("gen_ai.tool.call.result"));
        assertNotNull(toolResult);
        assertTrue(toolResult.contains("[document: report.pdf, application/pdf"), toolResult);
        assertFalse(toolResult.contains("aGVsbG8gd29ybGQ="), toolResult);
    }

    @Test
    @DisplayName("Format request attributes keep text but redact media payloads")
    void formatRequestAttributesRedactMediaPayloads() {
        DocumentBlock document =
                DocumentBlock.builder()
                        .source(
                                Base64Source.builder()
                                        .mediaType("application/pdf")
                                        .data("aGVsbG8gd29ybGQ=")
                                        .build())
                        .name("report.pdf")
                        .mimeType("application/pdf")
                        .build();
        Msg msg =
                Msg.builder()
                        .role(MsgRole.USER)
                        .content(List.of(TextBlock.builder().text("Read this").build(), document))
                        .build();

        Attributes attributes =
                AttributesExtractors.getFormatRequestAttributes(stubFormatter(), List.of(msg));

        String input = attributes.get(AttributeKey.stringKey("agentscope.function.input"));
        assertNotNull(input);
        assertTrue(input.contains("Read this"), input);
        assertTrue(input.contains("[document: report.pdf, application/pdf"), input);
        assertFalse(input.contains("aGVsbG8gd29ybGQ="), input);
    }

    @Test
    @DisplayName("Format request attributes redact documents nested in tool results")
    void formatRequestAttributesRedactDocumentsInsideToolResults() {
        DocumentBlock document =
                DocumentBlock.builder()
                        .source(
                                Base64Source.builder()
                                        .mediaType("application/pdf")
                                        .data("aGVsbG8gd29ybGQ=")
                                        .build())
                        .name("report.pdf")
                        .mimeType("application/pdf")
                        .build();
        Msg msg =
                Msg.builder()
                        .role(MsgRole.TOOL)
                        .content(
                                List.of(
                                        ToolResultBlock.builder()
                                                .id("call-1")
                                                .name("generate_report")
                                                .output(
                                                        List.of(
                                                                TextBlock.builder()
                                                                        .text("done")
                                                                        .build(),
                                                                document))
                                                .build()))
                        .build();

        Attributes attributes =
                AttributesExtractors.getFormatRequestAttributes(stubFormatter(), List.of(msg));

        String input = attributes.get(AttributeKey.stringKey("agentscope.function.input"));
        assertNotNull(input);
        assertTrue(input.contains("done"), input);
        assertTrue(input.contains("[document: report.pdf, application/pdf"), input);
        assertFalse(input.contains("aGVsbG8gd29ybGQ="), input);
    }

    @Test
    @DisplayName("Format request attributes pass text-only messages through unchanged")
    void formatRequestAttributesKeepTextOnlyMessagesIntact() {
        Msg msg =
                Msg.builder()
                        .role(MsgRole.USER)
                        .content(
                                List.of(
                                        TextBlock.builder().text("plain text").build(),
                                        TextBlock.builder().text("second line").build()))
                        .build();

        Attributes attributes =
                AttributesExtractors.getFormatRequestAttributes(stubFormatter(), List.of(msg));

        String input = attributes.get(AttributeKey.stringKey("agentscope.function.input"));
        assertNotNull(input);
        assertTrue(input.contains("plain text"), input);
        assertTrue(input.contains("second line"), input);
    }

    /**
     * {@code getFormatRequestAttributes} only reads the runtime class name; the formatter behavior
     * itself is irrelevant here.
     */
    private static AbstractBaseFormatter<Object, Object, Object> stubFormatter() {
        return new AbstractBaseFormatter<Object, Object, Object>() {
            @Override
            protected List<Object> doFormat(List<Msg> msgs) {
                return List.of();
            }

            @Override
            public ChatResponse parseResponse(Object response, Instant startTime) {
                throw new UnsupportedOperationException();
            }

            @Override
            public void applyOptions(
                    Object paramsBuilder,
                    GenerateOptions options,
                    GenerateOptions defaultOptions) {}

            @Override
            public void applyTools(Object paramsBuilder, List<ToolSchema> tools) {}
        };
    }
}
