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
package io.agentscope.core.formatter.gemini;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.agentscope.core.message.AudioBlock;
import io.agentscope.core.message.Base64Source;
import io.agentscope.core.message.ImageBlock;
import io.agentscope.core.message.Msg;
import io.agentscope.core.message.MsgRole;
import io.agentscope.core.message.TextBlock;
import io.agentscope.core.message.ToolResultBlock;
import io.agentscope.core.message.ToolUseBlock;
import io.agentscope.core.message.URLSource;
import java.util.List;
import java.util.Map;

/**
 * Test data for Gemini formatter tests.
 * Contains message fixtures and expected output data for validation.
 */
public class GeminiFormatterTestData {

    private static final ObjectMapper objectMapper = new ObjectMapper();

    // Mock audio path from Python tests
    public static final String MOCK_AUDIO_PATH =
            "/var/folders/gf/krg8x_ws409cpw_46b2s6rjc0000gn/T/tmpfymnv2w9.wav";

    /**
     * Build system message.
     *
     * @return List containing a system message
     */
    public static List<Msg> buildSystemMessage() {
        return List.of(
                Msg.builder()
                        .name("system")
                        .content(
                                List.of(
                                        TextBlock.builder()
                                                .text("You're a helpful assistant.")
                                                .build()))
                        .role(MsgRole.SYSTEM)
                        .build());
    }

    /**
     * Build conversation messages with text and multimodal content.
     *
     * @param imagePath Path to the image file
     * @param audioPath Path to the audio file
     * @return List of conversation messages
     */
    public static List<Msg> buildConversationMessages(String imagePath, String audioPath) {
        return List.of(
                Msg.builder()
                        .name("user")
                        .content(
                                List.of(
                                        TextBlock.builder()
                                                .text("What is the capital of France?")
                                                .build(),
                                        ImageBlock.builder()
                                                .source(URLSource.builder().url(imagePath).build())
                                                .build()))
                        .role(MsgRole.USER)
                        .build(),
                Msg.builder()
                        .name("assistant")
                        .content(
                                List.of(
                                        TextBlock.builder()
                                                .text("The capital of France is Paris.")
                                                .build()))
                        .role(MsgRole.ASSISTANT)
                        .build(),
                Msg.builder()
                        .name("user")
                        .content(
                                List.of(
                                        TextBlock.builder()
                                                .text("What is the capital of Germany?")
                                                .build(),
                                        AudioBlock.builder()
                                                .source(URLSource.builder().url(audioPath).build())
                                                .build()))
                        .role(MsgRole.USER)
                        .build(),
                Msg.builder()
                        .name("assistant")
                        .content(
                                List.of(
                                        TextBlock.builder()
                                                .text("The capital of Germany is Berlin.")
                                                .build()))
                        .role(MsgRole.ASSISTANT)
                        .build(),
                Msg.builder()
                        .name("user")
                        .content(
                                List.of(
                                        TextBlock.builder()
                                                .text("What is the capital of Japan?")
                                                .build()))
                        .role(MsgRole.USER)
                        .build());
    }

    /**
     * Build tool-related messages.
     *
     * @param imagePath Path to the image file
     * @return List of messages with tool use and tool result
     */
    public static List<Msg> buildToolMessages(String imagePath) {
        return List.of(
                Msg.builder()
                        .name("assistant")
                        .content(
                                List.of(
                                        ToolUseBlock.builder()
                                                .id("1")
                                                .name("get_capital")
                                                .input(Map.of("country", "Japan"))
                                                .build()))
                        .role(MsgRole.ASSISTANT)
                        .build(),
                Msg.builder()
                        .name("system")
                        .content(
                                List.of(
                                        ToolResultBlock.builder()
                                                .id("1")
                                                .name("get_capital")
                                                .output(
                                                        List.of(
                                                                TextBlock.builder()
                                                                        .text(
                                                                                "The capital of"
                                                                                    + " Japan is"
                                                                                    + " Tokyo.")
                                                                        .build(),
                                                                ImageBlock.builder()
                                                                        .source(
                                                                                URLSource.builder()
                                                                                        .url(
                                                                                                imagePath)
                                                                                        .build())
                                                                        .build(),
                                                                AudioBlock.builder()
                                                                        .source(
                                                                                Base64Source
                                                                                        .builder()
                                                                                        .mediaType(
                                                                                                "audio/wav")
                                                                                        .data(
                                                                                                "ZmFrZSBhdWRpbyBjb250ZW50")
                                                                                        .build())
                                                                        .build()))
                                                .build()))
                        .role(MsgRole.SYSTEM)
                        .build(),
                Msg.builder()
                        .name("assistant")
                        .content(
                                List.of(
                                        TextBlock.builder()
                                                .text("The capital of Japan is Tokyo.")
                                                .build()))
                        .role(MsgRole.ASSISTANT)
                        .build());
    }

    /**
     * Build second round of conversation messages.
     *
     * @return List of conversation messages
     */
    public static List<Msg> buildConversationMessages2() {
        return List.of(
                Msg.builder()
                        .name("user")
                        .content(
                                List.of(
                                        TextBlock.builder()
                                                .text("What is the capital of South Korea?")
                                                .build()))
                        .role(MsgRole.USER)
                        .build());
    }

    /**
     * Build second round of tool messages.
     *
     * @param imagePath Path to the image file
     * @return List of messages with tool use and tool result
     */
    public static List<Msg> buildToolMessages2(String imagePath) {
        return List.of(
                Msg.builder()
                        .name("assistant")
                        .content(
                                List.of(
                                        ToolUseBlock.builder()
                                                .id("1")
                                                .name("get_capital")
                                                .input(Map.of("country", "South Korea"))
                                                .build()))
                        .role(MsgRole.ASSISTANT)
                        .build(),
                Msg.builder()
                        .name("system")
                        .content(
                                List.of(
                                        ToolResultBlock.builder()
                                                .id("1")
                                                .name("get_capital")
                                                .output(
                                                        List.of(
                                                                TextBlock.builder()
                                                                        .text(
                                                                                "The capital of"
                                                                                    + " South Korea"
                                                                                    + " is Seoul.")
                                                                        .build(),
                                                                ImageBlock.builder()
                                                                        .source(
                                                                                URLSource.builder()
                                                                                        .url(
                                                                                                imagePath)
                                                                                        .build())
                                                                        .build(),
                                                                AudioBlock.builder()
                                                                        .source(
                                                                                Base64Source
                                                                                        .builder()
                                                                                        .mediaType(
                                                                                                "audio/wav")
                                                                                        .data(
                                                                                                "ZmFrZSBhdWRpbyBjb250ZW50")
                                                                                        .build())
                                                                        .build()))
                                                .build()))
                        .role(MsgRole.SYSTEM)
                        .build(),
                Msg.builder()
                        .name("assistant")
                        .content(
                                List.of(
                                        TextBlock.builder()
                                                .text("The capital of South Korea is Seoul.")
                                                .build()))
                        .role(MsgRole.ASSISTANT)
                        .build());
    }

    /**
     * Expected JSON output for GeminiChatFormatter.
     * Represents the expected Gemini API format for: system + conversation + tools
     *
     * @return JSON string of expected output
     */
    public static String getGroundTruthChatJson() {
        return """
        [
            {
                "role": "user",
                "parts": [
                    {
                        "text": "You're a helpful assistant."
                    }
                ]
            },
            {
                "role": "user",
                "parts": [
                    {
                        "text": "What is the capital of France?"
                    },
                    {
                        "inline_data": {
                            "data": "ZmFrZSBpbWFnZSBjb250ZW50",
                            "mime_type": "image/png"
                        }
                    }
                ]
            },
            {
                "role": "model",
                "parts": [
                    {
                        "text": "The capital of France is Paris."
                    }
                ]
            },
            {
                "role": "user",
                "parts": [
                    {
                        "text": "What is the capital of Germany?"
                    },
                    {
                        "inline_data": {
                            "data": "ZmFrZSBhdWRpbyBjb250ZW50",
                            "mime_type": "audio/mp3"
                        }
                    }
                ]
            },
            {
                "role": "model",
                "parts": [
                    {
                        "text": "The capital of Germany is Berlin."
                    }
                ]
            },
            {
                "role": "user",
                "parts": [
                    {
                        "text": "What is the capital of Japan?"
                    }
                ]
            },
            {
                "role": "model",
                "parts": [
                    {
                        "function_call": {
                            "id": "1",
                            "name": "get_capital",
                            "args": {
                                "country": "Japan"
                            }
                        }
                    }
                ]
            },
            {
                "role": "user",
                "parts": [
                    {
                        "function_response": {
                            "id": "1",
                            "name": "get_capital",
                            "response": {
                                "output": "- The capital of Japan is Tokyo.\\n- The returned image can be found at: ./image.png\\n- The returned audio can be found at: /var/folders/gf/krg8x_ws409cpw_46b2s6rjc0000gn/T/tmpfymnv2w9.wav"
                            }
                        }
                    }
                ]
            },
            {
                "role": "model",
                "parts": [
                    {
                        "text": "The capital of Japan is Tokyo."
                    }
                ]
            }
        ]
        """;
    }

    /**
     * Expected JSON output for GeminiMultiAgentFormatter.
     * Represents the expected Gemini API format for: system + conversation + tools
     *
     * @return JSON string of expected output
     */
    public static String getGroundTruthMultiAgentJson() {
        return """
        [
            {
                "role": "user",
                "parts": [
                    {
                        "text": "You're a helpful assistant."
                    }
                ]
            },
            {
                "role": "user",
                "parts": [
                    {
                        "text": "# Conversation History\\nThe content between <history></history> tags contains your conversation history\\n<history>user: What is the capital of France?"
                    },
                    {
                        "inline_data": {
                            "data": "ZmFrZSBpbWFnZSBjb250ZW50",
                            "mime_type": "image/png"
                        }
                    },
                    {
                        "text": "assistant: The capital of France is Paris.\\nuser: What is the capital of Germany?"
                    },
                    {
                        "inline_data": {
                            "data": "ZmFrZSBhdWRpbyBjb250ZW50",
                            "mime_type": "audio/mp3"
                        }
                    },
                    {
                        "text": "assistant: The capital of Germany is Berlin.\\nuser: What is the capital of Japan?\\n</history>"
                    }
                ]
            },
            {
                "role": "model",
                "parts": [
                    {
                        "function_call": {
                            "id": "1",
                            "name": "get_capital",
                            "args": {
                                "country": "Japan"
                            }
                        }
                    }
                ]
            },
            {
                "role": "user",
                "parts": [
                    {
                        "function_response": {
                            "id": "1",
                            "name": "get_capital",
                            "response": {
                                "output": "- The capital of Japan is Tokyo.\\n- The returned image can be found at: ./image.png\\n- The returned audio can be found at: /var/folders/gf/krg8x_ws409cpw_46b2s6rjc0000gn/T/tmpfymnv2w9.wav"
                            }
                        }
                    }
                ]
            },
            {
                "role": "user",
                "parts": [
                    {
                        "text": "<history>assistant: The capital of Japan is Tokyo.\\n</history>"
                    }
                ]
            }
        ]
        """;
    }

    /**
     * Ground truth for GeminiMultiAgentFormatter with 2 rounds of conversation.
     * Expected output for: system + conversation + tools + conversation2 + tools2
     *
     * @return JSON string of expected output
     */
    public static String getGroundTruthMultiAgent2Json() {
        return """
        [
            {
                "role": "user",
                "parts": [
                    {
                        "text": "You're a helpful assistant."
                    }
                ]
            },
            {
                "role": "user",
                "parts": [
                    {
                        "text": "# Conversation History\\nThe content between <history></history> tags contains your conversation history\\n<history>user: What is the capital of France?"
                    },
                    {
                        "inline_data": {
                            "data": "ZmFrZSBpbWFnZSBjb250ZW50",
                            "mime_type": "image/png"
                        }
                    },
                    {
                        "text": "assistant: The capital of France is Paris.\\nuser: What is the capital of Germany?"
                    },
                    {
                        "inline_data": {
                            "data": "ZmFrZSBhdWRpbyBjb250ZW50",
                            "mime_type": "audio/mp3"
                        }
                    },
                    {
                        "text": "assistant: The capital of Germany is Berlin.\\nuser: What is the capital of Japan?\\n</history>"
                    }
                ]
            },
            {
                "role": "model",
                "parts": [
                    {
                        "function_call": {
                            "id": "1",
                            "name": "get_capital",
                            "args": {
                                "country": "Japan"
                            }
                        }
                    }
                ]
            },
            {
                "role": "user",
                "parts": [
                    {
                        "function_response": {
                            "id": "1",
                            "name": "get_capital",
                            "response": {
                                "output": "- The capital of Japan is Tokyo.\\n- The returned image can be found at: ./image.png\\n- The returned audio can be found at: /var/folders/gf/krg8x_ws409cpw_46b2s6rjc0000gn/T/tmpfymnv2w9.wav"
                            }
                        }
                    }
                ]
            },
            {
                "role": "user",
                "parts": [
                    {
                        "text": "<history>assistant: The capital of Japan is Tokyo.\\nuser: What is the capital of South Korea?\\n</history>"
                    }
                ]
            },
            {
                "role": "model",
                "parts": [
                    {
                        "function_call": {
                            "id": "1",
                            "name": "get_capital",
                            "args": {
                                "country": "South Korea"
                            }
                        }
                    }
                ]
            },
            {
                "role": "user",
                "parts": [
                    {
                        "function_response": {
                            "id": "1",
                            "name": "get_capital",
                            "response": {
                                "output": "- The capital of South Korea is Seoul.\\n- The returned image can be found at: ./image.png\\n- The returned audio can be found at: /var/folders/gf/krg8x_ws409cpw_46b2s6rjc0000gn/T/tmpfymnv2w9.wav"
                            }
                        }
                    }
                ]
            },
            {
                "role": "user",
                "parts": [
                    {
                        "text": "<history>assistant: The capital of South Korea is Seoul.\\n</history>"
                    }
                ]
            }
        ]
        """;
    }

    /**
     * Parse ground truth JSON string into list of maps.
     *
     * @param json JSON string
     * @return List of maps representing the expected output
     */
    public static List<Map<String, Object>> parseGroundTruth(String json) {
        try {
            return objectMapper.readValue(json, new TypeReference<List<Map<String, Object>>>() {});
        } catch (Exception e) {
            throw new RuntimeException("Failed to parse ground truth JSON", e);
        }
    }
}
