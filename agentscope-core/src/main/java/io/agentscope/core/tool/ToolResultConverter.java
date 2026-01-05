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
package io.agentscope.core.tool;

import io.agentscope.core.message.ToolResultBlock;
import java.lang.reflect.Type;

/**
 * Converts tool method return values to ToolResultBlock.
 * Custom implementations can override conversion logic for specific tools.
 *
 * <p>This interface allows users to customize how tool results are converted and presented to LLMs.
 * Implementations can control JSON serialization, add metadata, filter sensitive data, or compress
 * large outputs.
 *
 * <p><b>Usage Example:</b>
 * <pre>{@code
 * public class CustomConverter implements ToolResultConverter {
 *     @Override
 *     public ToolResultBlock convert(Object result, Type returnType) {
 *         // Custom conversion logic
 *         return ToolResultBlock.of(...);
 *     }
 * }
 *
 * @Tool(
 *     name = "my_tool",
 *     converter = CustomConverter.class
 * )
 * public String myTool() { ... }
 * }</pre>
 *
 * @see DefaultToolResultConverter
 */
public interface ToolResultConverter {

    /**
     * Convert tool call result to ToolResultBlock.
     *
     * @param result the tool call result (may be null)
     * @param returnType the return type of the tool method (may be null)
     * @return ToolResultBlock containing the converted result (never null)
     */
    ToolResultBlock convert(Object result, Type returnType);
}
