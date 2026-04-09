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
package io.agentscope.core.util;

import java.util.Collections;
import java.util.Map;

/** Utility helpers for normalizing tool-call arguments stored as JSON strings. */
public final class ToolCallJsonUtils {

    private ToolCallJsonUtils() {}

    /**
     * Parse a raw JSON object string into a map.
     *
     * @param rawJson raw JSON object string
     * @return parsed map, or empty map when the payload is blank or invalid
     */
    public static Map<String, Object> parseJsonObjectOrEmpty(String rawJson) {
        if (rawJson == null || rawJson.isBlank()) {
            return Collections.emptyMap();
        }

        try {
            @SuppressWarnings("unchecked")
            Map<String, Object> parsed = JsonUtils.getJsonCodec().fromJson(rawJson, Map.class);
            return parsed != null ? parsed : Collections.emptyMap();
        } catch (Exception ignored) {
            return Collections.emptyMap();
        }
    }

    /**
     * Return a provider-safe JSON object string for tool call arguments.
     *
     * <p>Valid raw JSON is preserved as-is. Invalid or partial JSON falls back to serializing the
     * structured input map, and ultimately to an empty JSON object.
     *
     * @param rawJson raw tool-call content accumulated from streaming chunks
     * @param input structured tool-call input map
     * @return safe JSON object string for downstream provider formatters
     */
    public static String sanitizeArgumentsJson(String rawJson, Map<String, Object> input) {
        if (isValidJsonObject(rawJson)) {
            return rawJson;
        }
        return serializeInputOrEmpty(input);
    }

    /**
     * Check whether a raw tool-call payload is a valid JSON object.
     *
     * @param rawJson raw tool-call content
     * @return true when the payload parses as a JSON object
     */
    public static boolean isValidJsonObject(String rawJson) {
        return !parseJsonObjectOrEmpty(rawJson).isEmpty()
                || (rawJson != null && "{}".equals(rawJson.trim()));
    }

    private static String serializeInputOrEmpty(Map<String, Object> input) {
        if (input == null || input.isEmpty()) {
            return "{}";
        }

        try {
            return JsonUtils.getJsonCodec().toJson(input);
        } catch (Exception ignored) {
            return "{}";
        }
    }
}
