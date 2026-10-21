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
package io.agentscope.builder.runtime.protocol;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/** Immutable execution configuration provisioned before a public Session becomes ready. */
public record RuntimeSessionBootstrap(
        RuntimeRequestContext context,
        String runtimeKind,
        String configurationRevision,
        Map<String, Object> configuration) {
    public RuntimeSessionBootstrap {
        Objects.requireNonNull(context, "context");
        RuntimeProtocol.requireId(runtimeKind, "runtimeKind");
        RuntimeProtocol.requireId(configurationRevision, "configurationRevision");
        Objects.requireNonNull(configuration, "configuration");
        configuration = freeze(configuration, 0);
        var session = session(configuration);
        String owner = string(session, "ownerId");
        RuntimeProtocol.requireId(owner, "ownerId");
        if (context.tenant().length() > 128
                || context.namespace().length() > 128
                || owner.length() > 128)
            throw new IllegalArgumentException("Session scope exceeds 128 characters");
        if (!context.sessionId().equals(string(session, "id")))
            throw new IllegalArgumentException("Configuration belongs to another Session");
    }

    public String ownerId() {
        return string(session(configuration), "ownerId");
    }

    public String environmentId() {
        return string(session(configuration), "environmentId");
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> session(Map<String, Object> configuration) {
        if (!(configuration.get("session") instanceof Map<?, ?> session))
            throw new IllegalArgumentException("Configuration has no Session identity");
        return (Map<String, Object>) session;
    }

    private static String string(Map<String, Object> value, String key) {
        Object field = value.get(key);
        if (field == null) return null;
        if (field instanceof String text) return text;
        throw new IllegalArgumentException("Session " + key + " must be a string");
    }

    static Map<String, Object> freeze(Map<?, ?> source, int depth) {
        if (depth > 64) throw new IllegalArgumentException("Configuration nesting limit exceeded");
        var result = new LinkedHashMap<String, Object>();
        for (var entry : source.entrySet()) {
            if (!(entry.getKey() instanceof String key))
                throw new IllegalArgumentException("Configuration keys must be strings");
            result.put(key, freezeValue(entry.getValue(), depth + 1));
        }
        return Collections.unmodifiableMap(result);
    }

    private static Object freezeValue(Object value, int depth) {
        if (depth > 64) throw new IllegalArgumentException("Configuration nesting limit exceeded");
        if (value instanceof Map<?, ?> map) return freeze(map, depth);
        if (value instanceof List<?> list) {
            var copy = new ArrayList<Object>(list.size());
            for (var element : list) copy.add(freezeValue(element, depth + 1));
            return Collections.unmodifiableList(copy);
        }
        if (value == null
                || value instanceof String
                || value instanceof Number
                || value instanceof Boolean) return value;
        throw new IllegalArgumentException("Configuration must contain JSON values");
    }
}
