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
package io.agentscope.examples.copilotkit.a2ui;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Builders and structural checks for A2UI v0.9 server-to-client messages.
 *
 * <p>The checks here are intentionally structural (surface consistency, a resolvable {@code root},
 * known component names).
 */
public final class A2uiOperations {

    private A2uiOperations() {}

    public static Map<String, Object> createSurface(String surfaceId) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("surfaceId", surfaceId);
        body.put("catalogId", WorkbenchCatalog.CATALOG_ID);
        return message("createSurface", body);
    }

    public static Map<String, Object> updateDataModel(String surfaceId, Map<String, Object> value) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("surfaceId", surfaceId);
        body.put("path", "/");
        body.put("value", value);
        return message("updateDataModel", body);
    }

    public static Map<String, Object> updateComponents(
            String surfaceId, List<Map<String, Object>> components) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("surfaceId", surfaceId);
        body.put("components", components);
        return message("updateComponents", body);
    }

    public static Map<String, Object> component(
            String id, String component, Map<String, Object> props) {
        Map<String, Object> node = new LinkedHashMap<>();
        node.put("id", id);
        node.put("component", component);
        props.forEach(
                (key, value) -> {
                    if (value != null) {
                        node.put(key, value);
                    }
                });
        return node;
    }

    private static Map<String, Object> message(String key, Map<String, Object> body) {
        Map<String, Object> message = new LinkedHashMap<>();
        message.put("version", WorkbenchCatalog.VERSION);
        message.put(key, body);
        return message;
    }

    /** Counts the components declared across every {@code updateComponents} message. */
    @SuppressWarnings("unchecked")
    public static int countComponents(List<Map<String, Object>> operations) {
        int count = 0;
        for (Map<String, Object> operation : operations) {
            if (operation.get("updateComponents") instanceof Map<?, ?> body
                    && body.get("components") instanceof List<?> components) {
                count += components.size();
            }
        }
        return count;
    }

    /** Returns the surface id referenced by the first operation that declares one. */
    public static String surfaceIdOf(List<Map<String, Object>> operations) {
        for (Map<String, Object> operation : operations) {
            for (Object value : operation.values()) {
                if (value instanceof Map<?, ?> body
                        && body.get("surfaceId") instanceof String surfaceId) {
                    return surfaceId;
                }
            }
        }
        return null;
    }

    /**
     * Normalises model output into a renderable operation list.
     *
     * <p>Models routinely drop the {@code createSurface} preamble or invent their own surface id, so
     * the list is rewritten onto {@code surfaceId} and the preamble is prepended when missing.
     *
     * @throws IllegalArgumentException when the payload cannot be repaired into a valid surface
     */
    @SuppressWarnings("unchecked")
    public static List<Map<String, Object>> normalize(List<?> rawOperations, String surfaceId) {
        if (rawOperations == null || rawOperations.isEmpty()) {
            throw new IllegalArgumentException("A2UI 负载为空");
        }

        List<Map<String, Object>> operations = new ArrayList<>();
        boolean hasCreateSurface = false;
        boolean hasComponents = false;

        for (Object raw : rawOperations) {
            if (!(raw instanceof Map<?, ?> map)) {
                throw new IllegalArgumentException("A2UI 消息必须是对象，实际为 " + raw);
            }
            Map<String, Object> operation = new LinkedHashMap<>((Map<String, Object>) map);
            operation.put("version", WorkbenchCatalog.VERSION);

            String kind = operationKind(operation);
            Map<String, Object> body =
                    new LinkedHashMap<>((Map<String, Object>) operation.get(kind));
            body.put("surfaceId", surfaceId);
            if ("createSurface".equals(kind)) {
                body.put("catalogId", WorkbenchCatalog.CATALOG_ID);
                hasCreateSurface = true;
            }
            if ("updateComponents".equals(kind)) {
                validateComponents((List<Object>) body.get("components"));
                hasComponents = true;
            }
            operation.put(kind, body);
            operations.add(operation);
        }

        if (!hasComponents) {
            throw new IllegalArgumentException("A2UI 负载缺少 updateComponents 消息");
        }
        if (!hasCreateSurface) {
            operations.add(0, createSurface(surfaceId));
        }
        return operations;
    }

    private static String operationKind(Map<String, Object> operation) {
        for (String kind :
                List.of("createSurface", "updateComponents", "updateDataModel", "deleteSurface")) {
            if (operation.get(kind) instanceof Map<?, ?>) {
                return kind;
            }
        }
        throw new IllegalArgumentException("无法识别的 A2UI 消息：" + operation.keySet());
    }

    @SuppressWarnings("unchecked")
    private static void validateComponents(List<Object> components) {
        if (components == null || components.isEmpty()) {
            throw new IllegalArgumentException("updateComponents.components 不能为空");
        }
        Set<String> ids = new LinkedHashSet<>();
        for (Object raw : components) {
            if (!(raw instanceof Map<?, ?> map)) {
                throw new IllegalArgumentException("组件必须是对象");
            }
            Map<String, Object> node = (Map<String, Object>) map;
            if (!(node.get("id") instanceof String id) || id.isBlank()) {
                throw new IllegalArgumentException("组件缺少 id");
            }
            if (!(node.get("component") instanceof String component)) {
                throw new IllegalArgumentException("组件 " + id + " 缺少 component 字段");
            }
            if (!WorkbenchCatalog.isKnownComponent(component)) {
                throw new IllegalArgumentException(
                        "组件 %s 使用了 catalog 之外的类型 %s".formatted(id, component));
            }
            if (!ids.add(id)) {
                throw new IllegalArgumentException("组件 id 重复：" + id);
            }
        }
        if (!ids.contains("root")) {
            throw new IllegalArgumentException("组件树缺少 id 为 root 的根节点");
        }
    }
}
