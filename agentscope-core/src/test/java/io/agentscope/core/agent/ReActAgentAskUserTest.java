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
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.agentscope.core.ReActAgent;
import io.agentscope.core.event.AgentEvent;
import io.agentscope.core.event.AgentResultEvent;
import io.agentscope.core.event.AskUserResult;
import io.agentscope.core.event.ConfirmResult;
import io.agentscope.core.event.RequestStopEvent;
import io.agentscope.core.event.RequireUserAskEvent;
import io.agentscope.core.event.RequireUserConfirmEvent;
import io.agentscope.core.event.UserAskResultEvent;
import io.agentscope.core.message.ContentBlock;
import io.agentscope.core.message.GenerateReason;
import io.agentscope.core.message.Msg;
import io.agentscope.core.message.MsgRole;
import io.agentscope.core.message.TextBlock;
import io.agentscope.core.message.ToolCallState;
import io.agentscope.core.message.ToolResultBlock;
import io.agentscope.core.message.ToolUseBlock;
import io.agentscope.core.model.ChatModelBase;
import io.agentscope.core.model.ChatResponse;
import io.agentscope.core.model.GenerateOptions;
import io.agentscope.core.model.ToolSchema;
import io.agentscope.core.permission.PermissionContextState;
import io.agentscope.core.permission.PermissionDecision;
import io.agentscope.core.permission.PermissionMode;
import io.agentscope.core.tool.ToolBase;
import io.agentscope.core.tool.ToolCallParam;
import io.agentscope.core.tool.Toolkit;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Supplier;
import org.junit.jupiter.api.Test;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

/**
 * End-to-end tests for the model-initiated question flow (HITL "ask" direction): a tool whose
 * {@code checkPermissions()} returns {@link PermissionDecision#askUser(String)} pauses the run
 * with {@link GenerateReason#ASK_USER_ASKING}; callers resume by issuing a second
 * {@code call} carrying {@link AskUserResult}(s) under {@link Msg#METADATA_ASK_USER_RESULTS}.
 * The tool itself is never executed.
 */
class ReActAgentAskUserTest {

    private static final class ScriptedModel extends ChatModelBase {
        private final List<Supplier<Flux<ChatResponse>>> scripts;
        private final AtomicInteger idx = new AtomicInteger(0);
        private final List<List<Msg>> seenInputs = new ArrayList<>();

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
            seenInputs.add(List.copyOf(messages));
            int i = idx.getAndIncrement();
            if (i >= scripts.size()) {
                return Flux.just(textResponse(""));
            }
            return scripts.get(i).get();
        }
    }

    private static ChatResponse textResponse(String text) {
        return ChatResponse.builder()
                .content(List.<ContentBlock>of(TextBlock.builder().text(text).build()))
                .build();
    }

    private static ChatResponse askToolUseResponse(String toolId) {
        return askToolUseResponse(toolId, "ask_user");
    }

    private static ChatResponse askToolUseResponse(String toolId, String toolName) {
        Map<String, Object> questions =
                Map.of(
                        "id", "q_1",
                        "question", "What is your budget?",
                        "type", "single",
                        "options", List.of(Map.of("label", "cheap"), Map.of("label", "premium")));
        Map<String, Object> input = new HashMap<>();
        input.put("questions", List.of(questions));
        return ChatResponse.builder()
                .content(
                        List.<ContentBlock>of(
                                ToolUseBlock.builder()
                                        .id(toolId)
                                        .name(toolName)
                                        .input(input)
                                        .build()))
                .build();
    }

    private static ChatResponse secretAskToolUseResponse(String toolId) {
        Map<String, Object> question =
                Map.of(
                        "id", "q_secret",
                        "question", "What is the API key?",
                        "type", "secret");
        return ChatResponse.builder()
                .content(
                        List.<ContentBlock>of(
                                ToolUseBlock.builder()
                                        .id(toolId)
                                        .name("ask_user")
                                        .input(Map.of("questions", List.of(question)))
                                        .build()))
                .build();
    }

    private static ChatResponse secretAskToolUseResponseWithoutId(String toolId) {
        Map<String, Object> question = Map.of("question", "What is the API key?", "type", "secret");
        return ChatResponse.builder()
                .content(
                        List.<ContentBlock>of(
                                ToolUseBlock.builder()
                                        .id(toolId)
                                        .name("ask_user")
                                        .input(Map.of("questions", List.of(question)))
                                        .build()))
                .build();
    }

    private static ChatResponse mixedToolUseResponse() {
        Map<String, Object> question =
                Map.of(
                        "id", "q_1",
                        "question", "What is your budget?",
                        "type", "single",
                        "options", List.of(Map.of("label", "cheap"), Map.of("label", "premium")));
        Map<String, Object> askInput = new HashMap<>();
        askInput.put("questions", List.of(question));
        return ChatResponse.builder()
                .content(
                        List.of(
                                ToolUseBlock.builder()
                                        .id("ask-1")
                                        .name("ask_user")
                                        .input(askInput)
                                        .build(),
                                ToolUseBlock.builder()
                                        .id("confirm-1")
                                        .name("confirm")
                                        .input(Map.of("query", "mutate"))
                                        .build()))
                .build();
    }

    private static final class AskTool extends ToolBase {
        private final AtomicInteger invocations = new AtomicInteger(0);

        AskTool(String name) {
            super(name, "asks the user", schemaFor(), true, true, false, null, false, false);
        }

        private static Map<String, Object> schemaFor() {
            Map<String, Object> schema = new HashMap<>();
            schema.put("type", "object");
            Map<String, Object> props = new HashMap<>();
            Map<String, Object> q = new HashMap<>();
            q.put("type", "string");
            props.put("questions", q);
            schema.put("properties", props);
            return schema;
        }

        @Override
        public Mono<PermissionDecision> checkPermissions(
                Map<String, Object> toolInput, PermissionContextState context) {
            return Mono.just(PermissionDecision.askUser("ask the user: " + getName()));
        }

        @Override
        public Mono<ToolResultBlock> callAsync(ToolCallParam param) {
            invocations.incrementAndGet();
            return Mono.just(ToolResultBlock.text("executed:" + getName()));
        }
    }

    private static final class ConfirmTool extends ToolBase {
        private final AtomicInteger invocations = new AtomicInteger(0);

        ConfirmTool() {
            super(
                    "confirm",
                    "requires confirmation",
                    schemaFor(),
                    false,
                    true,
                    false,
                    null,
                    false,
                    false);
        }

        private static Map<String, Object> schemaFor() {
            Map<String, Object> schema = new HashMap<>();
            schema.put("type", "object");
            Map<String, Object> props = new HashMap<>();
            Map<String, Object> query = new HashMap<>();
            query.put("type", "string");
            props.put("query", query);
            schema.put("properties", props);
            return schema;
        }

        @Override
        public Mono<PermissionDecision> checkPermissions(
                Map<String, Object> toolInput, PermissionContextState context) {
            return Mono.just(PermissionDecision.ask("confirm before execution"));
        }

        @Override
        public Mono<ToolResultBlock> callAsync(ToolCallParam param) {
            invocations.incrementAndGet();
            return Mono.just(ToolResultBlock.text("confirmed"));
        }
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

    private static ReActAgent buildBypassAgent(ChatModelBase model, Toolkit toolkit) {
        return ReActAgent.builder()
                .name("asst")
                .model(model)
                .toolkit(toolkit)
                .permissionContext(
                        PermissionContextState.builder().mode(PermissionMode.BYPASS).build())
                .build();
    }

    private static Msg answerMsg(AskUserResult... results) {
        Map<String, Object> meta = new HashMap<>();
        meta.put(Msg.METADATA_ASK_USER_RESULTS, List.of(results));
        return Msg.builder()
                .name("user")
                .role(MsgRole.USER)
                .textContent("[answers]")
                .metadata(meta)
                .build();
    }

    private static Msg confirmMsg(ToolUseBlock... toolCalls) {
        Map<String, Object> meta = new HashMap<>();
        List<ConfirmResult> results =
                List.of(toolCalls).stream().map(call -> new ConfirmResult(true, call)).toList();
        meta.put(Msg.METADATA_CONFIRM_RESULTS, results);
        return Msg.builder()
                .name("user")
                .role(MsgRole.USER)
                .textContent("[confirmations]")
                .metadata(meta)
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

    @Test
    void askUserToolPausesFirstCallWithAskingReasonAndIsNotExecuted() {
        AskTool tool = new AskTool("ask_user");
        ChatModelBase model =
                new ScriptedModel(List.of(() -> Flux.just(askToolUseResponse("tc1"))));
        ReActAgent agent = buildAgent(model, toolkitWith(tool));

        Msg firstResult = agent.call(List.of()).block();
        assertNotNull(firstResult);
        assertEquals(GenerateReason.ASK_USER_ASKING, firstResult.getGenerateReason());

        List<ToolUseBlock> returnedBlocks = firstResult.getContentBlocks(ToolUseBlock.class);
        assertEquals(
                1, returnedBlocks.size(), "returned Msg must contain the pending ToolUseBlock");
        assertEquals(ToolCallState.ASKING, returnedBlocks.get(0).getState());
        assertEquals("tc1", returnedBlocks.get(0).getId());
        assertEquals(
                "ASK_USER",
                returnedBlocks.get(0).getMetadata().get(ToolUseBlock.METADATA_PERMISSION_BEHAVIOR));
        assertEquals(0, tool.invocations.get(), "ask_user must never be executed");
    }

    @Test
    void askUserPausesUnderBypassMode() {
        AskTool tool = new AskTool("ask_user");
        ChatModelBase model =
                new ScriptedModel(List.of(() -> Flux.just(askToolUseResponse("tc1"))));
        ReActAgent agent = buildBypassAgent(model, toolkitWith(tool));

        Msg firstResult = agent.call(List.of()).block();
        assertNotNull(firstResult);
        assertEquals(GenerateReason.ASK_USER_ASKING, firstResult.getGenerateReason());
        assertEquals(0, tool.invocations.get());
    }

    @Test
    void resumeWithAnswersContinuesAndToolIsNotExecuted() {
        AskTool tool = new AskTool("ask_user");
        ScriptedModel model =
                new ScriptedModel(
                        List.of(
                                () -> Flux.just(askToolUseResponse("tc1")),
                                () -> Flux.just(textResponse("done"))));
        ReActAgent agent = buildAgent(model, toolkitWith(tool));

        Msg firstResult = agent.call(List.of()).block();
        assertNotNull(firstResult);
        assertEquals(GenerateReason.ASK_USER_ASKING, firstResult.getGenerateReason());

        Msg resumed =
                agent.call(List.of(answerMsg(new AskUserResult("tc1", Map.of("q_1", "premium")))))
                        .block();
        assertNotNull(resumed);
        assertEquals(GenerateReason.MODEL_STOP, resumed.getGenerateReason());
        assertEquals(0, tool.invocations.get(), "ask_user must never be executed");

        // The model's second reasoning round must see the answer as a tool result.
        List<Msg> secondInput = model.seenInputs.get(1);
        String toolResultText =
                secondInput.stream()
                        .filter(m -> m.getRole() == MsgRole.TOOL)
                        .flatMap(
                                m ->
                                        m.getContentBlocks(ToolResultBlock.class).stream()
                                                .flatMap(r -> r.getOutput().stream()))
                        .filter(TextBlock.class::isInstance)
                        .map(TextBlock.class::cast)
                        .map(TextBlock::getText)
                        .reduce("", (a, b) -> a + " " + b);
        assertTrue(
                toolResultText.contains("q_1"),
                "model must see the answer, got: " + toolResultText);
        assertTrue(
                toolResultText.contains("premium"),
                "model must see the answer value, got: " + toolResultText);
        assertTrue(
                secondInput.stream()
                        .flatMap(m -> m.getContentBlocks(ToolUseBlock.class).stream())
                        .anyMatch(
                                toolUse ->
                                        "tc1".equals(toolUse.getId())
                                                && toolUse.getState() == ToolCallState.FINISHED),
                "answered ask_user calls must no longer remain ASKING");
    }

    @Test
    void resumeRecoversWhenAskUserReplyMetadataIsLost() {
        AskTool tool = new AskTool("ask_user");
        ScriptedModel model =
                new ScriptedModel(
                        List.of(
                                () -> Flux.just(askToolUseResponse("tc1")),
                                () -> Flux.just(textResponse("done"))));
        ReActAgent agent = buildAgent(model, toolkitWith(tool));

        Msg firstResult = agent.call(List.of()).block();
        assertNotNull(firstResult);

        List<Msg> context = agent.getAgentState().contextMutable();
        int assistantIndex = context.size() - 1;
        Msg assistant = context.get(assistantIndex);
        Map<String, Object> metadata = new HashMap<>(assistant.getMetadata());
        metadata.remove(Msg.METADATA_ASK_REQUEST_REPLY_ID);
        context.set(assistantIndex, assistant.withMetadata(metadata));

        Msg resumed =
                agent.call(List.of(answerMsg(new AskUserResult("tc1", Map.of("q_1", "premium")))))
                        .block();
        assertNotNull(resumed);
        assertEquals(GenerateReason.MODEL_STOP, resumed.getGenerateReason());
    }

    @Test
    void resumeRecoversCustomAskUserWhenAllPauseMetadataIsLost() {
        AskTool tool = new AskTool("custom_ask_user");
        ScriptedModel model =
                new ScriptedModel(
                        List.of(
                                () -> Flux.just(askToolUseResponse("tc1", "custom_ask_user")),
                                () -> Flux.just(textResponse("done"))));
        ReActAgent agent = buildAgent(model, toolkitWith(tool));

        Msg firstResult = agent.call(List.of()).block();
        assertNotNull(firstResult);

        List<Msg> context = agent.getAgentState().contextMutable();
        int assistantIndex = context.size() - 1;
        Msg assistant = context.get(assistantIndex);
        Map<String, Object> metadata = new HashMap<>(assistant.getMetadata());
        metadata.remove(Msg.METADATA_ASK_REQUEST_REPLY_ID);
        List<ContentBlock> contentWithoutPauseMetadata =
                assistant.getContent().stream()
                        .map(
                                block -> {
                                    if (!(block instanceof ToolUseBlock toolCall)) {
                                        return block;
                                    }
                                    return new ToolUseBlock(
                                            toolCall.getId(),
                                            toolCall.getName(),
                                            toolCall.getInput(),
                                            toolCall.getContent(),
                                            Map.of(),
                                            toolCall.getState());
                                })
                        .toList();
        context.set(
                assistantIndex,
                assistant.withMetadata(metadata).withContent(contentWithoutPauseMetadata));

        Msg resumed =
                agent.call(List.of(answerMsg(new AskUserResult("tc1", Map.of("q_1", "premium")))))
                        .block();
        assertNotNull(resumed);
        assertEquals(GenerateReason.MODEL_STOP, resumed.getGenerateReason());
    }

    @Test
    void resumeWithoutAnswersIsRejectedWithGuidance() {
        AskTool tool = new AskTool("ask_user");
        ChatModelBase model =
                new ScriptedModel(List.of(() -> Flux.just(askToolUseResponse("tc1"))));
        ReActAgent agent = buildAgent(model, toolkitWith(tool));

        Msg firstResult = agent.call(List.of()).block();
        assertNotNull(firstResult);
        assertEquals(GenerateReason.ASK_USER_ASKING, firstResult.getGenerateReason());

        IllegalStateException ex =
                assertThrows(
                        IllegalStateException.class,
                        () -> agent.call(List.of()).block(),
                        "resuming an ASK_USER pause without answers must fail loudly");
        assertTrue(
                ex.getMessage().contains(Msg.METADATA_ASK_USER_RESULTS),
                "error must point at the resume metadata key");
    }

    @Test
    void streamingEmitsRequireUserAskAndUserAskResultEvents() {
        AskTool tool = new AskTool("ask_user");
        ChatModelBase model =
                new ScriptedModel(
                        List.of(
                                () -> Flux.just(askToolUseResponse("tc1")),
                                () -> Flux.just(textResponse("done"))));
        ReActAgent agent = buildAgent(model, toolkitWith(tool));

        List<AgentEvent> pauseEvents = agent.streamEvents(List.of()).collectList().block();
        assertNotNull(pauseEvents);
        int iAsk = indexOf(pauseEvents, RequireUserAskEvent.class);
        int iStop = indexOf(pauseEvents, RequestStopEvent.class);
        assertTrue(iAsk >= 0, "RequireUserAskEvent must be emitted");
        assertTrue(iStop > iAsk, "RequestStopEvent must follow RequireUserAskEvent");

        RequireUserAskEvent req = (RequireUserAskEvent) pauseEvents.get(iAsk);
        assertEquals(1, req.getToolCalls().size());
        assertEquals("tc1", req.getToolCalls().get(0).getId());
        RequestStopEvent stop = (RequestStopEvent) pauseEvents.get(iStop);
        assertEquals(GenerateReason.ASK_USER_ASKING, stop.getGenerateReason());

        List<AgentEvent> resumeEvents =
                agent.streamEvents(
                                List.of(
                                        answerMsg(
                                                new AskUserResult(
                                                        "tc1", Map.of("q_1", "premium")))))
                        .collectList()
                        .block();
        assertNotNull(resumeEvents);
        int iResult = indexOf(resumeEvents, UserAskResultEvent.class);
        assertTrue(iResult >= 0, "UserAskResultEvent must be emitted on answered resume");
        UserAskResultEvent result = (UserAskResultEvent) resumeEvents.get(iResult);
        assertEquals(req.getReplyId(), result.getReplyId());
        assertEquals(1, result.getAskUserResults().size());
        assertEquals("tc1", result.getAskUserResults().get(0).getToolCallId());
    }

    @Test
    void secretAnswersAreRedactedFromModelContextAndEvents() {
        AskTool tool = new AskTool("ask_user");
        ScriptedModel model =
                new ScriptedModel(
                        List.of(
                                () -> Flux.just(secretAskToolUseResponse("tc1")),
                                () -> Flux.just(textResponse("done"))));
        ReActAgent agent = buildAgent(model, toolkitWith(tool));

        Msg firstResult = agent.call(List.of()).block();
        assertNotNull(firstResult);
        assertEquals(GenerateReason.ASK_USER_ASKING, firstResult.getGenerateReason());

        List<AgentEvent> resumeEvents =
                agent.streamEvents(
                                List.of(
                                        answerMsg(
                                                new AskUserResult(
                                                        "tc1", Map.of("q_secret", "top-secret")))))
                        .collectList()
                        .block();
        assertNotNull(resumeEvents);

        UserAskResultEvent resultEvent =
                resumeEvents.stream()
                        .filter(UserAskResultEvent.class::isInstance)
                        .map(UserAskResultEvent.class::cast)
                        .findFirst()
                        .orElseThrow();
        assertEquals(
                "[REDACTED]", resultEvent.getAskUserResults().get(0).getAnswers().get("q_secret"));

        String toolResultText =
                model.seenInputs.get(1).stream()
                        .filter(m -> m.getRole() == MsgRole.TOOL)
                        .flatMap(
                                m ->
                                        m.getContentBlocks(ToolResultBlock.class).stream()
                                                .flatMap(r -> r.getOutput().stream()))
                        .filter(TextBlock.class::isInstance)
                        .map(TextBlock.class::cast)
                        .map(TextBlock::getText)
                        .reduce("", (a, b) -> a + " " + b);
        assertTrue(toolResultText.contains("q_secret: [REDACTED]"));
        assertFalse(toolResultText.contains("top-secret"));
    }

    @Test
    void secretAnswerWithMismatchedKeyIsRedactedFromModelContextAndEvents() {
        AskTool tool = new AskTool("ask_user");
        ScriptedModel model =
                new ScriptedModel(
                        List.of(
                                () -> Flux.just(secretAskToolUseResponseWithoutId("tc1")),
                                () -> Flux.just(textResponse("done"))));
        ReActAgent agent = buildAgent(model, toolkitWith(tool));

        Msg firstResult = agent.call(List.of()).block();
        assertNotNull(firstResult);

        List<AgentEvent> resumeEvents =
                agent.streamEvents(
                                List.of(
                                        answerMsg(
                                                new AskUserResult(
                                                        "tc1",
                                                        Map.of(
                                                                "What is the API key?",
                                                                "top-secret")))))
                        .collectList()
                        .block();
        assertNotNull(resumeEvents);

        UserAskResultEvent resultEvent =
                resumeEvents.stream()
                        .filter(UserAskResultEvent.class::isInstance)
                        .map(UserAskResultEvent.class::cast)
                        .findFirst()
                        .orElseThrow();
        assertEquals(
                "[REDACTED]",
                resultEvent.getAskUserResults().get(0).getAnswers().get("What is the API key?"));

        String toolResultText =
                model.seenInputs.get(1).stream()
                        .filter(m -> m.getRole() == MsgRole.TOOL)
                        .flatMap(
                                m ->
                                        m.getContentBlocks(ToolResultBlock.class).stream()
                                                .flatMap(r -> r.getOutput().stream()))
                        .filter(TextBlock.class::isInstance)
                        .map(TextBlock.class::cast)
                        .map(TextBlock::getText)
                        .reduce("", (a, b) -> a + " " + b);
        assertTrue(toolResultText.contains("What is the API key?: [REDACTED]"));
        assertFalse(toolResultText.contains("top-secret"));
    }

    @Test
    void mixedAskUserAndPermissionBatchPausesSequentially() {
        AskTool askTool = new AskTool("ask_user");
        ConfirmTool confirmTool = new ConfirmTool();
        ScriptedModel model =
                new ScriptedModel(
                        List.of(
                                () -> Flux.just(mixedToolUseResponse()),
                                () -> Flux.just(textResponse("done"))));
        ReActAgent agent = buildAgent(model, toolkitWith(askTool, confirmTool));

        List<AgentEvent> firstEvents = agent.streamEvents(List.of()).collectList().block();
        assertNotNull(firstEvents);
        assertTrue(indexOf(firstEvents, RequireUserAskEvent.class) >= 0);
        assertTrue(indexOf(firstEvents, RequireUserConfirmEvent.class) >= 0);
        AgentResultEvent firstResult =
                firstEvents.stream()
                        .filter(AgentResultEvent.class::isInstance)
                        .map(AgentResultEvent.class::cast)
                        .findFirst()
                        .orElseThrow();
        assertEquals(
                GenerateReason.PERMISSION_AND_ASK_USER_ASKING,
                firstResult.getResult().getGenerateReason());

        List<AgentEvent> afterAnswer =
                agent.streamEvents(
                                List.of(
                                        answerMsg(
                                                new AskUserResult(
                                                        "ask-1", Map.of("q_1", "premium")))))
                        .collectList()
                        .block();
        assertNotNull(afterAnswer);
        int confirmPause = indexOf(afterAnswer, RequireUserConfirmEvent.class);
        assertTrue(confirmPause >= 0, "the remaining permission pause must be surfaced");
        RequireUserConfirmEvent confirmEvent =
                (RequireUserConfirmEvent) afterAnswer.get(confirmPause);

        List<AgentEvent> afterConfirmation =
                agent.streamEvents(List.of(confirmMsg(confirmEvent.getToolCalls().get(0))))
                        .collectList()
                        .block();
        assertNotNull(afterConfirmation);
        assertEquals(1, confirmTool.invocations.get());
    }

    @Test
    void resumeRejectsDuplicateAskUserResultIds() {
        AskTool tool = new AskTool("ask_user");
        ChatModelBase model =
                new ScriptedModel(List.of(() -> Flux.just(askToolUseResponse("tc1"))));
        ReActAgent agent = buildAgent(model, toolkitWith(tool));

        Msg firstResult = agent.call(List.of()).block();
        assertNotNull(firstResult);
        assertEquals(GenerateReason.ASK_USER_ASKING, firstResult.getGenerateReason());

        IllegalStateException ex =
                assertThrows(
                        IllegalStateException.class,
                        () ->
                                agent.call(
                                                List.of(
                                                        answerMsg(
                                                                new AskUserResult(
                                                                        "tc1", Map.of("q_1", "a")),
                                                                new AskUserResult(
                                                                        "tc1",
                                                                        Map.of("q_1", "b")))))
                                        .block(),
                        "duplicate AskUserResult for one tool call must be rejected");
        assertTrue(
                ex.getMessage().contains("Duplicate AskUserResult"),
                "error must name the duplicate rule, got: " + ex.getMessage());
    }

    @Test
    void resumeRejectsAskUserResultForUnknownToolCall() {
        AskTool tool = new AskTool("ask_user");
        ChatModelBase model =
                new ScriptedModel(List.of(() -> Flux.just(askToolUseResponse("tc1"))));
        ReActAgent agent = buildAgent(model, toolkitWith(tool));

        Msg firstResult = agent.call(List.of()).block();
        assertNotNull(firstResult);
        assertEquals(GenerateReason.ASK_USER_ASKING, firstResult.getGenerateReason());

        IllegalStateException ex =
                assertThrows(
                        IllegalStateException.class,
                        () ->
                                agent.call(
                                                List.of(
                                                        answerMsg(
                                                                new AskUserResult(
                                                                        "no-such-call",
                                                                        Map.of("q_1", "a")))))
                                        .block(),
                        "an answer for a tool call that is not ASKING must be rejected");
        assertTrue(
                ex.getMessage().contains("references non-ASKING tool call ID"),
                "error must point at the stale id, got: " + ex.getMessage());
    }

    @Test
    void formatAnswersHandlesScalarsListsMapsAndSkips() {
        Map<String, Object> nullAnswer = new HashMap<>();
        nullAnswer.put("q_0", null);
        assertEquals("q_0: (no answer)", AskUserResult.formatAnswers(nullAnswer));
        assertEquals("q_1: premium", AskUserResult.formatAnswers(Map.of("q_1", "premium")));
        assertEquals(
                "q_2: a; b; c", AskUserResult.formatAnswers(Map.of("q_2", List.of("a", "b", "c"))));
        assertEquals(
                "q_3: (skipped)",
                AskUserResult.formatAnswers(
                        Map.of("q_3", Map.of("selected", List.of(), "text", "", "skipped", true))));
        assertEquals(
                "q_4: a; b | custom note",
                AskUserResult.formatAnswers(
                        Map.of(
                                "q_4",
                                Map.of("selected", List.of("a", "b"), "text", "custom note"))));
        assertEquals(
                "The user did not answer any question.", AskUserResult.formatAnswers(Map.of()));
    }
}
