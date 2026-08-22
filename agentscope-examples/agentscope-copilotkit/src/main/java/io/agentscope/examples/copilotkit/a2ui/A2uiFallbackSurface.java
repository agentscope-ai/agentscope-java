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
import java.util.List;
import java.util.Map;

/**
 * Deterministic workbench dashboard, assembled straight from the shared state.
 *
 * <p>Used when no model backend is configured or every one of them failed, so the A2UI half of the
 * demo stays functional offline. It also doubles as a reference for the exact wire shape the model
 * is asked to produce.
 */
final class A2uiFallbackSurface {

    private A2uiFallbackSurface() {}

    @SuppressWarnings("unchecked")
    static A2uiSurface build(String surfaceId, String intent, Map<String, Object> state) {
        List<Map<String, Object>> components = new ArrayList<>();
        List<String> rootChildren = new ArrayList<>();

        components.add(
                A2uiOperations.component(
                        "title",
                        "Text",
                        Map.of(
                                "text",
                                string(state.get("topic"), "AgentScope 工作台"),
                                "variant",
                                "h3")));
        rootChildren.add("title");

        components.add(
                A2uiOperations.component(
                        "subtitle", "Text", Map.of("text", "意图：" + intent, "variant", "caption")));
        rootChildren.add("subtitle");

        Map<String, Object> metrics =
                state.get("metrics") instanceof Map<?, ?> map
                        ? (Map<String, Object>) map
                        : Map.of();
        if (!metrics.isEmpty()) {
            List<String> tileIds = new ArrayList<>();
            int index = 0;
            for (Map.Entry<String, Object> entry : metrics.entrySet()) {
                String id = "metric-" + index++;
                components.add(
                        A2uiOperations.component(
                                id,
                                "MetricTile",
                                Map.of(
                                        "label", entry.getKey(),
                                        "value", String.valueOf(entry.getValue()),
                                        "accent", index % 2 == 0 ? "aurora" : "champagne")));
                tileIds.add(id);
            }
            components.add(
                    A2uiOperations.component(
                            "metric-row",
                            "Row",
                            Map.of("children", tileIds, "justify", "spaceBetween")));
            rootChildren.add("metric-row");
        }

        Map<String, Object> plan =
                state.get("plan") instanceof Map<?, ?> map ? (Map<String, Object>) map : Map.of();
        List<Map<String, Object>> tasks =
                plan.get("tasks") instanceof List<?> list
                        ? (List<Map<String, Object>>) list
                        : List.of();
        if (!tasks.isEmpty()) {
            components.add(A2uiOperations.component("plan-divider", "Divider", Map.of()));
            rootChildren.add("plan-divider");

            List<String> stepIds = new ArrayList<>();
            for (int i = 0; i < tasks.size(); i++) {
                Map<String, Object> task = tasks.get(i);
                String id = "step-" + i;
                components.add(
                        A2uiOperations.component(
                                id,
                                "TimelineStep",
                                Map.of(
                                        "index", i + 1,
                                        "title", string(task.get("title"), "未命名步骤"),
                                        "state", string(task.get("state"), "pending"))));
                stepIds.add(id);
            }
            components.add(
                    A2uiOperations.component("plan-column", "Column", Map.of("children", stepIds)));
            rootChildren.add("plan-column");
        }

        List<Map<String, Object>> audit =
                state.get("permissionAudit") instanceof List<?> list
                        ? (List<Map<String, Object>>) list
                        : List.of();
        if (!audit.isEmpty()) {
            Map<String, Object> latest = audit.get(0);
            boolean granted = Boolean.TRUE.equals(latest.get("granted"));
            components.add(
                    A2uiOperations.component(
                            "audit",
                            "ApprovalCallout",
                            Map.of(
                                    "title",
                                    granted ? "最近一次授权已通过" : "最近一次授权被拒绝",
                                    "body",
                                    "%s · %s"
                                            .formatted(
                                                    string(latest.get("tool"), "unknown"),
                                                    string(latest.get("detail"), "")),
                                    "tone",
                                    granted ? "info" : "danger")));
            rootChildren.add("audit");
        }

        components.add(
                A2uiOperations.component("root", "Column", Map.of("children", rootChildren)));

        Map<String, Object> dataModel = new LinkedHashMap<>();
        dataModel.put("topic", state.get("topic"));
        dataModel.put("status", state.get("status"));

        List<Map<String, Object>> operations =
                List.of(
                        A2uiOperations.createSurface(surfaceId),
                        A2uiOperations.updateDataModel(surfaceId, dataModel),
                        A2uiOperations.updateComponents(surfaceId, components));

        return new A2uiSurface(
                surfaceId,
                WorkbenchCatalog.CATALOG_ID,
                intent,
                "fallback",
                components.size(),
                operations);
    }

    private static String string(Object value, String fallback) {
        return value == null || String.valueOf(value).isBlank() ? fallback : String.valueOf(value);
    }
}
