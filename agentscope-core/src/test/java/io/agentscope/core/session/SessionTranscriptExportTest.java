package io.agentscope.core.session;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.agentscope.core.message.AssistantMessage;
import io.agentscope.core.message.DataBlock;
import io.agentscope.core.message.TextBlock;
import io.agentscope.core.message.ToolResultBlock;
import io.agentscope.core.message.ToolResultMessage;
import io.agentscope.core.message.ToolUseBlock;
import io.agentscope.core.message.URLSource;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class SessionTranscriptExportTest {
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
                new SessionViews.Transcript(
                        9,
                        List.of(
                                AssistantMessage.builder().id("request").content(request).build(),
                                ToolResultMessage.builder().id("result").result(result).build()));
        var entries = SessionTranscriptExport.entries(transcript);
        assertEquals(2, entries.size());
        assertEquals("TOOL", entries.get(0).role());
        assertEquals("plot.png", entries.get(0).toolInput().get("path"));
        assertEquals("TOOL", entries.get(1).role());
        assertTrue(entries.get(1).toolResult().contains("found plot"));
        assertTrue(entries.get(1).toolResult().contains("https://example.test/plot.png"));
        assertTrue(SessionTranscriptExport.jsonl(transcript).contains("toolInput"));
    }
}
