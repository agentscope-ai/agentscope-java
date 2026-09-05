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
package io.agentscope.core.responses.builder;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;

import io.agentscope.core.message.MessageMetadataKeys;
import io.agentscope.core.message.Msg;
import io.agentscope.core.message.MsgRole;
import io.agentscope.core.message.TextBlock;
import io.agentscope.core.message.ToolUseBlock;
import io.agentscope.core.model.ChatUsage;
import io.agentscope.core.responses.converter.ResponsesValidationException;
import io.agentscope.core.responses.model.ResponsesOutputItem;
import io.agentscope.core.responses.model.ResponsesRequest;
import io.agentscope.core.responses.model.ResponsesResponse;
import java.util.LinkedHashMap;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class ResponsesResponseBuilderTest {

    private ResponsesResponseBuilder builder;

    @BeforeEach
    void setUp() {
        builder = new ResponsesResponseBuilder();
    }

    @Test
    void shouldBuildTextAndToolCallResponse() {
        ResponsesRequest request = new ResponsesRequest();
        request.setModel("gpt-4.1-mini");
        request.setInstructions("Use tools when useful.");

        Msg reply =
                Msg.builder()
                        .id("assistant-1")
                        .role(MsgRole.ASSISTANT)
                        .content(
                                TextBlock.builder().text("Let me check.").build(),
                                ToolUseBlock.builder()
                                        .id("call_123")
                                        .name("get_weather")
                                        .input(Map.of("city", "Hangzhou"))
                                        .build())
                        .metadata(
                                Map.of(
                                        MessageMetadataKeys.CHAT_USAGE,
                                        ChatUsage.builder()
                                                .inputTokens(10)
                                                .outputTokens(5)
                                                .time(0.3)
                                                .build()))
                        .build();

        ResponsesResponse response = builder.buildResponse(request, reply, "resp_123");

        assertEquals("resp_123", response.getId());
        assertEquals("completed", response.getStatus());
        assertEquals("gpt-4.1-mini", response.getModel());
        assertEquals("Use tools when useful.", response.getInstructions());
        assertEquals("auto", response.getToolChoice());
        assertEquals("Let me check.", response.getOutputText());
        assertEquals(15, response.getUsage().getTotalTokens());
        assertEquals(2, response.getOutput().size());

        ResponsesOutputItem message = response.getOutput().get(0);
        assertEquals("message", message.getType());
        assertEquals("assistant", message.getRole());
        assertEquals("Let me check.", message.getContent().get(0).getText());

        ResponsesOutputItem functionCall = response.getOutput().get(1);
        assertEquals("function_call", functionCall.getType());
        assertEquals("call_123", functionCall.getCallId());
        assertEquals("get_weather", functionCall.getName());
        assertEquals("{\"city\":\"Hangzhou\"}", functionCall.getArguments());
    }

    @Test
    void shouldBuildStructuredResponseFromMessageMetadata() {
        ResponsesRequest request = new ResponsesRequest();
        Map<String, Object> structuredOutput = new LinkedHashMap<>();
        structuredOutput.put("answer", "42");
        structuredOutput.put("ok", true);
        Msg reply =
                Msg.builder()
                        .role(MsgRole.ASSISTANT)
                        .metadata(Map.of(MessageMetadataKeys.STRUCTURED_OUTPUT, structuredOutput))
                        .build();

        ResponsesResponse response = builder.buildStructuredResponse(request, reply, "resp_json");

        assertEquals("completed", response.getStatus());
        assertEquals("{\"answer\":\"42\",\"ok\":true}", response.getOutputText());
        assertEquals("output_text", response.getOutput().get(0).getContent().get(0).getType());
    }

    @Test
    void shouldBuildFailedStructuredResponseWhenMetadataIsMissing() {
        ResponsesResponse response =
                builder.buildStructuredResponse(
                        new ResponsesRequest(),
                        Msg.builder().role(MsgRole.ASSISTANT).build(),
                        "resp_bad");

        assertEquals("failed", response.getStatus());
        assertNotNull(response.getError());
        assertEquals("invalid_response", response.getError().getCode());
    }

    @Test
    void shouldBuildErrorResponseFromValidationException() {
        ResponsesValidationException error =
                ResponsesValidationException.unsupported("Unsupported", "input[0].type");

        assertEquals(
                "unsupported_parameter", builder.buildErrorResponse(error).getError().getCode());
        assertEquals("input[0].type", builder.buildErrorResponse(error).getError().getParam());
        assertNull(
                builder.buildFailedResponse(new ResponsesRequest(), (Throwable) null, "resp")
                        .getUsage());
    }
}
