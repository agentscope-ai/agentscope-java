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
package io.agentscope.claw2.web.session;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.ArrayList;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Parses the JSONL session transcript produced by HarnessAgent into structured turn entries.
 *
 * <p>Each line of the JSONL file is a JSON object with at minimum the fields:
 *
 * <pre>{@code
 * {"type":"message","id":"…","timestamp":…,"role":"USER|ASSISTANT|TOOL","content":"…"}
 * }</pre>
 *
 * <p>Tool-use lines additionally carry {@code toolName}, {@code toolInput}, and (for results)
 * {@code toolResult} fields.
 */
public final class SessionTurnParser {

    private static final Logger log = LoggerFactory.getLogger(SessionTurnParser.class);
    private static final ObjectMapper MAPPER = new ObjectMapper();

    private SessionTurnParser() {}

    /**
     * Parses a JSONL string into a list of {@link TurnEntry} objects.
     *
     * @param jsonl the full JSONL content of a session transcript (may be blank)
     * @return ordered list of turns; never null
     */
    public static List<TurnEntry> parse(String jsonl) {
        List<TurnEntry> turns = new ArrayList<>();
        if (jsonl == null || jsonl.isBlank()) return turns;

        for (String line : jsonl.split("\n")) {
            line = line.strip();
            if (line.isEmpty()) continue;
            try {
                JsonNode node = MAPPER.readTree(line);
                String type = text(node, "type");
                if (!"message".equals(type)) continue;

                String id = text(node, "id");
                String parentId = text(node, "parentId");
                String role = text(node, "role");
                String content = text(node, "content");
                double ts = node.path("timestamp").asDouble(0);
                long timestampMs = (long) (ts * 1000);

                String toolName = text(node, "toolName");
                String toolInput = node.has("toolInput") ? node.get("toolInput").toString() : null;
                String toolResult =
                        node.has("toolResult") ? node.get("toolResult").toString() : null;

                turns.add(
                        new TurnEntry(
                                id,
                                parentId,
                                role,
                                content,
                                timestampMs,
                                toolName,
                                toolInput,
                                toolResult));
            } catch (Exception e) {
                log.debug("Skipping unparseable session line: {}", e.getMessage());
            }
        }
        return turns;
    }

    private static String text(JsonNode node, String field) {
        JsonNode v = node.path(field);
        return v.isMissingNode() || v.isNull() ? null : v.asText();
    }

    /**
     * A single parsed entry from a session JSONL transcript.
     *
     * @param id entry id
     * @param parentId parent entry id (for threading)
     * @param role {@code USER}, {@code ASSISTANT}, or {@code TOOL}
     * @param content text content of the message (null for pure tool-call entries)
     * @param timestampMs epoch milliseconds
     * @param toolName name of the tool invoked (null for non-tool entries)
     * @param toolInput JSON string of the tool invocation arguments (null if not applicable)
     * @param toolResult JSON string of the tool result (null if not applicable)
     */
    @JsonIgnoreProperties(ignoreUnknown = true)
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record TurnEntry(
            String id,
            String parentId,
            String role,
            String content,
            long timestampMs,
            String toolName,
            String toolInput,
            String toolResult) {}
}
