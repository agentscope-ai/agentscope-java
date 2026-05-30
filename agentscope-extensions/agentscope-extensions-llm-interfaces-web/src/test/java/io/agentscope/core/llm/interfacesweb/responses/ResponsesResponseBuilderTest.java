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
package io.agentscope.core.llm.interfacesweb.responses;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import io.agentscope.core.message.AudioBlock;
import io.agentscope.core.message.GenerateReason;
import io.agentscope.core.message.MessageMetadataKeys;
import io.agentscope.core.message.Msg;
import io.agentscope.core.message.MsgRole;
import io.agentscope.core.message.Source;
import io.agentscope.core.message.TextBlock;
import io.agentscope.core.message.ThinkingBlock;
import io.agentscope.core.message.ToolUseBlock;
import io.agentscope.core.model.ChatUsage;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

@DisplayName("ResponsesResponseBuilder Tests")
class ResponsesResponseBuilderTest {

    private final ResponsesResponseBuilder builder = new ResponsesResponseBuilder();

    @Test
    @DisplayName("Should build text, tool-call, usage, and completion status")
    void shouldBuildTextToolUsageAndStatus() {
        ResponsesRequest request = request("test-model");
        Msg reply =
                Msg.builder()
                        .role(MsgRole.ASSISTANT)
                        .content(
                                TextBlock.builder().text("Hello").build(),
                                ToolUseBlock.builder()
                                        .id("call_1")
                                        .name("lookup")
                                        .input(Map.of("q", "Paris"))
                                        .build())
                        .metadata(
                                Map.of(
                                        MessageMetadataKeys.CHAT_USAGE,
                                        ChatUsage.builder().inputTokens(3).outputTokens(5).build()))
                        .build();

        ResponsesResponse response = builder.buildResponse(request, reply, "resp_1");

        assertEquals("resp_1", response.getId());
        assertEquals("response", response.getObject());
        assertEquals("completed", response.getStatus());
        assertEquals("test-model", response.getModel());
        assertEquals("Hello", response.getOutputText());
        assertEquals(2, response.getOutput().size());
        assertEquals("message", response.getOutput().get(0).getType());
        assertEquals("output_text", response.getOutput().get(0).getContent().get(0).getType());
        assertEquals("function_call", response.getOutput().get(1).getType());
        assertEquals("call_1", response.getOutput().get(1).getCallId());
        assertEquals("{\"q\":\"Paris\"}", response.getOutput().get(1).getArguments());
        assertEquals(3, response.getUsage().getInputTokens());
        assertEquals(5, response.getUsage().getOutputTokens());
        assertEquals(8, response.getUsage().getTotalTokens());
    }

    @Test
    @DisplayName("Should map max iterations to incomplete status")
    void shouldMapMaxIterationsToIncompleteStatus() {
        Msg reply =
                Msg.builder()
                        .role(MsgRole.ASSISTANT)
                        .textContent("partial")
                        .generateReason(GenerateReason.MAX_ITERATIONS)
                        .build();

        ResponsesResponse response = builder.buildResponse(request("test-model"), reply, "resp_1");

        assertEquals("incomplete", response.getStatus());
    }

    @Test
    @DisplayName("Should build failed Responses payload")
    void shouldBuildFailedResponse() {
        ResponsesResponse response =
                builder.buildErrorResponse(
                        request("test-model"), new IllegalStateException("boom"), "resp_1");

        assertEquals("failed", response.getStatus());
        assertEquals("", response.getOutputText());
        assertTrue(response.getOutput().isEmpty());
        assertNotNull(response.getError());
    }

    @Test
    @DisplayName("Should build failed Responses payload with fallback message")
    void shouldBuildFailedResponseWithFallbackMessage() {
        ResponsesResponse response = builder.buildErrorResponse(null, null, "resp_1");

        assertEquals("failed", response.getStatus());
        assertEquals("", response.getOutputText());
        assertTrue(response.getOutput().isEmpty());
        assertEquals(null, response.getModel());
    }

    @Test
    @DisplayName("Should return no output items for null replies")
    void shouldReturnNoOutputItemsForNullReplies() {
        assertTrue(builder.outputItems(null, "resp_1").isEmpty());
        ResponsesResponse response = builder.buildResponse(request("test-model"), null, "resp_1");
        assertEquals("completed", response.getStatus());
        assertNull(response.getUsage());
        Msg nullContent = mock(Msg.class);
        when(nullContent.getContent()).thenReturn(null);
        assertTrue(builder.outputItems(nullContent, "resp_1").isEmpty());
    }

    @Test
    @DisplayName("Should serialize non-text blocks as fallback message output")
    void shouldSerializeNonTextBlocksAsFallbackMessageOutput() {
        Msg reply =
                Msg.builder()
                        .role(MsgRole.ASSISTANT)
                        .content(ThinkingBlock.builder().thinking("hidden reasoning").build())
                        .build();

        List<ResponsesOutputItem> items = builder.outputItems(reply, "resp_1");

        assertEquals(1, items.size());
        assertEquals("message", items.get(0).getType());
        assertTrue(items.get(0).getContent().get(0).getText().contains("hidden reasoning"));

        Msg ignored =
                Msg.builder()
                        .role(MsgRole.ASSISTANT)
                        .content(AudioBlock.builder().source(new Source()).build())
                        .build();
        assertTrue(builder.outputItems(ignored, "resp_2").isEmpty());

        Msg textAndThinking =
                Msg.builder()
                        .role(MsgRole.ASSISTANT)
                        .content(
                                TextBlock.builder().text("visible").build(),
                                ThinkingBlock.builder().thinking("hidden").build())
                        .build();
        assertEquals(1, builder.outputItems(textAndThinking, "resp_3").size());
    }

    @Test
    @DisplayName("Should prefer explicit tool-call argument content")
    void shouldPreferExplicitToolCallArgumentContent() {
        ToolUseBlock block =
                ToolUseBlock.builder()
                        .id("call_1")
                        .name("lookup")
                        .content("{\"city\":\"Paris\"}")
                        .input(Map.of("city", "London"))
                        .build();

        ResponsesOutputItem item = builder.toolUseItem(block);

        assertEquals("{\"city\":\"Paris\"}", item.getArguments());

        ToolUseBlock blankContent =
                ToolUseBlock.builder()
                        .id("call_2")
                        .name("lookup")
                        .content(" ")
                        .input(Map.of("city", "Rome"))
                        .build();
        assertEquals("{\"city\":\"Rome\"}", builder.toolUseItem(blankContent).getArguments());
    }

    private ResponsesRequest request(String model) {
        ResponsesRequest request = new ResponsesRequest();
        request.setModel(model);
        return request;
    }
}
