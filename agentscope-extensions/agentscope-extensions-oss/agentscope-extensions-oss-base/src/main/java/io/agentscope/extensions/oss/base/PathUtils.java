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
package io.agentscope.extensions.oss.base;

/** Object key / prefix normalization helpers shared by all vendor implementations. */
public final class PathUtils {

    private PathUtils() {}

    /**
     * Normalizes a key prefix so that it uses forward slashes, contains no leading slashes,
     * and ends with exactly one trailing slash. Returns {@code fallback} when the input is
     * {@code null}, blank, or normalizes to an empty string.
     *
     * @param prefix raw prefix, may be null or blank
     * @param fallback value returned when {@code prefix} is null/blank/empty
     * @return the normalized prefix
     */
    public static String normalizePrefix(String prefix, String fallback) {
        if (prefix == null || prefix.isBlank()) {
            return fallback;
        }
        String p = prefix.replace('\\', '/');
        while (p.startsWith("/")) {
            p = p.substring(1);
        }
        if (!p.isEmpty() && !p.endsWith("/")) {
            p = p + "/";
        }
        return p.isEmpty() ? fallback : p;
    }

    /**
     * Strips all leading forward-slashes from the given key component. Returns the input
     * unchanged when {@code null} or empty.
     *
     * @param s input string
     * @return input with leading slashes removed
     */
    public static String stripLeadingSlashes(String s) {
        if (s == null || s.isEmpty()) {
            return s;
        }
        int i = 0;
        while (i < s.length() && s.charAt(i) == '/') {
            i++;
        }
        return i == 0 ? s : s.substring(i);
    }
}
