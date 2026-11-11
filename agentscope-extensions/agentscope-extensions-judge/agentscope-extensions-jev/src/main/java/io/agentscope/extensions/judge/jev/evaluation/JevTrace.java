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

package io.agentscope.extensions.judge.jev.evaluation;

import io.agentscope.core.message.Msg;
import io.agentscope.core.message.MsgRole;
import io.agentscope.core.message.TextBlock;
import io.agentscope.core.message.ThinkingBlock;
import io.agentscope.core.message.ToolResultBlock;
import io.agentscope.core.message.ToolUseBlock;
import io.agentscope.core.model.ToolSchema;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/** A detached, invocation-scoped trace. Message metadata and thinking are not evaluation evidence. */
public record JevTrace(String id, Map<String, Object> fields, List<String> issues) {
    public JevTrace {
        if (id == null || id.isBlank()) throw new IllegalArgumentException("trace id required");
        fields = TraceData.snapshot(fields);
        issues = List.copyOf(issues);
    }

    /** Custom samples use the same state as online traces; absence is different from an empty list. */
    public JevTrace(String id, Map<String, Object> fields) {
        this(id, fields, List.of());
    }

    public boolean has(String key) {
        Object v = fields.get(key);
        return v != null && (!(v instanceof String s) || !s.isBlank());
    }

    public String text(String key) {
        return fields.get(key) instanceof String s ? s : "";
    }

    public List<?> list(String key) {
        return fields.get(key) instanceof List<?> l ? l : List.of();
    }

    public JevTrace with(String key, Object value) {
        Map<String, Object> copy = new LinkedHashMap<>(fields);
        copy.put(key, value);
        return new JevTrace(id, copy, issues);
    }

    /** Tool results must follow a uniquely identified call. Partial traces carry explicit issues. */
    public static JevTrace fromMessages(String id, List<Msg> messages, List<ToolSchema> tools) {
        List<Map<String, Object>> turns = new ArrayList<>();
        Map<String, Map<String, Object>> calls = new LinkedHashMap<>();
        List<String> issues = new ArrayList<>();
        String request = "", answer = "";
        for (Msg message : List.copyOf(messages)) {
            Objects.requireNonNull(message);
            String text = message.getTextContent();
            turns.add(Map.of("role", message.getRole().name(), "text", text));
            if (message.getRole() == MsgRole.USER && !text.isBlank()) {
                request = text;
                answer = "";
            }
            boolean hasCalls = !message.getContentBlocks(ToolUseBlock.class).isEmpty();
            if (message.getRole() == MsgRole.ASSISTANT) answer = hasCalls ? "" : text;
            for (var block : message.getContent()) {
                if (block instanceof ToolUseBlock use) {
                    if (use.getId() == null
                            || use.getId().isBlank()
                            || calls.containsKey(use.getId())) {
                        issues.add("AMBIGUOUS_CALL_ID");
                        continue;
                    }
                    Map<String, Object> call = new LinkedHashMap<>();
                    call.put("id", use.getId());
                    call.put("name", use.getName());
                    call.put("arguments", use.getInput());
                    call.put("message_index", turns.size() - 1);
                    calls.put(use.getId(), call);
                } else if (block instanceof ToolResultBlock result) {
                    var call = calls.get(result.getId());
                    if (call == null
                            || call.containsKey("result")
                            || !Objects.equals(call.get("name"), result.getName())) {
                        issues.add("UNPAIRED_TOOL_RESULT");
                        continue;
                    }
                    List<String> texts = new ArrayList<>();
                    for (var part : result.getOutput()) {
                        if (part instanceof TextBlock t) texts.add(t.getText());
                        else issues.add("UNSUPPORTED_TOOL_CONTENT");
                    }
                    call.put("result", String.join("\n", texts));
                    call.put(
                            "result_state",
                            result.getState() == null ? "UNKNOWN" : result.getState().name());
                    call.put("result_message_index", turns.size() - 1);
                } else if (!(block instanceof TextBlock) && !(block instanceof ThinkingBlock)) {
                    issues.add("UNSUPPORTED_MESSAGE_CONTENT");
                }
            }
        }
        if (calls.values().stream().anyMatch(c -> !c.containsKey("result")))
            issues.add("PENDING_TOOL_CALL");
        Map<String, Object> fields = new LinkedHashMap<>();
        fields.put("request", request);
        fields.put("final_answer", answer);
        fields.put("messages", turns);
        fields.put("tool_calls", new ArrayList<>(calls.values()));
        fields.put(
                "tool_results",
                calls.values().stream().filter(c -> c.containsKey("result")).toList());
        if (tools != null)
            fields.put(
                    "available_tools",
                    tools.stream()
                            .map(
                                    t ->
                                            Map.of(
                                                    "name",
                                                    t.getName(),
                                                    "description",
                                                    t.getDescription(),
                                                    "parameters",
                                                    t.getParameters()))
                            .toList());
        return new JevTrace(id, fields, issues);
    }
}
