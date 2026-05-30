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
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.agentscope.core.llm.interfacesweb.common.ProtocolException;
import io.agentscope.core.message.ImageBlock;
import io.agentscope.core.message.Msg;
import io.agentscope.core.message.MsgRole;
import io.agentscope.core.message.TextBlock;
import io.agentscope.core.message.ToolResultBlock;
import io.agentscope.core.message.ToolUseBlock;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

@DisplayName("ResponsesMessageConverter Tests")
class ResponsesMessageConverterTest {

    private final ObjectMapper objectMapper = new ObjectMapper();
    private final ResponsesMessageConverter converter = new ResponsesMessageConverter();

    @Test
    @DisplayName("Should parse instructions and string input")
    void shouldParseInstructionsAndStringInput() throws Exception {
        ResponsesRequest request =
                objectMapper.readValue(
                        """
                        {
                          "model": "test-model",
                          "instructions": "Be concise",
                          "input": "Hello",
                          "parallel_tool_calls": true
                        }
                        """,
                        ResponsesRequest.class);

        List<Msg> messages = converter.convert(request);

        assertEquals(2, messages.size());
        assertEquals(MsgRole.SYSTEM, messages.get(0).getRole());
        assertEquals("Be concise", messages.get(0).getTextContent());
        assertEquals(MsgRole.USER, messages.get(1).getRole());
        assertEquals("Hello", messages.get(1).getTextContent());
    }

    @Test
    @DisplayName("Should parse message-array input, tools, images, and function outputs")
    void shouldParseMessageArrayInput() throws Exception {
        ResponsesRequest request =
                objectMapper.readValue(
                        """
                        {
                          "model": "test-model",
                          "stream": true,
                          "input": [
                            {
                              "role": "user",
                              "content": [
                                {"type": "input_text", "text": "What is the weather?"},
                                {"type": "input_image", "image_url": "https://example.com/a.png"}
                              ]
                            },
                            {
                              "type": "function_call",
                              "call_id": "call_1",
                              "name": "get_weather",
                              "arguments": "{\\"city\\":\\"Paris\\"}"
                            },
                            {
                              "type": "function_call_output",
                              "call_id": "call_1",
                              "name": "get_weather",
                              "output": "Sunny"
                            }
                          ]
                        }
                        """,
                        ResponsesRequest.class);

        List<Msg> messages = converter.convert(request);

        assertEquals(3, messages.size());
        assertEquals(MsgRole.USER, messages.get(0).getRole());
        assertEquals("What is the weather?", messages.get(0).getTextContent());
        assertInstanceOf(ImageBlock.class, messages.get(0).getContent().get(1));

        assertEquals(MsgRole.ASSISTANT, messages.get(1).getRole());
        ToolUseBlock toolUse =
                assertInstanceOf(ToolUseBlock.class, messages.get(1).getContent().get(0));
        assertEquals("call_1", toolUse.getId());
        assertEquals("get_weather", toolUse.getName());
        assertEquals(Map.of("city", "Paris"), toolUse.getInput());

        assertEquals(MsgRole.TOOL, messages.get(2).getRole());
        ToolResultBlock toolResult =
                assertInstanceOf(ToolResultBlock.class, messages.get(2).getContent().get(0));
        assertEquals("call_1", toolResult.getId());
        assertEquals("get_weather", toolResult.getName());
        assertEquals("Sunny", ((TextBlock) toolResult.getOutput().get(0)).getText());
    }

    @Test
    @DisplayName("Should parse object input, data images, and fallback text content")
    void shouldParseObjectInputAndFallbackContent() throws Exception {
        ResponsesRequest request =
                objectMapper.readValue(
                        """
                        {
                          "model": "test-model",
                          "input": {
                            "role": "developer",
                            "content": [
                              {
                                "type": "input_image",
                                "image_url": "data:image/png;base64,abc"
                              },
                              {
                                "type": "unsupported",
                                "payload": {"text": "fallback text"}
                              }
                            ]
                          }
                        }
                        """,
                        ResponsesRequest.class);

        List<Msg> messages = converter.convert(request);

        assertEquals(1, messages.size());
        assertEquals(MsgRole.SYSTEM, messages.get(0).getRole());
        assertInstanceOf(ImageBlock.class, messages.get(0).getContent().get(0));
        assertEquals(
                "{\"type\":\"unsupported\",\"payload\":{\"text\":\"fallback text\"}}",
                ((TextBlock) messages.get(0).getContent().get(1)).getText());
    }

    @Test
    @DisplayName("Should parse scalar items, object content, roles, and fallback outputs")
    void shouldParseScalarItemsObjectContentRolesAndFallbackOutputs() throws Exception {
        ResponsesRequest request =
                objectMapper.readValue(
                        """
                        {
                          "model": "test-model",
                          "input": [
                            "plain",
                            {"role": "assistant", "text": "assistant text"},
                            {"role": "tool", "output_text": "tool text"},
                            {"role": "unknown", "content": "content text"},
                            {
                              "role": "user",
                              "content": {
                                "type": "image",
                                "url": "https://example.com/b.png"
                              }
                            },
                            {
                              "role": "user",
                              "content": {"type": "input_image"}
                            },
                            {
                              "role": "assistant",
                              "content": {"type": "tool_use", "id": "call_2", "name": "noop"}
                            },
                            {
                              "type": "function_call",
                              "id": "call_3",
                              "name": "noop",
                              "arguments": "{}"
                            },
                            {
                              "type": "function_call_output",
                              "call_id": "call_3",
                              "content": [{"type": "output_text", "text": "done"}]
                            }
                          ]
                        }
                        """,
                        ResponsesRequest.class);

        List<Msg> messages = converter.convert(request);

        assertEquals("plain", messages.get(0).getTextContent());
        assertEquals(MsgRole.ASSISTANT, messages.get(1).getRole());
        assertEquals("assistant text", messages.get(1).getTextContent());
        assertEquals(MsgRole.TOOL, messages.get(2).getRole());
        assertEquals("tool text", messages.get(2).getTextContent());
        assertEquals(MsgRole.USER, messages.get(3).getRole());
        assertEquals("content text", messages.get(3).getTextContent());
        assertInstanceOf(ImageBlock.class, messages.get(4).getContent().get(0));
        assertEquals("[Unsupported image]", messages.get(5).getTextContent());
        ToolUseBlock inlineTool =
                assertInstanceOf(ToolUseBlock.class, messages.get(6).getContent().get(0));
        assertEquals(Map.of(), inlineTool.getInput());
        assertEquals("{}", inlineTool.getContent());
        ToolUseBlock functionCall =
                assertInstanceOf(ToolUseBlock.class, messages.get(7).getContent().get(0));
        assertEquals("call_3", functionCall.getId());
        ToolResultBlock result =
                assertInstanceOf(ToolResultBlock.class, messages.get(8).getContent().get(0));
        assertEquals("done", ((TextBlock) result.getOutput().get(0)).getText());
    }

    @Test
    @DisplayName("Should parse loose content parts and role fallbacks")
    void shouldParseLooseContentPartsAndRoleFallbacks() throws Exception {
        ResponsesRequest request =
                objectMapper.readValue(
                        """
                        {
                          "input": [
                            {"content": [null, "loose text"]},
                            {
                              "role": "",
                              "content": {
                                "type": "input_image",
                                "image_url": "data:image/png;base64abc"
                              }
                            },
                            {
                              "role": "assistant",
                              "content": {
                                "type": "tool_use",
                                "id": "call_4",
                                "name": "lookup",
                                "input": {"city": "Paris"}
                              }
                            },
                            {
                              "type": "function_call_output",
                              "call_id": "call_4",
                              "content": "finished"
                            },
                            {
                              "type": "function_call_output",
                              "call_id": "call_5",
                              "content": null
                            },
                            {
                              "role": "user",
                              "content": {"type": "output_text", "text": ""}
                            }
                          ]
                        }
                        """,
                        ResponsesRequest.class);

        List<Msg> messages = converter.convert(request);

        assertEquals(MsgRole.USER, messages.get(0).getRole());
        assertEquals("loose text", messages.get(0).getTextContent());
        assertEquals(MsgRole.USER, messages.get(1).getRole());
        assertInstanceOf(ImageBlock.class, messages.get(1).getContent().get(0));
        ToolUseBlock toolUse =
                assertInstanceOf(ToolUseBlock.class, messages.get(2).getContent().get(0));
        assertEquals(Map.of("city", "Paris"), toolUse.getInput());
        ToolResultBlock textResult =
                assertInstanceOf(ToolResultBlock.class, messages.get(3).getContent().get(0));
        assertEquals("finished", ((TextBlock) textResult.getOutput().get(0)).getText());
        ToolResultBlock nullResult =
                assertInstanceOf(ToolResultBlock.class, messages.get(4).getContent().get(0));
        assertEquals("", ((TextBlock) nullResult.getOutput().get(0)).getText());
        assertEquals("", messages.get(5).getTextContent());
    }

    @Test
    @DisplayName("Should reject arrays without input messages")
    void shouldRejectArraysWithoutInputMessages() throws Exception {
        ResponsesRequest request =
                objectMapper.readValue(
                        """
                        {"input": [null]}
                        """,
                        ResponsesRequest.class);

        ProtocolException error =
                assertThrows(ProtocolException.class, () -> converter.convert(request));

        assertEquals("invalid_request_error", error.getCode());
    }

    @Test
    @DisplayName("Should reject unsupported scalar input")
    void shouldRejectUnsupportedScalarInput() throws Exception {
        ResponsesRequest request =
                objectMapper.readValue(
                        """
                        {"model": "test", "input": 42}
                        """,
                        ResponsesRequest.class);

        ProtocolException error =
                assertThrows(ProtocolException.class, () -> converter.convert(request));

        assertEquals("invalid_request_error", error.getCode());
    }

    @Test
    @DisplayName("Should reject unsupported stateful Responses fields")
    void shouldRejectUnsupportedStatefulFields() throws Exception {
        ResponsesRequest previous =
                objectMapper.readValue(
                        """
                        {"model": "test", "input": "hello", "previous_response_id": "resp_1"}
                        """,
                        ResponsesRequest.class);
        ResponsesRequest conversation =
                objectMapper.readValue(
                        """
                        {"model": "test", "input": "hello", "conversation": {"id": "conv_1"}}
                        """,
                        ResponsesRequest.class);
        ResponsesRequest background =
                objectMapper.readValue(
                        """
                        {"model": "test", "input": "hello", "background": true}
                        """,
                        ResponsesRequest.class);

        assertEquals(
                "unsupported_feature",
                assertThrows(ProtocolException.class, () -> converter.convert(previous)).getCode());
        assertEquals(
                "unsupported_feature",
                assertThrows(ProtocolException.class, () -> converter.convert(conversation))
                        .getCode());
        assertEquals(
                "unsupported_feature",
                assertThrows(ProtocolException.class, () -> converter.convert(background))
                        .getCode());
    }

    @Test
    @DisplayName("Should reject malformed function-call arguments")
    void shouldRejectMalformedFunctionArguments() throws Exception {
        ResponsesRequest request =
                objectMapper.readValue(
                        """
                        {
                          "model": "test",
                          "input": [
                            {
                              "type": "function_call",
                              "call_id": "call_1",
                              "name": "bad_tool",
                              "arguments": "{bad-json"
                            }
                          ]
                        }
                        """,
                        ResponsesRequest.class);

        ProtocolException error =
                assertThrows(ProtocolException.class, () -> converter.convert(request));

        assertEquals("invalid_request_error", error.getCode());
    }

    @Test
    @DisplayName("Should require input")
    void shouldRequireInput() {
        ResponsesRequest request = new ResponsesRequest();

        ProtocolException error =
                assertThrows(ProtocolException.class, () -> converter.convert(request));

        assertEquals("invalid_request_error", error.getCode());
    }
}
