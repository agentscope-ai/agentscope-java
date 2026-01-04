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
package io.agentscope.core.memory.mem0;

/**
 * Enumeration of Mem0 API deployment types.
 *
 * <p>Mem0 provides two deployment options with different API endpoints:
 * <ul>
 *   <li><b>PLATFORM</b>: Official cloud service with endpoints /v1/memories/ and /v2/memories/search/</li>
 *   <li><b>SELF_HOSTED</b>: Self-deployed service with endpoints /memories and /search</li>
 * </ul>
 */
public enum Mem0ApiType {
    /** Platform Mem0 (default) - official cloud service. */
    PLATFORM,

    /** Self-hosted Mem0 - user-deployed service. */
    SELF_HOSTED;

    /**
     * Parses a string to Mem0ApiType (case-insensitive).
     *
     * @param value String value to parse
     * @return Corresponding Mem0ApiType, or PLATFORM if value is null/empty/invalid
     */
    public static Mem0ApiType fromString(String value) {
        if (value == null || value.isEmpty()) {
            return PLATFORM;
        }
        String normalized = value.toLowerCase().replace("_", "-");
        if ("self-hosted".equals(normalized) || "selfhosted".equals(normalized)) {
            return SELF_HOSTED;
        }
        return PLATFORM; // Default to PLATFORM
    }
}
