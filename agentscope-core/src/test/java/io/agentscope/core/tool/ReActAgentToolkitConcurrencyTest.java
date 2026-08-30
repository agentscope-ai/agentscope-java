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
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.agentscope.core.ReActAgent;
import io.agentscope.core.agent.RuntimeContext;
import io.agentscope.core.event.AgentEvent;
import io.agentscope.core.event.ToolResultTextDeltaEvent;
import io.agentscope.core.message.Msg;
import io.agentscope.core.message.MsgRole;
import io.agentscope.core.message.TextBlock;
import io.agentscope.core.message.ToolResultBlock;
import io.agentscope.core.message.ToolUseBlock;
import io.agentscope.core.model.ChatResponse;
import io.agentscope.core.model.ChatUsage;
import io.agentscope.core.model.GenerateOptions;
import io.agentscope.core.model.Model;
import io.agentscope.core.model.ToolSchema;
import io.agentscope.core.util.JsonUtils;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

/**
 * Concurrency regression tests for the shared-{@link Toolkit} de-sharing work: a single {@link
 * ReActAgent} instance serving multiple sessions must never let one session's activation groups or
 * streaming chunk callbacks leak into another.
 */
class ReActAgentToolkitConcurrencyTest {

    private static final Duration TIMEOUT = Duration.ofSeconds(30);

    // ==================== Tool group contributors ====================

    static class GroupedToolA {
        @Tool(name = "tool_a", description = "tool A")
        public String toolA(@ToolParam(name = "q", description = "q") String q) {
            return "a:" + q;
        }
    }

    static class GroupedToolB {
        @Tool(name = "tool_b", description = "tool B")
        public String toolB(@ToolParam(name = "q", description = "q") String q) {
            return "b:" + q;
        }
    }

    static class ChunkTool {
        @Tool(name = "stream_task", description = "emits streaming chunks")
        public ToolResultBlock run(
                @ToolParam(name = "input", description = "input") String input,
                ToolEmitter emitter) {
            emitter.emit(ToolResultBlock.text("chunk-" + input));
            emitter.emit(ToolResultBlock.text("chunk2-" + input));
            return ToolResultBlock.text("done-" + input);
        }
    }

    // ==================== Spying / driving models ====================

    /** Records, per reasoning call, the tool names offered, keyed by the user prompt text. */
    static class ToolSpyingModel implements Model {
        final Map<String, Set<String>> toolsByPrompt = new ConcurrentHashMap<>();

        @Override
        public Flux<ChatResponse> stream(
                List<Msg> messages, List<ToolSchema> tools, GenerateOptions options) {
            String prompt = lastUserText(messages);
            Set<String> names =
                    tools == null
                            ? Set.of()
                            : tools.stream().map(ToolSchema::getName).collect(Collectors.toSet());
            toolsByPrompt.put(prompt, names);
            return Flux.just(textResponse("done"));
        }

        @Override
        public String getModelName() {
            return "tool-spy";
        }
    }

    /** Drives a {@code reset_equipped_tools} call (group gA or gB selected by the prompt). */
    static class ResetMetaModel implements Model {
        @Override
        public Flux<ChatResponse> stream(
                List<Msg> messages, List<ToolSchema> tools, GenerateOptions options) {
            if (hasToolResult(messages)) {
                return Flux.just(textResponse("done"));
            }
            String group = lastUserText(messages).contains("A") ? "gA" : "gB";
            ToolUseBlock use =
                    ToolUseBlock.builder()
                            .id("reset-" + group)
                            .name("reset_equipped_tools")
                            .input(Map.of("to_activate", List.of(group)))
                            .content(
                                    JsonUtils.getJsonCodec()
                                            .toJson(Map.of("to_activate", List.of(group))))
                            .build();
            return Flux.just(
                    ChatResponse.builder()
                            .content(List.of(use))
                            .usage(new ChatUsage(1, 1, 0))
                            .build());
        }

        @Override
        public String getModelName() {
            return "reset-meta";
        }
    }

    /** Drives a {@code stream_task} tool call whose chunk text encodes the session ("A"/"B"). */
    static class ChunkModel implements Model {
        @Override
        public Flux<ChatResponse> stream(
                List<Msg> messages, List<ToolSchema> tools, GenerateOptions options) {
            if (hasToolResult(messages)) {
                return Flux.just(textResponse("done"));
            }
            String input = lastUserText(messages).contains("A") ? "A" : "B";
            ToolUseBlock use =
                    ToolUseBlock.builder()
                            .id("stream-" + input)
                            .name("stream_task")
                            .input(Map.of("input", input))
                            .content(JsonUtils.getJsonCodec().toJson(Map.of("input", input)))
                            .build();
            return Flux.just(
                    ChatResponse.builder()
                            .content(List.of(use))
                            .usage(new ChatUsage(1, 1, 0))
                            .build());
        }

        @Override
        public String getModelName() {
            return "chunk-model";
        }
    }

    // ==================== Tests ====================

    @Test
    @DisplayName("concurrent sessions resolve tools from their own activated groups")
    void concurrentSessionsResolveToolsFromTheirOwnActivatedGroups() {
        Toolkit toolkit = new Toolkit();
        toolkit.createToolGroup("gA", "group A", false);
        toolkit.createToolGroup("gB", "group B", false);
        toolkit.registration().tool(new GroupedToolA()).group("gA").apply();
        toolkit.registration().tool(new GroupedToolB()).group("gB").apply();

        ToolSpyingModel model = new ToolSpyingModel();
        ReActAgent agent =
                ReActAgent.builder().name("tool-iso").model(model).toolkit(toolkit).build();

        // Seed distinct activated groups per session.
        agent.getAgentState("uA", "sA").getToolContext().setActivatedGroups(List.of("gA"));
        agent.getAgentState("uB", "sB").getToolContext().setActivatedGroups(List.of("gB"));

        List<String> sharedBefore = new ArrayList<>(toolkit.getActiveGroups());

        Mono<Msg> callA =
                agent.call(
                        List.of(userMsg("prompt-A")),
                        RuntimeContext.builder().userId("uA").sessionId("sA").build());
        Mono<Msg> callB =
                agent.call(
                        List.of(userMsg("prompt-B")),
                        RuntimeContext.builder().userId("uB").sessionId("sB").build());
        Mono.zip(callA, callB).block(TIMEOUT);

        Set<String> toolsA = model.toolsByPrompt.get("prompt-A");
        Set<String> toolsB = model.toolsByPrompt.get("prompt-B");
        assertNotNull(toolsA, "session A reasoning should have run");
        assertNotNull(toolsB, "session B reasoning should have run");
        assertTrue(toolsA.contains("tool_a"), "session A should see tool_a: " + toolsA);
        assertFalse(toolsA.contains("tool_b"), "session A must not see tool_b: " + toolsA);
        assertTrue(toolsB.contains("tool_b"), "session B should see tool_b: " + toolsB);
        assertFalse(toolsB.contains("tool_a"), "session B must not see tool_a: " + toolsB);
        assertEquals(sharedBefore, toolkit.getActiveGroups(), "shared toolkit must not be mutated");
    }

    @Test
    @DisplayName("reset_equipped_tools writes only its own session state, never the shared toolkit")
    void resetEquippedToolsWritesOnlyItsOwnSessionState() {
        Toolkit toolkit = new Toolkit();
        toolkit.createToolGroup("gA", "group A", false);
        toolkit.createToolGroup("gB", "group B", false);
        toolkit.registerMetaTool();

        ReActAgent agent =
                ReActAgent.builder()
                        .name("meta-iso")
                        .model(new ResetMetaModel())
                        .toolkit(toolkit)
                        .build();

        List<String> sharedBefore = new ArrayList<>(toolkit.getActiveGroups());

        Mono<Msg> callA =
                agent.call(
                        List.of(userMsg("activate A")),
                        RuntimeContext.builder().userId("uA").sessionId("sA").build());
        Mono<Msg> callB =
                agent.call(
                        List.of(userMsg("activate B")),
                        RuntimeContext.builder().userId("uB").sessionId("sB").build());
        Mono.zip(callA, callB).block(TIMEOUT);

        assertEquals(
                List.of("gA"),
                agent.getAgentState("uA", "sA").getToolContext().getActivatedGroups(),
                "session A state should keep gA");
        assertEquals(
                List.of("gB"),
                agent.getAgentState("uB", "sB").getToolContext().getActivatedGroups(),
                "session B state should keep gB");
        assertEquals(sharedBefore, toolkit.getActiveGroups(), "shared toolkit must not be mutated");
    }

    @Test
    @DisplayName("concurrent calls do not cross-talk streaming chunk callbacks")
    void concurrentCallsDoNotCrossTalkChunkCallbacks() {
        Toolkit toolkit = new Toolkit();
        toolkit.registerTool(new ChunkTool());

        ReActAgent agent =
                ReActAgent.builder()
                        .name("chunk-iso")
                        .model(new ChunkModel())
                        .toolkit(toolkit)
                        .build();

        RuntimeContext rcA = RuntimeContext.builder().userId("uA").sessionId("sA").build();
        RuntimeContext rcB = RuntimeContext.builder().userId("uB").sessionId("sB").build();

        Flux<AgentEvent> streamA = agent.streamEvents(List.of(userMsg("prompt-A")), rcA);
        Flux<AgentEvent> streamB = agent.streamEvents(List.of(userMsg("prompt-B")), rcB);

        Mono<List<String>> deltasA =
                streamA.ofType(ToolResultTextDeltaEvent.class)
                        .map(ToolResultTextDeltaEvent::getDelta)
                        .collectList();
        Mono<List<String>> deltasB =
                streamB.ofType(ToolResultTextDeltaEvent.class)
                        .map(ToolResultTextDeltaEvent::getDelta)
                        .collectList();

        var tuple = Mono.zip(deltasA, deltasB).block(TIMEOUT);
        assertNotNull(tuple, "both streams should complete");

        List<String> a = tuple.getT1();
        List<String> b = tuple.getT2();
        assertFalse(a.isEmpty(), "session A should receive chunk deltas");
        assertFalse(b.isEmpty(), "session B should receive chunk deltas");
        assertTrue(
                a.stream().allMatch(s -> s.contains("A")),
                "session A chunks must all belong to A, got: " + a);
        assertTrue(
                b.stream().allMatch(s -> s.contains("B")),
                "session B chunks must all belong to B, got: " + b);
    }

    // ==================== Helpers ====================

    private static Msg userMsg(String text) {
        return Msg.builder()
                .name("user")
                .role(MsgRole.USER)
                .content(TextBlock.builder().text(text).build())
                .build();
    }

    private static ChatResponse textResponse(String text) {
        return ChatResponse.builder()
                .content(List.of(TextBlock.builder().text(text).build()))
                .usage(new ChatUsage(1, 1, 0))
                .build();
    }

    private static String lastUserText(List<Msg> messages) {
        for (int i = messages.size() - 1; i >= 0; i--) {
            Msg m = messages.get(i);
            if (m.getRole() == MsgRole.USER) {
                return m.getTextContent();
            }
        }
        return "";
    }

    private static boolean hasToolResult(List<Msg> messages) {
        return messages.stream()
                .flatMap(m -> m.getContent().stream())
                .anyMatch(b -> b instanceof ToolResultBlock);
    }
}
