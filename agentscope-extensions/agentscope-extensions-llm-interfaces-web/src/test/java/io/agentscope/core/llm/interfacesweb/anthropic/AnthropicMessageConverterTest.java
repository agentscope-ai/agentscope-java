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
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.NullNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
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

@DisplayName("AnthropicMessageConverter Tests")
class AnthropicMessageConverterTest {

    private final ObjectMapper objectMapper = new ObjectMapper();
    private final AnthropicMessageConverter converter = new AnthropicMessageConverter();

    @Test
    @DisplayName("Should parse system prompt and message history")
    void shouldParseSystemPromptAndMessages() throws Exception {
        AnthropicMessagesRequest request =
                objectMapper.readValue(
                        """
                        {
                          "model": "claude-test",
                          "system": "Be helpful",
                          "messages": [
                            {"role": "user", "content": "Hello"},
                            {"role": "assistant", "content": "Hi"}
                          ]
                        }
                        """,
                        AnthropicMessagesRequest.class);

        List<Msg> messages = converter.convert(request);

        assertEquals(3, messages.size());
        assertEquals(MsgRole.SYSTEM, messages.get(0).getRole());
        assertEquals("Be helpful", messages.get(0).getTextContent());
        assertEquals(MsgRole.USER, messages.get(1).getRole());
        assertEquals("Hello", messages.get(1).getTextContent());
        assertEquals(MsgRole.ASSISTANT, messages.get(2).getRole());
        assertEquals("Hi", messages.get(2).getTextContent());
    }

    @Test
    @DisplayName("Should parse image, tool_use, and tool_result content blocks")
    void shouldParseToolAndImageBlocks() throws Exception {
        AnthropicMessagesRequest request =
                objectMapper.readValue(
                        """
                        {
                          "model": "claude-test",
                          "messages": [
                            {
                              "role": "assistant",
                              "content": [
                                {"type": "text", "text": "Checking"},
                                {
                                  "type": "tool_use",
                                  "id": "toolu_1",
                                  "name": "get_weather",
                                  "input": {"city": "Paris"}
                                }
                              ]
                            },
                            {
                              "role": "user",
                              "content": [
                                {
                                  "type": "tool_result",
                                  "tool_use_id": "toolu_1",
                                  "content": "Sunny"
                                },
                                {
                                  "type": "image",
                                  "source": {
                                    "type": "url",
                                    "url": "https://example.com/a.png"
                                  }
                                }
                              ]
                            }
                          ]
                        }
                        """,
                        AnthropicMessagesRequest.class);

        List<Msg> messages = converter.convert(request);

        assertEquals(MsgRole.ASSISTANT, messages.get(0).getRole());
        assertEquals("Checking", ((TextBlock) messages.get(0).getContent().get(0)).getText());
        ToolUseBlock toolUse =
                assertInstanceOf(ToolUseBlock.class, messages.get(0).getContent().get(1));
        assertEquals("toolu_1", toolUse.getId());
        assertEquals("get_weather", toolUse.getName());
        assertEquals(Map.of("city", "Paris"), toolUse.getInput());

        assertEquals(MsgRole.USER, messages.get(1).getRole());
        ToolResultBlock result =
                assertInstanceOf(ToolResultBlock.class, messages.get(1).getContent().get(0));
        assertEquals("toolu_1", result.getId());
        assertEquals("Sunny", ((TextBlock) result.getOutput().get(0)).getText());
        assertInstanceOf(ImageBlock.class, messages.get(1).getContent().get(1));
    }

    @Test
    @DisplayName("Should parse structured system, base64 images, and unknown blocks")
    void shouldParseStructuredSystemAndBase64Images() throws Exception {
        AnthropicMessagesRequest request =
                objectMapper.readValue(
                        """
                        {
                          "model": "claude-test",
                          "system": [
                            {"type": "text", "text": "Use tools carefully"}
                          ],
                          "messages": [
                            {
                              "role": "user",
                              "content": [
                                {
                                  "type": "image",
                                  "source": {
                                    "type": "base64",
                                    "media_type": "image/png",
                                    "data": "abc"
                                  }
                                },
                                {
                                  "type": "custom",
                                  "text": "fallback"
                                }
                              ]
                            }
                          ]
                        }
                        """,
                        AnthropicMessagesRequest.class);

        List<Msg> messages = converter.convert(request);

        assertEquals(2, messages.size());
        assertEquals(MsgRole.SYSTEM, messages.get(0).getRole());
        assertEquals("Use tools carefully", messages.get(0).getTextContent());
        assertInstanceOf(ImageBlock.class, messages.get(1).getContent().get(0));
        assertEquals("fallback", ((TextBlock) messages.get(1).getContent().get(1)).getText());
    }

    @Test
    @DisplayName("Should parse null, scalar, and unsupported content shapes")
    void shouldParseNullScalarAndUnsupportedContentShapes() throws Exception {
        AnthropicMessagesRequest request =
                objectMapper.readValue(
                        """
                        {
                          "system": null,
                          "messages": [
                            {"role": "user"},
                            {"role": "tool", "content": ["plain", null]},
                            {
                              "role": "assistant",
                              "content": {
                                "type": "image",
                                "source": {"type": "file", "file_id": "img_1"}
                              }
                            },
                            {
                              "role": "",
                              "content": {"type": "image"}
                            },
                            {
                              "role": "assistant",
                              "content": [{"type": "tool_use", "id": "toolu_2", "name": "noop"}]
                            },
                            {
                              "role": "tool",
                              "content": [{"type": "tool_result", "tool_use_id": "toolu_3"}]
                            },
                            {
                              "content": {"type": "custom", "payload": true}
                            },
                            {
                              "role": "assistant",
                              "content": [{"type": "image", "source": null}]
                            }
                          ]
                        }
                        """,
                        AnthropicMessagesRequest.class);

        List<Msg> messages = converter.convert(request);

        assertEquals("", messages.get(0).getTextContent());
        assertEquals(MsgRole.TOOL, messages.get(1).getRole());
        assertEquals("plain", ((TextBlock) messages.get(1).getContent().get(0)).getText());
        assertEquals("[Unsupported image]", messages.get(2).getTextContent());
        assertEquals(MsgRole.USER, messages.get(3).getRole());
        assertEquals("[Unsupported image]", messages.get(3).getTextContent());
        ToolUseBlock toolUse =
                assertInstanceOf(ToolUseBlock.class, messages.get(4).getContent().get(0));
        assertEquals(Map.of(), toolUse.getInput());
        assertEquals("{}", toolUse.getContent());
        ToolResultBlock toolResult =
                assertInstanceOf(ToolResultBlock.class, messages.get(5).getContent().get(0));
        assertEquals("", ((TextBlock) toolResult.getOutput().get(0)).getText());
        assertEquals(MsgRole.USER, messages.get(6).getRole());
        assertEquals("{\"type\":\"custom\",\"payload\":true}", messages.get(6).getTextContent());
        assertEquals("[Unsupported image]", messages.get(7).getTextContent());
    }

    @Test
    @DisplayName("Should ignore blank system prompt")
    void shouldIgnoreBlankSystemPrompt() throws Exception {
        AnthropicMessagesRequest request =
                objectMapper.readValue(
                        """
                        {
                          "system": " ",
                          "messages": [
                            {"role": "user", "content": "Hello"}
                          ]
                        }
                        """,
                        AnthropicMessagesRequest.class);

        List<Msg> messages = converter.convert(request);

        assertEquals(1, messages.size());
        assertEquals("Hello", messages.get(0).getTextContent());
    }

    @Test
    @DisplayName("Should parse explicit JSON null nodes")
    void shouldParseExplicitJsonNullNodes() {
        AnthropicMessagesRequest request = new AnthropicMessagesRequest();
        request.setSystem(NullNode.getInstance());

        AnthropicMessage emptyContent = new AnthropicMessage();
        emptyContent.setRole("user");
        emptyContent.setContent(List.of(NullNode.getInstance()));
        AnthropicMessage nullNodeContent = new AnthropicMessage();
        nullNodeContent.setRole("user");
        nullNodeContent.setContent(NullNode.getInstance());

        ObjectNode resultPart = objectMapper.createObjectNode();
        resultPart.put("type", "tool_result");
        resultPart.put("tool_use_id", "toolu_null");
        resultPart.set("content", NullNode.getInstance());
        AnthropicMessage nullResult = new AnthropicMessage();
        nullResult.setRole("tool");
        nullResult.setContent(List.of(resultPart));

        request.setMessages(List.of(emptyContent, nullNodeContent, nullResult));

        List<Msg> messages = converter.convert(request);

        assertEquals(3, messages.size());
        assertEquals("", messages.get(0).getTextContent());
        assertEquals("", messages.get(1).getTextContent());
        ToolResultBlock result =
                assertInstanceOf(ToolResultBlock.class, messages.get(2).getContent().get(0));
        assertEquals("", ((TextBlock) result.getOutput().get(0)).getText());
    }

    @Test
    @DisplayName("Should reject empty Anthropic messages")
    void shouldRejectEmptyMessages() {
        AnthropicMessagesRequest request = new AnthropicMessagesRequest();

        ProtocolException error =
                assertThrows(ProtocolException.class, () -> converter.convert(request));

        assertEquals("invalid_request_error", error.getCode());
    }
}
