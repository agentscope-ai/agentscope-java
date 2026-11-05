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
package io.agentscope.examples.chat;

import io.agentscope.core.agent.accumulator.TextAccumulator;
import io.agentscope.core.agent.accumulator.ThinkingAccumulator;
import io.agentscope.core.agent.accumulator.ToolCallsAccumulator;
import io.agentscope.core.message.ContentBlock;
import io.agentscope.core.message.Msg;
import io.agentscope.core.message.TextBlock;
import io.agentscope.core.message.ThinkingBlock;
import io.agentscope.core.message.ToolResultBlock;
import io.agentscope.core.message.ToolUseBlock;
import io.agentscope.core.session.SessionEvent;
import io.agentscope.core.state.AgentState;
import io.agentscope.core.util.JsonUtils;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/** Rebuilds both finished and unfinished UI items from a committed event prefix. */
final class ChatItems {
    record Item(
            String id,
            String kind,
            String role,
            String turnId,
            String runId,
            long seq,
            String status,
            List<ContentBlock> content,
            List<ContentBlock> progress) {}

    private static class Entry {
        final String id;
        final String kind;
        final String role;
        final String turn;
        final String run;
        final long seq;
        String status;
        List<ContentBlock> content = List.of();
        final List<ContentBlock> progress = new ArrayList<>();
        final StringBuilder arguments = new StringBuilder();
        ToolUseBlock call;
        ToolResultBlock result;

        Entry(String id, String kind, String role, SessionEvent event, String status) {
            this.id = id;
            this.kind = kind;
            this.role = role;
            turn = event.turnId();
            run = event.executionRunId();
            seq = event.seq();
            this.status = status;
        }

        Item freeze() {
            var blocks = new ArrayList<>(content);
            if (call != null) blocks.add(call);
            if (result != null) blocks.add(result);
            return new Item(
                    id,
                    kind,
                    role,
                    turn,
                    run,
                    seq,
                    status,
                    List.copyOf(blocks),
                    List.copyOf(progress));
        }
    }

    private static final class ModelDraft {
        final Entry item;
        final TextAccumulator text = new TextAccumulator();
        final ThinkingAccumulator thinking = new ThinkingAccumulator();
        final ToolCallsAccumulator tools = new ToolCallsAccumulator();
        boolean committed;

        ModelDraft(Entry item) {
            this.item = item;
        }

        void refresh() {
            var content = new ArrayList<ContentBlock>();
            if (thinking.hasContent()) content.add(thinking.buildAggregated());
            if (text.hasContent()) content.add(text.buildAggregated());
            item.content = content;
        }
    }

    private final Map<String, Entry> items = new LinkedHashMap<>();
    private final Map<String, ModelDraft> models = new LinkedHashMap<>();
    private final Map<String, Entry> messages = new LinkedHashMap<>();
    private final Set<String> auxiliaryCalls = new HashSet<>();

    static List<Item> read(List<SessionEvent> facts) {
        var view = new ChatItems();
        facts.forEach(view::apply);
        return view.items.values().stream()
                .filter(item -> !item.content.isEmpty() || item.call != null || item.result != null)
                .map(Entry::freeze)
                .toList();
    }

    private void apply(SessionEvent event) {
        var data = event.data();
        String modelId = (String) data.get("modelCallId");
        switch (event.type()) {
            case "request/prepared" -> {
                if (!"REASONING".equals(data.get("purpose"))) auxiliaryCalls.add(modelId);
            }
            case "model/chunk" -> {
                if (auxiliaryCalls.contains(modelId)) return;
                var chunk = (Map<?, ?>) data.get("chunk");
                var draft =
                        models.computeIfAbsent(
                                modelId,
                                id -> {
                                    var entry =
                                            new Entry(
                                                    "model:" + id,
                                                    "message",
                                                    "assistant",
                                                    event,
                                                    "generating");
                                    items.put(entry.id, entry);
                                    return new ModelDraft(entry);
                                });
                if (draft.committed) return;
                if (chunk.get("id") instanceof String id) messages.put(id, draft.item);
                for (var value : (List<?>) chunk.get("content")) {
                    var block = decode(value, ContentBlock.class);
                    if (block instanceof TextBlock text) draft.text.add(text);
                    else if (block instanceof ThinkingBlock thinking) draft.thinking.add(thinking);
                    else if (block instanceof ToolUseBlock use) {
                        draft.tools.add(use);
                        // The same accumulators as the runtime handle interleaved tool arguments.
                        String fragmentId =
                                use.getId() == null || use.getId().isBlank()
                                        ? draft.tools.getCurrentToolCallId()
                                        : use.getId();
                        for (var call : draft.tools.buildAllToolCalls()) {
                            var entry = tool(event, call.getId());
                            if (Objects.equals(call.getId(), fragmentId)
                                    && use.getContent() != null)
                                entry.arguments.append(use.getContent());
                            entry.call =
                                    new ToolUseBlock(
                                            call.getId(),
                                            call.getName(),
                                            call.getInput(),
                                            entry.arguments.toString(),
                                            call.getMetadata(),
                                            call.getState());
                            entry.status = "generating_arguments";
                        }
                    }
                }
                draft.refresh();
            }
            case "model/end" -> {
                var draft = models.get(modelId);
                if (draft != null && !draft.committed)
                    draft.item.status =
                            "completed".equals(data.get("status"))
                                    ? "finishing"
                                    : String.valueOf(data.get("status"));
            }
            case "migration/baseline" -> {
                if (data.get("stateJson") instanceof String json)
                    AgentState.fromJsonString(json)
                            .getContext()
                            .forEach(message -> message(event, message));
            }
            case "message/user", "message/assistant", "tool/result", "turn/output" -> {
                if (data.get("message") != null)
                    message(event, decode(data.get("message"), Msg.class));
            }
            case "tool/requested" -> {
                var call = decode(data.get("call"), ToolUseBlock.class);
                var entry = tool(event, call.getId());
                entry.call = call;
                if (entry.status.equals("generating_arguments")) entry.status = "requested";
            }
            case "tool/dispatch" -> {
                var entry = tool(event, (String) data.get("toolCallId"));
                entry.status = "running";
                if (entry.call == null)
                    entry.call =
                            ToolUseBlock.builder()
                                    .id((String) data.get("toolCallId"))
                                    .name((String) data.get("name"))
                                    .input(decode(data.get("arguments"), Map.class))
                                    .build();
            }
            case "tool/chunk" -> {
                var entry = tool(event, (String) data.get("toolCallId"));
                entry.status = "running";
                entry.progress.addAll(decode(data.get("chunk"), ToolResultBlock.class).getOutput());
            }
            case "action/end" -> {
                var entry = tool(event, (String) data.get("toolCallId"));
                if (data.get("result") != null)
                    result(entry, decode(data.get("result"), ToolResultBlock.class));
                else if (data.get("observation") instanceof Map<?, ?> observation)
                    entry.status = String.valueOf(observation.get("status")).toLowerCase();
            }
            case "interaction/requested" -> {
                if (data.get("call") != null) {
                    var call = decode(data.get("call"), ToolUseBlock.class);
                    var entry = tool(event, call.getId());
                    entry.call = call;
                    entry.status = "suspended";
                }
            }
            case "run/end" -> {
                for (var entry : items.values())
                    if (Objects.equals(event.executionRunId(), entry.run)
                            && Set.of(
                                            "generating",
                                            "finishing",
                                            "running",
                                            "requested",
                                            "generating_arguments")
                                    .contains(entry.status))
                        entry.status =
                                "completed".equals(data.get("status"))
                                        ? "completed"
                                        : "interrupted";
            }
            default -> {}
        }
    }

    private void message(SessionEvent event, Msg message) {
        if (message.getRole().name().equals("SYSTEM")) return;
        var content = new ArrayList<ContentBlock>();
        for (var block : message.getContent()) {
            if (block instanceof ToolUseBlock call) tool(event, call.getId()).call = call;
            else if (block instanceof ToolResultBlock result)
                result(tool(event, result.getId()), result);
            else content.add(block);
        }
        var entry = messages.get(message.getId());
        if (entry == null && !content.isEmpty()) {
            entry =
                    new Entry(
                            "message:" + message.getId(),
                            "message",
                            message.getRole().name().toLowerCase(),
                            event,
                            "completed");
            items.put(entry.id, entry);
            messages.put(message.getId(), entry);
        }
        if (entry != null) {
            entry.content = content;
            entry.status = "completed";
            for (var draft : models.values()) if (draft.item == entry) draft.committed = true;
        }
    }

    private Entry tool(SessionEvent event, String callId) {
        String key = "tool:" + event.turnId() + ":" + callId;
        return items.computeIfAbsent(key, id -> new Entry(id, "tool", "tool", event, "requested"));
    }

    private void result(Entry entry, ToolResultBlock result) {
        entry.result = result;
        entry.status = result.isSuspended() ? "suspended" : result.getState().name().toLowerCase();
    }

    private static <T> T decode(Object value, Class<T> type) {
        return JsonUtils.getJsonCodec().convertValue(value, type);
    }
}
