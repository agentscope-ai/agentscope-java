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
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.JsonNode;
import io.agentscope.core.ReActAgent;
import io.agentscope.core.agent.RuntimeContext;
import io.agentscope.core.event.AgentResultEvent;
import io.agentscope.core.journal.InMemoryThreadJournalStore;
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
import io.agentscope.core.util.JsonUtils;
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
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

/** Verifies that overflow recovery continues the current reasoning step and preserves its input. */
@HarnessQuiescence
class HarnessAgentOverflowRecoveryTest {

    private static final String SESSION_ID = "overflow-session";

    /** Enough prior turns to leave a compressible prefix. */
    private static final int PRIOR_TURNS = 12;

    /** Padding that keeps prior turns above the fixed summary-wrapper cost after folding. */
    private static final String PAD = " earlier conversation detail".repeat(40);

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
        HarnessAgent agent = newAgent(model, store, new Toolkit(), false);
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
                retryInput.stream().noneMatch(m -> priorTurn(0).equals(m.getTextContent())),
                "retry must run on the compacted context, not the reloaded original");
    }

    /** Overflow after a tool step: the retry continues from it instead of redoing the turn. */
    @ParameterizedTest
    @CsvSource({
        "false,false,false",
        "false,true,false",
        "true,false,false",
        "true,true,false",
        "false,false,true",
        "false,true,true",
        "true,false,true",
        "true,true,true"
    })
    void overflowAfterAToolStepRetriesOnceWithoutRerunningTheTool(
            boolean journal, boolean stream, boolean retryOverflows) {
        AgentStateStore store = new JsonFileAgentStateStore(stateDir);
        ScriptedModel model = new ScriptedModel();
        Probe probe = new Probe();
        Toolkit toolkit = new Toolkit();
        toolkit.registerTool(probe);
        try (HarnessAgent agent = newAgent(model, store, toolkit, journal)) {
            RuntimeContext ctx = runPriorTurns(agent);
            Msg input = userMsg("final question");
            int before = model.mainInputs.size();
            model.script(toolUse("probe-call", "probe"), overflow());
            if (retryOverflows) {
                model.script(overflow());
                RuntimeException error =
                        assertThrows(
                                RuntimeException.class, () -> invoke(agent, input, ctx, stream));
                assertTrue(error.getMessage().contains("context_length_exceeded"));
            } else {
                assertEquals("ok", invoke(agent, input, ctx, stream).getTextContent());
                List<Msg> persisted =
                        journal
                                ? agent.getDelegate().getAgentState(ctx).getContext()
                                : persistedContext(store);
                assertEquals(1, count(persisted, input), "input persisted once");
                assertEquals(1, toolResults(persisted, "probe-call"), "tool result persisted once");
            }
            assertEquals(before + 3, model.mainInputs.size(), "one tool step and two attempts");
            assertEquals(1, probe.calls.get(), "the tool must not run again on the retry");
            assertEquals(1, toolResults(model.lastMainInput(), "probe-call"));
        }
    }

    @ParameterizedTest
    @CsvSource({
        "false,false,false", "false,false,true", "false,true,false", "false,true,true",
        "true,false,false", "true,false,true", "true,true,false", "true,true,true"
    })
    void preservesStructuredOutputAcrossOverflow(
            boolean journal, boolean jsonSchema, boolean stream) {
        var model = new ScriptedModel();
        try (var agent = newAgent(model, new InMemoryAgentStateStore(), new Toolkit(), journal)) {
            List<Msg> messages =
                    List.of(userMsg(priorTurn(0).repeat(8)), userMsg("final question"));
            RuntimeContext ctx = RuntimeContext.builder().sessionId(SESSION_ID).build();
            model.script(
                    overflow(),
                    toolUse(
                            "output",
                            ReActAgent.STRUCTURED_OUTPUT_TOOL_NAME,
                            Map.of("response", Map.of("value", "structured"))));
            JsonNode schema =
                    JsonUtils.getJsonCodec()
                            .fromJson(
                                    """
                                    {"type":"object","properties":{"value":{"type":"string"}},"required":["value"]}
                                    """,
                                    JsonNode.class);
            Mono<Msg> response =
                    stream
                            ? (jsonSchema
                                            ? agent.streamEvents(messages, schema, ctx)
                                            : agent.streamEvents(messages, Output.class, ctx))
                                    .ofType(AgentResultEvent.class)
                                    .single()
                                    .map(AgentResultEvent::getResult)
                            : jsonSchema
                                    ? agent.call(messages, schema, ctx)
                                    : agent.call(messages, Output.class, ctx);
            Msg result = response.block(Duration.ofSeconds(30));
            assertNotNull(result);
            assertEquals("structured", result.getStructuredData(Output.class).value());
            assertEquals(List.of(true, true), model.structuredRequests);
        }
    }

    public record Output(String value) {}

    private static Msg invoke(HarnessAgent agent, Msg input, RuntimeContext ctx, boolean stream) {
        return (stream
                        ? agent.streamEvents(input, ctx)
                                .ofType(AgentResultEvent.class)
                                .single()
                                .map(AgentResultEvent::getResult)
                        : agent.call(input, ctx))
                .block(Duration.ofSeconds(30));
    }

    @ParameterizedTest
    @CsvSource({"class,false", "schema,false", "class,true", "schema,true"})
    void structuredCallsRetainTheirOutputFormatDuringRecovery(
            String outputFormat, boolean overflow) {
        AgentStateStore store = new JsonFileAgentStateStore(stateDir);
        StructuredOverflowModel model = new StructuredOverflowModel();
        try (HarnessAgent agent = newAgent(model, store, new Toolkit(), false)) {
            RuntimeContext ctx = runPriorTurns(agent);
            model.overflowUntilSummarized.set(overflow);
            Msg input = userMsg("Return a receipt with value ok.");
            Msg reply;
            if ("class".equals(outputFormat)) {
                reply =
                        agent.call(List.of(input), Receipt.class, ctx)
                                .block(Duration.ofSeconds(30));
            } else {
                JsonNode schema =
                        JsonUtils.getJsonCodec()
                                .fromJson(
                                        """
                                        {"type":"object","properties":{"value":{"type":"string"}},
                                         "required":["value"],"additionalProperties":false}
                                        """,
                                        JsonNode.class);
                reply = agent.call(List.of(input), schema, ctx).block(Duration.ofSeconds(30));
            }

            assertNotNull(reply);
            assertEquals(
                    overflow, model.summarySeen.get(), "emergency compaction must run on overflow");
            assertEquals(1, count(persistedContext(store), input), "input persisted once");
            assertEquals(
                    1, count(model.lastMainInput, input), "retry must not duplicate the input");
            if (overflow) {
                assertTrue(
                        model.lastMainInput.stream()
                                .noneMatch(m -> priorTurn(0).equals(m.getTextContent())),
                        "retry must use the compacted history");
            }
            assertTrue(reply.hasStructuredData(), "recovery must preserve structured output");
            assertEquals("ok", reply.getStructuredData(Receipt.class).value);
            assertNotNull(
                    model.lastMainOptions.getResponseFormat(), "retry must retain response_format");
        }
    }

    public static class Receipt {
        public String value;
    }

    private HarnessAgent newAgent(
            Model model, AgentStateStore store, Toolkit toolkit, boolean journal) {
        return HarnessAgent.builder()
                .name("overflow-agent")
                .model(model)
                .workspace(workspace)
                .toolkit(toolkit)
                .journalEnabled(journal)
                // The journal rows exercise recovery over the native Thread journal, not its
                // persistence backend. The default workspace journal is SQLite on Windows and
                // pays one durable transaction per storage operation: those rows took 6-38s there
                // against 0.4-1.3s on ubuntu, and one call crossed the 30s deadline (#4042, #4426
                // on 2026-11-26).
                .threadJournalStore(new InMemoryThreadJournalStore())
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
            assertNotNull(agent.call(userMsg(priorTurn(i)), ctx).block(Duration.ofSeconds(30)));
        }
        return ctx;
    }

    /** Creates the padded text of one prior turn so folded turns stay identifiable. */
    private static String priorTurn(int i) {
        return "turn " + i + PAD;
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
        return toolUse(id, name, Map.of());
    }

    private static Flux<ChatResponse> toolUse(String id, String name, Map<String, Object> input) {
        ToolUseBlock use =
                ToolUseBlock.builder()
                        .id(id)
                        .name(name)
                        .input(input)
                        .content(JsonUtils.getJsonCodec().toJson(input))
                        .build();
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

    /** Rejects both structured-output paths until emergency compaction has summarized history. */
    private static final class StructuredOverflowModel implements Model {
        private final AtomicBoolean overflowUntilSummarized = new AtomicBoolean();
        private final AtomicBoolean summarySeen = new AtomicBoolean();
        private volatile List<Msg> lastMainInput = List.of();
        private volatile GenerateOptions lastMainOptions;

        @Override
        public String getModelName() {
            return "structured-overflow-model";
        }

        @Override
        public boolean supportsNativeStructuredOutput() {
            return true;
        }

        @Override
        public boolean supportsNativeStructuredOutputWithTools() {
            return true;
        }

        @Override
        public Flux<ChatResponse> stream(
                List<Msg> messages, List<ToolSchema> tools, GenerateOptions options) {
            if (messages.stream()
                    .anyMatch(m -> m.getTextContent().contains("Context Extraction Assistant"))) {
                summarySeen.set(true);
                return text("Summary of the earlier discussion.");
            }
            if (messages.stream()
                    .anyMatch(m -> m.getTextContent().contains("memory extraction assistant"))) {
                return text("NO_REPLY");
            }
            lastMainInput = List.copyOf(messages);
            lastMainOptions = options;
            if (overflowUntilSummarized.get() && !summarySeen.get()) {
                return overflow();
            }
            return text("{\"value\":\"ok\"}");
        }

        private static Flux<ChatResponse> text(String value) {
            return Flux.just(
                    ChatResponse.builder()
                            .content(List.of(TextBlock.builder().text(value).build()))
                            .finishReason("stop")
                            .build());
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
        private final List<Boolean> structuredRequests = new CopyOnWriteArrayList<>();

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
                structuredRequests.add(
                        tools.stream()
                                .anyMatch(
                                        tool ->
                                                ReActAgent.STRUCTURED_OUTPUT_TOOL_NAME.equals(
                                                        tool.getName())));
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
