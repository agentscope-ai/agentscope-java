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
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.agentscope.core.ReActAgent;
import io.agentscope.core.event.AgentEvent;
import io.agentscope.core.event.AgentResultEvent;
import io.agentscope.core.event.ExternalExecutionResultEvent;
import io.agentscope.core.event.RequireExternalExecutionEvent;
import io.agentscope.core.event.TextBlockDeltaEvent;
import io.agentscope.core.event.TextBlockEndEvent;
import io.agentscope.core.event.TextBlockStartEvent;
import io.agentscope.core.hook.Hook;
import io.agentscope.core.hook.HookEvent;
import io.agentscope.core.hook.PostActingEvent;
import io.agentscope.core.message.DataBlock;
import io.agentscope.core.message.GenerateReason;
import io.agentscope.core.message.MessageMetadataKeys;
import io.agentscope.core.message.Msg;
import io.agentscope.core.message.MsgRole;
import io.agentscope.core.message.TextBlock;
import io.agentscope.core.message.ToolResultBlock;
import io.agentscope.core.message.ToolResultState;
import io.agentscope.core.message.ToolUseBlock;
import io.agentscope.core.message.URLSource;
import io.agentscope.core.model.ChatModelBase;
import io.agentscope.core.model.ChatResponse;
import io.agentscope.core.model.GenerateOptions;
import io.agentscope.core.model.ToolSchema;
import io.agentscope.core.permission.PermissionContextState;
import io.agentscope.core.permission.PermissionDecision;
import io.agentscope.core.tool.Tool;
import io.agentscope.core.tool.ToolBase;
import io.agentscope.core.tool.ToolCallParam;
import io.agentscope.core.tool.ToolParam;
import io.agentscope.core.tool.Toolkit;
import io.agentscope.core.util.JsonUtils;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Supplier;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

/**
 * End-to-end tests for the {@code returnDirect} tool semantics implemented in
 * {@link ReActAgent.CallExecution#acting(int)}.
 *
 * <p>A tool that declares {@code returnDirect = true} short-circuits the ReAct loop: instead of
 * feeding the tool result back to the model for another reasoning round, the agent lifts the full
 * result into a synthetic closing assistant message and returns it as the turn's final answer.
 */
class ReActAgentReturnDirectTest {

    private static final String RETURN_DIRECT_PLACEHOLDER =
            "Tool call completed. The result has been presented to the user as the final output"
                    + " of this turn.";

    /** Scripted model returning one {@link ChatResponse} stream per sequential model call. */
    private static final class ScriptedModel extends ChatModelBase {

        private final List<Supplier<Flux<ChatResponse>>> scripts;
        private final AtomicInteger idx = new AtomicInteger(0);

        ScriptedModel(List<Supplier<Flux<ChatResponse>>> scripts) {
            this.scripts = scripts;
        }

        @Override
        public String getModelName() {
            return "scripted";
        }

        @Override
        protected Flux<ChatResponse> doStream(
                List<Msg> messages, List<ToolSchema> tools, GenerateOptions options) {
            int i = idx.getAndIncrement();
            if (i >= scripts.size()) {
                return Flux.just(textResponse(""));
            }
            return scripts.get(i).get();
        }

        int callCount() {
            return idx.get();
        }
    }

    /** Test tool whose returnDirect flag and result are injected via the builder. */
    private static final class TestTool extends ToolBase {

        private ToolResultBlock result;
        private boolean deny;
        private boolean suspended;
        private long delayMillis;

        TestTool(String name, boolean returnDirect, ToolResultBlock result) {
            super(
                    ToolBase.builder()
                            .name(name)
                            .description(name)
                            .inputSchema(schemaFor())
                            .returnDirect(returnDirect));
            this.result = result;
        }

        TestTool deny() {
            this.deny = true;
            return this;
        }

        TestTool suspended() {
            this.suspended = true;
            return this;
        }

        TestTool delayed(long millis) {
            this.delayMillis = millis;
            return this;
        }

        @Override
        public Mono<PermissionDecision> checkPermissions(
                Map<String, Object> toolInput, PermissionContextState context) {
            if (deny) {
                return Mono.just(PermissionDecision.deny("deny: " + getName()));
            }
            return Mono.just(PermissionDecision.passthrough(getName()));
        }

        @Override
        public Mono<ToolResultBlock> callAsync(ToolCallParam param) {
            if (suspended) {
                return Mono.just(ToolResultBlock.suspended(param.getToolUseBlock()));
            }
            return delayMillis > 0
                    ? Mono.just(result).delayElement(Duration.ofMillis(delayMillis))
                    : Mono.just(result);
        }
    }

    static final class AnnotatedReturnDirectTools {
        @Tool(name = "annotated_direct", description = "returns directly", returnDirect = true)
        public String direct(@ToolParam(name = "q", description = "q") String q) {
            return "direct:" + q;
        }
    }

    private static Map<String, Object> schemaFor() {
        Map<String, Object> schema = new HashMap<>();
        schema.put("type", "object");
        Map<String, Object> props = new HashMap<>();
        Map<String, Object> q = new HashMap<>();
        q.put("type", "string");
        props.put("query", q);
        schema.put("properties", props);
        return schema;
    }

    private static ChatResponse textResponse(String text) {
        return ChatResponse.builder()
                .content(List.of(TextBlock.builder().text(text).build()))
                .build();
    }

    private static ChatResponse toolUseResponse(
            String toolId, String toolName, Map<String, Object> arguments) {
        return ChatResponse.builder()
                .content(
                        List.of(
                                ToolUseBlock.builder()
                                        .id(toolId)
                                        .name(toolName)
                                        .input(arguments == null ? Map.of() : arguments)
                                        .content(
                                                JsonUtils.getJsonCodec()
                                                        .toJson(
                                                                arguments == null
                                                                        ? Map.of()
                                                                        : arguments))
                                        .build()))
                .build();
    }

    private static ChatResponse toolUsesResponse(List<ToolUseBlock> toolUses) {
        return ChatResponse.builder().content(List.copyOf(toolUses)).build();
    }

    private static Toolkit toolkitWith(ToolBase... tools) {
        Toolkit tk = new Toolkit();
        for (ToolBase t : tools) {
            tk.registerAgentTool(t);
        }
        return tk;
    }

    private static ReActAgent buildAgent(ChatModelBase model, Toolkit toolkit) {
        return ReActAgent.builder().name("asst").model(model).toolkit(toolkit).build();
    }

    private static List<Msg> contextOf(ReActAgent agent) {
        return agent.getAgentState().getContext();
    }

    private static List<ToolResultBlock> toolResults(ReActAgent agent) {
        List<ToolResultBlock> results = new ArrayList<>();
        for (Msg msg : contextOf(agent)) {
            results.addAll(msg.getContentBlocks(ToolResultBlock.class));
        }
        return results;
    }

    private static Msg findToolResultMsg(ReActAgent agent, String toolId) {
        for (Msg msg : contextOf(agent)) {
            if (msg.getRole() != MsgRole.TOOL) {
                continue;
            }
            List<ToolResultBlock> blocks = msg.getContentBlocks(ToolResultBlock.class);
            if (blocks.stream().anyMatch(b -> toolId.equals(b.getId()))) {
                return msg;
            }
        }
        return null;
    }

    private static Msg findLastAssistantMsg(ReActAgent agent) {
        for (int i = contextOf(agent).size() - 1; i >= 0; i--) {
            Msg m = contextOf(agent).get(i);
            if (m.getRole() == MsgRole.ASSISTANT) {
                return m;
            }
        }
        return null;
    }

    private static long countText(ReActAgent agent, String text) {
        return contextOf(agent).stream()
                .flatMap(m -> m.getContentBlocks(TextBlock.class).stream())
                .filter(b -> text.equals(b.getText()))
                .count();
    }

    /** Resume payload carrying externally produced tool results, mirroring the HITL pattern. */
    private static Msg externalResultMsg(ToolResultBlock... results) {
        return Msg.builder().role(MsgRole.TOOL).content(List.of(results)).build();
    }

    private static ToolResultBlock successResult(String id, String name, String text) {
        return ToolResultBlock.builder()
                .id(id)
                .name(name)
                .output(TextBlock.builder().text(text).build())
                .state(ToolResultState.SUCCESS)
                .build();
    }

    private static int indexOf(List<AgentEvent> events, Class<?> type) {
        for (int i = 0; i < events.size(); i++) {
            if (type.isInstance(events.get(i))) {
                return i;
            }
        }
        return -1;
    }

    // ==== Tests ====

    @Test
    void singleReturnDirectToolBypassesModelAndReturnsOnce() {
        ScriptedModel model =
                new ScriptedModel(
                        List.of(() -> Flux.just(toolUseResponse("tc1", "weather", Map.of()))));
        Toolkit toolkit = toolkitWith(new TestTool("weather", true, ToolResultBlock.text("sunny")));
        ReActAgent agent = buildAgent(model, toolkit);

        Msg result = agent.call(List.of()).block();

        assertNotNull(result);
        assertEquals(GenerateReason.TOOL_RETURN_DIRECT, result.getGenerateReason());
        assertEquals(1, model.callCount(), "returnDirect must skip the follow-up model call");
    }

    @Test
    void sequenceIsCompleteAndFullResultAppearsOnlyInClosingAssistant() {
        ScriptedModel model =
                new ScriptedModel(
                        List.of(() -> Flux.just(toolUseResponse("tc1", "weather", Map.of()))));
        Toolkit toolkit = toolkitWith(new TestTool("weather", true, ToolResultBlock.text("sunny")));
        ReActAgent agent = buildAgent(model, toolkit);

        Msg result = agent.call(List.of()).block();

        // Tool result keeps id/name/state=SUCCESS but only carries the placeholder.
        Msg toolMsg = findToolResultMsg(agent, "tc1");
        assertNotNull(toolMsg);
        ToolResultBlock toolResult = toolMsg.getContentBlocks(ToolResultBlock.class).get(0);
        assertEquals("tc1", toolResult.getId());
        assertEquals("weather", toolResult.getName());
        assertEquals(ToolResultState.SUCCESS, toolResult.getState());
        assertEquals(
                RETURN_DIRECT_PLACEHOLDER,
                ((TextBlock) toolResult.getOutput().get(0)).getText(),
                "tool_result must be replaced with the placeholder");

        // Full result appears exactly once, in the closing assistant message.
        assertEquals(1, countText(agent, "sunny"), "full result must not be duplicated");
        Msg closing = findLastAssistantMsg(agent);
        assertEquals("sunny", closing.getTextContent());
        assertEquals(
                Boolean.TRUE, closing.getMetadata().get(MessageMetadataKeys.TOOL_RETURN_DIRECT));
        assertNotNull(result);
        assertEquals(GenerateReason.TOOL_RETURN_DIRECT, result.getGenerateReason());
    }

    @Test
    void nonReturnDirectToolContinuesLoopToSummarize() {
        ScriptedModel model =
                new ScriptedModel(
                        List.of(
                                () -> Flux.just(toolUseResponse("tc1", "weather", Map.of())),
                                () -> Flux.just(textResponse("it is sunny outside"))));
        Toolkit toolkit =
                toolkitWith(new TestTool("weather", false, ToolResultBlock.text("sunny")));
        ReActAgent agent = buildAgent(model, toolkit);

        Msg result = agent.call(List.of()).block();

        assertNotNull(result);
        assertEquals(GenerateReason.MODEL_STOP, result.getGenerateReason());
        assertEquals("it is sunny outside", result.getTextContent());
    }

    @Test
    void mixedBatchKeepsRealResultAndDoesNotReturnDirect() {
        ScriptedModel model =
                new ScriptedModel(
                        List.of(
                                () ->
                                        Flux.just(
                                                toolUsesResponse(
                                                        List.of(
                                                                ToolUseBlock.builder()
                                                                        .id("tc1")
                                                                        .name("direct")
                                                                        .input(Map.of())
                                                                        .build(),
                                                                ToolUseBlock.builder()
                                                                        .id("tc2")
                                                                        .name("normal")
                                                                        .input(Map.of())
                                                                        .build()))),
                                () -> Flux.just(textResponse("done"))));
        Toolkit toolkit =
                toolkitWith(
                        new TestTool("direct", true, ToolResultBlock.text("direct-out")),
                        new TestTool("normal", false, ToolResultBlock.text("normal-out")));
        ReActAgent agent = buildAgent(model, toolkit);

        Msg result = agent.call(List.of()).block();

        assertNotNull(result);
        assertNotEquals(GenerateReason.TOOL_RETURN_DIRECT, result.getGenerateReason());
        assertEquals(GenerateReason.MODEL_STOP, result.getGenerateReason());

        // The returnDirect tool's real result must remain in context (not placeholder-ized).
        Msg directResultMsg = findToolResultMsg(agent, "tc1");
        assertNotNull(directResultMsg);
        assertEquals(
                "direct-out",
                ((TextBlock)
                                directResultMsg
                                        .getContentBlocks(ToolResultBlock.class)
                                        .get(0)
                                        .getOutput()
                                        .get(0))
                        .getText());
    }

    @Test
    void allReturnDirectToolsAggregateInExecutionOrder() {
        ScriptedModel model =
                new ScriptedModel(
                        List.of(
                                () ->
                                        Flux.just(
                                                toolUsesResponse(
                                                        List.of(
                                                                ToolUseBlock.builder()
                                                                        .id("tc1")
                                                                        .name("first")
                                                                        .input(Map.of())
                                                                        .build(),
                                                                ToolUseBlock.builder()
                                                                        .id("tc2")
                                                                        .name("second")
                                                                        .input(Map.of())
                                                                        .build())))));
        Toolkit toolkit =
                toolkitWith(
                        new TestTool("first", true, ToolResultBlock.text("one")),
                        new TestTool("second", true, ToolResultBlock.text("two")));
        ReActAgent agent = buildAgent(model, toolkit);

        Msg result = agent.call(List.of()).block();

        assertNotNull(result);
        assertEquals(GenerateReason.TOOL_RETURN_DIRECT, result.getGenerateReason());
        List<TextBlock> textBlocks = result.getContentBlocks(TextBlock.class);
        assertEquals(2, textBlocks.size());
        assertEquals("one", textBlocks.get(0).getText());
        assertEquals("two", textBlocks.get(1).getText());
    }

    @Test
    void returnDirectToolThatSuspendsPublishesSuspended() {
        ScriptedModel model =
                new ScriptedModel(
                        List.of(() -> Flux.just(toolUseResponse("tc1", "external", Map.of()))));
        Toolkit toolkit = toolkitWith(new TestTool("external", true, null).suspended());
        ReActAgent agent = buildAgent(model, toolkit);

        Msg result = agent.call(List.of()).block();

        assertNotNull(result);
        assertEquals(GenerateReason.TOOL_SUSPENDED, result.getGenerateReason());
    }

    @Test
    void hitlStopTakesPriorityOverReturnDirect() {
        ScriptedModel model =
                new ScriptedModel(
                        List.of(() -> Flux.just(toolUseResponse("tc1", "weather", Map.of()))));
        Toolkit toolkit = toolkitWith(new TestTool("weather", true, ToolResultBlock.text("sunny")));

        Hook stoppingHook =
                new Hook() {
                    @Override
                    public <T extends HookEvent> Mono<T> onEvent(T event) {
                        if (event instanceof PostActingEvent pa) {
                            pa.stopAgent();
                        }
                        return Mono.just(event);
                    }
                };

        ReActAgent agent =
                ReActAgent.builder()
                        .name("asst")
                        .model(model)
                        .toolkit(toolkit)
                        .hook(stoppingHook)
                        .build();

        Msg result = agent.call(List.of()).block();

        assertNotNull(result);
        assertEquals(GenerateReason.ACTING_STOP_REQUESTED, result.getGenerateReason());
    }

    @Test
    void earlierPostActingStopWinsOverReturnDirect() {
        ScriptedModel model =
                new ScriptedModel(
                        List.of(
                                () ->
                                        Flux.just(
                                                toolUsesResponse(
                                                        List.of(
                                                                ToolUseBlock.builder()
                                                                        .id("tc1")
                                                                        .name("weather")
                                                                        .input(Map.of())
                                                                        .build(),
                                                                ToolUseBlock.builder()
                                                                        .id("tc2")
                                                                        .name("forecast")
                                                                        .input(Map.of())
                                                                        .build())))));
        Toolkit toolkit =
                toolkitWith(
                        new TestTool("weather", true, ToolResultBlock.text("sunny")),
                        new TestTool("forecast", true, ToolResultBlock.text("cloudy")));

        AtomicInteger postActingCount = new AtomicInteger();
        Hook stopFirstToolHook =
                new Hook() {
                    @Override
                    public <T extends HookEvent> Mono<T> onEvent(T event) {
                        if (event instanceof PostActingEvent pa) {
                            postActingCount.incrementAndGet();
                            if ("tc1".equals(pa.getToolUse().getId())) {
                                pa.stopAgent();
                            }
                        }
                        return Mono.just(event);
                    }
                };

        ReActAgent agent =
                ReActAgent.builder()
                        .name("asst")
                        .model(model)
                        .toolkit(toolkit)
                        .hook(stopFirstToolHook)
                        .build();

        Msg result = agent.call(List.of()).block();

        assertNotNull(result);
        assertEquals(
                GenerateReason.ACTING_STOP_REQUESTED,
                result.getGenerateReason(),
                "a stopAgent() raised on an earlier tool of the batch must not be swallowed"
                        + " by taking only the last hook event");
        assertEquals(
                "sunny",
                ((TextBlock)
                                result.getContentBlocks(ToolResultBlock.class)
                                        .get(0)
                                        .getOutput()
                                        .get(0))
                        .getText(),
                "the stop message carries the stopping tool's real result for review");
        assertEquals(1, model.callCount(), "the stop must prevent any further iteration");
        Msg toolMsg = findToolResultMsg(agent, "tc1");
        assertNotNull(toolMsg);
        assertEquals(
                "sunny",
                ((TextBlock)
                                toolMsg.getContentBlocks(ToolResultBlock.class)
                                        .get(0)
                                        .getOutput()
                                        .get(0))
                        .getText(),
                "a stopping tool's result is persisted verbatim, never placeholder-shaped");
        Msg tailMsg = findToolResultMsg(agent, "tc2");
        assertNotNull(tailMsg, "the non-stopping tool of the batch must still be recorded");
        assertEquals(
                "cloudy",
                ((TextBlock)
                                tailMsg.getContentBlocks(ToolResultBlock.class)
                                        .get(0)
                                        .getOutput()
                                        .get(0))
                        .getText(),
                "an executed tool keeps its real result in context even when an earlier tool"
                        + " stops the batch — no placeholder, no dangling tool_use");
        assertEquals(
                2,
                postActingCount.get(),
                "PostActing fires for every executed tool of the batch, even after a stop");
    }

    @Test
    void failingReturnDirectToolDoesNotReturnDirect() {
        ScriptedModel model =
                new ScriptedModel(
                        List.of(
                                () -> Flux.just(toolUseResponse("tc1", "weather", Map.of())),
                                () -> Flux.just(textResponse("recovered"))));
        Toolkit toolkit = toolkitWith(new TestTool("weather", true, ToolResultBlock.error("boom")));
        ReActAgent agent = buildAgent(model, toolkit);

        Msg result = agent.call(List.of()).block();

        assertNotNull(result);
        assertNotEquals(GenerateReason.TOOL_RETURN_DIRECT, result.getGenerateReason());
        assertEquals(GenerateReason.MODEL_STOP, result.getGenerateReason());
        assertEquals("recovered", result.getTextContent());
    }

    @Test
    void deniedReturnDirectToolDoesNotReturnDirect() {
        ScriptedModel model =
                new ScriptedModel(
                        List.of(
                                () -> Flux.just(toolUseResponse("tc1", "weather", Map.of())),
                                () -> Flux.just(textResponse("done"))));
        Toolkit toolkit =
                toolkitWith(new TestTool("weather", true, ToolResultBlock.text("sunny")).deny());
        ReActAgent agent = buildAgent(model, toolkit);

        Msg result = agent.call(List.of()).block();

        assertNotNull(result);
        assertNotEquals(GenerateReason.TOOL_RETURN_DIRECT, result.getGenerateReason());
        assertEquals(GenerateReason.MODEL_STOP, result.getGenerateReason());
        // "Permission denied by rules" must be fed back to the model, not returned as the answer.
        assertEquals("done", result.getTextContent());

        List<ToolResultBlock> results = toolResults(agent);
        assertTrue(
                results.stream().anyMatch(b -> b.getState() == ToolResultState.DENIED),
                "the denied result must be recorded in context");
    }

    @Test
    void nonTextOutputIsForwardedVerbatim() {
        ScriptedModel model =
                new ScriptedModel(
                        List.of(() -> Flux.just(toolUseResponse("tc1", "image", Map.of()))));
        DataBlock image =
                DataBlock.builder().source(new URLSource("https://example.com/img.png")).build();
        Toolkit toolkit = toolkitWith(new TestTool("image", true, ToolResultBlock.of(image)));
        ReActAgent agent = buildAgent(model, toolkit);

        Msg result = agent.call(List.of()).block();

        assertNotNull(result);
        assertEquals(GenerateReason.TOOL_RETURN_DIRECT, result.getGenerateReason());
        List<DataBlock> dataBlocks = result.getContentBlocks(DataBlock.class);
        assertEquals(1, dataBlocks.size());
        assertEquals(
                "https://example.com/img.png",
                ((URLSource) dataBlocks.get(0).getSource()).getUrl());
    }

    @Test
    void emptyOutputIsPaddedWithPlaceholder() {
        ScriptedModel model =
                new ScriptedModel(
                        List.of(() -> Flux.just(toolUseResponse("tc1", "silent", Map.of()))));
        Toolkit toolkit = toolkitWith(new TestTool("silent", true, ToolResultBlock.of(List.of())));
        ReActAgent agent = buildAgent(model, toolkit);

        Msg result = agent.call(List.of()).block();

        assertNotNull(result);
        assertEquals(GenerateReason.TOOL_RETURN_DIRECT, result.getGenerateReason());
        assertEquals(1, model.callCount());
        assertFalse(result.getContent().isEmpty(), "closing message must not be empty");
        assertEquals("(no output)", result.getTextContent());
    }

    @Test
    void partiallyEmptyBatchOmitsPlaceholder() {
        ScriptedModel model =
                new ScriptedModel(
                        List.of(
                                () ->
                                        Flux.just(
                                                toolUsesResponse(
                                                        List.of(
                                                                ToolUseBlock.builder()
                                                                        .id("tc1")
                                                                        .name("empty")
                                                                        .input(Map.of())
                                                                        .build(),
                                                                ToolUseBlock.builder()
                                                                        .id("tc2")
                                                                        .name("text")
                                                                        .input(Map.of())
                                                                        .build())))));
        Toolkit toolkit =
                toolkitWith(
                        new TestTool("empty", true, ToolResultBlock.of(List.of())),
                        new TestTool("text", true, ToolResultBlock.text("real")));
        ReActAgent agent = buildAgent(model, toolkit);

        Msg result = agent.call(List.of()).block();

        assertNotNull(result);
        assertEquals(GenerateReason.TOOL_RETURN_DIRECT, result.getGenerateReason());
        // The zero-block tool contributes nothing — like a model summary skipping it — and no
        // placeholder is injected while the batch still has output.
        assertEquals(1, result.getContent().size());
        assertEquals("real", result.getTextContent());
        assertEquals(
                0, countText(agent, "(no output)"), "no placeholder while the batch has output");
    }

    @Test
    void errorTextPrefixIsDetectedDespiteRunningState() {
        ScriptedModel model =
                new ScriptedModel(
                        List.of(
                                () -> Flux.just(toolUseResponse("tc1", "weather", Map.of())),
                                () -> Flux.just(textResponse("recovered"))));
        ToolResultBlock errorText =
                new ToolResultBlock(
                        null, null, List.of(TextBlock.builder().text("[ERROR] bad").build()), null);
        Toolkit toolkit = toolkitWith(new TestTool("weather", true, errorText));
        ReActAgent agent = buildAgent(model, toolkit);

        Msg result = agent.call(List.of()).block();

        assertNotNull(result);
        assertNotEquals(GenerateReason.TOOL_RETURN_DIRECT, result.getGenerateReason());
        assertEquals(GenerateReason.MODEL_STOP, result.getGenerateReason());
    }

    @Test
    void postActingRewriteDoesNotAffectReturnDirectResult() {
        ScriptedModel model =
                new ScriptedModel(
                        List.of(() -> Flux.just(toolUseResponse("tc1", "weather", Map.of()))));
        Toolkit toolkit = toolkitWith(new TestTool("weather", true, ToolResultBlock.text("sunny")));

        Hook rewritingHook =
                new Hook() {
                    @Override
                    public <T extends HookEvent> Mono<T> onEvent(T event) {
                        if (event instanceof PostActingEvent pa) {
                            pa.setToolResult(ToolResultBlock.error("redacted"));
                        }
                        return Mono.just(event);
                    }
                };

        ReActAgent agent =
                ReActAgent.builder()
                        .name("asst")
                        .model(model)
                        .toolkit(toolkit)
                        .hook(rewritingHook)
                        .build();

        Msg result = agent.call(List.of()).block();

        assertNotNull(result);
        assertEquals(GenerateReason.TOOL_RETURN_DIRECT, result.getGenerateReason());
        assertEquals("sunny", result.getTextContent());
    }

    @Test
    void annotatedReturnDirectToolTakesEffectEndToEnd() {
        ScriptedModel model =
                new ScriptedModel(
                        List.of(
                                () ->
                                        Flux.just(
                                                toolUseResponse(
                                                        "tc1",
                                                        "annotated_direct",
                                                        Map.of("q", "hi")))));
        Toolkit toolkit = new Toolkit();
        toolkit.registerTool(new AnnotatedReturnDirectTools());
        ReActAgent agent = buildAgent(model, toolkit);

        Msg result = agent.call(List.of()).block();

        assertNotNull(result);
        assertEquals(GenerateReason.TOOL_RETURN_DIRECT, result.getGenerateReason());
        // The default @Tool converter JSON-serializes the String result, hence the quotes.
        assertTrue(result.getTextContent().contains("direct:hi"));
    }

    // ==== External-resume closure (caller supplies results after TOOL_SUSPENDED) ====

    @Test
    void externalResumeShortCircuitsReturnDirect() {
        ScriptedModel model =
                new ScriptedModel(
                        List.of(() -> Flux.just(toolUseResponse("tc1", "ext", Map.of()))));
        Toolkit toolkit = toolkitWith(new TestTool("ext", true, null).suspended());
        ReActAgent agent = buildAgent(model, toolkit);

        Msg suspended = agent.call(List.of()).block();
        assertNotNull(suspended);
        assertEquals(GenerateReason.TOOL_SUSPENDED, suspended.getGenerateReason());

        Msg resumed =
                agent.call(List.of(externalResultMsg(successResult("tc1", "ext", "ext-result"))))
                        .block();

        assertNotNull(resumed);
        assertEquals(GenerateReason.TOOL_RETURN_DIRECT, resumed.getGenerateReason());
        assertEquals("ext-result", resumed.getTextContent());
        assertEquals(
                1,
                model.callCount(),
                "external resume short-circuit must skip the closing model call");

        // Context keeps the three-message invariant: tool_use / placeholder tool_result /
        // closing assistant carrying the full result.
        Msg toolMsg = findToolResultMsg(agent, "tc1");
        assertNotNull(toolMsg);
        ToolResultBlock toolResult = toolMsg.getContentBlocks(ToolResultBlock.class).get(0);
        assertEquals("tc1", toolResult.getId());
        assertEquals("ext", toolResult.getName());
        assertEquals(ToolResultState.SUCCESS, toolResult.getState());
        assertEquals(
                RETURN_DIRECT_PLACEHOLDER,
                ((TextBlock) toolResult.getOutput().get(0)).getText(),
                "supplied tool_result must be replaced with the placeholder");
        Msg closing = findLastAssistantMsg(agent);
        assertEquals("ext-result", closing.getTextContent());
        assertEquals(
                Boolean.TRUE, closing.getMetadata().get(MessageMetadataKeys.TOOL_RETURN_DIRECT));
        assertEquals(1, countText(agent, "ext-result"), "full result must not be duplicated");
    }

    @Test
    void externalResumeNormalizesPlaceholderState() {
        ScriptedModel model =
                new ScriptedModel(
                        List.of(() -> Flux.just(toolUseResponse("tc1", "ext", Map.of()))));
        Toolkit toolkit = toolkitWith(new TestTool("ext", true, null).suspended());
        ReActAgent agent = buildAgent(model, toolkit);

        agent.call(List.of()).block();
        ToolResultBlock unsetState =
                ToolResultBlock.builder()
                        .id("tc1")
                        .name("ext")
                        .output(TextBlock.builder().text("ext-result").build())
                        .build();

        Msg resumed = agent.call(List.of(externalResultMsg(unsetState))).block();

        assertNotNull(resumed);
        assertEquals(GenerateReason.TOOL_RETURN_DIRECT, resumed.getGenerateReason());
        Msg toolMsg = findToolResultMsg(agent, "tc1");
        assertNotNull(toolMsg);
        assertEquals(
                ToolResultState.SUCCESS,
                toolMsg.getContentBlocks(ToolResultBlock.class).get(0).getState(),
                "the persisted placeholder must carry the state the short-circuit gate"
                        + " determined (null/RUNNING normalized to SUCCESS), matching the"
                        + " in-framework path");
    }

    @ParameterizedTest
    @EnumSource(
            value = ToolResultState.class,
            names = {"ERROR", "DENIED"})
    void externalResumeWithNonSuccessResultDoesNotShortCircuit(ToolResultState state) {
        ScriptedModel model =
                new ScriptedModel(
                        List.of(
                                () -> Flux.just(toolUseResponse("tc1", "ext", Map.of())),
                                () -> Flux.just(textResponse("recovered"))));
        Toolkit toolkit = toolkitWith(new TestTool("ext", true, null).suspended());
        ReActAgent agent = buildAgent(model, toolkit);

        agent.call(List.of()).block();
        ToolResultBlock nonSuccess =
                ToolResultBlock.builder()
                        .id("tc1")
                        .name("ext")
                        .output(TextBlock.builder().text("failed externally").build())
                        .state(state)
                        .build();

        Msg resumed = agent.call(List.of(externalResultMsg(nonSuccess))).block();

        assertNotNull(resumed);
        assertEquals(GenerateReason.MODEL_STOP, resumed.getGenerateReason());
        assertEquals("recovered", resumed.getTextContent());
        assertEquals(
                2,
                model.callCount(),
                state + " results must be fed back to the model, not returned as the answer");
    }

    @Test
    void externalResumeWithMixedBatchDoesNotShortCircuit() {
        ScriptedModel model =
                new ScriptedModel(
                        List.of(
                                () ->
                                        Flux.just(
                                                toolUsesResponse(
                                                        List.of(
                                                                ToolUseBlock.builder()
                                                                        .id("tc1")
                                                                        .name("direct")
                                                                        .input(Map.of())
                                                                        .build(),
                                                                ToolUseBlock.builder()
                                                                        .id("tc2")
                                                                        .name("normal")
                                                                        .input(Map.of())
                                                                        .build()))),
                                () -> Flux.just(textResponse("done"))));
        Toolkit toolkit =
                toolkitWith(
                        new TestTool("direct", true, null).suspended(),
                        new TestTool("normal", false, null).suspended());
        ReActAgent agent = buildAgent(model, toolkit);

        Msg suspended = agent.call(List.of()).block();
        assertNotNull(suspended);
        assertEquals(GenerateReason.TOOL_SUSPENDED, suspended.getGenerateReason());

        Msg resumed =
                agent.call(
                                List.of(
                                        externalResultMsg(
                                                successResult("tc1", "direct", "direct-out"),
                                                successResult("tc2", "normal", "normal-out"))))
                        .block();

        assertNotNull(resumed);
        assertNotEquals(GenerateReason.TOOL_RETURN_DIRECT, resumed.getGenerateReason());
        assertEquals(GenerateReason.MODEL_STOP, resumed.getGenerateReason());
        assertEquals("done", resumed.getTextContent());

        // Mixed batches keep the real (non-placeholder) results in context.
        Msg directResultMsg = findToolResultMsg(agent, "tc1");
        assertNotNull(directResultMsg);
        assertEquals(
                "direct-out",
                ((TextBlock)
                                directResultMsg
                                        .getContentBlocks(ToolResultBlock.class)
                                        .get(0)
                                        .getOutput()
                                        .get(0))
                        .getText());
    }

    @Test
    void externalAndInternalPathsAreIsomorphic() {
        // Internal path: the framework executes the returnDirect tool itself.
        ScriptedModel internalModel =
                new ScriptedModel(
                        List.of(() -> Flux.just(toolUseResponse("tc1", "ext", Map.of()))));
        ReActAgent internalAgent =
                buildAgent(
                        internalModel,
                        toolkitWith(new TestTool("ext", true, ToolResultBlock.text("sunny"))));
        Msg internal = internalAgent.call(List.of()).block();

        // External path: the tool suspends and the caller supplies the result.
        ScriptedModel externalModel =
                new ScriptedModel(
                        List.of(() -> Flux.just(toolUseResponse("tc1", "ext", Map.of()))));
        ReActAgent externalAgent =
                buildAgent(externalModel, toolkitWith(new TestTool("ext", true, null).suspended()));
        externalAgent.call(List.of()).block();
        Msg external =
                externalAgent
                        .call(List.of(externalResultMsg(successResult("tc1", "ext", "sunny"))))
                        .block();

        assertNotNull(internal);
        assertNotNull(external);
        // Return contract.
        assertEquals(internal.getGenerateReason(), external.getGenerateReason());
        assertEquals(GenerateReason.TOOL_RETURN_DIRECT, external.getGenerateReason());
        assertEquals(internal.getTextContent(), external.getTextContent());
        // Metadata: same keys, same flags.
        Msg internalClosing = findLastAssistantMsg(internalAgent);
        Msg externalClosing = findLastAssistantMsg(externalAgent);
        assertEquals(
                internalClosing.getMetadata().keySet(), externalClosing.getMetadata().keySet());
        // Context shape: [assistant(tool_use), tool(placeholder), assistant(full result)].
        List<Msg> internalContext = contextOf(internalAgent);
        List<Msg> externalContext = contextOf(externalAgent);
        assertEquals(3, internalContext.size());
        assertEquals(3, externalContext.size());
        for (int i = 0; i < 3; i++) {
            assertEquals(internalContext.get(i).getRole(), externalContext.get(i).getRole());
            assertEquals(
                    internalContext.get(i).getContentBlocks(ToolUseBlock.class).size(),
                    externalContext.get(i).getContentBlocks(ToolUseBlock.class).size());
            assertEquals(
                    internalContext.get(i).getContentBlocks(ToolResultBlock.class).size(),
                    externalContext.get(i).getContentBlocks(ToolResultBlock.class).size());
        }
        assertEquals(
                RETURN_DIRECT_PLACEHOLDER,
                ((TextBlock)
                                externalContext
                                        .get(1)
                                        .getContentBlocks(ToolResultBlock.class)
                                        .get(0)
                                        .getOutput()
                                        .get(0))
                        .getText());
    }

    @Test
    void internalReturnDirectEmitsClosingTextEventsBeforeAgentResult() {
        ScriptedModel model =
                new ScriptedModel(
                        List.of(() -> Flux.just(toolUseResponse("tc1", "weather", Map.of()))));
        Toolkit toolkit = toolkitWith(new TestTool("weather", true, ToolResultBlock.text("sunny")));
        ReActAgent agent = buildAgent(model, toolkit);

        List<AgentEvent> events = agent.streamEvents(List.of()).collectList().block();
        assertNotNull(events);

        int iStart = indexOf(events, TextBlockStartEvent.class);
        int iDelta = indexOf(events, TextBlockDeltaEvent.class);
        int iEnd = indexOf(events, TextBlockEndEvent.class);
        int iResult = indexOf(events, AgentResultEvent.class);
        assertTrue(iStart >= 0, "closing TextBlockStartEvent must be emitted");
        assertTrue(iDelta > iStart, "closing delta must follow its start");
        assertTrue(iEnd > iDelta, "closing end must follow its delta");
        assertTrue(iResult > iEnd, "closing text events must precede AgentResultEvent");

        TextBlockStartEvent start = (TextBlockStartEvent) events.get(iStart);
        TextBlockDeltaEvent delta = (TextBlockDeltaEvent) events.get(iDelta);
        TextBlockEndEvent end = (TextBlockEndEvent) events.get(iEnd);
        assertEquals(start.getBlockId(), delta.getBlockId());
        assertEquals(delta.getBlockId(), end.getBlockId());
        assertEquals(start.getReplyId(), delta.getReplyId());
        assertEquals("sunny", delta.getDelta(), "delta must carry the full closing text");
        assertEquals(
                GenerateReason.TOOL_RETURN_DIRECT.name(),
                delta.getMetadata().get(AgentEvent.METADATA_GENERATE_REASON),
                "delta metadata must mark the substitution reason for audit");
    }

    @Test
    void externalResumeClosingEventsCorrelateToSuspendedReplyId() {
        ScriptedModel model =
                new ScriptedModel(
                        List.of(() -> Flux.just(toolUseResponse("tc1", "ext", Map.of()))));
        Toolkit toolkit = toolkitWith(new TestTool("ext", true, null).suspended());
        ReActAgent agent = buildAgent(model, toolkit);

        List<AgentEvent> suspendEvents = agent.streamEvents(List.of()).collectList().block();
        assertNotNull(suspendEvents);
        int iRequire = indexOf(suspendEvents, RequireExternalExecutionEvent.class);
        assertTrue(iRequire >= 0, "RequireExternalExecutionEvent expected");
        String suspendedReplyId =
                ((RequireExternalExecutionEvent) suspendEvents.get(iRequire)).getReplyId();

        List<AgentEvent> resumeEvents =
                agent.streamEvents(
                                List.of(
                                        externalResultMsg(
                                                successResult("tc1", "ext", "ext-result"))))
                        .collectList()
                        .block();
        assertNotNull(resumeEvents);

        int iExternalResult = indexOf(resumeEvents, ExternalExecutionResultEvent.class);
        int iStart = indexOf(resumeEvents, TextBlockStartEvent.class);
        int iDelta = indexOf(resumeEvents, TextBlockDeltaEvent.class);
        int iEnd = indexOf(resumeEvents, TextBlockEndEvent.class);
        int iResult = indexOf(resumeEvents, AgentResultEvent.class);
        assertTrue(iExternalResult >= 0, "ExternalExecutionResultEvent must still be emitted");
        ExternalExecutionResultEvent externalResult =
                (ExternalExecutionResultEvent) resumeEvents.get(iExternalResult);
        assertEquals(1, externalResult.getToolResults().size());
        assertEquals(
                "ext-result",
                ((TextBlock) externalResult.getToolResults().get(0).getOutput().get(0)).getText(),
                "ExternalExecutionResultEvent must carry the caller's real result, not the"
                        + " returnDirect placeholder");
        assertTrue(iStart > iExternalResult, "closing events follow the external-result event");
        assertTrue(iDelta > iStart);
        assertTrue(iEnd > iDelta);
        assertTrue(iResult > iEnd, "closing text events must precede AgentResultEvent");

        TextBlockDeltaEvent delta = (TextBlockDeltaEvent) resumeEvents.get(iDelta);
        assertEquals(
                suspendedReplyId,
                delta.getReplyId(),
                "closing events must correlate to the suspended request's replyId");
        assertEquals("ext-result", delta.getDelta());
        assertEquals(
                GenerateReason.TOOL_RETURN_DIRECT.name(),
                delta.getMetadata().get(AgentEvent.METADATA_GENERATE_REASON));
    }

    @Test
    void parallelSkewPreservesDeclarationOrder() {
        ScriptedModel model =
                new ScriptedModel(
                        List.of(
                                () ->
                                        Flux.just(
                                                toolUsesResponse(
                                                        List.of(
                                                                ToolUseBlock.builder()
                                                                        .id("tc1")
                                                                        .name("slow")
                                                                        .input(Map.of())
                                                                        .build(),
                                                                ToolUseBlock.builder()
                                                                        .id("tc2")
                                                                        .name("fast")
                                                                        .input(Map.of())
                                                                        .build())))));
        DataBlock image =
                DataBlock.builder().source(new URLSource("https://example.com/a.png")).build();
        ToolResultBlock slowResult =
                ToolResultBlock.of(List.of(TextBlock.builder().text("slow-text").build(), image));
        Toolkit toolkit =
                toolkitWith(
                        new TestTool("slow", true, slowResult).delayed(300),
                        new TestTool("fast", true, ToolResultBlock.text("fast-text")));
        ReActAgent agent = buildAgent(model, toolkit);

        Msg result = agent.call(List.of()).block();

        assertNotNull(result);
        assertEquals(GenerateReason.TOOL_RETURN_DIRECT, result.getGenerateReason());
        // Content order follows tool_calls declaration order, not completion order (slow is
        // delayed, fast finishes first).
        List<TextBlock> textBlocks = result.getContentBlocks(TextBlock.class);
        assertEquals(2, textBlocks.size());
        assertEquals("slow-text", textBlocks.get(0).getText());
        assertEquals("fast-text", textBlocks.get(1).getText());
        assertEquals(3, result.getContent().size(), "slow-text + image + fast-text");
    }
}
