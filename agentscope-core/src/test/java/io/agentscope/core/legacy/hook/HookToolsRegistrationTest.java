/*
 * Copyright 2024-2026 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      https://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package io.agentscope.core.legacy.hook;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import io.agentscope.core.ReActAgent;
import io.agentscope.core.agent.test.MockModel;
import io.agentscope.core.hook.Hook;
import io.agentscope.core.hook.HookEvent;
import io.agentscope.core.message.ToolResultBlock;
import io.agentscope.core.message.ToolUseBlock;
import io.agentscope.core.model.ToolSchema;
import io.agentscope.core.tool.AgentTool;
import io.agentscope.core.tool.Tool;
import io.agentscope.core.tool.ToolBase;
import io.agentscope.core.tool.ToolCallParam;
import io.agentscope.core.tool.ToolEmitter;
import io.agentscope.core.tool.Toolkit;
import io.agentscope.core.tool.ToolkitConfig;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import reactor.core.publisher.Mono;

/** Tests {@link Hook#tools()} registration during {@link ReActAgent.Builder#build()}. */
@DisplayName("Hook bundled tools registration")
class HookToolsRegistrationTest {

    private final MockModel model = new MockModel("ok");

    @Test
    @DisplayName("build() registers AgentTool instances from Hook.tools() on agent toolkit")
    void registersAgentToolsFromHook() {
        AgentTool ping =
                new AgentTool() {
                    @Override
                    public String getName() {
                        return "hook_ping";
                    }

                    @Override
                    public String getDescription() {
                        return "ping";
                    }

                    @Override
                    public Map<String, Object> getParameters() {
                        return Map.of(
                                "type", "object",
                                "properties", Map.of(),
                                "required", Collections.emptyList());
                    }

                    @Override
                    public Mono<ToolResultBlock> callAsync(ToolCallParam param) {
                        return Mono.just(ToolResultBlock.text("ok"));
                    }
                };

        Hook hook =
                new Hook() {
                    @Override
                    public <T extends HookEvent> Mono<T> onEvent(T event) {
                        return Mono.just(event);
                    }

                    @Override
                    public List<Object> tools() {
                        return List.of(ping);
                    }
                };

        Toolkit builderToolkit = new Toolkit();
        ReActAgent agent =
                ReActAgent.builder()
                        .name("a")
                        .model(model)
                        .toolkit(builderToolkit)
                        .hook(hook)
                        .build();

        assertNotNull(agent.getToolkit().getTool("hook_ping"));
        assertFalse(builderToolkit.getToolNames().contains("hook_ping"));
    }

    @Test
    @DisplayName("build() registers @Tool POJOs returned by Hook.tools()")
    void registersMethodToolsFromHook() {
        class Pojo {
            @Tool(name = "hook_add")
            public int add(int a, int b) {
                return a + b;
            }
        }

        Hook hook =
                new Hook() {
                    @Override
                    public <T extends HookEvent> Mono<T> onEvent(T event) {
                        return Mono.just(event);
                    }

                    @Override
                    public List<Object> tools() {
                        return List.of(new Pojo());
                    }
                };

        Toolkit builderToolkit = new Toolkit();
        ReActAgent agent =
                ReActAgent.builder()
                        .name("a")
                        .model(model)
                        .toolkit(builderToolkit)
                        .hook(hook)
                        .build();

        assertTrue(agent.getToolkit().getToolNames().contains("hook_add"));
    }

    @Test
    void filteredHookToolsPreserveMetadataAndToolkitConfiguration() {
        AtomicInteger toolsCalls = new AtomicInteger();
        AtomicInteger chunks = new AtomicInteger();
        AtomicReference<String> executionThread = new AtomicReference<>();
        class Pojo {
            @Tool(name = "hook_keep", readOnly = true, concurrencySafe = false)
            public String keep(ToolEmitter emitter) {
                executionThread.set(Thread.currentThread().getName());
                emitter.emit(ToolResultBlock.text("progress"));
                return "done";
            }

            @Tool(name = "hook_drop")
            public String drop() {
                return "excluded";
            }
        }
        Pojo pojo = new Pojo();
        Hook hook =
                new Hook() {
                    @Override
                    public <T extends HookEvent> Mono<T> onEvent(T event) {
                        return Mono.just(event);
                    }

                    @Override
                    public List<Object> tools() {
                        toolsCalls.incrementAndGet();
                        return List.of(pojo);
                    }
                };
        ExecutorService executor =
                Executors.newSingleThreadExecutor(r -> new Thread(r, "hook-filter-executor"));
        Toolkit toolkit =
                new Toolkit(
                        ToolkitConfig.builder()
                                .executorService(executor)
                                .allowToolDeletion(false)
                                .build());
        toolkit.setChunkCallback((call, chunk) -> chunks.incrementAndGet());
        try (ReActAgent agent =
                ReActAgent.builder()
                        .name("filtered")
                        .model(model)
                        .toolkit(toolkit)
                        .hook(hook)
                        .hook(hook)
                        .build(toolName -> !"hook_drop".equals(toolName))) {
            assertEquals(1, toolsCalls.get());
            assertEquals(1, agent.getHooks().size());
            assertSame(hook, agent.getHooks().get(0));
            assertTrue(toolkit.getToolNames().isEmpty());
            assertNull(agent.getToolkit().getTool("hook_drop"));
            ToolBase kept =
                    assertInstanceOf(ToolBase.class, agent.getToolkit().getTool("hook_keep"));
            assertTrue(kept.isReadOnly());
            assertFalse(kept.isConcurrencySafe());
            ToolUseBlock call =
                    ToolUseBlock.builder()
                            .id("keep")
                            .name("hook_keep")
                            .input(Map.of())
                            .content("{}")
                            .build();
            List<ToolResultBlock> results =
                    agent.getToolkit()
                            .callTools(List.of(call), null, agent, null)
                            .block(Duration.ofSeconds(5));
            assertNotNull(results);
            assertEquals(1, results.size());
            assertEquals("hook-filter-executor", executionThread.get());
            assertEquals(1, chunks.get());
            agent.getToolkit().removeTool("hook_keep");
            assertSame(kept, agent.getToolkit().getTool("hook_keep"));
        } finally {
            executor.shutdownNow();
        }
    }

    @ParameterizedTest
    @ValueSource(booleans = {true, false})
    void filtersAgentToolsWithoutFilteringExistingOrBuiltinTools(boolean keep) {
        AgentTool existing = namedTool("existing");
        AgentTool original = namedTool("shared");
        AgentTool replacement = namedTool("shared");
        AgentTool contributed = namedTool("hook_tool");
        Toolkit toolkit = new Toolkit();
        toolkit.registerAgentTool(existing);
        toolkit.registerAgentTool(original);
        Hook hook = mock(Hook.class);
        when(hook.tools()).thenReturn(List.of(replacement, contributed));
        List<String> testedNames = new ArrayList<>();

        try (ReActAgent agent =
                ReActAgent.builder()
                        .name("filtered")
                        .model(model)
                        .toolkit(toolkit)
                        .enableMetaTool(true)
                        .hook(hook)
                        .build(
                                toolName -> {
                                    testedNames.add(toolName);
                                    return keep;
                                })) {
            assertEquals(Set.of("shared", "hook_tool"), Set.copyOf(testedNames));
            assertEquals(2, testedNames.size());
            assertSame(existing, agent.getToolkit().getTool("existing"));
            assertSame(keep ? replacement : original, agent.getToolkit().getTool("shared"));
            assertSame(keep ? contributed : null, agent.getToolkit().getTool("hook_tool"));
            assertNotNull(agent.getToolkit().getTool("reset_equipped_tools"));
            assertSame(original, toolkit.getTool("shared"));
            assertEquals(Set.of("existing", "shared"), toolkit.getToolNames());
        }
    }

    @Test
    void filtersResolvedAgentAndAnnotatedToolNamesIndependently() {
        class Pojo {
            @Tool(name = "annotated_keep")
            public String keep() {
                return "kept";
            }

            @Tool(name = "annotated_drop")
            public String drop() {
                return "dropped";
            }

            @Tool
            public String defaultName() {
                return "method name";
            }
        }
        AgentTool kept = namedTool("agent_keep");
        AgentTool dropped = namedTool("agent_drop");
        Hook hook = mock(Hook.class);
        when(hook.tools()).thenReturn(List.of(kept, dropped, new Pojo()));
        Set<String> retainedNames = Set.of("agent_keep", "annotated_keep", "defaultName");
        List<String> testedNames = new ArrayList<>();

        try (ReActAgent agent =
                ReActAgent.builder()
                        .name("filtered")
                        .model(model)
                        .hook(hook)
                        .build(
                                toolName -> {
                                    testedNames.add(toolName);
                                    return retainedNames.contains(toolName);
                                })) {
            assertEquals(
                    Set.of(
                            "agent_keep",
                            "agent_drop",
                            "annotated_keep",
                            "annotated_drop",
                            "defaultName"),
                    Set.copyOf(testedNames));
            assertEquals(5, testedNames.size());
            assertSame(kept, agent.getToolkit().getTool("agent_keep"));
            assertEquals(retainedNames, agent.getToolkit().getToolNames());
            assertEquals(
                    retainedNames,
                    Set.copyOf(
                            agent.getToolkit().getToolSchemas().stream()
                                    .map(ToolSchema::getName)
                                    .toList()));
        }
    }

    @Test
    void filterRunsAfterToolNameResolutionWithoutChangingHookOrderOrDeduplication() {
        AgentTool firstTool = namedTool("shared");
        AgentTool lastTool = namedTool("shared");
        Hook first = mock(Hook.class);
        Hook second = mock(Hook.class);
        Hook third = mock(Hook.class);
        when(first.priority()).thenReturn(100);
        when(second.priority()).thenReturn(10);
        when(third.priority()).thenReturn(100);
        List<String> registrationOrder = new ArrayList<>();
        when(first.tools())
                .thenAnswer(
                        invocation -> {
                            registrationOrder.add("first");
                            return List.of(firstTool);
                        });
        when(second.tools())
                .thenAnswer(
                        invocation -> {
                            registrationOrder.add("second");
                            return List.of(lastTool);
                        });
        when(third.tools()).thenReturn(List.of());
        List<String> testedNames = new ArrayList<>();

        try (ReActAgent agent =
                ReActAgent.builder()
                        .name("filtered")
                        .model(model)
                        .hooks(List.of(first, second, third, first))
                        .build(
                                toolName -> {
                                    assertEquals(List.of("first", "second"), registrationOrder);
                                    testedNames.add(toolName);
                                    return true;
                                })) {
            assertEquals(List.of("shared"), testedNames);
            assertSame(lastTool, agent.getToolkit().getTool("shared"));
            assertEquals(List.of(second, first, third), agent.getHooks());
            verify(first, times(1)).tools();
            verify(second, times(1)).tools();
            verify(third, times(1)).tools();
        }
    }

    private static AgentTool namedTool(String name) {
        AgentTool tool = mock(AgentTool.class);
        when(tool.getName()).thenReturn(name);
        when(tool.getDescription()).thenReturn(name);
        when(tool.getParameters()).thenReturn(Map.of("type", "object", "properties", Map.of()));
        return tool;
    }

    @Test
    @DisplayName("Hook.tools() returning null is treated as empty")
    void nullToolsListIgnored() {
        Hook hook =
                new Hook() {
                    @Override
                    public <T extends HookEvent> Mono<T> onEvent(T event) {
                        return Mono.just(event);
                    }

                    @Override
                    public List<Object> tools() {
                        return null;
                    }
                };

        assertDoesNotThrow(
                () -> {
                    ReActAgent agent =
                            ReActAgent.builder().name("a").model(model).hook(hook).build();
                    assertNotNull(agent.getToolkit());
                });
    }
}
