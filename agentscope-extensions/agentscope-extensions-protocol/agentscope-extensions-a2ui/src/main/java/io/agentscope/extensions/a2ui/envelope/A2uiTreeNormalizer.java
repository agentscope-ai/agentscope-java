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
package io.agentscope.extensions.a2ui.envelope;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Accepts a component tree in either submitted shape and returns the flat {@code {id, component,
 * props}} list the envelope carries:
 *
 * <ul>
 *   <li>flat — the list our render prompt and catalog describe (each element is still re-mapped, so
 *       props written inline beside `component` are grouped the same way);
 *   <li>nested — {@code {root: {component, children: [...], ...props}}}, optionally wrapped in a
 *       {@code content} envelope, the shape modelled UI generators tend to produce because it maps
 *       to how they describe layout;
 *   <li>JSON-encoded — the same two shapes handed over as a string, which is how an untyped tool
 *       property reaches us.
 * </ul>
 *
 * <p>Props may sit either under a {@code props} key or directly beside {@code component}; nesting
 * is flattened depth-first (document order) and nodes without an id get a stable generated one.
 * Only the submission is normalised — the emitted envelope keeps the single flat shape, so renderers
 * stay unchanged.
 */
public final class A2uiTreeNormalizer {

    private static final com.fasterxml.jackson.databind.ObjectMapper MAPPER =
            new com.fasterxml.jackson.databind.ObjectMapper();

    private A2uiTreeNormalizer() {}

    /** @return the flat component list, or {@code null} when the input is not a component tree */
    public static List<Map<String, Object>> normalize(Object raw) {
        if (raw instanceof String s) {
            // A submission can arrive JSON-encoded; treat it as the tree it spells out.
            String text = s.strip();
            if (!text.startsWith("[") && !text.startsWith("{")) {
                return null;
            }
            try {
                return normalize(MAPPER.readValue(text, Object.class));
            } catch (Exception e) {
                return null;
            }
        }
        if (raw instanceof List<?> list) {
            List<Map<String, Object>> flat = new ArrayList<>();
            for (Object node : list) {
                flatten(node, flat);
            }
            return flat.isEmpty() ? null : flat;
        }
        if (!(raw instanceof Map<?, ?> map)) {
            return null;
        }
        if (map.containsKey("components")) {
            return normalize(map.get("components"));
        }
        Object root = map.containsKey("root") ? map.get("root") : map.get("content");
        if (root instanceof Map<?, ?> content && content.containsKey("root")) {
            root = content.get("root");
        }
        if (root == null && !map.containsKey("component")) {
            return null;
        }
        Object node = root != null ? root : map;
        List<Map<String, Object>> flat = new ArrayList<>();
        flatten(node, flat);
        return flat.isEmpty() ? null : flat;
    }

    @SuppressWarnings("unchecked")
    private static void flatten(Object node, List<Map<String, Object>> out) {
        if (!(node instanceof Map<?, ?> map)) {
            return;
        }
        Map<String, Object> component = new LinkedHashMap<>();
        Map<String, Object> props = new LinkedHashMap<>();
        Object declaredProps = map.get("props");
        if (declaredProps instanceof Map<?, ?> declared) {
            props.putAll((Map<String, Object>) declared);
        }
        for (Map.Entry<?, ?> entry : map.entrySet()) {
            String key = String.valueOf(entry.getKey());
            if ("component".equals(key)
                    || "id".equals(key)
                    || "props".equals(key)
                    || "children".equals(key)) {
                continue;
            }
            props.put(key, entry.getValue());
        }
        Object declaredId = map.get("id");
        component.put(
                "id", declaredId instanceof String s && !s.isBlank() ? s : "c" + (out.size() + 1));
        component.put("component", map.get("component"));
        component.put("props", props);
        out.add(component);
        if (map.get("children") instanceof List<?> children) {
            for (Object child : children) {
                flatten(child, out);
            }
        }
    }
}
