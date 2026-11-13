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

import io.agentscope.core.formatter.DocumentReferenceRenderer;
import io.agentscope.core.message.ContentBlock;
import io.agentscope.core.message.DocumentBlock;
import io.agentscope.core.message.Msg;
import io.agentscope.core.message.TextBlock;
import io.agentscope.core.message.ToolResultBlock;
import io.agentscope.core.message.ToolUseBlock;
import io.agentscope.core.util.JsonUtils;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/** Read-time export for clients that still consume flat transcript entries. Never writes a log. */
public final class ThreadTranscriptExport {
    private static final DateTimeFormatter TIMESTAMP =
            DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss.SSS");

    private ThreadTranscriptExport() {}

    public record Entry(
            String id,
            String role,
            String content,
            long timestampMs,
            String toolName,
            Map<String, Object> toolInput,
            String toolResult) {}

    public static List<Entry> entries(ThreadHistory.Transcript transcript) {
        List<Entry> entries = new ArrayList<>();
        for (Msg message : transcript.messages()) {
            long timestamp = timestampMillis(message.getTimestamp());
            // Tool results are exported as their own entries below.
            String text = DocumentReferenceRenderer.renderMessageText(message, false);
            boolean hasTools =
                    message.getContent().stream()
                            .anyMatch(
                                    block ->
                                            block instanceof ToolUseBlock
                                                    || block instanceof ToolResultBlock);
            if (!text.isBlank() || !hasTools) {
                entries.add(
                        new Entry(
                                message.getId(),
                                message.getRole().name(),
                                text,
                                timestamp,
                                null,
                                null,
                                null));
            }
            int index = 0;
            for (var block : message.getContent()) {
                String id = message.getId() + ":" + index++;
                if (block instanceof ToolUseBlock use) {
                    entries.add(
                            new Entry(
                                    id,
                                    "TOOL",
                                    "",
                                    timestamp,
                                    use.getName(),
                                    use.getInput(),
                                    null));
                } else if (block instanceof ToolResultBlock result) {
                    String output = toolResultText(result);
                    entries.add(
                            new Entry(id, "TOOL", "", timestamp, result.getName(), null, output));
                }
            }
        }
        return List.copyOf(entries);
    }

    /**
     * Renders a tool result for a flat transcript.
     *
     * <p>Text and document blocks become readable text. Anything else (images, audio) keeps the
     * existing JSON representation, because the flat entry has no place for a block - but a
     * document among them still collapses to its reference, so a mixed result never embeds the
     * document's base64 payload into the transcript.
     */
    private static String toolResultText(ToolResultBlock result) {
        List<ContentBlock> outputs = result.getOutput();
        boolean textual =
                outputs.stream()
                        .allMatch(
                                block ->
                                        block instanceof TextBlock
                                                || block instanceof DocumentBlock);
        if (!textual) {
            boolean hasDocument =
                    outputs.stream().anyMatch(block -> block instanceof DocumentBlock);
            if (!hasDocument) {
                return JsonUtils.getJsonCodec().toJson(outputs);
            }
            return JsonUtils.getJsonCodec()
                    .toJson(outputs.stream().map(ThreadTranscriptExport::referenceOrSelf).toList());
        }
        // Joining with "\n" mirrors Msg.getTextContent(): every block occupies one line, so a
        // blank text keeps its line and leading, trailing, and consecutive blanks all survive.
        return outputs.stream()
                .map(
                        block ->
                                block instanceof TextBlock text
                                        ? text.getText()
                                        : DocumentReferenceRenderer.render((DocumentBlock) block))
                .collect(Collectors.joining("\n"));
    }

    /**
     * Replaces a document block with its one-line reference, leaving every other block alone.
     *
     * @param block the block to render
     * @return a text block carrying the reference, or the block itself
     */
    private static ContentBlock referenceOrSelf(ContentBlock block) {
        if (block instanceof DocumentBlock document) {
            return TextBlock.builder().text(DocumentReferenceRenderer.render(document)).build();
        }
        return block;
    }

    public static String jsonl(ThreadHistory.Transcript transcript) {
        StringBuilder output = new StringBuilder();
        for (Entry entry : entries(transcript)) {
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("type", "message");
            row.put("id", entry.id());
            row.put("role", entry.role());
            row.put("content", entry.content());
            row.put("timestamp", entry.timestampMs() / 1000.0);
            if (entry.toolName() != null) row.put("toolName", entry.toolName());
            if (entry.toolInput() != null) row.put("toolInput", entry.toolInput());
            if (entry.toolResult() != null) row.put("toolResult", entry.toolResult());
            output.append(JsonUtils.getJsonCodec().toJson(row)).append('\n');
        }
        return output.toString();
    }

    private static long timestampMillis(String timestamp) {
        if (timestamp == null) return 0;
        try {
            return LocalDateTime.parse(timestamp, TIMESTAMP)
                    .atZone(ZoneId.systemDefault())
                    .toInstant()
                    .toEpochMilli();
        } catch (DateTimeParseException ignored) {
            return 0;
        }
    }
}
