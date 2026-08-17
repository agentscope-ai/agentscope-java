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
package io.agentscope.builder.web.managed;

import java.util.LinkedHashMap;
import java.util.Map;

/** Shared merge helper for session-scoped agent overrides. */
public final class AgentOverrideMerger {

    private AgentOverrideMerger() {}

    /** Returns a new map with {@code patch} applied over {@code current} (null-safe). */
    public static Map<String, Object> merge(
            Map<String, Object> current, Map<String, Object> patch) {
        Map<String, Object> result =
                current == null ? new LinkedHashMap<>() : new LinkedHashMap<>(current);
        if (patch != null) {
            result.putAll(patch);
        }
        return result;
    }
}
