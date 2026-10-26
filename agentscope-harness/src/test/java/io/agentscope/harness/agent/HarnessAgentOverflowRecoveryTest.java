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
package io.agentscope.harness.agent;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.agentscope.core.agent.RuntimeContext;
import io.agentscope.core.message.Msg;
import io.agentscope.core.message.MsgRole;
import io.agentscope.core.message.TextBlock;
import io.agentscope.core.message.ToolResultBlock;
import io.agentscope.core.message.ToolUseBlock;
import io.agentscope.core.model.ChatResponse;
import io.agentscope.core.model.GenerateOptions;
import io.agentscope.core.model.Model;
import io.agentscope.core.model.ToolSchema;
import io.agentscope.core.state.AgentState;
import io.agentscope.core.state.AgentStateStore;
import io.agentscope.core.state.InMemoryAgentStateStore;
import io.agentscope.core.state.JsonFileAgentStateStore;
import io.agentscope.core.tool.Tool;
import io.agentscope.core.tool.Toolkit;
import io.agentscope.harness.agent.memory.MemoryConfig;
import io.agentscope.harness.agent.memory.compaction.CompactionConfig;
import io.agentscope.harness.agent.testing.HarnessQuiescence;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Queue;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import reactor.core.publisher.Flux;

/**
 * Regression tests for {@link HarnessAgent}'s context-overflow recovery: since a failed call
 * persists its state (including the turn's input), the retry must neither re-send that input nor
 * be reloaded from the uncompacted persisted state.
 */
@HarnessQuiescence
class HarnessAgentOverflowRecoveryTest {

    private static final String SESSION_ID = "overflow-session";

    /** Enough prior turns that the forced compaction (keepMessages = 20) has a prefix to fold. */
    private static final int PRIOR_TURNS = 12;

    @TempDir Path workspace;

    @TempDir Path stateDir;

    /**
     * Overflow on the turn's first reasoning step. The JSON-file store returns a copy on load, so
     * it also exposes a retry that reloads the uncompacted context; the in-memory store does not.
     */
    @ParameterizedTest
    @ValueSource(strings = {"json-file", "in-memory"})
    void recoveredOverflowKeepsTheInputOnceAndRetriesWithTheCompactedContext(String storeKind) {
        AgentStateStore store =
                "json-file".equals(storeKind)
                        ? new JsonFileAgentStateStore(stateDir)
                        : new InMemoryAgentStateStore();
        ScriptedModel model = new ScriptedModel();
        HarnessAgent agent = newAgent(model, store, new Toolkit());
        RuntimeContext ctx = runPriorTurns(agent);

        Msg input = userMsg("final question");
        model.script(overflow());
        Msg reply = agent.call(input, ctx).block(Duration.ofSeconds(30));

        assertNotNull(reply);
        assertEquals("ok", reply.getTextContent());
        assertEquals(1, count(persistedContext(store), input), "input persisted once");
        List<Msg> retryInput = model.lastMainInput();
        assertEquals(1, count(retryInput, input), "retry must send the input to the model once");
        assertTrue(
                retryInput.stream().noneMatch(m -> "turn 0".equals(m.getTextContent())),
                "retry must run on the compacted context, not the reloaded original");
    }

    /** Overflow after a tool step: the retry continues from it instead of redoing the turn. */
    @Test
    void overflowAfterAToolStepContinuesWithoutRerunningTheTool() {
        AgentStateStore store = new JsonFileAgentStateStore(stateDir);
        ScriptedModel model = new ScriptedModel();
        Probe probe = new Probe();
        Toolkit toolkit = new Toolkit();
        toolkit.registerTool(probe);
        HarnessAgent agent = newAgent(model, store, toolkit);
        RuntimeContext ctx = runPriorTurns(agent);

        Msg input = userMsg("final question");
        model.script(toolUse("probe-call", "probe"), overflow());
        Msg reply = agent.call(input, ctx).block(Duration.ofSeconds(30));

        assertNotNull(reply);
        assertEquals("ok", reply.getTextContent());
        assertEquals(1, probe.calls.get(), "the tool must not run again on the retry");
        List<Msg> persisted = persistedContext(store);
        assertEquals(1, count(persisted, input), "input persisted once");
        assertEquals(1, toolResults(persisted, "probe-call"), "tool result persisted once");
        assertEquals(1, toolResults(model.lastMainInput(), "probe-call"));
    }

    private HarnessAgent newAgent(Model model, AgentStateStore store, Toolkit toolkit) {
        return HarnessAgent.builder()
                .name("overflow-agent")
                .model(model)
                .workspace(workspace)
                .toolkit(toolkit)
                // Overflow recovery only runs on the legacy state-store path.
                .legacySessionHistory(true)
                .stateStore(store)
                .compaction(CompactionConfig.builder().triggerMessages(1_000).build())
                .memory(
                        MemoryConfig.builder()
                                .flushTrigger(MemoryConfig.FlushTrigger.never())
                                .build())
                .build();
    }

    private static RuntimeContext runPriorTurns(HarnessAgent agent) {
        RuntimeContext ctx = RuntimeContext.builder().sessionId(SESSION_ID).build();
        for (int i = 0; i < PRIOR_TURNS; i++) {
            assertNotNull(agent.call(userMsg("turn " + i), ctx).block(Duration.ofSeconds(30)));
        }
        return ctx;
    }

    private static List<Msg> persistedContext(AgentStateStore store) {
        return store.get(null, SESSION_ID, "agent_state", AgentState.class)
                .orElseThrow()
                .getContext();
    }

    private static Msg userMsg(String text) {
        return Msg.builder()
                .role(MsgRole.USER)
                .content(TextBlock.builder().text(text).build())
                .build();
    }

    private static long count(List<Msg> msgs, Msg target) {
        return msgs.stream()
                .filter(m -> Objects.equals(m.getTextContent(), target.getTextContent()))
                .count();
    }

    private static long toolResults(List<Msg> msgs, String toolUseId) {
        return msgs.stream()
                .flatMap(m -> m.getContentBlocks(ToolResultBlock.class).stream())
                .filter(r -> toolUseId.equals(r.getId()))
                .count();
    }

    private static Flux<ChatResponse> overflow() {
        return Flux.error(
                new RuntimeException(
                        "context_length_exceeded: this model's maximum context length was"
                                + " exceeded"));
    }

    private static Flux<ChatResponse> toolUse(String id, String name) {
        ToolUseBlock use =
                ToolUseBlock.builder().id(id).name(name).input(Map.of()).content("{}").build();
        return Flux.just(new ChatResponse("tool", List.of(use), null, Map.of(), "tool_use"));
    }

    /** A tool that counts its invocations. */
    public static class Probe {
        private final AtomicInteger calls = new AtomicInteger();

        @Tool(description = "Read a fixed probe value", readOnly = true)
        public String probe() {
            calls.incrementAndGet();
            return "observed";
        }
    }

    /**
     * Answers {@code "ok"} unless a scripted response is queued. Scripted responses only apply to
     * main-loop requests, which carry the agent's tool schemas; that tells them apart from the
     * compaction summary request.
     */
    private static final class ScriptedModel implements Model {

        private final Queue<Flux<ChatResponse>> script = new ConcurrentLinkedQueue<>();
        private final List<List<Msg>> mainInputs = new CopyOnWriteArrayList<>();

        @SafeVarargs
        final void script(Flux<ChatResponse>... responses) {
            script.addAll(List.of(responses));
        }

        List<Msg> lastMainInput() {
            return mainInputs.get(mainInputs.size() - 1);
        }

        @Override
        public String getModelName() {
            return "scripted-model";
        }

        @Override
        public Flux<ChatResponse> stream(
                List<Msg> messages, List<ToolSchema> tools, GenerateOptions options) {
            if (tools != null && !tools.isEmpty()) {
                mainInputs.add(List.copyOf(messages));
                Flux<ChatResponse> scripted = script.poll();
                if (scripted != null) {
                    return scripted;
                }
            }
            return Flux.just(
                    new ChatResponse(
                            "scripted-model",
                            List.of(TextBlock.builder().text("ok").build()),
                            null,
                            Map.of(),
                            "stop"));
        }
    }
}
