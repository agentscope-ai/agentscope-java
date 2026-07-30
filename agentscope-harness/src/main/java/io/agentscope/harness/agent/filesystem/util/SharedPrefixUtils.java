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
package io.agentscope.harness.agent.filesystem.util;

import io.agentscope.harness.agent.filesystem.spec.LocalFilesystemSpec;

/** Shared directory-prefix normalization used by {@link LocalFilesystemSpec}. */
public final class SharedPrefixUtils {

    private SharedPrefixUtils() {}

    public static String normalizeDirectoryPrefix(String prefix) {
        String normalized = prefix.replace('\\', '/').strip();
        while (normalized.startsWith("/")) {
            normalized = normalized.substring(1);
        }
        while (normalized.endsWith("/")) {
            normalized = normalized.substring(0, normalized.length() - 1);
        }
        if (normalized.isBlank()) {
            throw new IllegalArgumentException("shared prefix must not resolve to workspace root");
        }
        for (String segment : normalized.split("/")) {
            if (segment.isBlank() || ".".equals(segment) || "..".equals(segment)) {
                throw new IllegalArgumentException("invalid shared prefix: " + prefix);
            }
        }
        return normalized + "/";
    }

    public static String routeSegment(String normalizedPrefix) {
        return normalizedPrefix.substring(0, normalizedPrefix.length() - 1);
    }
}
