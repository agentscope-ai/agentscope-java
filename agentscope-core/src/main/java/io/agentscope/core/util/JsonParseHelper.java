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

import java.util.Map;

/**
 * Helper for parsing JSON that may contain invalid characters (e.g. literal newlines inside
 * string values). LLMs sometimes output pretty-printed or multi-line strings which are not
 * valid JSON; this helper allows best-effort recovery.
 */
public final class JsonParseHelper {

    private JsonParseHelper() {}

    /**
     * Parse a JSON string into a map, with fallback to sanitizing literal newlines inside
     * strings when the first attempt fails. Use for tool input JSON that may contain
     * multi-line content (e.g. HTML).
     *
     * @param json JSON string (e.g. tool call arguments)
     * @return parsed map, or null if parsing failed even after sanitization
     */
    @SuppressWarnings("unchecked")
    public static Map<String, Object> parseMapWithNewlineFallback(String json) {
        if (json == null || json.isEmpty()) {
            return null;
        }
        try {
            Object parsed = JsonUtils.getJsonCodec().fromJson(json, Map.class);
            return parsed != null ? (Map<String, Object>) parsed : null;
        } catch (Exception e) {
            String sanitized = escapeNewlinesInJsonStrings(json);
            try {
                Object parsed = JsonUtils.getJsonCodec().fromJson(sanitized, Map.class);
                return parsed != null ? (Map<String, Object>) parsed : null;
            } catch (Exception e2) {
                return null;
            }
        }
    }

    /**
     * Sanitize a JSON string by escaping literal newlines and carriage returns inside
     * double-quoted string values. Standard JSON does not allow unescaped newlines in strings;
     * when an LLM returns tool arguments with multi-line content (e.g. HTML with newlines),
     * parsing fails. This method makes such content parseable.
     *
     * @param raw non-null JSON-like string
     * @return new string with \n and \r inside quoted strings replaced by \\n and \\r
     */
    public static String escapeNewlinesInJsonStrings(String raw) {
        if (raw == null || raw.isEmpty()) {
            return raw;
        }
        StringBuilder sb = new StringBuilder(raw.length() * 2);
        boolean inString = false;
        boolean escape = false;
        for (int i = 0; i < raw.length(); i++) {
            char c = raw.charAt(i);
            if (escape) {
                sb.append(c);
                escape = false;
                continue;
            }
            if (c == '\\') {
                sb.append(c);
                escape = true;
                continue;
            }
            if (c == '"') {
                inString = !inString;
                sb.append(c);
                continue;
            }
            if (inString) {
                if (c == '\n') {
                    sb.append("\\n");
                } else if (c == '\r') {
                    sb.append("\\r");
                } else {
                    sb.append(c);
                }
            } else {
                sb.append(c);
            }
        }
        return sb.toString();
    }
}
