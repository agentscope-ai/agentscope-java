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
package io.agentscope.core.agent;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.agentscope.core.ReActAgent;
import io.agentscope.core.message.Msg;
import io.agentscope.core.message.TextBlock;
import io.agentscope.core.message.ToolResultBlock;
import io.agentscope.core.message.ToolUseBlock;
import io.agentscope.core.middleware.MiddlewareBase;
import io.agentscope.core.model.ChatModelBase;
import io.agentscope.core.model.ChatResponse;
import io.agentscope.core.model.ChatUsage;
import io.agentscope.core.model.GenerateOptions;
import io.agentscope.core.model.ToolSchema;
import io.agentscope.core.tool.AgentTool;
import io.agentscope.core.tool.ToolCallParam;
import io.agentscope.core.tool.Toolkit;
import io.agentscope.core.util.JsonUtils;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

@DisplayName("ReActAgent Tool Group Sync")
class ReActAgentToolGroupSyncTest {

    private static final Duration TIMEOUT = Duration.ofSeconds(3);

    @Test
    @DisplayName("onSystemPrompt-activated groups are visible in the first reasoning round")
    void onSystemPromptActivatedGroupsVisibleImmediately() {
        Toolkit toolkit = new Toolkit();
        CopyOnWriteArrayList<List<String>> seenToolRounds = new CopyOnWriteArrayList<>();

        AgentTool dynamicProbe = simpleTool("dynamic_probe", "dynamic-ready");
        MiddlewareBase middleware =
                new MiddlewareBase() {
                    @Override
                    public Mono<String> onSystemPrompt(
                            Agent agent, RuntimeContext ctx, String currentPrompt) {
                        Toolkit liveToolkit = agent.getToolkit();
                        if (liveToolkit.getToolGroup("dynamic_skill_tools") == null) {
                            liveToolkit.createToolGroup(
                                    "dynamic_skill_tools", "dynamic skill tools", false);
                        }
                        if (liveToolkit.getTool("dynamic_probe") == null) {
                            liveToolkit
                                    .registration()
                                    .agentTool(dynamicProbe)
                                    .group("dynamic_skill_tools")
                                    .apply();
                        }
                        liveToolkit.updateToolGroups(List.of("dynamic_skill_tools"), true);
                        return Mono.just(currentPrompt);
                    }
                };

        ChatModelBase model =
                new ChatModelBase() {
                    @Override
                    public String getModelName() {
                        return "capture-first-round-tools";
                    }

                    @Override
                    protected Flux<ChatResponse> doStream(
                            List<Msg> messages, List<ToolSchema> tools, GenerateOptions options) {
                        seenToolRounds.add(toolNames(tools));
                        return Flux.just(textResponse("done"));
                    }
                };

        ReActAgent agent =
                ReActAgent.builder()
                        .name("tool-sync-agent")
                        .sysPrompt("system")
                        .model(model)
                        .toolkit(toolkit)
                        .middlewares(List.of(middleware))
                        .build();

        Msg out = agent.call(List.of()).block(TIMEOUT);

        assertNotNull(out);
        assertEquals(1, seenToolRounds.size());
        assertTrue(
                seenToolRounds.get(0).contains("dynamic_probe"),
                "expected first reasoning round to see the middleware-activated tool, got "
                        + seenToolRounds);
        assertTrue(
                agent.getAgentState()
                        .getToolContext()
                        .getActivatedGroups()
                        .contains("dynamic_skill_tools"));
    }

    @Test
    @DisplayName("tool-activated groups become visible on the next reasoning round")
    void actingActivatedGroupsVisibleOnNextReasoningRound() {
        Toolkit toolkit = new Toolkit();
        String skillGroup = "weather_workspace_skill_tools";
        toolkit.createToolGroup(skillGroup, skillGroup, false);
        toolkit.registration()
                .agentTool(
                        new AgentTool() {
                            @Override
                            public String getName() {
                                return "load_skill_through_path";
                            }

                            @Override
                            public String getDescription() {
                                return "Activate a skill tool group";
                            }

                            @Override
                            public Map<String, Object> getParameters() {
                                return Map.of(
                                        "type",
                                        "object",
                                        "properties",
                                        Map.of(
                                                "skillId", Map.of("type", "string"),
                                                "path", Map.of("type", "string")),
                                        "required",
                                        List.of("skillId", "path"));
                            }

                            @Override
                            public Mono<ToolResultBlock> callAsync(ToolCallParam param) {
                                param.getAgent()
                                        .getToolkit()
                                        .updateToolGroups(List.of(skillGroup), true);
                                return Mono.just(ToolResultBlock.text("loaded"));
                            }
                        })
                .apply();
        toolkit.registration()
                .agentTool(simpleTool("weather_lookup", "sunny"))
                .group(skillGroup)
                .apply();

        AtomicInteger round = new AtomicInteger();
        CopyOnWriteArrayList<List<String>> seenToolRounds = new CopyOnWriteArrayList<>();
        ChatModelBase model =
                new ChatModelBase() {
                    @Override
                    public String getModelName() {
                        return "capture-post-acting-tools";
                    }

                    @Override
                    protected Flux<ChatResponse> doStream(
                            List<Msg> messages, List<ToolSchema> tools, GenerateOptions options) {
                        List<String> toolNames = toolNames(tools);
                        seenToolRounds.add(toolNames);
                        return switch (round.getAndIncrement()) {
                            case 0 -> {
                                assertTrue(
                                        toolNames.contains("load_skill_through_path"),
                                        "expected skill loader in first round tools: " + toolNames);
                                assertFalse(
                                        toolNames.contains("weather_lookup"),
                                        "skill-bound tool should stay hidden before activation: "
                                                + toolNames);
                                yield Flux.just(
                                        toolCallResponse(
                                                "load_skill_through_path",
                                                "load-1",
                                                Map.of(
                                                        "skillId",
                                                        "weather_workspace",
                                                        "path",
                                                        "SKILL.md")));
                            }
                            case 1 -> {
                                assertTrue(
                                        toolNames.contains("weather_lookup"),
                                        "expected activated skill tool in next reasoning round: "
                                                + toolNames);
                                yield Flux.just(
                                        toolCallResponse("weather_lookup", "weather-1", Map.of()));
                            }
                            default -> Flux.just(textResponse("done"));
                        };
                    }
                };

        ReActAgent agent =
                ReActAgent.builder()
                        .name("tool-sync-agent")
                        .sysPrompt("system")
                        .model(model)
                        .toolkit(toolkit)
                        .build();

        Msg out = agent.call(List.of()).block(TIMEOUT);

        assertNotNull(out);
        assertEquals(3, seenToolRounds.size());
        assertTrue(
                agent.getAgentState().getToolContext().getActivatedGroups().contains(skillGroup));
    }

    private static AgentTool simpleTool(String name, String resultText) {
        return new AgentTool() {
            @Override
            public String getName() {
                return name;
            }

            @Override
            public String getDescription() {
                return "Test tool " + name;
            }

            @Override
            public Map<String, Object> getParameters() {
                return Map.of("type", "object", "properties", Map.of());
            }

            @Override
            public Mono<ToolResultBlock> callAsync(ToolCallParam param) {
                return Mono.just(ToolResultBlock.text(resultText));
            }
        };
    }

    private static List<String> toolNames(List<ToolSchema> tools) {
        return tools == null ? List.of() : tools.stream().map(ToolSchema::getName).toList();
    }

    private static ChatResponse textResponse(String text) {
        return ChatResponse.builder()
                .content(List.of(TextBlock.builder().text(text).build()))
                .usage(new ChatUsage(1, 1, 0))
                .build();
    }

    private static ChatResponse toolCallResponse(
            String toolName, String toolCallId, Map<String, Object> input) {
        return ChatResponse.builder()
                .content(
                        List.of(
                                ToolUseBlock.builder()
                                        .name(toolName)
                                        .id(toolCallId)
                                        .input(input)
                                        .content(JsonUtils.getJsonCodec().toJson(input))
                                        .build()))
                .usage(new ChatUsage(1, 1, 0))
                .build();
    }
}
