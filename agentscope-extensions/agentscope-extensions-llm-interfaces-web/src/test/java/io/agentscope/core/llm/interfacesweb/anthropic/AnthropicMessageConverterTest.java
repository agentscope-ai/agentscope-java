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
    @DisplayName("Should reject empty Anthropic messages")
    void shouldRejectEmptyMessages() {
        AnthropicMessagesRequest request = new AnthropicMessagesRequest();

        ProtocolException error =
                assertThrows(ProtocolException.class, () -> converter.convert(request));

        assertEquals("invalid_request_error", error.getCode());
    }
}
