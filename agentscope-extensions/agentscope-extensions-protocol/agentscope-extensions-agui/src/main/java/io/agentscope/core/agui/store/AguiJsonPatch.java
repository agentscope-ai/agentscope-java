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
package io.agentscope.core.agui.store;

import io.agentscope.core.agui.event.AguiEvent.JsonPatchOperation;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Minimal RFC 6902 patch applier for the AG-UI presentation snapshot store.
 *
 * <p>Supports {@code add} / {@code replace} / {@code remove}; unknown operations are ignored and
 * logged at debug. JSON Pointer tokens are split on {@code /}, unescaping {@code ~1} to {@code /}
 * and then {@code ~0} to {@code ~} (order matters). Nested {@link Map} values are traversed;
 * {@link List} index segments and the {@code -} append token are supported.
 *
 * <p>This is the inverse of {@link io.agentscope.core.agui.converter.AguiJsonDiff}, which emits
 * exactly {@code add} / {@code remove} / {@code replace} with the same escaping, so a delta
 * computed from one state always applies cleanly onto a matching state.
 */
final class AguiJsonPatch {

    private static final Logger logger = LoggerFactory.getLogger(AguiJsonPatch.class);

    private AguiJsonPatch() {}

    /**
     * Apply a list of patch operations to a (defensively copied) state map.
     *
     * @param target the state to patch, may be null
     * @param ops the patch operations
     * @return a new map with the operations applied; never mutates the input
     */
    static Map<String, Object> apply(Map<String, Object> target, List<JsonPatchOperation> ops) {
        Map<String, Object> result = deepCopyMap(target);
        if (ops == null || ops.isEmpty()) {
            return result;
        }
        for (JsonPatchOperation op : ops) {
            switch (op.op()) {
                case "add" -> applyAdd(result, op.path(), op.value());
                case "replace" -> applyReplace(result, op.path(), op.value());
                case "remove" -> applyRemove(result, op.path());
                default ->
                        logger.debug(
                                "Ignoring unknown JSON Patch op '{}' at {}", op.op(), op.path());
            }
        }
        return result;
    }

    private static void applyAdd(Map<String, Object> root, String pointer, Object value) {
        List<String> tokens = parsePointer(pointer);
        if (tokens.isEmpty()) {
            return;
        }
        Object parent = navigateParent(root, tokens);
        if (parent == null) {
            logger.debug("Cannot add to missing parent at {}", pointer);
            return;
        }
        String last = tokens.get(tokens.size() - 1);
        Object coerced = deepCopyValue(value);
        if (parent instanceof Map) {
            asMap(parent).put(last, coerced);
        } else if (parent instanceof List) {
            List<Object> list = asList(parent);
            if ("-".equals(last)) {
                list.add(coerced);
            } else {
                int index = parseIndex(last);
                if (index < 0) {
                    logger.debug("Cannot add to non-numeric list index '{}' at {}", last, pointer);
                    return;
                }
                if (index >= list.size()) {
                    list.add(coerced);
                } else {
                    list.add(index, coerced);
                }
            }
        }
    }

    private static void applyReplace(Map<String, Object> root, String pointer, Object value) {
        List<String> tokens = parsePointer(pointer);
        if (tokens.isEmpty()) {
            return;
        }
        Object parent = navigateParent(root, tokens);
        if (parent == null) {
            logger.debug("Cannot replace missing parent at {}", pointer);
            return;
        }
        String last = tokens.get(tokens.size() - 1);
        Object coerced = deepCopyValue(value);
        if (parent instanceof Map) {
            if (asMap(parent).containsKey(last)) {
                asMap(parent).put(last, coerced);
            } else {
                logger.debug("Cannot replace missing key '{}' at {}", last, pointer);
            }
        } else if (parent instanceof List) {
            List<Object> list = asList(parent);
            int index = parseIndex(last);
            if (index < 0 || index >= list.size()) {
                logger.debug("Cannot replace list index '{}' at {}", last, pointer);
                return;
            }
            list.set(index, coerced);
        }
    }

    private static void applyRemove(Map<String, Object> root, String pointer) {
        List<String> tokens = parsePointer(pointer);
        if (tokens.isEmpty()) {
            return;
        }
        Object parent = navigateParent(root, tokens);
        if (parent == null) {
            logger.debug("Cannot remove from missing parent at {}", pointer);
            return;
        }
        String last = tokens.get(tokens.size() - 1);
        if (parent instanceof Map) {
            asMap(parent).remove(last);
        } else if (parent instanceof List) {
            List<Object> list = asList(parent);
            int index = parseIndex(last);
            if (index >= 0 && index < list.size()) {
                list.remove(index);
            }
        }
    }

    private static Object navigateParent(Object root, List<String> tokens) {
        Object current = root;
        for (int i = 0; i < tokens.size() - 1; i++) {
            current = descend(current, tokens.get(i));
            if (current == null) {
                return null;
            }
        }
        return current;
    }

    @SuppressWarnings("unchecked")
    private static Object descend(Object current, String token) {
        if (current instanceof Map) {
            return ((Map<String, Object>) current).get(token);
        }
        if (current instanceof List) {
            List<Object> list = (List<Object>) current;
            int index = parseIndex(token);
            if (index >= 0 && index < list.size()) {
                return list.get(index);
            }
        }
        return null;
    }

    private static List<String> parsePointer(String pointer) {
        if (pointer == null || pointer.isEmpty()) {
            return List.of();
        }
        if (!pointer.startsWith("/")) {
            return List.of(unescape(pointer));
        }
        String body = pointer.substring(1);
        if (body.isEmpty()) {
            return List.of();
        }
        String[] parts = body.split("/", -1);
        List<String> tokens = new ArrayList<>(parts.length);
        for (String part : parts) {
            tokens.add(unescape(part));
        }
        return tokens;
    }

    /** Unescape a JSON Pointer reference token: {@code ~1} to {@code /} then {@code ~0} to {@code ~}. */
    private static String unescape(String token) {
        return token.replace("~1", "/").replace("~0", "~");
    }

    private static int parseIndex(String token) {
        if (token == null || token.isEmpty()) {
            return -1;
        }
        try {
            return Integer.parseInt(token);
        } catch (NumberFormatException e) {
            return -1;
        }
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> asMap(Object value) {
        return (Map<String, Object>) value;
    }

    @SuppressWarnings("unchecked")
    private static List<Object> asList(Object value) {
        return (List<Object>) value;
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> deepCopyMap(Map<String, Object> source) {
        Map<String, Object> copy = new LinkedHashMap<>();
        if (source == null) {
            return copy;
        }
        for (Map.Entry<String, Object> entry : source.entrySet()) {
            copy.put(entry.getKey(), deepCopyValue(entry.getValue()));
        }
        return copy;
    }

    @SuppressWarnings("unchecked")
    private static Object deepCopyValue(Object value) {
        if (value instanceof Map) {
            return deepCopyMap((Map<String, Object>) value);
        }
        if (value instanceof List) {
            List<Object> copy = new ArrayList<>(((List<?>) value).size());
            for (Object element : (List<?>) value) {
                copy.add(deepCopyValue(element));
            }
            return copy;
        }
        return value;
    }
}
