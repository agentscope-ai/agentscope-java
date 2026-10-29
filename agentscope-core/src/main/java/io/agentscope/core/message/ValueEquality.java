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
package io.agentscope.core.message;

import java.lang.reflect.Array;
import java.util.Arrays;
import java.util.Base64;
import java.util.List;
import java.util.Map;

/**
 * Content-based equality for the values carried in message metadata maps.
 *
 * <p>{@link Map#equals(Object)} compares values with {@link Object#equals(Object)}, which compares
 * arrays by identity. Provider metadata stores binary payloads as arrays — Gemini's {@code
 * thoughtSignature} is a {@code byte[]} — so two messages that carry the same signature would
 * compare unequal and look modified to everyone watching for changes, including the state stores
 * that decide between an incremental append and a full rewrite.
 *
 * <p>The helpers recurse into arrays, maps and lists so equality is decided by content. Scalars
 * keep their own {@code equals} and {@code hashCode}, which keeps the result consistent with the
 * contract of the surrounding map.
 *
 * <p>Binary payloads survive a JSON persistence round-trip: Jackson serialises {@code byte[]} as
 * a Base64 {@code String}, and on deserialization into {@code Map<String, Object>} the value comes
 * back as a {@code String} rather than a {@code byte[]}. The helpers bridge that gap by treating
 * a {@code byte[]} and its Base64 {@code String} encoding as equal, so a message rebuilt from
 * storage compares equal to the message that was originally saved.
 */
final class ValueEquality {

    private ValueEquality() {
        // Utility class, prevent instantiation
    }

    /** Returns {@code true} when both arguments hold the same content. */
    static boolean equal(Object left, Object right) {
        if (left == right) {
            return true;
        }
        if (left == null || right == null) {
            return false;
        }
        if (left.getClass().isArray() && right.getClass().isArray()) {
            int length = Array.getLength(left);
            if (length != Array.getLength(right)) {
                return false;
            }
            for (int i = 0; i < length; i++) {
                if (!equal(Array.get(left, i), Array.get(right, i))) {
                    return false;
                }
            }
            return true;
        }
        // Jackson serialises byte[] as a Base64 String; after a JSON round-trip the value
        // comes back as a String, so bridge the two forms here.
        if (left instanceof byte[] leftBytes && right instanceof String rightStr) {
            return bytesEqualBase64String(leftBytes, rightStr);
        }
        if (left instanceof String leftStr && right instanceof byte[] rightBytes) {
            return bytesEqualBase64String(rightBytes, leftStr);
        }
        if (left instanceof Map<?, ?> leftMap && right instanceof Map<?, ?> rightMap) {
            return mapsEqual(leftMap, rightMap);
        }
        if (left instanceof List<?> leftList && right instanceof List<?> rightList) {
            return listsEqual(leftList, rightList);
        }
        return left.equals(right);
    }

    /** Compares two maps entry by entry, recursing into array values. */
    static boolean mapsEqual(Map<?, ?> left, Map<?, ?> right) {
        if (left == right) {
            return true;
        }
        if (left.size() != right.size()) {
            return false;
        }
        for (Map.Entry<?, ?> entry : left.entrySet()) {
            Object key = entry.getKey();
            if (!right.containsKey(key)) {
                return false;
            }
            if (!equal(entry.getValue(), right.get(key))) {
                return false;
            }
        }
        return true;
    }

    /** Hashes a value, recursing into arrays, maps and lists. */
    static int hash(Object value) {
        if (value == null) {
            return 0;
        }
        if (value instanceof byte[] bytes) {
            // Hash through the Base64 String form so a byte[] and its serialised
            // String counterpart produce the same hash.
            return Base64.getEncoder().encodeToString(bytes).hashCode();
        }
        if (value.getClass().isArray()) {
            int result = 1;
            int length = Array.getLength(value);
            for (int i = 0; i < length; i++) {
                result = 31 * result + hash(Array.get(value, i));
            }
            return result;
        }
        if (value instanceof Map<?, ?> map) {
            int result = 0;
            for (Map.Entry<?, ?> entry : map.entrySet()) {
                result += hash(entry.getKey()) ^ hash(entry.getValue());
            }
            return result;
        }
        if (value instanceof List<?> list) {
            int result = 1;
            for (Object element : list) {
                result = 31 * result + hash(element);
            }
            return result;
        }
        return value.hashCode();
    }

    private static boolean listsEqual(List<?> left, List<?> right) {
        int size = left.size();
        if (size != right.size()) {
            return false;
        }
        for (int i = 0; i < size; i++) {
            if (!equal(left.get(i), right.get(i))) {
                return false;
            }
        }
        return true;
    }

    /** Decodes the string as Base64 and compares the result to the byte array. */
    private static boolean bytesEqualBase64String(byte[] bytes, String str) {
        byte[] decoded;
        try {
            decoded = Base64.getDecoder().decode(str);
        } catch (IllegalArgumentException e) {
            return false;
        }
        return Arrays.equals(bytes, decoded);
    }
}
