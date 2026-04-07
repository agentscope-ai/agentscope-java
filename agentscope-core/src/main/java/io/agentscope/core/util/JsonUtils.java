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

package io.agentscope.core.util;

import io.agentscope.core.message.ToolUseBlock;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Utility class for accessing the global {@link JsonCodec} instance.
 *
 * <p>This class provides a centralized way to access JSON serialization/deserialization
 * functionality throughout the framework. By default, it uses {@link JacksonJsonCodec},
 * but users can replace it with a custom implementation.
 *
 * <p>Usage:
 * <pre>{@code
 * // Basic usage
 * JsonUtils.getJsonCodec().toJson(obj);
 * JsonUtils.getJsonCodec().fromJson(json, MyClass.class);
 *
 * // Cache reference for frequent calls
 * JsonCodec codec = JsonUtils.getJsonCodec();
 * codec.toJson(obj1);
 * codec.toJson(obj2);
 *
 * // Replace with custom implementation
 * JsonUtils.setJsonCodec(new MyCustomJsonCodec());
 * }</pre>
 *
 * @see JsonCodec
 * @see JacksonJsonCodec
 */
public final class JsonUtils {

    private static final Logger log = LoggerFactory.getLogger(JsonUtils.class);

    private static volatile JsonCodec codec = new JacksonJsonCodec();

    private JsonUtils() {
        // Utility class, no instantiation
    }

    /**
     * Get the global JsonCodec instance.
     *
     * @return the JsonCodec instance
     */
    public static JsonCodec getJsonCodec() {
        return codec;
    }

    /**
     * Set a custom JsonCodec implementation.
     *
     * <p>This allows users to replace the default Jackson-based implementation
     * with their own implementation (e.g., Gson, Fastjson, etc.).
     *
     * @param jsonCodec the custom JsonCodec implementation
     * @throws IllegalArgumentException if jsonCodec is null
     */
    public static void setJsonCodec(JsonCodec jsonCodec) {
        if (jsonCodec == null) {
            throw new IllegalArgumentException("JsonCodec cannot be null");
        }
        codec = jsonCodec;
    }

    /**
     * Reset to the default JacksonJsonCodec implementation.
     *
     * <p>This is useful for testing or when you want to restore the default behavior.
     */
    public static void resetToDefault() {
        codec = new JacksonJsonCodec();
    }

    /**
     * Check whether the given string is a valid JSON object (i.e. starts with '{' and
     * can be parsed into a {@link Map}).
     *
     * <p>Tool call {@code arguments} must be JSON objects, so plain JSON values like
     * {@code null}, arrays, or strings are rejected.
     *
     * @param str the string to validate
     * @return {@code true} if {@code str} is a non-null, parseable JSON object
     */
    @SuppressWarnings("unchecked")
    public static boolean isValidJsonObject(String str) {
        if (str == null || str.isEmpty()) {
            return false;
        }
        try {
            Map<String, Object> parsed = codec.fromJson(str, Map.class);
            return parsed != null;
        } catch (Exception e) {
            return false;
        }
    }

    /**
     * Resolve the arguments JSON string from a {@link ToolUseBlock}, ensuring the
     * result is always a valid JSON object.
     *
     * <p>Resolution order:
     * <ol>
     *   <li>Use {@link ToolUseBlock#getContent()} if it is a valid JSON object</li>
     *   <li>Serialize {@link ToolUseBlock#getInput()} via {@link JsonCodec#toJson}</li>
     *   <li>Fall back to {@code "{}"}</li>
     * </ol>
     *
     * <p>This prevents sending malformed JSON (e.g. from interrupted streaming) as
     * tool call arguments, which would cause model APIs to reject the request.
     *
     * @param toolUse the tool use block
     * @return a valid JSON object string representing the tool call arguments
     */
    public static String resolveToolCallArgsJson(ToolUseBlock toolUse) {
        String content = toolUse.getContent();
        if (content != null && !content.isEmpty()) {
            if (isValidJsonObject(content)) {
                return content;
            }
            log.warn(
                    "Invalid JSON in tool call content for '{}', falling back to input"
                            + " serialization",
                    toolUse.getName());
        }

        try {
            return codec.toJson(toolUse.getInput());
        } catch (Exception e) {
            log.warn("Failed to serialize tool call arguments: {}", e.getMessage());
            return "{}";
        }
    }
}
