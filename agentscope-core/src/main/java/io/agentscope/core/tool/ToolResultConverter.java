/*
 * Copyright 2024-2025 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * You may not use this file except in compliance with the License.
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
package io.agentscope.core.tool;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.agentscope.core.message.TextBlock;
import io.agentscope.core.message.ToolResultBlock;
import io.agentscope.core.util.JsonSchemaUtils;

import java.lang.reflect.Type;
import java.util.List;
import java.util.Map;

/**
 * Converts tool method return values to ToolResultBlock.
 * This class handles serialization of various return types into a format
 * suitable for LLM consumption.
 */
class ToolResultConverter {

    private final ObjectMapper objectMapper;

    ToolResultConverter(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    /**
     * Convert tool call result to ToolResultBlock.
     *
     * @param result the tool call result
     * @param returnType the return type of the tool method
     * @return ToolResultBlock containing the converted result
     */
    ToolResultBlock convert(Object result, Type returnType) {
        if (result == null) {
            return handleNull();
        }

        if (returnType != null && returnType == Void.TYPE) {
            return handleVoid();
        }

        // If result is already a ToolResultBlock, return it directly
        if (result instanceof ToolResultBlock) {
            return (ToolResultBlock) result;
        }

        return serialize(result, returnType);
    }

    /**
     * Handle null result.
     *
     * @return ToolResultBlock with "null" text
     */
    private ToolResultBlock handleNull() {
        return ToolResultBlock.of(List.of(TextBlock.builder().text("null").build()));
    }

    /**
     * Handle void return type.
     *
     * @return ToolResultBlock with "Done" text
     */
    private ToolResultBlock handleVoid() {
        return ToolResultBlock.of(List.of(TextBlock.builder().text("Done").build()));
    }

    /**
     * Serialize result to JSON string.
     *
     * @param result the result to serialize
     * @return ToolResultBlock with JSON string
     */
    private ToolResultBlock serialize(Object result, Type returnType) {
        try {
            String json = objectMapper.writeValueAsString(result);
            Map<String, Object> resultJsonSchema = JsonSchemaUtils.generateSchemaFromType(returnType);
			String resultSchema = String.format("Result JSON Schema: %s", resultJsonSchema);
			return ToolResultBlock.of(List.of(TextBlock.builder().text(json).build(),
					TextBlock.builder().text(resultSchema).build()));
        } catch (Exception e) {
            // Fallback to string representation
            return ToolResultBlock.of(
                    List.of(TextBlock.builder().text(String.valueOf(result)).build()));
        }
    }
}
