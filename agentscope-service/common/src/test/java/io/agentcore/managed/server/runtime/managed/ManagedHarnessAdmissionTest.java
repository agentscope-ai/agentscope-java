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
package io.agentcore.managed.server.runtime.managed;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class ManagedHarnessAdmissionTest {
    @ParameterizedTest
    @ValueSource(strings = {"pi", "pi-durable", "codex"})
    void rejectsUnmappedFieldsFromDefinitionsAndOverrides(String kernel) {
        for (String field : List.of("contextPolicy", "jev")) {
            for (var definition :
                    List.of(
                            Map.<String, Object>of(
                                    "agentSnapshot", Map.of(field, Map.of("enabled", true))),
                            Map.<String, Object>of(
                                    "agentSnapshot",
                                    Map.of("name", "basic"),
                                    "session",
                                    Map.of(
                                            "agentOverridesJson",
                                            "{\"" + field + "\":{\"enabled\":true}}")))) {
                var error =
                        assertThrows(
                                ManagedStateException.class,
                                () -> ManagedSessionProtocol.validateAgent(definition, kernel));
                assertEquals("unsupported_capability", error.code());
                assertTrue(error.getMessage().contains(field));
                assertTrue(error.getMessage().contains(kernel));
                assertDoesNotThrow(
                        () -> ManagedSessionProtocol.validateAgent(definition, "agentscope-java"));
            }
        }
        assertDoesNotThrow(
                () ->
                        ManagedSessionProtocol.validateAgent(
                                Map.of(
                                        "agentSnapshot",
                                        Map.of(
                                                "name",
                                                "basic",
                                                "contextPolicy",
                                                Map.of(),
                                                "jev",
                                                Map.of())),
                                kernel));
    }

    @ParameterizedTest
    @ValueSource(strings = {"pi", "pi-durable", "codex"})
    void rejectsEnabledMissingToolsButAllowsExplicitlyDisabledOnes(String kernel) {
        for (String tool : List.of("edit", "glob", "grep", "web_fetch", "web_search")) {
            var enabled =
                    tools(Map.of("enabled", false), List.of(Map.of("name", tool, "enabled", true)));
            var error =
                    assertThrows(
                            ManagedStateException.class,
                            () ->
                                    ManagedSessionProtocol.validateAgent(
                                            Map.of("agentSnapshot", enabled), kernel));
            assertTrue(error.getMessage().contains(tool));
            assertDoesNotThrow(
                    () ->
                            ManagedSessionProtocol.validateAgent(
                                    Map.of("agentSnapshot", enabled), "agentscope-java"));
            assertDoesNotThrow(
                    () ->
                            ManagedSessionProtocol.validateAgent(
                                    Map.of(
                                            "agentSnapshot",
                                            tools(
                                                    Map.of(),
                                                    List.of(
                                                            Map.of(
                                                                    "name", tool, "enabled",
                                                                    false)))),
                                    kernel));
            var denied =
                    tools(
                            Map.of(),
                            List.of(
                                    Map.of(
                                            "name",
                                            tool,
                                            "permissionPolicy",
                                            Map.of("type", "deny"))));
            assertDoesNotThrow(
                    () -> ManagedSessionProtocol.validateHarnessDefinition(denied, kernel));
            // Preserve the public API's existing client-confirmation/policy boundary.
            assertThrows(
                    ManagedStateException.class,
                    () ->
                            ManagedSessionProtocol.validateAgent(
                                    Map.of("agentSnapshot", denied), kernel));
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {"pi", "pi-durable", "codex"})
    void publicDefaultsAdvertiseOnlyImplementedToolsAndKeepExplicitPolicies(String kernel) {
        var projected =
                ManagedSessionProtocol.agentToolsets(Map.of("name", "basic"), kernel).get(0);
        assertEquals(
                Map.of("enabled", false, "permission_policy", Map.of("type", "always_allow")),
                projected.get("default_config"));
        assertEquals(
                List.of(
                        config("bash", true, "always_allow"),
                        config("read", true, "always_allow"),
                        config("write", true, "always_allow")),
                projected.get("configs"));
        var restricted =
                tools(
                        Map.of("enabled", false),
                        List.of(
                                Map.of("name", "bash", "enabled", true),
                                Map.of("name", "read", "enabled", false)));
        assertEquals(
                List.of(
                        config("bash", true, "always_allow"),
                        config("read", false, "always_allow"),
                        config("write", false, "always_allow")),
                ManagedSessionProtocol.agentToolsets(restricted, kernel).get(0).get("configs"));
        var ask =
                tools(
                        Map.of(),
                        List.of(
                                Map.of(
                                        "name",
                                        "bash",
                                        "permissionPolicy",
                                        Map.of("type", "always_ask"))));
        assertDoesNotThrow(() -> ManagedSessionProtocol.validateHarnessDefinition(ask, kernel));
        assertThrows(
                ManagedStateException.class,
                () -> ManagedSessionProtocol.agentToolsets(ask, kernel));
    }

    @Test
    void rejectsAmbiguousToolOverridesAndKeepsJavaProjectionUnchanged() {
        var duplicate =
                tools(
                        Map.of(),
                        List.of(Map.of("name", "bash"), Map.of("name", "bash", "enabled", false)));
        assertThrows(
                ManagedStateException.class,
                () ->
                        ManagedSessionProtocol.validateAgent(
                                Map.of("agentSnapshot", duplicate), "pi"));
        var basic = Map.<String, Object>of("name", "basic");
        assertEquals(
                ManagedSessionProtocol.agentToolsets(basic),
                ManagedSessionProtocol.agentToolsets(basic, "agentscope-java"));
    }

    private static Map<String, Object> tools(
            Map<String, Object> defaults, List<Map<String, Object>> configs) {
        return Map.of(
                "tools",
                List.of(
                        Map.of(
                                "type",
                                "agent_toolset",
                                "defaultConfig",
                                defaults,
                                "configs",
                                configs)));
    }

    private static Map<String, Object> config(String name, boolean enabled, String policy) {
        return Map.of(
                "name",
                name,
                "type",
                name,
                "enabled",
                enabled,
                "permission_policy",
                Map.of("type", policy));
    }
}
