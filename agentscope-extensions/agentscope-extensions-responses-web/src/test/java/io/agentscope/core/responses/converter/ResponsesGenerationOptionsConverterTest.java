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

import com.fasterxml.jackson.databind.ObjectMapper;
import io.agentscope.core.model.GenerateOptions;
import io.agentscope.core.model.ToolChoice;
import io.agentscope.core.responses.model.ResponsesRequest;
import org.junit.jupiter.api.Test;

class ResponsesGenerationOptionsConverterTest {

    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();

    private final ResponsesGenerationOptionsConverter converter =
            new ResponsesGenerationOptionsConverter(new ResponsesToolConverter());

    @Test
    void shouldReturnNullWhenNoOptionsArePresent() {
        assertNull(converter.convert(new ResponsesRequest()));
    }

    @Test
    void shouldMapResponsesOptionsToGenerateOptions() throws Exception {
        ResponsesRequest request =
                OBJECT_MAPPER.readValue(
                        """
                        {
                          "input": "Hello",
                          "model": "gpt-4.1-mini",
                          "stream": true,
                          "temperature": 0.2,
                          "top_p": 0.9,
                          "max_output_tokens": 128,
                          "reasoning": {"effort": "low"},
                          "tool_choice": {
                            "type": "function",
                            "function": {"name": "get_weather"}
                          }
                        }
                        """,
                        ResponsesRequest.class);

        GenerateOptions options = converter.convert(request);

        assertEquals("gpt-4.1-mini", options.getModelName());
        assertEquals(true, options.getStream());
        assertEquals(0.2, options.getTemperature());
        assertEquals(0.9, options.getTopP());
        assertEquals(128, options.getMaxTokens());
        assertEquals("low", options.getReasoningEffort());
        ToolChoice.Specific toolChoice =
                assertInstanceOf(ToolChoice.Specific.class, options.getToolChoice());
        assertEquals("get_weather", toolChoice.toolName());
    }
}
