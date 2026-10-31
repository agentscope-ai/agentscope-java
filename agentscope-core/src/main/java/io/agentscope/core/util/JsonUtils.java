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

import com.fasterxml.jackson.core.JsonFactory;
import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.core.JsonToken;
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
    private static final JsonFactory JSON_FACTORY = new JsonFactory();

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
     * Check whether the entire string contains exactly one complete JSON object, allowing
     * trailing whitespace but no additional values or malformed suffix.
     *
     * <p>Tool call {@code arguments} must be JSON objects, so plain JSON values like
     * {@code null}, arrays, or strings are rejected.
     *
     * @param str the string to validate
     * @return {@code true} if {@code str} is a non-null, parseable JSON object
     */
    public static boolean isValidJsonObject(String str) {
        if (str == null || str.isEmpty()) {
            return false;
        }
        try (JsonParser parser = JSON_FACTORY.createParser(str)) {
            if (parser.nextToken() != JsonToken.START_OBJECT) {
                return false;
            }
            parser.skipChildren();
            return parser.nextToken() == null;
        } catch (Exception e) {
            return false;
        }
    }

    /**
     * Resolve the arguments JSON string from a {@link ToolUseBlock}, ensuring the
     * result is always a valid JSON object.
     *
     * <p>Malformed-argument blocks (see {@link ToolUseBlock#hasMalformedArguments()}) are
     * resolved before this order and never fall back to the input map: valid content is kept,
     * anything else resolves to {@code "{}"} — a call the agent loop refuses must not execute
     * through direct executor paths.
     *
     * <p>Resolution order:
     * <ol>
     *   <li>Use {@link ToolUseBlock#getContent()} if it is a valid JSON object, unless it is an
     *       empty object while {@link ToolUseBlock#getInput()} carries arguments — a fabricated
     *       or legacy {@code "{}"} must not mask intact parsed arguments</li>
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
        boolean inputHasArgs = toolUse.getInput() != null && !toolUse.getInput().isEmpty();
        if (toolUse.hasMalformedArguments()) {
            // Truncated or corrupt raw content marked by the accumulator: the input map may
            // hold merged fragments that even look complete, so falling back to it would let
            // a call the agent loop already refuses execute through direct Toolkit/executor
            // paths. Keep valid content, sanitize anything else to the empty object.
            return content != null && isValidJsonObject(content) ? content : "{}";
        }
        if (content != null && !content.isEmpty()) {
            if (isValidJsonObject(content)) {
                if (!isEmptyJsonObject(content) || !inputHasArgs) {
                    return content;
                }
                // An empty object with intact parsed arguments beside it: legacy blocks
                // persisted by the accumulator's old fabrication, or directly constructed
                // blocks. Log nothing — this is a recovery, not a malformed payload.
            } else {
                log.warn(
                        "Invalid JSON in tool call content for '{}', falling back to input"
                                + " serialization",
                        toolUse.getName());
            }
        }

        return toJsonFallbackEmptyObject(toolUse.getInput());
    }

    /**
     * Whether the JSON object text carries no members, allowing whitespace anywhere (an empty
     * object is the only JSON object whose text consists of braces and whitespace).
     */
    private static boolean isEmptyJsonObject(String content) {
        // An empty object's text is braces and whitespace only; scanning avoids a regex-driven
        // full-string copy per resolved tool call on the request-build hot path.
        int i = 0;
        int len = content.length();
        while (i < len && Character.isWhitespace(content.charAt(i))) {
            i++;
        }
        if (i >= len || content.charAt(i) != '{') {
            return false;
        }
        i++;
        while (i < len && Character.isWhitespace(content.charAt(i))) {
            i++;
        }
        if (i >= len || content.charAt(i) != '}') {
            return false;
        }
        i++;
        while (i < len && Character.isWhitespace(content.charAt(i))) {
            i++;
        }
        return i == len;
    }

    /**
     * Serializes tool-call arguments, falling back to {@code "{}"} when the map cannot be
     * serialized (pluggable codec failures, unserializable values). Consumers resolve
     * arguments content-first, so the fallback makes them see empty arguments; execution
     * of a surviving call reads the input map (ToolExecutor merges it), and the failure
     * never propagates out of the reply stream.
     *
     * @param args the parsed tool call arguments
     * @return a valid JSON object string representing the arguments
     */
    public static String toJsonFallbackEmptyObject(Map<String, Object> args) {
        try {
            return codec.toJson(args);
        } catch (Exception e) {
            log.warn("Failed to serialize tool call arguments: {}", e.getMessage());
            return "{}";
        }
    }
}
