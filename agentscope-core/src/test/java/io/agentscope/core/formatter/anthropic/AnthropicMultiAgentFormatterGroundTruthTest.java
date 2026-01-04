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
package io.agentscope.core.formatter.anthropic;

import static org.junit.jupiter.api.Assertions.assertEquals;

import com.anthropic.core.ObjectMappers;
import com.anthropic.models.messages.MessageParam;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.agentscope.core.message.ImageBlock;
import io.agentscope.core.message.Msg;
import io.agentscope.core.message.MsgRole;
import io.agentscope.core.message.TextBlock;
import io.agentscope.core.message.ToolResultBlock;
import io.agentscope.core.message.ToolUseBlock;
import io.agentscope.core.message.URLSource;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * Ground truth tests for AnthropicMultiAgentFormatter - compares with Python implementation.
 */
class AnthropicMultiAgentFormatterGroundTruthTest {

    private AnthropicMultiAgentFormatter formatter;
    private ObjectMapper objectMapper;
    private String imageUrl;
    private List<Msg> msgsSystem;
    private List<Msg> msgsConversation;
    private List<Msg> msgsTools;
    private List<Msg> msgsConversation2;
    private List<Msg> msgsTools2;

    @BeforeEach
    void setUp() {
        formatter = new AnthropicMultiAgentFormatter();
        objectMapper = new ObjectMapper();
        imageUrl = "https://www.example.com/image.png";

        // System message
        msgsSystem =
                List.of(
                        Msg.builder()
                                .name("system")
                                .role(MsgRole.SYSTEM)
                                .content(
                                        List.of(
                                                TextBlock.builder()
                                                        .text("You're a helpful assistant.")
                                                        .build()))
                                .build());

        // Conversation messages
        msgsConversation =
                List.of(
                        Msg.builder()
                                .name("user")
                                .role(MsgRole.USER)
                                .content(
                                        List.of(
                                                TextBlock.builder()
                                                        .text("What is the capital of France?")
                                                        .build(),
                                                ImageBlock.builder()
                                                        .source(
                                                                URLSource.builder()
                                                                        .url(imageUrl)
                                                                        .build())
                                                        .build()))
                                .build(),
                        Msg.builder()
                                .name("assistant")
                                .role(MsgRole.ASSISTANT)
                                .content(
                                        List.of(
                                                TextBlock.builder()
                                                        .text("The capital of France is Paris.")
                                                        .build()))
                                .build(),
                        Msg.builder()
                                .name("user")
                                .role(MsgRole.USER)
                                .content(
                                        List.of(
                                                TextBlock.builder()
                                                        .text("What is the capital of Japan?")
                                                        .build()))
                                .build());

        // Tool messages
        msgsTools =
                List.of(
                        Msg.builder()
                                .name("assistant")
                                .role(MsgRole.ASSISTANT)
                                .content(
                                        List.of(
                                                ToolUseBlock.builder()
                                                        .id("1")
                                                        .name("get_capital")
                                                        .input(Map.of("country", "Japan"))
                                                        .build()))
                                .build(),
                        Msg.builder()
                                .name("system")
                                .role(MsgRole.SYSTEM)
                                .content(
                                        List.of(
                                                ToolResultBlock.builder()
                                                        .id("1")
                                                        .name("get_capital")
                                                        .output(
                                                                TextBlock.builder()
                                                                        .text(
                                                                                "The capital of"
                                                                                    + " Japan is"
                                                                                    + " Tokyo.")
                                                                        .build())
                                                        .build()))
                                .build(),
                        Msg.builder()
                                .name("assistant")
                                .role(MsgRole.ASSISTANT)
                                .content(
                                        List.of(
                                                TextBlock.builder()
                                                        .text("The capital of Japan is Tokyo.")
                                                        .build()))
                                .build());

        // Second conversation
        msgsConversation2 =
                List.of(
                        Msg.builder()
                                .name("user")
                                .role(MsgRole.USER)
                                .content(
                                        List.of(
                                                TextBlock.builder()
                                                        .text("What is the capital of South Korea?")
                                                        .build()))
                                .build());

        // Second tool messages
        msgsTools2 =
                List.of(
                        Msg.builder()
                                .name("assistant")
                                .role(MsgRole.ASSISTANT)
                                .content(
                                        List.of(
                                                ToolUseBlock.builder()
                                                        .id("2")
                                                        .name("get_capital")
                                                        .input(Map.of("country", "South Korea"))
                                                        .build()))
                                .build(),
                        Msg.builder()
                                .name("system")
                                .role(MsgRole.SYSTEM)
                                .content(
                                        List.of(
                                                ToolResultBlock.builder()
                                                        .id("1")
                                                        .name("get_capital")
                                                        .output(
                                                                TextBlock.builder()
                                                                        .text(
                                                                                "The capital of"
                                                                                    + " South Korea"
                                                                                    + " is Seoul.")
                                                                        .build())
                                                        .build()))
                                .build(),
                        Msg.builder()
                                .name("assistant")
                                .role(MsgRole.ASSISTANT)
                                .content(
                                        List.of(
                                                TextBlock.builder()
                                                        .text(
                                                                "The capital of South Korea is"
                                                                        + " Seoul.")
                                                        .build()))
                                .build());
    }

    @Test
    void testMultiAgentFormatterFullHistory() throws Exception {
        // system + conversation + tools + conversation + tools
        List<Msg> allMsgs = new ArrayList<>();
        allMsgs.addAll(msgsSystem);
        allMsgs.addAll(msgsConversation);
        allMsgs.addAll(msgsTools);
        allMsgs.addAll(msgsConversation2);
        allMsgs.addAll(msgsTools2);

        List<MessageParam> result = formatter.format(allMsgs);
        String resultJson = ObjectMappers.jsonMapper().writeValueAsString(result);
        JsonNode resultNode = objectMapper.readTree(resultJson);

        // Ground truth from Python implementation
        String groundTruthJson =
                """
                [
                  {
                    "role": "user",
                    "content": [
                      {
                        "text": "You're a helpful assistant.",
                        "type": "text"
                      }
                    ]
                  },
                  {
                    "role": "user",
                    "content": [
                      {
                        "text": "# Conversation History\\nThe content between <history></history> tags contains your conversation history\\n<history>\\nuser: What is the capital of France?",
                        "type": "text"
                      },
                      {
                        "type": "image",
                        "source": {
                          "type": "url",
                          "url": "https://www.example.com/image.png"
                        }
                      },
                      {
                        "text": "assistant: The capital of France is Paris.\\nuser: What is the capital of Japan?\\n</history>",
                        "type": "text"
                      }
                    ]
                  },
                  {
                    "role": "assistant",
                    "content": [
                      {
                        "id": "1",
                        "type": "tool_use",
                        "name": "get_capital",
                        "input": {
                          "country": "Japan"
                        }
                      }
                    ]
                  },
                  {
                    "role": "user",
                    "content": [
                      {
                        "type": "tool_result",
                        "tool_use_id": "1",
                        "content": [
                          {
                            "type": "text",
                            "text": "The capital of Japan is Tokyo."
                          }
                        ]
                      }
                    ]
                  },
                  {
                    "role": "user",
                    "content": [
                      {
                        "text": "<history>\\nassistant: The capital of Japan is Tokyo.\\nuser: What is the capital of South Korea?\\n</history>",
                        "type": "text"
                      }
                    ]
                  },
                  {
                    "role": "assistant",
                    "content": [
                      {
                        "id": "2",
                        "type": "tool_use",
                        "name": "get_capital",
                        "input": {
                          "country": "South Korea"
                        }
                      }
                    ]
                  },
                  {
                    "role": "user",
                    "content": [
                      {
                        "type": "tool_result",
                        "tool_use_id": "1",
                        "content": [
                          {
                            "type": "text",
                            "text": "The capital of South Korea is Seoul."
                          }
                        ]
                      }
                    ]
                  },
                  {
                    "role": "user",
                    "content": [
                      {
                        "text": "<history>\\nassistant: The capital of South Korea is Seoul.\\n</history>",
                        "type": "text"
                      }
                    ]
                  }
                ]
                """;

        JsonNode groundTruthNode = objectMapper.readTree(groundTruthJson);
        assertEquals(groundTruthNode, resultNode, "Formatted output should match ground truth");
    }

    @Test
    void testMultiAgentFormatterWithoutSystemMessage() throws Exception {
        // conversation + tools (without the last assistant text message)
        List<Msg> allMsgs = new ArrayList<>();
        allMsgs.addAll(msgsConversation);
        // Only add first 2 messages from msgsTools (tool_use and tool_result)
        // excluding the last assistant text message
        allMsgs.add(msgsTools.get(0)); // assistant with tool_use
        allMsgs.add(msgsTools.get(1)); // system with tool_result

        List<MessageParam> result = formatter.format(allMsgs);
        String resultJson = ObjectMappers.jsonMapper().writeValueAsString(result);
        JsonNode resultNode = objectMapper.readTree(resultJson);

        String groundTruthJson =
                """
                [
                  {
                    "role": "user",
                    "content": [
                      {
                        "text": "# Conversation History\\nThe content between <history></history> tags contains your conversation history\\n<history>\\nuser: What is the capital of France?",
                        "type": "text"
                      },
                      {
                        "type": "image",
                        "source": {
                          "type": "url",
                          "url": "https://www.example.com/image.png"
                        }
                      },
                      {
                        "text": "assistant: The capital of France is Paris.\\nuser: What is the capital of Japan?\\n</history>",
                        "type": "text"
                      }
                    ]
                  },
                  {
                    "role": "assistant",
                    "content": [
                      {
                        "id": "1",
                        "type": "tool_use",
                        "name": "get_capital",
                        "input": {
                          "country": "Japan"
                        }
                      }
                    ]
                  },
                  {
                    "role": "user",
                    "content": [
                      {
                        "type": "tool_result",
                        "tool_use_id": "1",
                        "content": [
                          {
                            "type": "text",
                            "text": "The capital of Japan is Tokyo."
                          }
                        ]
                      }
                    ]
                  }
                ]
                """;

        JsonNode groundTruthNode = objectMapper.readTree(groundTruthJson);
        assertEquals(groundTruthNode, resultNode);
    }

    @Test
    void testMultiAgentFormatterOnlySystemMessage() throws Exception {
        List<MessageParam> result = formatter.format(msgsSystem);
        String resultJson = ObjectMappers.jsonMapper().writeValueAsString(result);
        JsonNode resultNode = objectMapper.readTree(resultJson);

        String groundTruthJson =
                """
                [
                  {
                    "role": "user",
                    "content": [
                      {
                        "text": "You're a helpful assistant.",
                        "type": "text"
                      }
                    ]
                  }
                ]
                """;

        JsonNode groundTruthNode = objectMapper.readTree(groundTruthJson);
        assertEquals(groundTruthNode, resultNode);
    }

    @Test
    void testMultiAgentFormatterOnlyConversation() throws Exception {
        List<MessageParam> result = formatter.format(msgsConversation);
        String resultJson = ObjectMappers.jsonMapper().writeValueAsString(result);
        JsonNode resultNode = objectMapper.readTree(resultJson);

        String groundTruthJson =
                """
                [
                  {
                    "role": "user",
                    "content": [
                      {
                        "text": "# Conversation History\\nThe content between <history></history> tags contains your conversation history\\n<history>\\nuser: What is the capital of France?",
                        "type": "text"
                      },
                      {
                        "type": "image",
                        "source": {
                          "type": "url",
                          "url": "https://www.example.com/image.png"
                        }
                      },
                      {
                        "text": "assistant: The capital of France is Paris.\\nuser: What is the capital of Japan?\\n</history>",
                        "type": "text"
                      }
                    ]
                  }
                ]
                """;

        JsonNode groundTruthNode = objectMapper.readTree(groundTruthJson);
        assertEquals(groundTruthNode, resultNode);
    }
}
