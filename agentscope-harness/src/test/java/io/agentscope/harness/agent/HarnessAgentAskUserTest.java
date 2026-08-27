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
package io.agentscope.harness.agent;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.agentscope.core.agent.RuntimeContext;
import io.agentscope.core.event.AskUserResult;
import io.agentscope.core.message.ContentBlock;
import io.agentscope.core.message.GenerateReason;
import io.agentscope.core.message.Msg;
import io.agentscope.core.message.MsgRole;
import io.agentscope.core.message.TextBlock;
import io.agentscope.core.message.ToolResultBlock;
import io.agentscope.core.message.ToolUseBlock;
import io.agentscope.core.model.ChatModelBase;
import io.agentscope.core.model.ChatResponse;
import io.agentscope.core.model.GenerateOptions;
import io.agentscope.core.model.ToolSchema;
import io.agentscope.core.permission.PermissionContextState;
import io.agentscope.core.permission.PermissionDecision;
import io.agentscope.core.permission.PermissionMode;
import io.agentscope.core.state.InMemoryAgentStateStore;
import io.agentscope.harness.agent.tool.AskUserTool;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Supplier;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import reactor.core.publisher.Flux;

/**
 * End-to-end tests for the harness {@code enableAskUser()} switch: registers the built-in
 * {@code ask_user} tool; a model call to it pauses the run with
 * {@link GenerateReason#ASK_USER_ASKING} (in every permission mode); resuming with answers
 * formats them into the tool result and continues without executing the tool.
 */
@Tag("integration")
class HarnessAgentAskUserTest {

    @TempDir Path workspace;

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
            if (scripts.isEmpty()) {
                return Flux.just(textResponse(""));
            }
            // The harness runs auxiliary model calls (e.g. memory extraction) after the main
            // conversation turn; recycle the last script so those calls stay harmless.
            Supplier<Flux<ChatResponse>> script =
                    i < scripts.size() ? scripts.get(i) : scripts.get(scripts.size() - 1);
            return script.get();
        }
    }

    /**
     * Builds a harness with every non-feature option constant across these tests, including a
     * process-local in-memory state store so runs never touch {@code ~/.agentscope} or disk.
     */
    private static HarnessAgent build(String name, Path workspace, ChatModelBase model) {
        return HarnessAgent.builder()
                .name(name)
                .description("ask user test")
                .sysPrompt("You are a test agent.")
                .model(model)
                .workspace(workspace)
                // Deterministic and quiet: no disk state, no background memory jobs.
                .stateStore(new InMemoryAgentStateStore())
                .disableCompaction()
                .disableMemoryHooks()
                .enableAskUser()
                .build();
    }

    private static HarnessAgent buildWithPermissions(
            String name, Path workspace, ChatModelBase model, PermissionContextState permCtx) {
        return HarnessAgent.builder()
                .name(name)
                .description("ask user test")
                .sysPrompt("You are a test agent.")
                .model(model)
                .workspace(workspace)
                .stateStore(new InMemoryAgentStateStore())
                .disableCompaction()
                .disableMemoryHooks()
                .permissionContext(permCtx)
                .enableAskUser()
                .build();
    }

    private static ChatResponse textResponse(String text) {
        return ChatResponse.builder()
                .content(List.<ContentBlock>of(TextBlock.builder().text(text).build()))
                .build();
    }

    private static ChatResponse askToolUseResponse(String toolId) {
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
                                        .name(AskUserTool.TOOL_NAME)
                                        .input(input)
                                        .build()))
                .build();
    }

    private static Msg userText(String text) {
        return Msg.builder()
                .role(MsgRole.USER)
                .content(TextBlock.builder().text(text).build())
                .build();
    }

    private static Msg answerMsg(AskUserResult result) {
        Map<String, Object> meta = new HashMap<>();
        meta.put(Msg.METADATA_ASK_USER_RESULTS, List.of(result));
        return Msg.builder()
                .name("user")
                .role(MsgRole.USER)
                .textContent("[answers]")
                .metadata(meta)
                .build();
    }

    @Test
    void enableAskUserRegistersToolAndPausesWithAskingReason() {
        ScriptedModel model =
                new ScriptedModel(List.of(() -> Flux.just(askToolUseResponse("tc1"))));
        HarnessAgent agent = build("ask-user-agent", workspace, model);

        Msg firstResult =
                agent.call(
                                List.of(userText("help me pick")),
                                RuntimeContext.builder().sessionId("s-1").build())
                        .block();
        assertNotNull(firstResult);
        assertEquals(GenerateReason.ASK_USER_ASKING, firstResult.getGenerateReason());

        List<ToolUseBlock> pending = firstResult.getContentBlocks(ToolUseBlock.class);
        assertEquals(1, pending.size());
        assertEquals(AskUserTool.TOOL_NAME, pending.get(0).getName());
        assertFalse(pending.get(0).getInput().isEmpty(), "questions must be in the tool input");
    }

    @Test
    void askUserPausesEvenUnderBypassMode() {
        ScriptedModel model =
                new ScriptedModel(List.of(() -> Flux.just(askToolUseResponse("tc1"))));
        HarnessAgent agent =
                buildWithPermissions(
                        "ask-user-bypass",
                        workspace,
                        model,
                        PermissionContextState.builder().mode(PermissionMode.BYPASS).build());

        Msg firstResult =
                agent.call(
                                List.of(userText("help me pick")),
                                RuntimeContext.builder().sessionId("s-bypass").build())
                        .block();
        assertNotNull(firstResult);
        assertEquals(GenerateReason.ASK_USER_ASKING, firstResult.getGenerateReason());
    }

    @Test
    void resumeWithAnswersContinues() {
        ScriptedModel model =
                new ScriptedModel(
                        List.of(
                                () -> Flux.just(askToolUseResponse("tc1")),
                                () -> Flux.just(textResponse("done"))));
        HarnessAgent agent = build("ask-user-resume", workspace, model);

        Msg firstResult =
                agent.call(
                                List.of(userText("help me pick")),
                                RuntimeContext.builder().sessionId("s-resume").build())
                        .block();
        assertNotNull(firstResult);
        assertEquals(GenerateReason.ASK_USER_ASKING, firstResult.getGenerateReason());

        Msg resumed =
                agent.call(
                                List.of(
                                        userText("help me pick"),
                                        answerMsg(
                                                new AskUserResult(
                                                        "tc1", Map.of("q_1", "premium")))),
                                RuntimeContext.builder().sessionId("s-resume").build())
                        .block();
        assertNotNull(resumed);
        assertEquals(GenerateReason.MODEL_STOP, resumed.getGenerateReason());

        // The model's reasoning round must see the formatted answer as a tool result. The harness
        // runs auxiliary model calls (e.g. memory extraction) around the conversation, so scan
        // every model input for the answer tool result.
        String toolResultText =
                model.seenInputs.stream()
                        .flatMap(List::stream)
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
                toolResultText.contains("q_1") && toolResultText.contains("premium"),
                "model must see the answer, got: " + toolResultText);
    }

    @Test
    void askUserToolSelfDescription() {
        AskUserTool tool = new AskUserTool();
        assertEquals(AskUserTool.TOOL_NAME, tool.getName());
        assertNotNull(tool.getDescription());
        @SuppressWarnings("unchecked")
        Map<String, Object> schemaProps =
                (Map<String, Object>) tool.getParameters().get("properties");
        assertTrue(schemaProps.containsKey("questions"), "schema must expose questions");
        assertEquals(
                PermissionDecision.askUser("x").getBehavior(),
                tool.checkPermissions(Map.of(), PermissionContextState.builder().build())
                        .block()
                        .getBehavior());
    }

    @Test
    void askUserToolCallAsyncFallbacksToInteractivePlaceholder() {
        // Normal operation never executes the tool (checkPermissions interrupts first); this
        // direct fallback protects against a misconfigured host that bypasses the interrupt.
        AskUserTool tool = new AskUserTool();
        ToolResultBlock result =
                tool.callAsync(
                                io.agentscope.core.tool.ToolCallParam.builder()
                                        .input(Map.of("questions", "x"))
                                        .build())
                        .block();
        assertNotNull(result);
        assertTrue(
                result.getOutput().stream()
                        .filter(TextBlock.class::isInstance)
                        .map(TextBlock.class::cast)
                        .anyMatch(t -> t.getText().contains(Msg.METADATA_ASK_USER_RESULTS)),
                "fallback must point the host at the resume metadata key");
    }
}
