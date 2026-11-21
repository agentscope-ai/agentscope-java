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
import com.networknt.schema.Error;
import com.networknt.schema.Schema;
import io.agentscope.extensions.a2ui.catalog.A2uiCatalog;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.Deque;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Validates a submitted component list against the official v1.0 JSON Schemas plus the graph rules
 * those schemas cannot express (spec §6.2). Failures come back as short actionable sentences the
 * render sub-agent can self-correct from; nothing throws here.
 *
 * <p>Schema checks run per component name rather than against the catalog's {@code anyComponent}
 * union: the union reports every branch's failure for one mistake, which buries the actionable line
 * under dozens of "must be const …" messages. The graph rules — a root named {@code root}, no
 * dangling id reference, no unreachable component — are plain JSON Schema blind spots, so they are
 * checked here; a renderer in strict mode rejects the same shapes.
 */
public final class A2uiEnvelopeValidator {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final String ROOT_ID = "root";
    private static final int MAX_REPORTED_ERRORS = 20;

    /** Property names the protocol uses to point at another component by id. */
    private static final Set<String> REFERENCE_KEYS =
            Set.of("child", "children", "trigger", "content", "componentId");

    /** Keywords whose failure means "none of the alternatives matched". */
    private static final Set<String> UNION_KEYWORDS = Set.of("oneOf", "anyOf");

    private A2uiEnvelopeValidator() {}

    /** @return list of human-readable errors; empty when the submission is valid */
    public static List<String> validate(
            List<Map<String, Object>> components, A2uiCatalog catalog, int maxComponents) {
        List<String> errors = new ArrayList<>();
        if (components == null || components.isEmpty()) {
            errors.add("`components` must be a non-empty array of {id, component, ...props}.");
            return errors;
        }
        if (components.size() > maxComponents) {
            errors.add(
                    "`components` has "
                            + components.size()
                            + " entries but at most "
                            + maxComponents
                            + " are allowed per surface. Generate repeated items from one List"
                            + " template (`children: {\"path\": \"/items\", \"componentId\":"
                            + " \"tpl\"}`) instead of one component per item; if the surface still"
                            + " does not fit, render the most important sections and add a Text"
                            + " stating how many entries were omitted — do not resubmit the same"
                            + " oversized tree.");
        }
        Map<String, Map<String, Object>> byId = new LinkedHashMap<>();
        for (int i = 0; i < components.size(); i++) {
            Map<String, Object> component = components.get(i);
            String where = "components[" + i + "]";
            if (component == null) {
                errors.add(where + " is not an object.");
                continue;
            }
            String id = stringOf(component.get("id"));
            if (id == null || id.isBlank()) {
                errors.add(where + " is missing a non-blank string `id`.");
            } else if (byId.put(id, component) != null) {
                errors.add("duplicate component id \"" + id + "\".");
            }
            String name = stringOf(component.get("component"));
            if (name == null || name.isBlank()) {
                errors.add(where + " is missing string `component`.");
                continue;
            }
            if (catalog.componentSchema(name) == null) {
                errors.add(
                        where
                                + " uses unknown component \""
                                + name
                                + "\"; available: "
                                + catalog.componentNames());
                continue;
            }
            errors.addAll(schemaErrors(component, id, name, catalog));
        }
        errors.addAll(graphErrors(byId));
        return capped(errors);
    }

    private static List<String> schemaErrors(
            Map<String, Object> component, String id, String name, A2uiCatalog catalog) {
        Schema schema = catalog.schemas().component(name);
        Set<String> declared = catalog.declaredProperties(name);
        List<Error> failures = new ArrayList<>();
        for (Error failure : schema.validate(MAPPER.valueToTree(component))) {
            // A closed schema also reports a declared property as unevaluated once that property's
            // own check failed; the specific failure above it is the actionable one.
            if ("unevaluatedProperties".equals(failure.getKeyword())
                    && declared.contains(failure.getProperty())) {
                continue;
            }
            failures.add(failure);
        }
        List<String> errors = new ArrayList<>();
        for (Error failure : collapseUnions(failures)) {
            String location = String.valueOf(failure.getInstanceLocation());
            errors.add(
                    "component \""
                            + id
                            + "\" ("
                            + name
                            + ")"
                            + (location.isEmpty() ? "" : " at " + location)
                            + ": "
                            + failure.getMessage());
        }
        return errors;
    }

    /**
     * A union reports every alternative's failure for one mistake, so a single mistyped prop can
     * produce dozens of lines and push the real problems past the report cap. Keep one line per
     * union — the deepest, first-reported specific failure inside it — and leave failures that sit
     * outside any union alone.
     */
    private static List<Error> collapseUnions(List<Error> failures) {
        List<String> unions = new ArrayList<>();
        for (Error failure : failures) {
            if (UNION_KEYWORDS.contains(failure.getKeyword())) {
                String location = String.valueOf(failure.getInstanceLocation());
                if (!unions.contains(location)) {
                    unions.add(location);
                }
            }
        }
        unions.sort(Comparator.comparingInt(String::length));
        boolean[] dropped = new boolean[failures.size()];
        for (String union : unions) {
            List<Integer> subtree = new ArrayList<>();
            int best = -1;
            int bestDepth = -1;
            for (int i = 0; i < failures.size(); i++) {
                if (dropped[i]) {
                    continue;
                }
                String at = String.valueOf(failures.get(i).getInstanceLocation());
                if (!at.equals(union) && !at.startsWith(union + "/")) {
                    continue;
                }
                subtree.add(i);
                if (UNION_KEYWORDS.contains(failures.get(i).getKeyword())) {
                    continue;
                }
                int depth = at.isEmpty() ? 0 : at.split("/").length;
                if (depth > bestDepth) {
                    bestDepth = depth;
                    best = i;
                }
            }
            if (best < 0) {
                continue;
            }
            for (int i : subtree) {
                if (i != best) {
                    dropped[i] = true;
                }
            }
        }
        List<Error> kept = new ArrayList<>();
        for (int i = 0; i < failures.size(); i++) {
            if (!dropped[i]) {
                kept.add(failures.get(i));
            }
        }
        return kept;
    }

    private static List<String> graphErrors(Map<String, Map<String, Object>> byId) {
        List<String> errors = new ArrayList<>();
        if (!byId.containsKey(ROOT_ID)) {
            errors.add(
                    "no component has id \"root\": the surface's root component must be named"
                            + " \"root\", and every other component must be reachable from it.");
            return errors;
        }
        Map<String, Set<String>> edges = new LinkedHashMap<>();
        byId.forEach(
                (id, component) -> {
                    Set<String> references = new LinkedHashSet<>();
                    component.forEach(
                            (key, value) -> {
                                if (!"id".equals(key) && !"component".equals(key)) {
                                    references(value, key, references);
                                }
                            });
                    edges.put(id, references);
                });
        edges.forEach(
                (id, references) -> {
                    for (String reference : references) {
                        if (!byId.containsKey(reference)) {
                            errors.add(
                                    "component \""
                                            + id
                                            + "\" references \""
                                            + reference
                                            + "\", which is not in `components`.");
                        }
                    }
                });
        Set<String> reached = new HashSet<>();
        Deque<String> pending = new ArrayDeque<>();
        pending.add(ROOT_ID);
        while (!pending.isEmpty()) {
            String id = pending.pop();
            if (!reached.add(id)) {
                continue;
            }
            for (String reference : edges.getOrDefault(id, Set.of())) {
                if (byId.containsKey(reference)) {
                    pending.add(reference);
                }
            }
        }
        for (String id : byId.keySet()) {
            if (!reached.contains(id)) {
                errors.add(
                        "component \""
                                + id
                                + "\" is unreachable from \"root\"; reference it from a parent's"
                                + " `child`/`children` or drop it.");
            }
        }
        return errors;
    }

    /** Collects id references at any depth, so nested holders like {@code tabs[].child} count. */
    private static void references(Object node, String key, Set<String> out) {
        if (node instanceof String value) {
            if (REFERENCE_KEYS.contains(key)) {
                out.add(value);
            }
        } else if (node instanceof List<?> list) {
            for (Object item : list) {
                references(item, key, out);
            }
        } else if (node instanceof Map<?, ?> map) {
            map.forEach((nestedKey, value) -> references(value, String.valueOf(nestedKey), out));
        }
    }

    private static List<String> capped(List<String> errors) {
        if (errors.size() <= MAX_REPORTED_ERRORS) {
            return errors;
        }
        List<String> reported = new ArrayList<>(errors.subList(0, MAX_REPORTED_ERRORS));
        reported.add(
                "… "
                        + (errors.size() - MAX_REPORTED_ERRORS)
                        + " further problems omitted; fix the ones above and resubmit.");
        return reported;
    }

    private static String stringOf(Object value) {
        return value instanceof String s ? s : null;
    }
}
