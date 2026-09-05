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
package io.agentscope.core.tool;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.agentscope.core.agent.RuntimeContext;
import io.agentscope.core.message.ToolResultBlock;
import io.agentscope.core.message.ToolUseBlock;
import io.agentscope.core.model.ToolSchema;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/** Unit tests for per-call {@link ToolRequestConfig} composition against a shared {@link Toolkit}. */
class ToolRequestConfigTest {

    @Test
    void getToolSchemas_mergeExternalPriority_overridesBackendAndLeavesRegistryUntouched() {
        Toolkit toolkit = new Toolkit();
        SchemaOnlyTool backend = schemaOnlyTool("overlap", "backend");
        toolkit.registerAgentTool(backend);

        ToolRequestConfig config =
                new ToolRequestConfig(
                        Map.of("overlap", schemaOnlyTool("overlap", "external")),
                        ToolMergeMode.MERGE_EXTERNAL_PRIORITY);

        List<ToolSchema> schemas = toolkit.getToolSchemas(List.of(), config);

        // Backend "overlap" is dropped in favour of the external tool of the same name.
        long overlapCount = schemas.stream().filter(s -> "overlap".equals(s.getName())).count();
        assertEquals(1, overlapCount);
        ToolSchema overlap =
                schemas.stream()
                        .filter(s -> "overlap".equals(s.getName()))
                        .findFirst()
                        .orElseThrow();
        assertEquals("external", overlap.getDescription());
        // Shared registry is never mutated.
        assertSame(backend, toolkit.getTool("overlap"));
    }

    @Test
    void getToolSchemas_externalOnly_hidesBackendOnlyWhenDeletionAllowed() {
        // allowToolDeletion defaults to true: EXTERNAL_ONLY hides the backend entirely.
        Toolkit deletable = new Toolkit();
        deletable.registerAgentTool(schemaOnlyTool("backend", "backend"));
        ToolRequestConfig externalOnly =
                new ToolRequestConfig(
                        Map.of("frontend", schemaOnlyTool("frontend", "frontend")),
                        ToolMergeMode.EXTERNAL_ONLY);

        List<ToolSchema> hidden = deletable.getToolSchemas(List.of(), externalOnly);
        assertTrue(hidden.stream().noneMatch(s -> "backend".equals(s.getName())));
        assertTrue(hidden.stream().anyMatch(s -> "frontend".equals(s.getName())));
        assertNull(deletable.getTool("backend", externalOnly));

        // allowToolDeletion=false: EXTERNAL_ONLY degrades to MERGE (backend retained).
        Toolkit nonDeletable =
                new Toolkit(ToolkitConfig.builder().allowToolDeletion(false).build());
        nonDeletable.registerAgentTool(schemaOnlyTool("backend", "backend"));
        List<ToolSchema> merged = nonDeletable.getToolSchemas(List.of(), externalOnly);
        assertTrue(merged.stream().anyMatch(s -> "backend".equals(s.getName())));
        assertTrue(merged.stream().anyMatch(s -> "frontend".equals(s.getName())));
        assertNotNull(nonDeletable.getTool("backend", externalOnly));
    }

    @Test
    void callTools_externalToolIsSuspendedNotInvoked() {
        Toolkit toolkit = new Toolkit();
        ToolRequestConfig config =
                new ToolRequestConfig(
                        Map.of("external_tool", schemaOnlyTool("external_tool", "external")),
                        ToolMergeMode.MERGE_EXTERNAL_PRIORITY);

        ToolUseBlock use =
                ToolUseBlock.builder()
                        .id("call-1")
                        .name("external_tool")
                        .input(Map.of())
                        .content("{}")
                        .build();

        List<ToolResultBlock> results =
                toolkit.callTools(List.of(use), null, null, RuntimeContext.empty(), config, null)
                        .block();

        assertNotNull(results);
        assertEquals(1, results.size());
        assertTrue(results.get(0).isSuspended(), "external tool should suspend: " + results.get(0));
    }

    @Test
    void externalToolOverridingInactiveGroupedBackendIsCallable() {
        Toolkit toolkit = new Toolkit();
        toolkit.createToolGroup("gA", "group A", false);
        toolkit.registration().agentTool(schemaOnlyTool("overlap", "backend")).group("gA").apply();

        ToolRequestConfig config =
                new ToolRequestConfig(
                        Map.of("overlap", schemaOnlyTool("overlap", "external")),
                        ToolMergeMode.MERGE_EXTERNAL_PRIORITY);

        // The model is shown the external override even though the grouped backend is inactive.
        List<ToolSchema> schemas = toolkit.getToolSchemas(List.of(), config);
        assertTrue(
                schemas.stream().anyMatch(s -> "overlap".equals(s.getName())),
                "external override should be visible: " + schemas);

        // Calling it must suspend (honour the external tool), not be rejected via the backend's
        // inactive-group activation gate.
        ToolUseBlock use =
                ToolUseBlock.builder()
                        .id("call-1")
                        .name("overlap")
                        .input(Map.of())
                        .content("{}")
                        .build();
        List<ToolResultBlock> results =
                toolkit.callTools(List.of(use), null, null, RuntimeContext.empty(), config, null)
                        .block();

        assertNotNull(results);
        assertEquals(1, results.size());
        assertTrue(
                results.get(0).isSuspended(),
                "external override should suspend, got: " + results.get(0));
    }

    @Test
    void constructorPreservesExternalToolInsertionOrder() {
        LinkedHashMap<String, SchemaOnlyTool> ordered = new LinkedHashMap<>();
        ordered.put("b_tool", schemaOnlyTool("b_tool", "b"));
        ordered.put("a_tool", schemaOnlyTool("a_tool", "a"));
        ordered.put("c_tool", schemaOnlyTool("c_tool", "c"));

        ToolRequestConfig config =
                new ToolRequestConfig(ordered, ToolMergeMode.MERGE_EXTERNAL_PRIORITY);

        assertEquals(
                List.of("b_tool", "a_tool", "c_tool"),
                new ArrayList<>(config.externalTools().keySet()));
    }

    @Test
    void constructorRejectsNullExternalToolEntry() {
        // An EMPTY externalTools map is valid (that is ToolRequestConfig.NONE); this guard is only
        // for a map entry whose SchemaOnlyTool VALUE is null. Map.copyOf() used to fail fast here,
        // so the order-preserving copy must keep that contract.
        Map<String, SchemaOnlyTool> withNullValue = new HashMap<>();
        withNullValue.put("broken", null);

        assertThrows(
                NullPointerException.class,
                () -> new ToolRequestConfig(withNullValue, ToolMergeMode.MERGE_EXTERNAL_PRIORITY));
    }

    private static SchemaOnlyTool schemaOnlyTool(String name, String description) {
        return new SchemaOnlyTool(
                ToolSchema.builder()
                        .name(name)
                        .description(description)
                        .parameters(
                                Map.of(
                                        "type",
                                        "object",
                                        "properties",
                                        Map.of("query", Map.of("type", "string"))))
                        .build());
    }
}
