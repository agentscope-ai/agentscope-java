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
package io.agentscope.extensions.mongodb;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Recursively escapes and restores MongoDB field names inside arbitrary {@link Map} values.
 *
 * <p>MongoDB rejects {@code '.'} and a leading {@code '$'} in field names. Since state/BaseStore
 * values are caller-supplied {@code Map<String, Object>}, their keys can contain such characters
 * and would otherwise fail the write at encode time. This utility uses a backslash-prefix
 * encoding on write and its inverse on read so callers are not restricted by MongoDB naming rules.
 *
 * <p>The encoding is collision-free: {@code .} → {@code \E}, {@code $} → {@code \$}, {@code \0}
 * → {@code \N}, and a literal backslash → {@code \\}. Escaping the backslash itself is what
 * guarantees a bijective round-trip — a key that literally contains {@code \E} is stored as
 * {@code \\E} and restored to {@code \E}, never confused with an escaped dot.
 */
public final class MongoKeyEscaper {

    private static final char ESCAPE_PREFIX = '\\';
    private static final char DOT_CODE = 'E';
    private static final char DOLLAR_CODE = '$';
    private static final char NULL_CODE = 'N';
    private static final char BACKSLASH_CODE = '\\';

    private MongoKeyEscaper() {}

    /**
     * Escapes every map key recursively (nested maps and list elements included).
     *
     * @param map the map to escape; must not be {@code null}
     * @return a new map with escaped keys, sharing non-map leaf values with the input
     */
    public static Map<String, Object> escape(Map<String, Object> map) {
        Map<String, Object> result = new LinkedHashMap<>(map.size());
        for (Map.Entry<String, Object> entry : map.entrySet()) {
            result.put(escapeKey(entry.getKey()), escapeValue(entry.getValue()));
        }
        return result;
    }

    /**
     * Restores every map key recursively (nested maps and list elements included).
     *
     * @param map the map to unescape; must not be {@code null}
     * @return a new map with unescaped keys, sharing non-map leaf values with the input
     */
    public static Map<String, Object> unescape(Map<String, Object> map) {
        Map<String, Object> result = new LinkedHashMap<>(map.size());
        for (Map.Entry<String, Object> entry : map.entrySet()) {
            result.put(unescapeKey(entry.getKey()), unescapeValue(entry.getValue()));
        }
        return result;
    }

    @SuppressWarnings("unchecked")
    private static Object escapeValue(Object value) {
        if (value instanceof Map<?, ?> nested) {
            return escape((Map<String, Object>) nested);
        }
        if (value instanceof List<?> list) {
            List<Object> result = new ArrayList<>(list.size());
            for (Object item : list) {
                result.add(escapeValue(item));
            }
            return result;
        }
        return value;
    }

    @SuppressWarnings("unchecked")
    private static Object unescapeValue(Object value) {
        if (value instanceof Map<?, ?> nested) {
            return unescape((Map<String, Object>) nested);
        }
        if (value instanceof List<?> list) {
            List<Object> result = new ArrayList<>(list.size());
            for (Object item : list) {
                result.add(unescapeValue(item));
            }
            return result;
        }
        return value;
    }

    private static String escapeKey(String key) {
        if (key.indexOf('.') < 0
                && key.indexOf('$') < 0
                && key.indexOf('\0') < 0
                && key.indexOf(ESCAPE_PREFIX) < 0) {
            return key;
        }
        StringBuilder sb = new StringBuilder(key.length() + 4);
        for (int i = 0; i < key.length(); i++) {
            char c = key.charAt(i);
            if (c == '.') {
                sb.append(ESCAPE_PREFIX).append(DOT_CODE);
            } else if (c == '$') {
                sb.append(ESCAPE_PREFIX).append(DOLLAR_CODE);
            } else if (c == '\0') {
                sb.append(ESCAPE_PREFIX).append(NULL_CODE);
            } else if (c == ESCAPE_PREFIX) {
                sb.append(ESCAPE_PREFIX).append(BACKSLASH_CODE);
            } else {
                sb.append(c);
            }
        }
        return sb.toString();
    }

    private static String unescapeKey(String key) {
        if (key.indexOf(ESCAPE_PREFIX) < 0) {
            return key;
        }
        StringBuilder sb = new StringBuilder(key.length());
        for (int i = 0; i < key.length(); i++) {
            char c = key.charAt(i);
            if (c == ESCAPE_PREFIX && i + 1 < key.length()) {
                char next = key.charAt(i + 1);
                if (next == DOT_CODE) {
                    sb.append('.');
                } else if (next == DOLLAR_CODE) {
                    sb.append('$');
                } else if (next == NULL_CODE) {
                    sb.append('\0');
                } else if (next == BACKSLASH_CODE) {
                    sb.append(ESCAPE_PREFIX);
                } else {
                    // Unknown escape sequence (can only come from pre-escaping data); keep as-is.
                    sb.append(c).append(next);
                }
                i++;
            } else {
                sb.append(c);
            }
        }
        return sb.toString();
    }
}
