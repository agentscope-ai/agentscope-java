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
package io.agentscope.core.llm.interfacesweb.anthropic;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.agentscope.core.message.GenerateReason;
import io.agentscope.core.message.MessageMetadataKeys;
import io.agentscope.core.message.Msg;
import io.agentscope.core.message.MsgRole;
import io.agentscope.core.message.ToolUseBlock;
import io.agentscope.core.model.ChatUsage;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

@DisplayName("AnthropicResponseBuilder Tests")
class AnthropicResponseBuilderTest {

    private final AnthropicResponseBuilder builder = new AnthropicResponseBuilder();

    @Test
    @DisplayName("Should build Anthropic text response with usage")
    void shouldBuildTextResponseWithUsage() {
        Msg reply =
                Msg.builder()
                        .role(MsgRole.ASSISTANT)
                        .textContent("Hello")
                        .metadata(
                                Map.of(
                                        MessageMetadataKeys.CHAT_USAGE,
                                        ChatUsage.builder().inputTokens(2).outputTokens(4).build()))
                        .build();

        AnthropicMessagesResponse response =
                builder.buildResponse(request("claude-test"), reply, "msg_1");

        assertEquals("msg_1", response.getId());
        assertEquals("message", response.getType());
        assertEquals("assistant", response.getRole());
        assertEquals("claude-test", response.getModel());
        assertEquals("text", response.getContent().get(0).get("type"));
        assertEquals("Hello", response.getContent().get(0).get("text"));
        assertEquals("end_turn", response.getStopReason());
        assertEquals(2, response.getUsage().getInputTokens());
        assertEquals(4, response.getUsage().getOutputTokens());
    }

    @Test
    @DisplayName("Should map stop reasons for tool use and max tokens")
    void shouldMapStopReasons() {
        Msg toolReply =
                Msg.builder()
                        .role(MsgRole.ASSISTANT)
                        .content(
                                ToolUseBlock.builder()
                                        .id("toolu_1")
                                        .name("lookup")
                                        .input(Map.of())
                                        .build())
                        .build();
        Msg maxIters =
                Msg.builder()
                        .role(MsgRole.ASSISTANT)
                        .textContent("partial")
                        .generateReason(GenerateReason.MAX_ITERATIONS)
                        .build();
        Msg suspended =
                Msg.builder()
                        .role(MsgRole.ASSISTANT)
                        .textContent("suspended")
                        .generateReason(GenerateReason.TOOL_SUSPENDED)
                        .build();

        assertEquals("tool_use", builder.stopReason(toolReply));
        assertEquals("tool_use", builder.stopReason(suspended));
        assertEquals("max_tokens", builder.stopReason(maxIters));
        assertEquals("end_turn", builder.stopReason(null));
    }

    @Test
    @DisplayName("Should build Anthropic error shape")
    void shouldBuildErrorShape() {
        Map<String, Object> error = builder.buildError(new IllegalStateException("boom"));
        Map<String, Object> fallback = builder.buildError(null);

        assertEquals("error", error.get("type"));
        assertNotNull(error.get("error"));
        assertEquals("error", fallback.get("type"));
        assertNotNull(fallback.get("error"));
    }

    @Test
    @DisplayName("Should build empty Anthropic base response with null request")
    void shouldBuildBaseResponseWithNullRequest() {
        AnthropicMessagesResponse response = builder.baseResponse(null, "msg_1");

        assertEquals("msg_1", response.getId());
        assertEquals(null, response.getModel());
        assertEquals(0, response.getUsage().getInputTokens());
    }

    @Test
    @DisplayName("Should build Anthropic responses for null and no-usage replies")
    void shouldBuildResponsesForNullAndNoUsageReplies() {
        AnthropicMessagesResponse nullReply =
                builder.buildResponse(request("claude-test"), null, "msg_null");
        Msg noUsage = Msg.builder().role(MsgRole.ASSISTANT).textContent("Hello").build();
        AnthropicMessagesResponse noUsageReply =
                builder.buildResponse(request("claude-test"), noUsage, "msg_no_usage");

        assertEquals("end_turn", nullReply.getStopReason());
        assertTrue(nullReply.getContent().isEmpty());
        assertNull(nullReply.getUsage());
        assertEquals("end_turn", noUsageReply.getStopReason());
        assertNull(noUsageReply.getUsage());
    }

    private AnthropicMessagesRequest request(String model) {
        AnthropicMessagesRequest request = new AnthropicMessagesRequest();
        request.setModel(model);
        return request;
    }
}
