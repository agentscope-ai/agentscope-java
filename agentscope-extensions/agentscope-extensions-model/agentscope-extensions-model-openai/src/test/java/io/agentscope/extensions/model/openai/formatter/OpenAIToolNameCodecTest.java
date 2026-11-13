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
package io.agentscope.extensions.model.openai.formatter;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.agentscope.core.message.Msg;
import io.agentscope.core.message.MsgRole;
import io.agentscope.core.message.TextBlock;
import io.agentscope.core.message.ToolUseBlock;
import io.agentscope.core.model.ChatResponse;
import io.agentscope.core.model.ToolSchema;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** Unit tests for {@link OpenAIToolNameCodec}. */
class OpenAIToolNameCodecTest {

    @Test
    @DisplayName("Valid names are returned unchanged")
    void testEncodeValidNames() {
        assertEquals("get_weather", OpenAIToolNameCodec.encode("get_weather"));
        assertEquals("task-get_2", OpenAIToolNameCodec.encode("task-get_2"));
        assertEquals("a", OpenAIToolNameCodec.encode("a"));
        assertNull(OpenAIToolNameCodec.encode(null));
    }

    @Test
    @DisplayName("Dots and other invalid characters are replaced with underscore")
    void testEncodeInvalidNames() {
        assertEquals("task_get", OpenAIToolNameCodec.encode("task.get"));
        assertEquals("issue_comment_add", OpenAIToolNameCodec.encode("issue.comment.add"));
        assertEquals("run_node_complete", OpenAIToolNameCodec.encode("run.node.complete"));
        assertEquals("a_b_c", OpenAIToolNameCodec.encode("a b/c"));
        assertTrue(OpenAIToolNameCodec.isValid(OpenAIToolNameCodec.encode("task.get")));
    }

    @Test
    @DisplayName("Names longer than 64 chars are truncated with deterministic hash suffix")
    void testEncodeLongNames() {
        String longName = "tool." + "x".repeat(100);
        String encoded = OpenAIToolNameCodec.encode(longName);
        assertEquals(64, encoded.length());
        assertTrue(OpenAIToolNameCodec.isValid(encoded));
        assertEquals(encoded, OpenAIToolNameCodec.encode(longName));
    }

    @Test
    @DisplayName("Decode map only contains names that change during encoding")
    void testBuildDecodeMap() {
        List<ToolSchema> tools =
                List.of(
                        ToolSchema.builder().name("get_weather").description("d").build(),
                        ToolSchema.builder().name("task.get").description("d").build(),
                        ToolSchema.builder().name("issue.comment.add").description("d").build());

        Map<String, String> map = OpenAIToolNameCodec.buildDecodeMap(tools);

        assertEquals(2, map.size());
        assertEquals("task.get", map.get("task_get"));
        assertEquals("issue.comment.add", map.get("issue_comment_add"));
    }

    @Test
    @DisplayName("Decode map handles null and empty tool lists")
    void testBuildDecodeMapEmpty() {
        assertTrue(OpenAIToolNameCodec.buildDecodeMap(null).isEmpty());
        assertTrue(OpenAIToolNameCodec.buildDecodeMap(List.of()).isEmpty());
    }

    @Test
    @DisplayName("Decode map keeps first registration on encoding collision")
    void testBuildDecodeMapCollision() {
        List<ToolSchema> tools =
                List.of(
                        ToolSchema.builder().name("task.get").description("d").build(),
                        ToolSchema.builder().name("task/get").description("d").build());

        Map<String, String> map = OpenAIToolNameCodec.buildDecodeMap(tools);

        assertEquals(1, map.size());
        assertEquals("task.get", map.get("task_get"));
    }

    @Test
    @DisplayName("History encoding renames tool use blocks and preserves all other fields")
    void testEncodeToolNamesInMessages() {
        ToolUseBlock toolUse =
                ToolUseBlock.builder()
                        .id("call-1")
                        .name("task.get")
                        .input(Map.of("taskId", "t-1"))
                        .metadata(Map.of("k", "v"))
                        .build();
        Msg assistant =
                Msg.builder()
                        .role(MsgRole.ASSISTANT)
                        .content(List.of(TextBlock.builder().text("checking").build(), toolUse))
                        .build();
        Msg user = Msg.builder().role(MsgRole.USER).textContent("hi").build();

        List<Msg> result = OpenAIToolNameCodec.encodeToolNames(List.of(assistant, user));

        ToolUseBlock renamed = (ToolUseBlock) result.get(0).getContent().get(1);
        assertEquals("task_get", renamed.getName());
        assertEquals("call-1", renamed.getId());
        assertEquals(Map.of("taskId", "t-1"), renamed.getInput());
        assertEquals(Map.of("k", "v"), renamed.getMetadata());
        // text block and untouched message are preserved
        assertEquals("checking", ((TextBlock) result.get(0).getContent().get(0)).getText());
        assertSame(user, result.get(1));
        // original message is not mutated
        assertEquals("task.get", ((ToolUseBlock) assistant.getContent().get(1)).getName());
    }

    @Test
    @DisplayName("History encoding returns the same list when nothing needs encoding")
    void testEncodeToolNamesNoOp() {
        Msg msg =
                Msg.builder()
                        .role(MsgRole.ASSISTANT)
                        .content(
                                ToolUseBlock.builder()
                                        .id("call-1")
                                        .name("get_weather")
                                        .input(Map.of())
                                        .build())
                        .build();
        List<Msg> messages = List.of(msg);

        assertSame(messages, OpenAIToolNameCodec.encodeToolNames(messages));
    }

    @Test
    @DisplayName("Response decoding restores original tool names")
    void testDecodeToolNamesInResponse() {
        Map<String, String> decodeMap =
                Map.of("task_get", "task.get", "issue_comment_add", "issue.comment.add");
        ChatResponse response =
                ChatResponse.builder()
                        .id("resp-1")
                        .content(
                                List.of(
                                        TextBlock.builder().text("working").build(),
                                        ToolUseBlock.builder()
                                                .id("call-1")
                                                .name("task_get")
                                                .input(Map.of("taskId", "t-1"))
                                                .build(),
                                        ToolUseBlock.builder()
                                                .id("call-2")
                                                .name("get_weather")
                                                .input(Map.of())
                                                .build()))
                        .finishReason("tool_calls")
                        .build();

        ChatResponse decoded = OpenAIToolNameCodec.decodeToolNames(response, decodeMap);

        ToolUseBlock first = (ToolUseBlock) decoded.getContent().get(1);
        assertEquals("task.get", first.getName());
        assertEquals("call-1", first.getId());
        assertEquals(Map.of("taskId", "t-1"), first.getInput());
        // names not in the decode map pass through unchanged
        assertEquals("get_weather", ((ToolUseBlock) decoded.getContent().get(2)).getName());
        assertEquals("resp-1", decoded.getId());
        assertEquals("tool_calls", decoded.getFinishReason());
    }

    @Test
    @DisplayName("Response decoding passes through on null/empty decode map")
    void testDecodeToolNamesNoOp() {
        ChatResponse response =
                ChatResponse.builder()
                        .id("resp-1")
                        .content(
                                List.of(
                                        ToolUseBlock.builder()
                                                .id("call-1")
                                                .name("task_get")
                                                .input(Map.of())
                                                .build()))
                        .build();

        assertSame(response, OpenAIToolNameCodec.decodeToolNames(response, Map.of()));
        assertSame(response, OpenAIToolNameCodec.decodeToolNames(response, null));
        assertNull(OpenAIToolNameCodec.decodeToolNames(null, Map.of("task_get", "task.get")));
    }
}
