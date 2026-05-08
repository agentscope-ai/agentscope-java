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
package io.agentscope.core.hook;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.agentscope.core.ReActAgent;
import io.agentscope.core.agent.test.MockModel;
import io.agentscope.core.message.ToolResultBlock;
import io.agentscope.core.tool.AgentTool;
import io.agentscope.core.tool.Tool;
import io.agentscope.core.tool.ToolCallParam;
import io.agentscope.core.tool.Toolkit;
import java.util.Collections;
import java.util.List;
import java.util.Map;
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
