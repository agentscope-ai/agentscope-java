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

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.agentscope.core.message.AssistantMessage;
import io.agentscope.core.message.ContentBlock;
import io.agentscope.core.message.TextBlock;
import io.agentscope.core.message.ToolResultBlock;
import io.agentscope.core.message.ToolResultMessage;
import io.agentscope.core.message.ToolResultState;
import io.agentscope.core.message.ToolUseBlock;
import io.agentscope.core.model.ChatResponse;
import io.agentscope.core.session.SessionEvent;
import io.agentscope.core.util.JsonUtils;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class ChatItemsTest {
    private final List<SessionEvent> facts = new ArrayList<>();

    @Test
    void unfinishedTextSurvivesReplayAndFinalMessageReplacesItsDraft() {
        chunk("model-1", "message-1", text("before refresh "));
        var before = ChatItems.read(facts).get(0);
        assertEquals("before refresh ", ((TextBlock) before.content().get(0)).getText());
        assertEquals("generating", before.status());
        chunk("model-1", "message-1", text("after refresh"));
        add("model/end", Map.of("modelCallId", "model-1", "status", "completed"));
        var message =
                AssistantMessage.builder()
                        .id("message-1")
                        .textContent("before refresh after refresh")
                        .build();
        add("message/assistant", Map.of("message", message));
        add("turn/output", Map.of("message", message));
        chunk("model-2", "message-2", text("another message"));
        var items = ChatItems.read(facts);
        assertEquals(2, items.size());
        assertEquals(before.id(), items.get(0).id());
        assertEquals("completed", items.get(0).status());
        assertEquals(
                "before refresh after refresh",
                ((TextBlock) items.get(0).content().get(0)).getText());
        assertEquals("generating", items.get(1).status());
        assertEquals(
                JsonUtils.getJsonCodec().toJson(items),
                JsonUtils.getJsonCodec().toJson(ChatItems.read(List.copyOf(facts))));
    }

    @Test
    void interleavedArgumentsProgressAndResultsSurviveReplayWithoutDuplicates() {
        chunk("model-1", "message-1", fragment("call-a", "{\"topic\":\""));
        chunk("model-1", "message-1", fragment("call-b", "{\"topic\":\"B\"}"));
        var partial = ChatItems.read(facts);
        assertEquals(
                "{\"topic\":\"", ((ToolUseBlock) partial.get(0).content().get(0)).getContent());
        chunk("model-1", "message-1", fragment("call-a", "A\"}"));
        ToolUseBlock callA =
                ToolUseBlock.builder()
                        .id("call-a")
                        .name("lookup")
                        .input(Map.of("topic", "A"))
                        .build();
        ToolUseBlock callB =
                ToolUseBlock.builder()
                        .id("call-b")
                        .name("lookup")
                        .input(Map.of("topic", "B"))
                        .build();
        add(
                "message/assistant",
                Map.of(
                        "message",
                        AssistantMessage.builder()
                                .id("message-1")
                                .content(List.of(callA, callB))
                                .build()));
        add("tool/dispatch", Map.of("toolCallId", "call-a"));
        add("tool/dispatch", Map.of("toolCallId", "call-b"));
        add(
                "tool/chunk",
                Map.of("toolCallId", "call-a", "chunk", ToolResultBlock.text("first progress")));
        var result =
                ToolResultBlock.of("call-b", "lookup", text("B done"))
                        .withState(ToolResultState.SUCCESS);
        add("action/end", Map.of("toolCallId", "call-b", "result", result));
        var during = ChatItems.read(facts);
        assertEquals("running", during.get(0).status());
        assertEquals("first progress", ((TextBlock) during.get(0).progress().get(0)).getText());
        assertEquals("success", during.get(1).status());
        add("tool/result", Map.of("message", ToolResultMessage.builder().result(result).build()));
        chunk("model-2", "message-2", text("next reasoning"));
        var after = ChatItems.read(facts);
        assertEquals(3, after.size());
        assertEquals(during.get(0).id(), after.get(0).id());
        assertEquals("A", ((ToolUseBlock) after.get(0).content().get(0)).getInput().get("topic"));
        assertEquals(
                1,
                after.get(1).content().stream().filter(ToolResultBlock.class::isInstance).count());
        assertEquals("generating", after.get(2).status());
        assertTrue(after.get(0).seq() < after.get(1).seq());
    }

    private void chunk(String model, String message, ContentBlock block) {
        add(
                "model/chunk",
                Map.of(
                        "modelCallId",
                        model,
                        "chunk",
                        ChatResponse.builder().id(message).content(List.of(block)).build()));
    }

    private ToolUseBlock fragment(String id, String json) {
        return ToolUseBlock.builder().id(id).name("lookup").content(json).build();
    }

    private TextBlock text(String text) {
        return TextBlock.builder().text(text).build();
    }

    private void add(String type, Map<String, Object> data) {
        // Native records preserve the discriminator for blocks inside Map payloads.
        var payload = new LinkedHashMap<String, Object>(data);
        payload.replaceAll(
                (key, value) ->
                        value instanceof ContentBlock
                                ? JsonUtils.getJsonCodec()
                                        .fromJson(JsonUtils.getJsonCodec().toJson(value), Map.class)
                                : value);
        long seq = facts.size() + 1;
        facts.add(
                new SessionEvent(
                        1,
                        "event-" + seq,
                        seq,
                        0,
                        type,
                        "run",
                        "turn",
                        true,
                        JsonUtils.getJsonCodec().toJson(payload)));
    }
}
