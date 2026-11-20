/*
 * Copyright 2024-2026 the original author or authors.
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

import io.agentscope.core.message.ContentBlock;
import io.agentscope.core.message.TextBlock;
import io.agentscope.core.message.ToolResultBlock;
import io.agentscope.core.util.JsonUtils;
import java.lang.reflect.Type;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Default implementation of ToolResultConverter.
 * Handles JSON serialization with schema information.
 *
 * <p>This converter:
 * <ul>
 *   <li>Converts null results to "null" text</li>
 *   <li>Converts void results to "(completed with no output)" text</li>
 *   <li>Passes through ToolResultBlock instances unchanged</li>
 *   <li>Wraps a returned ContentBlock, or a non-empty list of only ContentBlocks, unchanged</li>
 *   <li>Serializes objects to JSON with schema information</li>
 *   <li>Falls back to toString() if serialization fails</li>
 * </ul>
 *
 * <p>Block returns close the loop: the blocks a tool returns are the blocks its result carries.
 * The framework wraps them without reinterpretation — no JSON round trip, no re-typing — so what
 * the model formatters and protocol boundaries receive is exactly what the developer constructed.
 * Developers stay in full control of a tool's blocks.
 */
public class DefaultToolResultConverter implements ToolResultConverter {

    private static final Logger logger = LoggerFactory.getLogger(DefaultToolResultConverter.class);

    @Override
    public ToolResultBlock convert(Object result, Type returnType) {
        if (returnType == Void.TYPE || returnType == Void.class) {
            return handleVoid();
        }

        if (result == null) {
            return handleNull();
        }

        // If result is already a ToolResultBlock, return it directly
        if (result instanceof ToolResultBlock) {
            return (ToolResultBlock) result;
        }

        // A returned ContentBlock (or a list of them) is the tool's typed output, such as a
        // generated image; wrap it as the result's output instead of JSON-serializing to text
        if (result instanceof ContentBlock block) {
            return ToolResultBlock.of(List.of(block));
        }
        if (isContentBlockList(result)) {
            List<ContentBlock> blocks =
                    ((List<?>) result).stream().map(ContentBlock.class::cast).toList();
            return ToolResultBlock.of(blocks);
        }

        return serialize(result, returnType);
    }

    /**
     * Determines whether the result is a non-empty list whose elements are all {@link
     * ContentBlock}s and none is itself a {@link ToolResultBlock}: a nested tool result would
     * embed result metadata inside an output, so such lists keep the JSON serialization path
     * alongside mixed or empty lists.
     *
     * @param result the tool result value
     * @return true when the list qualifies for block wrapping
     */
    private static boolean isContentBlockList(Object result) {
        if (!(result instanceof List<?> list) || list.isEmpty()) {
            return false;
        }
        for (Object element : list) {
            if (!(element instanceof ContentBlock) || element instanceof ToolResultBlock) {
                return false;
            }
        }
        return true;
    }

    /**
     * Handle null result.
     *
     * @return ToolResultBlock with "null" text
     */
    protected ToolResultBlock handleNull() {
        return ToolResultBlock.of(List.of(TextBlock.builder().text("null").build()));
    }

    /**
     * Handle void return type. The acknowledgement states that the tool completed and had no
     * output, so a successful no-return call is never mistaken for a missing tool result.
     *
     * @return ToolResultBlock with "(completed with no output)" text
     */
    protected ToolResultBlock handleVoid() {
        return ToolResultBlock.of(
                List.of(TextBlock.builder().text("(completed with no output)").build()));
    }

    /**
     * Serialize result to JSON string with schema.
     *
     * @param result the result to serialize
     * @param returnType the return type
     * @return ToolResultBlock with JSON string and schema
     */
    protected ToolResultBlock serialize(Object result, Type returnType) {
        try {
            String json = JsonUtils.getJsonCodec().toJson(result);
            return ToolResultBlock.of(List.of(TextBlock.builder().text(json).build()));
        } catch (Exception e) {
            // Fallback to string representation
            logger.warn("Failed to serialize result to JSON, falling back to toString()", e);
            return ToolResultBlock.of(
                    List.of(TextBlock.builder().text(String.valueOf(result)).build()));
        }
    }
}
