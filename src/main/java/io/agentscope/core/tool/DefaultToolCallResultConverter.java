/*
 * Copyright 2023-2025 the original author or authors.
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
 * Default implementation of ToolCallResultConverter that provides basic JSON parsing
 * for tool call results.
 *
 * The converter attempts to detect if the result is a JSON string and parse it
 * into a Map or List. If parsing fails or the result is not a JSON string,
 * it returns the result as a plain string.
 *
 * This implementation is suitable for most common use cases where tool results
 * are either plain text or simple JSON structures.
 */
public class DefaultToolCallResultConverter implements ToolCallResultConverter {

    private final ObjectMapper objectMapper;

    public DefaultToolCallResultConverter() {
        this.objectMapper = new ObjectMapper();
    }

    /**
     * Convert the tool call result to a string representation.
     *
     * @param result the tool call result
     * @param returnType the return type (optional, can be null)
     * @return string representation of the result
     */
    public ToolResponse convert(Object result, Type returnType) {
        if (result == null) {
            return new ToolResponse(List.of(TextBlock.builder().text("null").build()));
        }

        if (returnType != null && returnType == Void.TYPE) {
            return new ToolResponse(List.of(TextBlock.builder().text("Done").build()));
        }

        try {
            return new ToolResponse(
                    List.of(
                            TextBlock.builder()
                                    .text(objectMapper.writeValueAsString(result))
                                    .build()));
        } catch (Exception e) {
            // Fallback to string representation
            return new ToolResponse(
                    List.of(TextBlock.builder().text(String.valueOf(result)).build()));
        }
    }
}
