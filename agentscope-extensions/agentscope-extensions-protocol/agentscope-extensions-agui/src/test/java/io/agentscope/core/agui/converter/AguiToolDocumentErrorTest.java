/*
 * Copyright 2024-2026 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package io.agentscope.core.agui.converter;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.agentscope.core.agui.model.AguiMessage;
import io.agentscope.core.agui.model.MessageContent;
import io.agentscope.core.message.Base64Source;
import io.agentscope.core.message.ContentBlock;
import io.agentscope.core.message.DocumentBlock;
import io.agentscope.core.message.FileIdSource;
import io.agentscope.core.message.Msg;
import io.agentscope.core.message.MsgRole;
import io.agentscope.core.message.Source;
import io.agentscope.core.message.TextBlock;
import io.agentscope.core.message.ToolResultBlock;
import io.agentscope.core.message.ToolResultState;
import io.agentscope.core.message.URLSource;
import io.agentscope.core.util.JsonUtils;
import java.util.List;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

class AguiToolDocumentErrorTest {
    private final AguiMessageConverter converter = new AguiMessageConverter();

    static Stream<Arguments> documentResults() {
        List<Source> sources =
                List.of(
                        new URLSource("https://example.com/report.pdf", "application/pdf"),
                        new Base64Source("application/pdf", "JVBERi0xLjQ="),
                        new FileIdSource("dashscope", "file-report"));
        return sources.stream()
                .flatMap(
                        source ->
                                Stream.of(
                                        Arguments.of(source, ToolResultState.ERROR),
                                        Arguments.of(source, ToolResultState.SUCCESS)));
    }

    @ParameterizedTest
    @MethodSource("documentResults")
    void documentOnlyResultPreservesStateAcrossJson(Source source, ToolResultState state) {
        ToolResultBlock result =
                ToolResultBlock.builder()
                        .id("tc-document")
                        .state(state)
                        .output(document(source))
                        .build();
        AguiMessage wire =
                converter
                        .toAguiMessages(Msg.builder().role(MsgRole.TOOL).content(result).build())
                        .get(0);

        ToolResultBlock restored = restore(wire);

        assertEquals(state, restored.getState());
        assertEquals("tc-document", restored.getId());
        DocumentBlock attachment =
                restored.getOutput().stream()
                        .filter(DocumentBlock.class::isInstance)
                        .map(DocumentBlock.class::cast)
                        .findFirst()
                        .orElseThrow();
        assertEquals("doc-report", attachment.getId());
        assertEquals(
                JsonUtils.getJsonCodec().toJson(source),
                JsonUtils.getJsonCodec().toJson(attachment.getSource()));
        assertInstanceOf(MessageContent.Blocks.class, wire.getContent());
        if (state == ToolResultState.ERROR) {
            assertNotNull(wire.getError());
            assertFalse(wire.getError().isBlank());
            if (source instanceof Base64Source base64) {
                assertTrue(wire.getError().contains("inline base64"));
                assertFalse(wire.getError().contains(base64.getData()));
            }
        } else {
            assertNull(wire.getError());
        }
        String expectedShape = JsonUtils.getJsonCodec().toJson(restored.getOutput());
        for (int i = 0; i < 2; i++) {
            Msg replay = Msg.builder().role(MsgRole.TOOL).content(restored).build();
            restored = restore(converter.toAguiMessages(replay).get(0));
            assertEquals(state, restored.getState());
            assertEquals(expectedShape, JsonUtils.getJsonCodec().toJson(restored.getOutput()));
        }
    }

    static Stream<Arguments> unrenderableResults() {
        return Stream.of(
                        List.<ContentBlock>of(),
                        List.<ContentBlock>of(TextBlock.builder().text("").build()),
                        List.<ContentBlock>of(TextBlock.builder().text(" \n\t").build()))
                .flatMap(
                        output ->
                                Stream.of(
                                        Arguments.of(output, ToolResultState.ERROR),
                                        Arguments.of(output, ToolResultState.SUCCESS)));
    }

    @ParameterizedTest
    @MethodSource("unrenderableResults")
    void emptyOrBlankResultPreservesStateAcrossJson(
            List<ContentBlock> output, ToolResultState state) {
        ToolResultBlock result =
                ToolResultBlock.builder().id("tc-empty").state(state).output(output).build();
        Msg message = Msg.builder().role(MsgRole.TOOL).content(result).build();
        AguiMessage wire = converter.toAguiMessages(message).get(0);

        ToolResultBlock restored = restore(wire);

        assertEquals(state, restored.getState());
        assertEquals("tc-empty", restored.getId());
        if (state == ToolResultState.ERROR) {
            assertNotNull(wire.getError());
            assertFalse(wire.getError().isBlank());
            assertEquals(wire.getTextContent(), wire.getError());
        } else {
            assertNull(wire.getError());
            assertEquals(
                    output.stream()
                            .map(TextBlock.class::cast)
                            .map(TextBlock::getText)
                            .collect(java.util.stream.Collectors.joining("\n")),
                    wire.getTextContent());
        }
    }

    @Test
    void blankDiagnosticDoesNotTurnFailedDocumentIntoSuccess() {
        ToolResultBlock result =
                ToolResultBlock.builder()
                        .id("tc-document")
                        .state(ToolResultState.ERROR)
                        .output(
                                List.of(
                                        TextBlock.builder().text(" \n\t").build(),
                                        document(
                                                new URLSource(
                                                        "https://example.com/report.pdf",
                                                        "application/pdf"))))
                        .build();
        AguiMessage wire =
                converter
                        .toAguiMessages(Msg.builder().role(MsgRole.TOOL).content(result).build())
                        .get(0);

        assertEquals(ToolResultState.ERROR, restore(wire).getState());
    }

    @Test
    void expandedResultsKeepFailureAndSuccessDistinct() {
        Source source = new URLSource("https://example.com/report.pdf", "application/pdf");
        Msg msg =
                Msg.builder()
                        .role(MsgRole.TOOL)
                        .content(
                                List.of(
                                        ToolResultBlock.builder()
                                                .id("tc-failed")
                                                .state(ToolResultState.ERROR)
                                                .output(document(source))
                                                .build(),
                                        ToolResultBlock.builder()
                                                .id("tc-ok")
                                                .state(ToolResultState.SUCCESS)
                                                .output(document(source))
                                                .build()))
                        .build();

        List<AguiMessage> messages = converter.toAguiMessageList(List.of(msg));

        assertEquals(2, messages.size());
        ToolResultBlock failed = restore(messages.get(0));
        ToolResultBlock succeeded = restore(messages.get(1));
        assertEquals("tc-failed", failed.getId());
        assertEquals("tc-ok", succeeded.getId());
        assertEquals(ToolResultState.ERROR, failed.getState());
        assertEquals(ToolResultState.SUCCESS, succeeded.getState());
    }

    @Test
    void documentErrorDiagnosticDoesNotDuplicateAcrossRepeatedWireRoundTrips() {
        ToolResultBlock result =
                ToolResultBlock.builder()
                        .id("tc-document")
                        .state(ToolResultState.ERROR)
                        .output(
                                List.of(
                                        TextBlock.builder()
                                                .text("document extraction failed")
                                                .build(),
                                        document(
                                                new URLSource(
                                                        "https://example.com/report.pdf",
                                                        "application/pdf"))))
                        .build();
        Msg msg = Msg.builder().role(MsgRole.TOOL).content(result).build();
        for (int i = 0; i < 3; i++) {
            AguiMessage wire = converter.toAguiMessages(msg).get(0);
            assertEquals("document extraction failed", wire.getError());
            ToolResultBlock restored = restore(wire);
            assertEquals(ToolResultState.ERROR, restored.getState());
            String text =
                    restored.getOutput().stream()
                            .filter(TextBlock.class::isInstance)
                            .map(TextBlock.class::cast)
                            .map(TextBlock::getText)
                            .collect(java.util.stream.Collectors.joining("\n"));
            assertEquals("document extraction failed", text);
            assertEquals(
                    1,
                    restored.getOutput().stream().filter(DocumentBlock.class::isInstance).count());
            msg = Msg.builder().role(MsgRole.TOOL).content(restored).build();
        }
    }

    @Test
    void distinctFrontendErrorIsPreservedAlongsideStructuredContent() {
        ToolResultBlock original =
                ToolResultBlock.builder()
                        .id("tc-document")
                        .output(
                                List.of(
                                        TextBlock.builder().text("partial output").build(),
                                        document(
                                                new URLSource(
                                                        "https://example.com/report.pdf",
                                                        "application/pdf"))))
                        .build();
        AguiMessage content =
                converter
                        .toAguiMessages(Msg.builder().role(MsgRole.TOOL).content(original).build())
                        .get(0);
        AguiMessage wire =
                new AguiMessage(
                        content.getId(),
                        "tool",
                        content.getContent(),
                        null,
                        "tc-document",
                        "document extraction failed");

        ToolResultBlock restored = restore(wire);

        assertEquals(ToolResultState.ERROR, restored.getState());
        assertEquals(
                "document extraction failed", ((TextBlock) restored.getOutput().get(0)).getText());
        assertEquals("partial output", ((TextBlock) restored.getOutput().get(2)).getText());
        assertInstanceOf(DocumentBlock.class, restored.getOutput().get(1));
    }

    private ToolResultBlock restore(AguiMessage message) {
        String json = JsonUtils.getJsonCodec().toJson(message);
        AguiMessage parsed = JsonUtils.getJsonCodec().fromJson(json, AguiMessage.class);
        return converter.toMsg(parsed).getFirstContentBlock(ToolResultBlock.class);
    }

    private static DocumentBlock document(Source source) {
        return DocumentBlock.builder()
                .id("doc-report")
                .name("report.pdf")
                .mimeType("application/pdf")
                .source(source)
                .build();
    }
}
