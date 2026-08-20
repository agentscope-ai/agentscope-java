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
import static org.junit.jupiter.api.Assertions.assertNotNull;

import io.agentscope.core.ReActAgent;
import io.agentscope.core.event.ConfirmResult;
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
import io.agentscope.core.permission.PermissionBehavior;
import io.agentscope.core.permission.PermissionContextState;
import io.agentscope.core.permission.PermissionDecision;
import io.agentscope.core.permission.PermissionRule;
import io.agentscope.core.state.JsonFileAgentStateStore;
import io.agentscope.core.tool.ToolBase;
import io.agentscope.core.tool.ToolCallParam;
import io.agentscope.core.tool.ToolConfirmation;
import io.agentscope.core.tool.Toolkit;
import io.agentscope.core.tool.UserConfirmableTool;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Function;
import java.util.function.Supplier;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

class ReActAgentToolConfirmationTest {

    @TempDir java.nio.file.Path tempDir;

    private static final class ScriptedModel extends ChatModelBase {
        private final List<Supplier<Flux<ChatResponse>>> scripts;
        private final AtomicInteger index = new AtomicInteger();

        private ScriptedModel(List<Supplier<Flux<ChatResponse>>> scripts) {
            this.scripts = scripts;
        }

        @Override
        public String getModelName() {
            return "scripted";
        }

        @Override
        protected Flux<ChatResponse> doStream(
                List<Msg> messages, List<ToolSchema> tools, GenerateOptions options) {
            int current = index.getAndIncrement();
            return current < scripts.size()
                    ? scripts.get(current).get()
                    : Flux.just(textResponse("done"));
        }
    }

    private static class ConfirmableTool implements UserConfirmableTool {
        private final String name;
        private final Function<ToolCallParam, ToolConfirmation> confirmation;
        private final AtomicInteger prepareCalls = new AtomicInteger();
        private final AtomicInteger executeCalls = new AtomicInteger();
        private final List<Map<String, Object>> executedInputs = new ArrayList<>();

        private ConfirmableTool(
                String name, Function<ToolCallParam, ToolConfirmation> confirmation) {
            this.name = name;
            this.confirmation = confirmation;
        }

        @Override
        public String getName() {
            return name;
        }

        @Override
        public String getDescription() {
            return "test confirmation tool";
        }

        @Override
        public Map<String, Object> getParameters() {
            return Map.of("type", "object");
        }

        @Override
        public Mono<ToolConfirmation> prepareConfirmation(ToolCallParam param) {
            prepareCalls.incrementAndGet();
            return Mono.just(confirmation.apply(param));
        }

        @Override
        public Mono<ToolResultBlock> callAsync(ToolCallParam param) {
            executeCalls.incrementAndGet();
            executedInputs.add(new HashMap<>(param.getInput()));
            return Mono.just(ToolResultBlock.text("executed"));
        }
    }

    private static final class PermissionGatedConfirmableTool extends ToolBase
            implements UserConfirmableTool {
        private final AtomicInteger prepareCalls = new AtomicInteger();
        private final AtomicInteger executeCalls = new AtomicInteger();

        private PermissionGatedConfirmableTool(String name) {
            super(
                    name,
                    "permission and confirmation",
                    Map.of("type", "object"),
                    false,
                    true,
                    false,
                    null,
                    false,
                    false);
        }

        @Override
        public Mono<PermissionDecision> checkPermissions(
                Map<String, Object> toolInput, PermissionContextState context) {
            return Mono.just(PermissionDecision.ask("permission first"));
        }

        @Override
        public Mono<ToolConfirmation> prepareConfirmation(ToolCallParam param) {
            prepareCalls.incrementAndGet();
            return Mono.just(
                    ToolConfirmation.confirm(
                            Map.of("approvalId", "permission-then-confirm"),
                            "Approve prepared operation",
                            true));
        }

        @Override
        public Mono<ToolResultBlock> callAsync(ToolCallParam param) {
            executeCalls.incrementAndGet();
            return Mono.just(ToolResultBlock.text("executed"));
        }
    }

    @Test
    void immutableConfirmationExecutesPreparedInputOnceAndIgnoresCallerMutation() {
        ConfirmableTool tool =
                new ConfirmableTool(
                        "write",
                        ignored ->
                                ToolConfirmation.confirm(
                                        Map.of(
                                                "approvalId",
                                                "approval-1",
                                                "proposalDigest",
                                                "digest-1"),
                                        "Create key a with value b",
                                        true));
        ReActAgent agent =
                buildAgent(
                        new ScriptedModel(
                                List.of(
                                        () ->
                                                Flux.just(
                                                        toolUseResponse(
                                                                toolCall(
                                                                        "tc1",
                                                                        "write",
                                                                        Map.of(
                                                                                "payload",
                                                                                "server-secret")))),
                                        () -> Flux.just(textResponse("done")))),
                        tool);

        Msg first = agent.call(List.of()).block();

        assertNotNull(first);
        assertEquals(GenerateReason.PERMISSION_ASKING, first.getGenerateReason());
        assertEquals(1, tool.prepareCalls.get());
        assertEquals(0, tool.executeCalls.get());
        ToolUseBlock pending = onlyPending(first);
        assertEquals(
                Map.of("approvalId", "approval-1", "proposalDigest", "digest-1"),
                pending.getInput());
        assertEquals("Create key a with value b", pending.getContent());

        ToolUseBlock tampered =
                ToolUseBlock.builder()
                        .id(pending.getId())
                        .name(pending.getName())
                        .input(Map.of("approvalId", "attacker", "payload", "tampered"))
                        .content("tampered prompt")
                        .metadata(Map.of("attacker", true))
                        .state(ToolCallState.ASKING)
                        .build();

        PermissionRule injectedRule =
                new PermissionRule("write", null, PermissionBehavior.ALLOW, "untrusted-resume");
        Msg resumed =
                agent.call(List.of(confirmMsg(true, tampered, List.of(injectedRule)))).block();

        assertNotNull(resumed);
        assertEquals(1, tool.prepareCalls.get(), "resume must not prepare a second time");
        assertEquals(1, tool.executeCalls.get());
        assertEquals(
                Map.of("approvalId", "approval-1", "proposalDigest", "digest-1"),
                tool.executedInputs.get(0));
        assertEquals(
                Map.of(),
                agent.getAgentState().getPermissionContext().getAllowRules(),
                "immutable confirmation must ignore caller-supplied permission rules");
    }

    @Test
    void preparedInputDoesNotNeedToMatchOriginalModelInputSchema() {
        ConfirmableTool tool =
                new ConfirmableTool(
                        "write",
                        ignored ->
                                ToolConfirmation.confirm(
                                        Map.of(
                                                "approvalId",
                                                "approval-1",
                                                "proposalDigest",
                                                "digest-1",
                                                "preparedMode",
                                                "A2A_HANDOFF"),
                                        "Approve prepared operation",
                                        true)) {
                    @Override
                    public Map<String, Object> getParameters() {
                        return Map.of(
                                "type",
                                "object",
                                "required",
                                List.of("region", "items"),
                                "properties",
                                Map.of(
                                        "region", Map.of("type", "string"),
                                        "items", Map.of("type", "array")));
                    }
                };
        ReActAgent agent =
                buildAgent(
                        new ScriptedModel(
                                List.of(
                                        () ->
                                                Flux.just(
                                                        toolUseResponse(
                                                                toolCall(
                                                                        "tc1",
                                                                        "write",
                                                                        Map.of(
                                                                                "region",
                                                                                "default",
                                                                                "items",
                                                                                List.of(
                                                                                        Map.of(
                                                                                                "key",
                                                                                                "a",
                                                                                                "value",
                                                                                                "b")))))),
                                        () -> Flux.just(textResponse("done")))),
                        tool);

        Msg pending = agent.call(List.of()).block();
        agent.call(List.of(confirmMsg(true, onlyPending(pending)))).block();

        assertEquals(1, tool.executeCalls.get());
        assertEquals(
                Map.of(
                        "approvalId",
                        "approval-1",
                        "proposalDigest",
                        "digest-1",
                        "preparedMode",
                        "A2A_HANDOFF"),
                tool.executedInputs.get(0));
    }

    @Test
    void preparesWholeBatchAndExecutesNothingUntilEveryConfirmationIsResolved() {
        ConfirmableTool confirmed =
                new ConfirmableTool(
                        "confirmed",
                        ignored ->
                                ToolConfirmation.confirm(
                                        Map.of("approvalId", "approval-2"),
                                        "Approve operation",
                                        true));
        ConfirmableTool continued =
                new ConfirmableTool(
                        "continued",
                        ignored ->
                                ToolConfirmation.continueWith(
                                        Map.of("prepared", "continue-result")));
        ReActAgent agent =
                buildAgent(
                        new ScriptedModel(
                                List.of(
                                        () ->
                                                Flux.just(
                                                        toolUseResponse(
                                                                toolCall(
                                                                        "tc1",
                                                                        "confirmed",
                                                                        Map.of("raw", "one")),
                                                                toolCall(
                                                                        "tc2",
                                                                        "continued",
                                                                        Map.of("raw", "two")))),
                                        () -> Flux.just(textResponse("done")))),
                        confirmed,
                        continued);

        Msg first = agent.call(List.of()).block();

        assertEquals(GenerateReason.PERMISSION_ASKING, first.getGenerateReason());
        assertEquals(1, confirmed.prepareCalls.get());
        assertEquals(1, continued.prepareCalls.get());
        assertEquals(0, confirmed.executeCalls.get());
        assertEquals(0, continued.executeCalls.get());

        agent.call(List.of(confirmMsg(true, onlyPending(first)))).block();

        assertEquals(1, confirmed.prepareCalls.get());
        assertEquals(1, continued.prepareCalls.get());
        assertEquals(1, confirmed.executeCalls.get());
        assertEquals(1, continued.executeCalls.get());
        assertEquals(Map.of("prepared", "continue-result"), continued.executedInputs.get(0));
    }

    @Test
    void rejectingPreparedConfirmationNeverInvokesTool() {
        ConfirmableTool tool =
                new ConfirmableTool(
                        "write",
                        ignored ->
                                ToolConfirmation.confirm(
                                        Map.of("approvalId", "approval-3"),
                                        "Approve operation",
                                        true));
        ReActAgent agent =
                buildAgent(
                        new ScriptedModel(
                                List.of(
                                        () ->
                                                Flux.just(
                                                        toolUseResponse(
                                                                toolCall(
                                                                        "tc1",
                                                                        "write",
                                                                        Map.of("raw", "value")))),
                                        () -> Flux.just(textResponse("done")))),
                        tool);

        Msg first = agent.call(List.of()).block();
        agent.call(List.of(confirmMsg(false, onlyPending(first)))).block();

        assertEquals(1, tool.prepareCalls.get());
        assertEquals(0, tool.executeCalls.get());
    }

    @Test
    void permissionAskCompletesBeforeConfirmationPreparation() {
        PermissionGatedConfirmableTool tool = new PermissionGatedConfirmableTool("guarded");
        Toolkit toolkit = new Toolkit();
        toolkit.registerAgentTool(tool);
        ReActAgent agent =
                ReActAgent.builder()
                        .name("asst")
                        .model(
                                new ScriptedModel(
                                        List.of(
                                                () ->
                                                        Flux.just(
                                                                toolUseResponse(
                                                                        toolCall(
                                                                                "tc1",
                                                                                "guarded",
                                                                                Map.of(
                                                                                        "raw",
                                                                                        "value")))),
                                                () -> Flux.just(textResponse("done")))))
                        .toolkit(toolkit)
                        .build();

        Msg permissionAsk = agent.call(List.of()).block();

        assertEquals(GenerateReason.PERMISSION_ASKING, permissionAsk.getGenerateReason());
        assertEquals(0, tool.prepareCalls.get());
        assertEquals(0, tool.executeCalls.get());

        Msg preparedAsk = agent.call(List.of(confirmMsg(true, onlyPending(permissionAsk)))).block();

        assertEquals(GenerateReason.PERMISSION_ASKING, preparedAsk.getGenerateReason());
        assertEquals(1, tool.prepareCalls.get());
        assertEquals(0, tool.executeCalls.get());

        agent.call(List.of(confirmMsg(true, onlyPending(preparedAsk)))).block();

        assertEquals(1, tool.prepareCalls.get());
        assertEquals(1, tool.executeCalls.get());
    }

    @Test
    void persistedPreparedInputSurvivesAgentRestartWithoutPreparingAgain() {
        ConfirmableTool tool =
                new ConfirmableTool(
                        "write",
                        ignored ->
                                ToolConfirmation.confirm(
                                        Map.of("approvalId", "restart-approval"),
                                        "Approve after restart",
                                        true));
        JsonFileAgentStateStore store = new JsonFileAgentStateStore(tempDir);
        RuntimeContext context =
                RuntimeContext.builder().userId("user-1").sessionId("session-1").build();
        ReActAgent firstAgent =
                buildAgent(
                        new ScriptedModel(
                                List.of(
                                        () ->
                                                Flux.just(
                                                        toolUseResponse(
                                                                toolCall(
                                                                        "tc1",
                                                                        "write",
                                                                        Map.of(
                                                                                "raw",
                                                                                "original")))))),
                        store,
                        tool);

        Msg first = firstAgent.call(List.of(), context).block();
        ToolUseBlock pending = onlyPending(first);
        assertEquals(1, tool.prepareCalls.get());

        ReActAgent restartedAgent =
                buildAgent(
                        new ScriptedModel(List.of(() -> Flux.just(textResponse("done")))),
                        store,
                        tool);
        restartedAgent.call(List.of(confirmMsg(true, pending)), context).block();

        assertEquals(1, tool.prepareCalls.get());
        assertEquals(1, tool.executeCalls.get());
        assertEquals(Map.of("approvalId", "restart-approval"), tool.executedInputs.get(0));
    }

    private static ReActAgent buildAgent(ChatModelBase model, UserConfirmableTool... tools) {
        return buildAgent(model, null, tools);
    }

    private static ReActAgent buildAgent(
            ChatModelBase model,
            io.agentscope.core.state.AgentStateStore stateStore,
            UserConfirmableTool... tools) {
        Toolkit toolkit = new Toolkit();
        for (UserConfirmableTool tool : tools) {
            toolkit.registerAgentTool(tool);
        }
        return ReActAgent.builder()
                .name("asst")
                .model(model)
                .toolkit(toolkit)
                .stateStore(stateStore)
                .build();
    }

    private static ToolUseBlock toolCall(String id, String name, Map<String, Object> input) {
        return ToolUseBlock.builder().id(id).name(name).input(input).build();
    }

    private static ChatResponse toolUseResponse(ToolUseBlock... calls) {
        return ChatResponse.builder().content(List.of(calls)).build();
    }

    private static ChatResponse textResponse(String text) {
        return ChatResponse.builder()
                .content(List.<ContentBlock>of(TextBlock.builder().text(text).build()))
                .build();
    }

    private static ToolUseBlock onlyPending(Msg result) {
        List<ToolUseBlock> pending =
                result.getContentBlocks(ToolUseBlock.class).stream()
                        .filter(tool -> tool.getState() == ToolCallState.ASKING)
                        .toList();
        assertEquals(1, pending.size());
        return pending.get(0);
    }

    private static Msg confirmMsg(boolean confirmed, ToolUseBlock toolCall) {
        return confirmMsg(confirmed, toolCall, null);
    }

    private static Msg confirmMsg(
            boolean confirmed, ToolUseBlock toolCall, List<PermissionRule> rules) {
        return Msg.builder()
                .name("user")
                .role(MsgRole.USER)
                .textContent("[confirm]")
                .metadata(
                        Map.of(
                                Msg.METADATA_CONFIRM_RESULTS,
                                List.of(new ConfirmResult(confirmed, toolCall, rules))))
                .build();
    }
}
