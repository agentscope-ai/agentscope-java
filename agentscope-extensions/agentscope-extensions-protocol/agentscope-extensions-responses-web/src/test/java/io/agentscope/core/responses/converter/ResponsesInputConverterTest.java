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
package io.agentscope.core.responses.converter;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.core.JsonProcessingException;
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
import io.agentscope.core.responses.model.ResponsesRequest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class ResponsesInputConverterTest {

    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();

    private ResponsesInputConverter converter;

    @BeforeEach
    void setUp() {
        converter = new ResponsesInputConverter();
    }

    @Test
    void shouldConvertStringInputAndInstructions() throws Exception {
        ResponsesConversionResult result =
                converter.convert(
                        request(
                                """
                                {
                                  "input": "Hello",
                                  "instructions": "Be concise."
                                }
                                """));

        assertEquals("text", result.textFormatType());
        assertNull(result.structuredOutputSchema());
        assertEquals(1, result.messages().size());
        assertEquals(MsgRole.USER, result.messages().get(0).getRole());
        assertEquals("Hello", result.messages().get(0).getTextContent());
        assertEquals("Be concise.", result.systemFragments().get(0));
    }

    @Test
    void shouldConvertMessagesAndMultimodalContent() throws Exception {
        ResponsesConversionResult result =
                converter.convert(
                        request(
                                """
                                {
                                  "input": [
                                    {"role": "system", "content": "System rules"},
                                    {"role": "developer", "content": [{"type": "input_text", "text": "Developer rules"}]},
                                    {
                                      "role": "user",
                                      "content": [
                                        {"type": "input_text", "text": "Describe this"},
                                        {"type": "input_image", "image_url": "https://example.com/cat.png"},
                                        {"type": "input_image", "image_url": "data:image/png;base64,aGVsbG8="}
                                      ]
                                    }
                                  ]
                                }
                                """));

        assertEquals(1, result.messages().size());
        assertEquals(2, result.systemFragments().size());
        assertEquals("System rules", result.systemFragments().get(0));
        assertEquals("Developer rules", result.systemFragments().get(1));

        Msg userMessage = result.messages().get(0);
        assertEquals(MsgRole.USER, userMessage.getRole());
        assertEquals("Describe this", ((TextBlock) userMessage.getContent().get(0)).getText());

        ImageBlock urlImage = assertInstanceOf(ImageBlock.class, userMessage.getContent().get(1));
        URLSource urlSource = assertInstanceOf(URLSource.class, urlImage.getSource());
        assertEquals("https://example.com/cat.png", urlSource.getUrl());

        ImageBlock dataImage = assertInstanceOf(ImageBlock.class, userMessage.getContent().get(2));
        Base64Source base64Source = assertInstanceOf(Base64Source.class, dataImage.getSource());
        assertEquals("image/png", base64Source.getMediaType());
        assertEquals("aGVsbG8=", base64Source.getData());
    }

    @Test
    void shouldConvertFunctionCallAndFunctionCallOutput() throws Exception {
        ResponsesConversionResult result =
                converter.convert(
                        request(
                                """
                                {
                                  "input": [
                                    {
                                      "type": "function_call",
                                      "call_id": "call_123",
                                      "name": "get_weather",
                                      "arguments": {"city": "Hangzhou"}
                                    },
                                    {
                                      "type": "function_call_output",
                                      "call_id": "call_123",
                                      "output": [{"type": "output_text", "text": "Sunny"}]
                                    }
                                  ]
                                }
                                """));

        assertEquals(2, result.messages().size());

        ToolUseBlock toolUse =
                assertInstanceOf(ToolUseBlock.class, result.messages().get(0).getContent().get(0));
        assertEquals("call_123", toolUse.getId());
        assertEquals("get_weather", toolUse.getName());
        assertEquals("Hangzhou", toolUse.getInput().get("city"));
        assertEquals("{\"city\":\"Hangzhou\"}", toolUse.getContent());

        ToolResultBlock toolResult =
                assertInstanceOf(
                        ToolResultBlock.class, result.messages().get(1).getContent().get(0));
        assertEquals("call_123", toolResult.getId());
        assertEquals("Sunny", ((TextBlock) toolResult.getOutput().get(0)).getText());
    }

    @Test
    void shouldFallbackToEmptyMapForMalformedFunctionArguments() throws Exception {
        ResponsesConversionResult result =
                converter.convert(
                        request(
                                """
                                {
                                  "input": [{
                                    "type": "function_call",
                                    "call_id": "call_bad",
                                    "name": "broken_tool",
                                    "arguments": "{not-json"
                                  }]
                                }
                                """));

        ToolUseBlock toolUse =
                assertInstanceOf(ToolUseBlock.class, result.messages().get(0).getContent().get(0));
        assertTrue(toolUse.getInput().isEmpty());
        assertEquals("{not-json", toolUse.getContent());
    }

    @Test
    void shouldExposeJsonSchemaFormat() throws Exception {
        ResponsesConversionResult result =
                converter.convert(
                        request(
                                """
                                {
                                  "input": "Return JSON",
                                  "text": {
                                    "format": {
                                      "type": "json_schema",
                                      "schema": {
                                        "type": "object",
                                        "properties": {
                                          "answer": {"type": "string"}
                                        },
                                        "required": ["answer"]
                                      }
                                    }
                                  }
                                }
                                """));

        assertEquals("json_schema", result.textFormatType());
        assertTrue(result.structuredOutputSchema().isObject());
        assertEquals("object", result.structuredOutputSchema().get("type").asText());
    }

    @Test
    void shouldAcceptStatefulAndAdditionalParameters() throws Exception {
        ResponsesRequest request =
                request(
                        """
                        {
                          "input": "Hello",
                          "previous_response_id": "resp_old",
                          "conversation": "conv_123",
                          "background": true,
                          "store": true,
                          "include": ["reasoning.encrypted_content"]
                        }
                        """);

        ResponsesConversionResult result = converter.convert(request);

        assertEquals(1, result.messages().size());
        assertEquals("resp_old", request.getPreviousResponseId());
        assertEquals("conv_123", request.getConversation());
        assertTrue(request.getAdditionalFields().containsKey("include"));
    }

    @Test
    void shouldAcceptFileAudioAndOpaqueOfficialItems() throws Exception {
        ResponsesConversionResult result =
                converter.convert(
                        request(
                                """
                                {
                                  "input": [
                                    {
                                      "role": "user",
                                      "content": [
                                        {"type": "input_file", "file_id": "file_123"},
                                        {"type": "input_audio", "data": "aGVsbG8=", "format": "wav"}
                                      ]
                                    },
                                    {
                                      "type": "reasoning",
                                      "summary": [{"type": "summary_text", "text": "Used cached context"}]
                                    }
                                  ]
                                }
                                """));

        assertEquals(2, result.messages().size());
        Msg userMessage = result.messages().get(0);
        assertTrue(((TextBlock) userMessage.getContent().get(0)).getText().contains("file_123"));
        AudioBlock audio = assertInstanceOf(AudioBlock.class, userMessage.getContent().get(1));
        Base64Source audioSource = assertInstanceOf(Base64Source.class, audio.getSource());
        assertEquals("audio/wav", audioSource.getMediaType());
        assertEquals(MsgRole.ASSISTANT, result.messages().get(1).getRole());
        assertTrue(result.messages().get(1).getTextContent().contains("reasoning"));
    }

    private ResponsesRequest request(String json) throws JsonProcessingException {
        return OBJECT_MAPPER.readValue(json, ResponsesRequest.class);
    }
}
