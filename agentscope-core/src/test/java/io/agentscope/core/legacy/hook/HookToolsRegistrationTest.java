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

import io.agentscope.core.ReActAgent;
import io.agentscope.core.agent.test.MockModel;
import io.agentscope.core.hook.Hook;
import io.agentscope.core.hook.HookEvent;
import io.agentscope.core.message.ToolResultBlock;
import io.agentscope.core.message.ToolUseBlock;
import io.agentscope.core.tool.AgentTool;
import io.agentscope.core.tool.Tool;
import io.agentscope.core.tool.ToolBase;
import io.agentscope.core.tool.ToolCallParam;
import io.agentscope.core.tool.ToolEmitter;
import io.agentscope.core.tool.Toolkit;
import io.agentscope.core.tool.ToolkitConfig;
import java.time.Duration;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
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
                        .build(hookTools -> hookTools.removeTool("hook_drop"))) {
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
