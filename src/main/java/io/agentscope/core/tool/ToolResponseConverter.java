/*
 * Copyright 2024-2025 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * You may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      https://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package io.agentscope.core.tool;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.agentscope.core.message.TextBlock;
import java.lang.reflect.Type;
import java.util.List;

/**
 * Converts tool method return values to ToolResponse.
 * This class handles serialization of various return types into a format
 * suitable for LLM consumption.
 */
class ToolResponseConverter {

    private final ObjectMapper objectMapper;

    ToolResponseConverter(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    /**
     * Convert tool call result to ToolResponse.
     *
     * @param result the tool call result
     * @param returnType the return type of the tool method
     * @return ToolResponse containing the converted result
     */
    ToolResponse convert(Object result, Type returnType) {
        if (result == null) {
            return handleNull();
        }

        if (returnType != null && returnType == Void.TYPE) {
            return handleVoid();
        }

        return serialize(result);
    }

    /**
     * Handle null result.
     *
     * @return ToolResponse with "null" text
     */
    private ToolResponse handleNull() {
        return new ToolResponse(List.of(TextBlock.builder().text("null").build()));
    }

    /**
     * Handle void return type.
     *
     * @return ToolResponse with "Done" text
     */
    private ToolResponse handleVoid() {
        return new ToolResponse(List.of(TextBlock.builder().text("Done").build()));
    }

    /**
     * Serialize result to JSON string.
     *
     * @param result the result to serialize
     * @return ToolResponse with JSON string
     */
    private ToolResponse serialize(Object result) {
        try {
            String json = objectMapper.writeValueAsString(result);
            return new ToolResponse(List.of(TextBlock.builder().text(json).build()));
        } catch (Exception e) {
            // Fallback to string representation
            return new ToolResponse(
                    List.of(TextBlock.builder().text(String.valueOf(result)).build()));
        }
    }
}
