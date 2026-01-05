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
import io.agentscope.core.message.ImageBlock;
import io.agentscope.core.message.Msg;
import io.agentscope.core.message.MsgRole;
import io.agentscope.core.message.TextBlock;
import io.agentscope.core.message.ToolResultBlock;
import io.agentscope.core.message.ToolUseBlock;
import io.agentscope.core.message.URLSource;
import io.agentscope.core.util.JsonCodec;
import io.agentscope.core.util.JsonUtils;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/** Ground truth tests for AnthropicChatFormatter - compares with Python implementation. */
class AnthropicChatFormatterGroundTruthTest {

    private AnthropicChatFormatter formatter;
    private JsonCodec jsonCodec;
    private String imageUrl;
    private List<Msg> msgsSystem;
    private List<Msg> msgsConversation;
    private List<Msg> msgsTools;

    @BeforeEach
    void setUp() {
        formatter = new AnthropicChatFormatter();
        jsonCodec = JsonUtils.getJsonCodec();
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
    }

    @Test
    void testChatFormatterFullHistory() throws Exception {
        // Full history: system + conversation + tools
        List<Msg> allMsgs = new ArrayList<>();
        allMsgs.addAll(msgsSystem);
        allMsgs.addAll(msgsConversation);
        allMsgs.addAll(msgsTools);

        List<MessageParam> result = formatter.format(allMsgs);

        // Convert to JSON string for comparison
        String resultJson = ObjectMappers.jsonMapper().writeValueAsString(result);
        JsonNode resultNode = jsonCodec.fromJson(resultJson, JsonNode.class);

        // Ground truth from Python implementation
        String groundTruthJson =
                """
                [
                  {
                    "role": "user",
                    "content": [
                      {
                        "type": "text",
                        "text": "You're a helpful assistant."
                      }
                    ]
                  },
                  {
                    "role": "user",
                    "content": [
                      {
                        "type": "text",
                        "text": "What is the capital of France?"
                      },
                      {
                        "type": "image",
                        "source": {
                          "type": "url",
                          "url": "https://www.example.com/image.png"
                        }
                      }
                    ]
                  },
                  {
                    "role": "assistant",
                    "content": [
                      {
                        "type": "text",
                        "text": "The capital of France is Paris."
                      }
                    ]
                  },
                  {
                    "role": "user",
                    "content": [
                      {
                        "type": "text",
                        "text": "What is the capital of Japan?"
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
                    "role": "assistant",
                    "content": [
                      {
                        "type": "text",
                        "text": "The capital of Japan is Tokyo."
                      }
                    ]
                  }
                ]
                """;

        JsonNode groundTruthNode = jsonCodec.fromJson(groundTruthJson, JsonNode.class);

        assertEquals(groundTruthNode, resultNode, "Formatted output should match ground truth");
    }

    @Test
    void testChatFormatterWithoutSystemMessage() throws Exception {
        // Without system message
        List<Msg> allMsgs = new ArrayList<>();
        allMsgs.addAll(msgsConversation);
        allMsgs.addAll(msgsTools);

        List<MessageParam> result = formatter.format(allMsgs);
        String resultJson = ObjectMappers.jsonMapper().writeValueAsString(result);
        JsonNode resultNode = jsonCodec.fromJson(resultJson, JsonNode.class);

        // Ground truth should be the same as full history, but without first message
        String groundTruthJson =
                """
                [
                  {
                    "role": "user",
                    "content": [
                      {
                        "type": "text",
                        "text": "What is the capital of France?"
                      },
                      {
                        "type": "image",
                        "source": {
                          "type": "url",
                          "url": "https://www.example.com/image.png"
                        }
                      }
                    ]
                  },
                  {
                    "role": "assistant",
                    "content": [
                      {
                        "type": "text",
                        "text": "The capital of France is Paris."
                      }
                    ]
                  },
                  {
                    "role": "user",
                    "content": [
                      {
                        "type": "text",
                        "text": "What is the capital of Japan?"
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
                    "role": "assistant",
                    "content": [
                      {
                        "type": "text",
                        "text": "The capital of Japan is Tokyo."
                      }
                    ]
                  }
                ]
                """;

        JsonNode groundTruthNode = jsonCodec.fromJson(groundTruthJson, JsonNode.class);
        assertEquals(groundTruthNode, resultNode);
    }

    @Test
    void testChatFormatterWithoutConversation() throws Exception {
        // Without conversation messages: system + tools only
        List<Msg> allMsgs = new ArrayList<>();
        allMsgs.addAll(msgsSystem);
        allMsgs.addAll(msgsTools);

        List<MessageParam> result = formatter.format(allMsgs);
        String resultJson = ObjectMappers.jsonMapper().writeValueAsString(result);
        JsonNode resultNode = jsonCodec.fromJson(resultJson, JsonNode.class);

        String groundTruthJson =
                """
                [
                  {
                    "role": "user",
                    "content": [
                      {
                        "type": "text",
                        "text": "You're a helpful assistant."
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
                    "role": "assistant",
                    "content": [
                      {
                        "type": "text",
                        "text": "The capital of Japan is Tokyo."
                      }
                    ]
                  }
                ]
                """;

        JsonNode groundTruthNode = jsonCodec.fromJson(groundTruthJson, JsonNode.class);
        assertEquals(groundTruthNode, resultNode);
    }

    @Test
    void testChatFormatterWithoutTools() throws Exception {
        // Without tool messages: system + conversation only
        List<Msg> allMsgs = new ArrayList<>();
        allMsgs.addAll(msgsSystem);
        allMsgs.addAll(msgsConversation);

        List<MessageParam> result = formatter.format(allMsgs);
        String resultJson = ObjectMappers.jsonMapper().writeValueAsString(result);
        JsonNode resultNode = jsonCodec.fromJson(resultJson, JsonNode.class);

        String groundTruthJson =
                """
                [
                  {
                    "role": "user",
                    "content": [
                      {
                        "type": "text",
                        "text": "You're a helpful assistant."
                      }
                    ]
                  },
                  {
                    "role": "user",
                    "content": [
                      {
                        "type": "text",
                        "text": "What is the capital of France?"
                      },
                      {
                        "type": "image",
                        "source": {
                          "type": "url",
                          "url": "https://www.example.com/image.png"
                        }
                      }
                    ]
                  },
                  {
                    "role": "assistant",
                    "content": [
                      {
                        "type": "text",
                        "text": "The capital of France is Paris."
                      }
                    ]
                  },
                  {
                    "role": "user",
                    "content": [
                      {
                        "type": "text",
                        "text": "What is the capital of Japan?"
                      }
                    ]
                  }
                ]
                """;

        JsonNode groundTruthNode = jsonCodec.fromJson(groundTruthJson, JsonNode.class);
        assertEquals(groundTruthNode, resultNode);
    }

    @Test
    void testChatFormatterEmptyMessages() {
        List<MessageParam> result = formatter.format(List.of());
        assertEquals(0, result.size(), "Empty input should produce empty output");
    }
}
