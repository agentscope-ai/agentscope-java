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

import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Accepts a component tree in any of the shapes a model submits and returns the flat A2UI v1.0
 * component list: properties inline beside {@code component}, children referenced by id, and one
 * entry whose id is {@code root}.
 *
 * <ul>
 *   <li>flat — the list the catalog guide describes;
 *   <li>nested — {@code children}/{@code child} holding component objects, at any depth (including
 *       inside props such as {@code Tabs.tabs[].child}), or a {@code root}/{@code content} wrapper;
 *   <li>legacy — properties grouped under a {@code props} key, which are lifted inline;
 *   <li>JSON-encoded — any of the above handed over as a string, which is how an untyped tool
 *       property reaches us.
 * </ul>
 *
 * <p>Any map bearing a {@code component} key is treated as a component node: it is lifted into the
 * flat list in document order and replaced by its id wherever it sat. That is what keeps the
 * parent→child structure the v1.0 renderer needs — flattening without rewriting the references
 * yields a list of orphans. Maps without {@code component} (data bindings, {@code ChildList}
 * templates) pass through untouched.
 */
public final class A2uiTreeNormalizer {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final String ROOT_ID = "root";

    private A2uiTreeNormalizer() {}

    /** @return the flat component list, or {@code null} when the input is not a component tree */
    public static List<Map<String, Object>> normalize(Object raw) {
        List<Map<String, Object>> nodes = new ArrayList<>();
        Set<String> usedIds = new HashSet<>();
        for (Map<String, Object> root : roots(raw)) {
            emit(root, nodes, usedIds);
        }
        if (nodes.isEmpty()) {
            return null;
        }
        promoteRoot(nodes);
        return nodes;
    }

    /** The submission's top-level component nodes, whichever wrapper shape they arrived in. */
    private static List<Map<String, Object>> roots(Object raw) {
        if (raw instanceof String s) {
            String text = s.strip();
            if (!text.startsWith("[") && !text.startsWith("{")) {
                return List.of();
            }
            try {
                return roots(MAPPER.readValue(text, Object.class));
            } catch (Exception e) {
                return List.of();
            }
        }
        if (raw instanceof List<?> list) {
            List<Map<String, Object>> out = new ArrayList<>();
            for (Object node : list) {
                if (node instanceof Map<?, ?> map && map.get("component") != null) {
                    out.add(asMap(map));
                }
            }
            return out;
        }
        if (!(raw instanceof Map<?, ?> map)) {
            return List.of();
        }
        if (map.containsKey("components")) {
            return roots(map.get("components"));
        }
        Object root = map.containsKey("root") ? map.get("root") : map.get("content");
        if (root instanceof Map<?, ?> content && content.containsKey("root")) {
            root = content.get("root");
        }
        if (root != null) {
            return roots(root);
        }
        return map.get("component") != null ? List.of(asMap(map)) : List.of();
    }

    private static Map<String, Object> asMap(Map<?, ?> map) {
        Map<String, Object> out = new LinkedHashMap<>();
        map.forEach((key, value) -> out.put(String.valueOf(key), value));
        return out;
    }

    /** Appends one node before its inline descendants, so the list stays in document order. */
    private static String emit(
            Map<String, Object> node, List<Map<String, Object>> out, Set<String> usedIds) {
        String id = declaredId(node);
        if (id == null) {
            id = generateId(usedIds);
        }
        usedIds.add(id);
        Map<String, Object> component = new LinkedHashMap<>();
        component.put("id", id);
        component.put("component", node.get("component"));
        out.add(component);
        Object declaredProps = node.get("props");
        if (declaredProps instanceof Map<?, ?> props) {
            asMap(props).forEach((key, value) -> component.put(key, lift(value, out, usedIds)));
        }
        node.forEach(
                (key, value) -> {
                    if (!"id".equals(key) && !"component".equals(key) && !"props".equals(key)) {
                        component.put(key, lift(value, out, usedIds));
                    }
                });
        return id;
    }

    /** Replaces inline component nodes with their ids, leaving every other value alone. */
    private static Object lift(Object value, List<Map<String, Object>> out, Set<String> usedIds) {
        if (value instanceof Map<?, ?> map) {
            if (map.get("component") != null) {
                return emit(asMap(map), out, usedIds);
            }
            Map<String, Object> copy = new LinkedHashMap<>();
            asMap(map).forEach((key, nested) -> copy.put(key, lift(nested, out, usedIds)));
            return copy;
        }
        if (value instanceof List<?> list) {
            List<Object> copy = new ArrayList<>(list.size());
            for (Object item : list) {
                copy.add(lift(item, out, usedIds));
            }
            return copy;
        }
        return value;
    }

    private static String declaredId(Map<String, Object> node) {
        return node.get("id") instanceof String s && !s.isBlank() ? s : null;
    }

    private static String generateId(Set<String> usedIds) {
        for (int i = 1; ; i++) {
            String candidate = "c" + i;
            if (!usedIds.contains(candidate)) {
                return candidate;
            }
        }
    }

    /**
     * The v1.0 renderer resolves the tree from the component whose id is {@code root}. When the
     * submission named its root something else, rename it and rewrite the references to it; when it
     * already has a {@code root}, leave every id alone so no duplicate is introduced.
     */
    private static void promoteRoot(List<Map<String, Object>> nodes) {
        for (Map<String, Object> node : nodes) {
            if (ROOT_ID.equals(node.get("id"))) {
                return;
            }
        }
        Map<String, Object> first = nodes.get(0);
        Object previousId = first.get("id");
        first.put("id", ROOT_ID);
        if (!(previousId instanceof String old) || old.isBlank()) {
            return;
        }
        for (Map<String, Object> node : nodes) {
            node.replaceAll((key, value) -> renameReference(value, old));
        }
    }

    private static Object renameReference(Object value, String oldId) {
        if (oldId.equals(value)) {
            return ROOT_ID;
        }
        if (value instanceof Map<?, ?> map) {
            Map<String, Object> copy = new LinkedHashMap<>();
            asMap(map).forEach((key, nested) -> copy.put(key, renameReference(nested, oldId)));
            return copy;
        }
        if (value instanceof List<?> list) {
            List<Object> copy = new ArrayList<>(list.size());
            for (Object item : list) {
                copy.add(renameReference(item, oldId));
            }
            return copy;
        }
        return value;
    }
}
