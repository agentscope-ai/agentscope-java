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

package io.agentscope.extensions.judge.jev.evaluation;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Immutable JSON snapshots for evaluation state. Never retain mutable agent arguments. */
final class TraceData {
    private static final ObjectMapper JSON = new ObjectMapper();

    private TraceData() {}

    @SuppressWarnings("unchecked")
    static Map<String, Object> snapshot(Map<String, ?> source) {
        return (Map<String, Object>) freeze(JSON.convertValue(source, Map.class));
    }

    private static Object freeze(Object value) {
        if (value instanceof Map<?, ?> map) {
            Map<String, Object> copy = new LinkedHashMap<>();
            map.forEach((k, v) -> copy.put((String) k, freeze(v)));
            return Collections.unmodifiableMap(copy);
        }
        if (value instanceof List<?> list) {
            List<Object> copy = new ArrayList<>();
            list.forEach(v -> copy.add(freeze(v)));
            return Collections.unmodifiableList(copy);
        }
        return value;
    }

    static int size(Object state) {
        try {
            return JSON.writeValueAsString(state).length();
        } catch (Exception e) {
            throw new IllegalArgumentException("evaluation state must be JSON serializable");
        }
    }
}
