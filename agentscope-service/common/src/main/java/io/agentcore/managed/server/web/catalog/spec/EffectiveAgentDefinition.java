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
package io.agentcore.managed.server.web.catalog.spec;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.util.Collections;
import java.util.Map;
import java.util.Set;

/** Kernel-neutral definition merging. Overrides cannot add tools, credentials or resource grants. */
public final class EffectiveAgentDefinition {
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final Set<String> TEXT = Set.of("name", "description", "system", "model");
    private static final Set<String> OBJECT = Set.of("contextPolicy", "jev");

    private EffectiveAgentDefinition() {}

    public static Map<String, Object> resolve(Map<String, Object> snapshot, String overridesJson) {
        if (snapshot == null || snapshot.isEmpty())
            throw new IllegalArgumentException("Agent definition is missing");
        ObjectNode effective = JSON.valueToTree(snapshot);
        if (overridesJson != null && !overridesJson.isBlank()) {
            JsonNode overrides;
            try {
                overrides = JSON.readTree(overridesJson);
            } catch (JsonProcessingException invalid) {
                throw new IllegalArgumentException("Agent overrides must be a JSON object");
            }
            if (overrides == null || !overrides.isObject())
                throw new IllegalArgumentException("Agent overrides must be a JSON object");
            overrides
                    .fields()
                    .forEachRemaining(
                            entry -> {
                                String key = entry.getKey();
                                var value = entry.getValue();
                                if (!TEXT.contains(key)
                                        && !OBJECT.contains(key)
                                        && !"maxIters".equals(key))
                                    throw new IllegalArgumentException(
                                            "Unsupported Agent override: " + key);
                                // A removed override inherits the frozen definition, never the
                                // latest catalog.
                                if (value.isNull()) return;
                                boolean valid =
                                        TEXT.contains(key) && value.isTextual()
                                                || OBJECT.contains(key) && value.isObject()
                                                || "maxIters".equals(key)
                                                        && value.isIntegralNumber()
                                                        && value.canConvertToInt()
                                                        && value.intValue() >= 1
                                                        && value.intValue() <= 10000;
                                if (!valid || "model".equals(key) && value.asText().isBlank())
                                    throw new IllegalArgumentException(
                                            "Invalid Agent override: " + key);
                                effective.set(key, value);
                            });
        }
        return Collections.unmodifiableMap(
                JSON.convertValue(effective, new TypeReference<Map<String, Object>>() {}));
    }
}
