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

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.agentscope.core.message.ToolResultBlock;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import reactor.core.publisher.Mono;

class ToolRegistryTest {

    private ToolRegistry registry;
    private AgentTool mockTool1;
    private AgentTool mockTool2;
    private RegisteredToolFunction registered1;
    private RegisteredToolFunction registered2;

    @BeforeEach
    void setUp() {
        registry = new ToolRegistry();

        // Create mock tools
        mockTool1 = createMockTool("tool1", "Description 1");
        mockTool2 = createMockTool("tool2", "Description 2");

        // Create registered wrappers
        registered1 = new RegisteredToolFunction(mockTool1, null, null);
        registered2 = new RegisteredToolFunction(mockTool2, null, "mcpClient1");
    }

    private AgentTool createMockTool(String name, String description) {
        return new AgentTool() {
            @Override
            public String getName() {
                return name;
            }

            @Override
            public String getDescription() {
                return description;
            }

            @Override
            public Map<String, Object> getParameters() {
                return new HashMap<>();
            }

            @Override
            public Mono<ToolResultBlock> callAsync(ToolCallParam param) {
                return Mono.just(ToolResultBlock.text("result"));
            }
        };
    }

    @Test
    void testRegisterTool() {
        // Act
        registry.registerTool("tool1", mockTool1, registered1);

        // Assert
        assertEquals(mockTool1, registry.getTool("tool1"));
        assertEquals(registered1, registry.getRegisteredTool("tool1"));
        assertTrue(registry.getToolNames().contains("tool1"));
    }

    @Test
    void testRegisterMultipleTools() {
        // Act
        registry.registerTool("tool1", mockTool1, registered1);
        registry.registerTool("tool2", mockTool2, registered2);

        // Assert
        assertEquals(2, registry.getToolNames().size());
        assertEquals(mockTool1, registry.getTool("tool1"));
        assertEquals(mockTool2, registry.getTool("tool2"));
    }

    @Test
    void testGetToolNotFound() {
        // Act
        AgentTool result = registry.getTool("nonexistent");

        // Assert
        assertNull(result);
    }

    @Test
    void testGetRegisteredToolNotFound() {
        // Act
        RegisteredToolFunction result = registry.getRegisteredTool("nonexistent");

        // Assert
        assertNull(result);
    }

    @Test
    void testGetRegisteredToolNullAndBlankName() {
        assertNull(registry.getRegisteredTool(null));
        assertNull(registry.getRegisteredTool("   "));
    }

    @Test
    void testGetToolNames() {
        // Arrange
        registry.registerTool("tool1", mockTool1, registered1);
        registry.registerTool("tool2", mockTool2, registered2);

        // Act
        Set<String> names = registry.getToolNames();

        // Assert
        assertEquals(2, names.size());
        assertTrue(names.contains("tool1"));
        assertTrue(names.contains("tool2"));
    }

    @Test
    void testGetToolNamesEmpty() {
        // Act
        Set<String> names = registry.getToolNames();

        // Assert
        assertTrue(names.isEmpty());
    }

    @Test
    void testGetAllRegisteredTools() {
        // Arrange
        registry.registerTool("tool1", mockTool1, registered1);
        registry.registerTool("tool2", mockTool2, registered2);

        // Act
        Map<String, RegisteredToolFunction> allTools = registry.getAllRegisteredTools();

        // Assert
        assertEquals(2, allTools.size());
        assertEquals(registered1, allTools.get("tool1"));
        assertEquals(registered2, allTools.get("tool2"));
    }

    @Test
    void testGetAllRegisteredToolsReturnsNewMap() {
        // Arrange
        registry.registerTool("tool1", mockTool1, registered1);

        // Act
        Map<String, RegisteredToolFunction> map1 = registry.getAllRegisteredTools();
        Map<String, RegisteredToolFunction> map2 = registry.getAllRegisteredTools();

        // Assert
        assertNotSame(map1, map2, "Should return a new map each time");
        assertEquals(map1, map2, "But maps should be equal");
    }

    @Test
    void testRemoveTool() {
        // Arrange
        registry.registerTool("tool1", mockTool1, registered1);
        registry.registerTool("tool2", mockTool2, registered2);

        // Act
        registry.removeTool("tool1");

        // Assert
        assertNull(registry.getTool("tool1"));
        assertNull(registry.getRegisteredTool("tool1"));
        assertEquals(1, registry.getToolNames().size());
        assertTrue(registry.getToolNames().contains("tool2"));
    }

    @Test
    void testRemoveNonexistentTool() {
        // Arrange
        registry.registerTool("tool1", mockTool1, registered1);

        // Act & Assert - should not throw
        assertDoesNotThrow(() -> registry.removeTool("nonexistent"));

        // Verify original tool still exists
        assertEquals(mockTool1, registry.getTool("tool1"));
    }

    @Test
    void testRemoveTools() {
        // Arrange
        registry.registerTool("tool1", mockTool1, registered1);
        registry.registerTool("tool2", mockTool2, registered2);
        AgentTool mockTool3 = createMockTool("tool3", "Description 3");
        RegisteredToolFunction registered3 = new RegisteredToolFunction(mockTool3, null, null);
        registry.registerTool("tool3", mockTool3, registered3);

        // Act
        registry.removeTools(Set.of("tool1", "tool3"));

        // Assert
        assertNull(registry.getTool("tool1"));
        assertNull(registry.getTool("tool3"));
        assertEquals(mockTool2, registry.getTool("tool2"));
        assertEquals(1, registry.getToolNames().size());
    }

    @Test
    void testRemoveToolsWithEmptySet() {
        // Arrange
        registry.registerTool("tool1", mockTool1, registered1);

        // Act
        registry.removeTools(Set.of());

        // Assert
        assertEquals(mockTool1, registry.getTool("tool1"));
        assertEquals(1, registry.getToolNames().size());
    }

    @Test
    void testRemoveToolsWithNonexistentNames() {
        // Arrange
        registry.registerTool("tool1", mockTool1, registered1);

        // Act & Assert
        assertDoesNotThrow(() -> registry.removeTools(Set.of("nonexistent1", "nonexistent2")));

        // Verify original tool still exists
        assertEquals(mockTool1, registry.getTool("tool1"));
    }

    @Test
    void testOverwriteTool() {
        // Arrange
        registry.registerTool("tool1", mockTool1, registered1);
        AgentTool newTool = createMockTool("tool1", "New Description");
        RegisteredToolFunction newRegistered = new RegisteredToolFunction(newTool, null, null);

        // A duplicate name from a different tool now fails fast (issue #3328)...
        IllegalStateException ex =
                assertThrows(
                        IllegalStateException.class,
                        () -> registry.registerTool("tool1", newTool, newRegistered));
        assertTrue(ex.getMessage().contains("tool1"));
        assertEquals(mockTool1, registry.getTool("tool1"));

        // ...while explicit replacement keeps the overwrite capability available.
        registry.registerTool("tool1", newTool, newRegistered, true);

        // Assert
        assertEquals(newTool, registry.getTool("tool1"));
        assertEquals(newRegistered, registry.getRegisteredTool("tool1"));
        assertEquals("New Description", registry.getTool("tool1").getDescription());
        assertEquals(1, registry.getToolNames().size());
    }

    @Test
    @DisplayName("The same-source refresh predicate sees the binding being replaced")
    void testRefreshPredicateEvaluatedInsideTheAtomicBind() {
        // The refresh decision must be taken against the CURRENT binding (under the per-name
        // lock), never against a snapshot inspected before it. Otherwise a registration that
        // merely re-declares an old source could silently overwrite an explicit replacement
        // that landed in between.
        AgentTool stale = createMockTool("race", "stale-source");
        registry.registerTool("race", stale, new RegisteredToolFunction(stale, null, null));

        // An explicit replacement lands first (deterministic stand-in for the racing thread).
        AgentTool replacement = createMockTool("race", "explicit-replacement");
        registry.registerTool(
                "race", replacement, new RegisteredToolFunction(replacement, null, null), true);

        AgentTool incomingRefresh = createMockTool("race", "same-source-refresh");
        AtomicBoolean predicateRan = new AtomicBoolean(false);
        // A refresh that would only accept the STALE binding must conflict against the current
        // one — the predicate is evaluated inside the atomic bind, where it observes the
        // replacement.
        assertThrows(
                IllegalStateException.class,
                () ->
                        registry.registerTool(
                                "race",
                                incomingRefresh,
                                new RegisteredToolFunction(incomingRefresh, null, null),
                                false,
                                existing -> {
                                    predicateRan.set(true);
                                    return existing == stale;
                                }));

        assertTrue(predicateRan.get(), "the predicate must be consulted inside the bind");
        assertSame(replacement, registry.getTool("race"), "the explicit replacement survives");
        assertSame(
                replacement,
                registry.getRegisteredTool("race").getTool(),
                "metadata stays with the surviving binding");
    }

    @Test
    @DisplayName("Metadata is accepted only with a tool, and removed together with its binding")
    void testRemoveDropsBindingAndMetadataTogether() {
        AgentTool first = createMockTool("paired", "first");
        registry.registerTool("paired", first, new RegisteredToolFunction(first, null, null));
        AgentTool second = createMockTool("paired", "second");
        registry.registerTool(
                "paired", second, new RegisteredToolFunction(second, null, null), true);

        assertFalse(
                registry.removeToolIfSame("paired", first),
                "a removal by a superseded instance must not touch the current binding");
        assertSame(second, registry.getTool("paired"));
        assertSame(second, registry.getRegisteredTool("paired").getTool());

        assertTrue(registry.removeToolIfSame("paired", second));
        assertNull(registry.getTool("paired"));
        assertNull(registry.getRegisteredTool("paired"), "metadata goes with the binding");
    }

    @Test
    @DisplayName("registerTool rejects a null tool or metadata instead of writing half a row")
    void testRegisterRejectsNullToolOrMetadata() {
        AgentTool tool = createMockTool("nullmeta", "desc");
        assertThrows(
                IllegalArgumentException.class,
                () -> registry.registerTool("nullmeta", tool, null, true));
        assertThrows(
                IllegalArgumentException.class,
                () ->
                        registry.registerTool(
                                "nullmeta", null, new RegisteredToolFunction(tool, null, null)));

        // Metadata must wrap the very tool being registered: the per-name identity check runs
        // against {@code tool} while the write installs {@code registered}, so a mismatched
        // pair could silently swap the bound instance under the guise of a same-instance
        // refresh, breaking the binding==metadata invariant.
        AgentTool different = createMockTool("nullmeta", "different");
        RegisteredToolFunction mismatched = new RegisteredToolFunction(different, null, null);
        assertThrows(
                IllegalArgumentException.class,
                () -> registry.registerTool("nullmeta", tool, mismatched));
        assertThrows(
                IllegalArgumentException.class,
                () -> registry.registerTool("nullmeta", tool, mismatched, true));

        assertTrue(registry.getToolNames().isEmpty(), "nothing may be written by a rejected call");
        assertTrue(registry.getAllRegisteredTools().isEmpty());
    }

    @Test
    @DisplayName("Concurrent replacement and removal never desync the binding from its metadata")
    void testReplacementRemovalInterleavingKeepsRegistryConsistent() throws Exception {
        String toolName = "contended";
        AgentTool initial = createMockTool(toolName, "initial");
        registry.registerTool(toolName, initial, new RegisteredToolFunction(initial, null, null));

        int rounds = 400;
        ExecutorService pool = Executors.newFixedThreadPool(4);
        try {
            List<Future<?>> futures = new ArrayList<>();
            for (int round = 0; round < rounds; round++) {
                final int iteration = round;
                futures.add(
                        pool.submit(
                                () -> {
                                    AgentTool next =
                                            createMockTool(toolName, "replacement-" + iteration);
                                    registry.registerTool(
                                            toolName,
                                            next,
                                            new RegisteredToolFunction(next, null, null),
                                            true);
                                    return null;
                                }));
                futures.add(
                        pool.submit(
                                () -> {
                                    registry.removeTool(toolName);
                                    return null;
                                }));
            }
            for (Future<?> future : futures) {
                future.get(60, TimeUnit.SECONDS);
            }
        } finally {
            pool.shutdownNow();
        }

        // Contract guard, checked through every read path: whatever a schema reader advertises
        // must resolve to a bound tool, and every bound name must carry its metadata. With two
        // independent maps, the interleaving above could leave metadata without a binding (a
        // ghost ToolSchemaProvider would keep advertising) — these loops are exactly what such
        // a defect would fail on; the single-map design keeps them true by construction.
        // Checked at quiescence on purpose: each getter is atomic only for ITS OWN read, so a
        // mid-run cross-getter assertion would flag legal interleavings (a concurrent replace
        // landing between the two calls) as false violations; ghosts, if ever reintroduced by
        // a split, are persistent and caught here.
        for (String name : registry.getAllRegisteredTools().keySet()) {
            assertNotNull(
                    registry.getTool(name),
                    "advertised metadata must resolve to a bound tool: " + name);
        }
        for (String name : registry.getToolNames()) {
            assertNotNull(
                    registry.getRegisteredTool(name),
                    "every binding must carry its metadata: " + name);
        }
        assertEquals(registry.getToolNames(), registry.getAllRegisteredTools().keySet());
    }

    @Test
    void testConcurrentAccess() {
        // This tests that ConcurrentHashMap is being used correctly
        // Register tools from multiple threads
        Thread t1 =
                new Thread(
                        () -> {
                            for (int i = 0; i < 100; i++) {
                                AgentTool tool = createMockTool("tool_t1_" + i, "Desc " + i);
                                RegisteredToolFunction reg =
                                        new RegisteredToolFunction(tool, null, null);
                                registry.registerTool("tool_t1_" + i, tool, reg);
                            }
                        });

        Thread t2 =
                new Thread(
                        () -> {
                            for (int i = 0; i < 100; i++) {
                                AgentTool tool = createMockTool("tool_t2_" + i, "Desc " + i);
                                RegisteredToolFunction reg =
                                        new RegisteredToolFunction(tool, null, null);
                                registry.registerTool("tool_t2_" + i, tool, reg);
                            }
                        });

        // Act
        t1.start();
        t2.start();

        // Wait for threads to complete
        assertDoesNotThrow(
                () -> {
                    t1.join();
                    t2.join();
                });

        // Assert
        assertEquals(200, registry.getToolNames().size());
    }

    @Test
    @DisplayName("Should throw IllegalArgumentException register null name tool")
    void testRegisterNullNameTool() {
        assertThrows(
                IllegalArgumentException.class,
                () -> registry.registerTool(null, mockTool1, registered1));
    }

    @Test
    @DisplayName("Should throw IllegalArgumentException register empty name tool")
    void testRegisterEmptyNameTool() {
        assertThrows(
                IllegalArgumentException.class,
                () -> registry.registerTool("", mockTool1, registered1));
    }

    @Test
    @DisplayName("Should throw IllegalArgumentException register blank name tool")
    void testRegisterBlankNameTool() {
        assertThrows(
                IllegalArgumentException.class,
                () -> registry.registerTool("  ", mockTool1, registered1));
    }

    @Test
    @DisplayName("Should throw IllegalArgumentException remove null name tool")
    void testRemoveNullNameTool() {
        assertThrows(IllegalArgumentException.class, () -> registry.removeTool(null));
    }

    @Test
    @DisplayName("Should throw IllegalArgumentException remove empty name tool")
    void testRemoveEmptyNameTool() {
        assertThrows(IllegalArgumentException.class, () -> registry.removeTool(""));
    }

    @Test
    @DisplayName("Should throw IllegalArgumentException remove blank name tool")
    void testRemoveBlankNameTool() {
        assertThrows(IllegalArgumentException.class, () -> registry.removeTool("  "));
    }

    @Test
    @DisplayName("Should return null get null name tool")
    void testGetNullNameTool() {
        assertNull(registry.getTool(null));
    }

    @Test
    @DisplayName("Should return null get empty name tool")
    void testGetEmptyNameTool() {
        assertNull(registry.getTool(""));
    }

    @Test
    @DisplayName("Should return null get blank name tool")
    void testGetBlankNameTool() {
        assertNull(registry.getTool("  "));
    }
}
