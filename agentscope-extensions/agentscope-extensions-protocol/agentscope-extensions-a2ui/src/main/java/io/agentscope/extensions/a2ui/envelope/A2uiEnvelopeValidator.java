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

import io.agentscope.extensions.a2ui.catalog.A2uiCatalog;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Validates LLM-submitted component lists against the catalog whitelist (spec §6.2). All failures
 * are collected into human-readable messages so the LLM can self-correct; nothing throws except
 * {@link A2uiValidationException} at the call site.
 */
public final class A2uiEnvelopeValidator {

    private A2uiEnvelopeValidator() {}

    /** @return list of human-readable errors; empty when the submission is valid */
    public static List<String> validate(
            List<Map<String, Object>> components, A2uiCatalog catalog, int maxComponents) {
        List<String> errors = new ArrayList<>();
        if (components == null || components.isEmpty()) {
            errors.add("`components` must be a non-empty array of {id, component, props}.");
            return errors;
        }
        if (components.size() > maxComponents) {
            errors.add(
                    "`components` has "
                            + components.size()
                            + " entries but at most "
                            + maxComponents
                            + " are allowed.");
        }
        Set<String> ids = new HashSet<>();
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
            } else if (!ids.add(id)) {
                errors.add("duplicate component id \"" + id + "\".");
            }
            String name = stringOf(component.get("component"));
            if (name == null || name.isBlank()) {
                errors.add(where + " is missing string `component`.");
                continue;
            }
            A2uiCatalog.ComponentDef def = catalog.defOf(name);
            if (def == null) {
                errors.add(
                        "unknown component \""
                                + name
                                + "\", available: "
                                + catalog.componentNames());
                continue;
            }
            Map<String, Object> props = propsOf(component.get("props"));
            for (String requiredProp : def.required()) {
                Object value = props.get(requiredProp);
                if (value == null) {
                    errors.add(
                            "component \""
                                    + name
                                    + "\" missing required prop \""
                                    + requiredProp
                                    + "\".");
                }
            }
            for (Map.Entry<String, Object> entry : props.entrySet()) {
                Map<String, Object> schema = schemaOf(def.props().get(entry.getKey()));
                if (schema.isEmpty()) {
                    continue; // undeclared prop: tolerated, renderer ignores unknown props
                }
                Object value = entry.getValue();
                if (value == null) {
                    continue;
                }
                String type = stringOf(schema.get("type"));
                if (type != null && !typeMatches(type, value)) {
                    errors.add(
                            "prop \""
                                    + entry.getKey()
                                    + "\" of component \""
                                    + name
                                    + "\" should be "
                                    + type
                                    + " but was "
                                    + value.getClass().getSimpleName()
                                    + ".");
                }
                if (schema.get("enum") instanceof List<?> enumValues
                        && !enumValues.contains(value)) {
                    errors.add(
                            "prop \""
                                    + entry.getKey()
                                    + "\" of component \""
                                    + name
                                    + "\" must be one of "
                                    + enumValues
                                    + ".");
                }
            }
        }
        return errors;
    }

    private static boolean typeMatches(String type, Object value) {
        return switch (type) {
            case "string" -> value instanceof String;
            case "integer" -> value instanceof Integer || value instanceof Long;
            case "number" -> value instanceof Number;
            case "boolean" -> value instanceof Boolean;
            case "array" -> value instanceof List;
            case "object" -> value instanceof Map;
            default -> true;
        };
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> propsOf(Object value) {
        return value instanceof Map<?, ?> map ? (Map<String, Object>) map : Map.of();
    }

    private static Map<String, Object> schemaOf(Object value) {
        return propsOf(value);
    }

    private static String stringOf(Object value) {
        return value instanceof String s ? s : null;
    }
}
