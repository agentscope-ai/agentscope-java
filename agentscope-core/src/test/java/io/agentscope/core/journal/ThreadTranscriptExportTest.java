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
package io.agentscope.core.journal;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.agentscope.core.message.AssistantMessage;
import io.agentscope.core.message.Base64Source;
import io.agentscope.core.message.DataBlock;
import io.agentscope.core.message.DocumentBlock;
import io.agentscope.core.message.FileIdSource;
import io.agentscope.core.message.Msg;
import io.agentscope.core.message.MsgRole;
import io.agentscope.core.message.TextBlock;
import io.agentscope.core.message.ToolResultBlock;
import io.agentscope.core.message.ToolResultMessage;
import io.agentscope.core.message.ToolUseBlock;
import io.agentscope.core.message.URLSource;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class ThreadTranscriptExportTest {
    @Test
    void blankTextKeepsItsLineLikeTheAllTextBaseline() {
        assertEquals(
                "alpha\n\nbeta",
                exportToolResult(
                        List.of(
                                TextBlock.builder().text("alpha").build(),
                                TextBlock.builder().text("").build(),
                                TextBlock.builder().text("beta").build())));
    }

    @Test
    void blankLinesSurviveLeadingTrailingConsecutiveAndAllBlankOutputs() {
        assertEquals(
                "\nbeta",
                exportToolResult(
                        List.of(
                                TextBlock.builder().text("").build(),
                                TextBlock.builder().text("beta").build())));
        assertEquals(
                "alpha\n",
                exportToolResult(
                        List.of(
                                TextBlock.builder().text("alpha").build(),
                                TextBlock.builder().text("").build())));
        assertEquals(
                "alpha\n\n\nbeta",
                exportToolResult(
                        List.of(
                                TextBlock.builder().text("alpha").build(),
                                TextBlock.builder().text("").build(),
                                TextBlock.builder().text("").build(),
                                TextBlock.builder().text("beta").build())));
        assertEquals(
                "\n",
                exportToolResult(
                        List.of(
                                TextBlock.builder().text("").build(),
                                TextBlock.builder().text("").build())));
        assertEquals("", exportToolResult(List.of(TextBlock.builder().text("").build())));
    }

    private String exportToolResult(List<io.agentscope.core.message.ContentBlock> output) {
        var request =
                ToolUseBlock.builder().id("call").name("log").input(Map.of("step", "1")).build();
        var result = ToolResultBlock.builder().id("call").name("log").output(output).build();
        var transcript =
                new ThreadHistory.Transcript(
                        9,
                        List.of(
                                AssistantMessage.builder().id("request").content(request).build(),
                                ToolResultMessage.builder().id("result").result(result).build()));
        return ThreadTranscriptExport.entries(transcript).get(1).toolResult();
    }

    @Test
    void documentInMixedToolResultBecomesAReferenceNotBase64() {
        var request =
                ToolUseBlock.builder()
                        .id("call")
                        .name("export")
                        .input(Map.of("file", "report.pdf"))
                        .build();
        var result =
                ToolResultBlock.builder()
                        .id("call")
                        .name("export")
                        .output(
                                List.of(
                                        TextBlock.builder().text("report ready").build(),
                                        DocumentBlock.builder()
                                                .name("report.pdf")
                                                .mimeType("application/pdf")
                                                .source(
                                                        Base64Source.builder()
                                                                .mediaType("application/pdf")
                                                                .data("aGVsbG8gd29ybGQ=")
                                                                .build())
                                                .build(),
                                        DataBlock.builder()
                                                .source(
                                                        URLSource.builder()
                                                                .url(
                                                                        "https://example.test/preview.png")
                                                                .build())
                                                .build()))
                        .build();
        var transcript =
                new ThreadHistory.Transcript(
                        9,
                        List.of(
                                AssistantMessage.builder().id("request").content(request).build(),
                                ToolResultMessage.builder().id("result").result(result).build()));

        String toolResult = ThreadTranscriptExport.entries(transcript).get(1).toolResult();

        assertTrue(toolResult.contains("[document: report.pdf, application/pdf"), toolResult);
        assertTrue(toolResult.contains("report ready"), toolResult);
        assertFalse(toolResult.contains("aGVsbG8gd29ybGQ="), toolResult);
    }

    @Test
    void flatUiRowsRetainToolInputAndMixedOutput() {
        var request =
                ToolUseBlock.builder()
                        .id("call")
                        .name("inspect")
                        .input(Map.of("path", "plot.png"))
                        .build();
        var result =
                ToolResultBlock.builder()
                        .id("call")
                        .name("inspect")
                        .output(
                                List.of(
                                        TextBlock.builder().text("found plot").build(),
                                        DataBlock.builder()
                                                .source(
                                                        URLSource.builder()
                                                                .url(
                                                                        "https://example.test/plot.png")
                                                                .build())
                                                .build()))
                        .build();
        var transcript =
                new ThreadHistory.Transcript(
                        9,
                        List.of(
                                AssistantMessage.builder().id("request").content(request).build(),
                                ToolResultMessage.builder().id("result").result(result).build()));
        var entries = ThreadTranscriptExport.entries(transcript);
        assertEquals(2, entries.size());
        assertEquals("TOOL", entries.get(0).role());
        assertEquals("plot.png", entries.get(0).toolInput().get("path"));
        assertEquals("TOOL", entries.get(1).role());
        assertTrue(entries.get(1).toolResult().contains("found plot"));
        assertTrue(entries.get(1).toolResult().contains("https://example.test/plot.png"));
        assertTrue(ThreadTranscriptExport.jsonl(transcript).contains("toolInput"));
    }

    @Test
    void aDocumentOnlyTurnExportsAReferenceInsteadOfAnEmptyEntry() {
        DocumentBlock document =
                DocumentBlock.builder()
                        .source(new FileIdSource("dashscope", "file-42"))
                        .name("report.pdf")
                        .mimeType("application/pdf")
                        .build();
        Msg msg =
                Msg.builder()
                        .id("turn")
                        .name("user")
                        .role(MsgRole.USER)
                        .content(List.of(document))
                        .build();
        var transcript = new ThreadHistory.Transcript(3, List.of(msg));

        var entries = ThreadTranscriptExport.entries(transcript);

        assertEquals(1, entries.size());
        assertEquals(
                "[document: report.pdf, application/pdf, provider file id: file-42 (dashscope)]",
                entries.get(0).content());
    }

    @Test
    void aDocumentInsideAToolResultIsRenderedReadably() {
        DocumentBlock document =
                DocumentBlock.builder()
                        .source(new URLSource("https://example.test/report.pdf"))
                        .name("report.pdf")
                        .mimeType("application/pdf")
                        .build();
        var result =
                ToolResultBlock.builder()
                        .id("call")
                        .name("download")
                        .output(List.of(TextBlock.builder().text("downloaded").build(), document))
                        .build();
        var transcript =
                new ThreadHistory.Transcript(
                        4,
                        List.of(ToolResultMessage.builder().id("result").result(result).build()));

        var entries = ThreadTranscriptExport.entries(transcript);

        assertEquals(1, entries.size());
        assertEquals(
                "downloaded\n[document: report.pdf, application/pdf,"
                        + " url: https://example.test/report.pdf]",
                entries.get(0).toolResult());
    }
}
