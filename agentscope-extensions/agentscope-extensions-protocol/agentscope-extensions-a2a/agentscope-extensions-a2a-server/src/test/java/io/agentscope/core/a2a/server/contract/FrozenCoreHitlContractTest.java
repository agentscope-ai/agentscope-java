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
package io.agentscope.core.a2a.server.contract;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.agentscope.core.ReActAgent;
import io.agentscope.core.agent.RuntimeContext;
import io.agentscope.core.event.ConfirmResult;
import io.agentscope.core.message.ContentBlock;
import io.agentscope.core.message.GenerateReason;
import io.agentscope.core.message.Msg;
import io.agentscope.core.message.MsgRole;
import io.agentscope.core.message.TextBlock;
import io.agentscope.core.message.ToolResultBlock;
import io.agentscope.core.message.ToolResultState;
import io.agentscope.core.message.ToolUseBlock;
import io.agentscope.core.model.ChatModelBase;
import io.agentscope.core.model.ChatResponse;
import io.agentscope.core.model.GenerateOptions;
import io.agentscope.core.model.ToolSchema;
import io.agentscope.core.permission.PermissionContextState;
import io.agentscope.core.permission.PermissionDecision;
import io.agentscope.core.state.AgentState;
import io.agentscope.core.state.InMemoryAgentStateStore;
import io.agentscope.core.tool.ToolBase;
import io.agentscope.core.tool.ToolCallParam;
import io.agentscope.core.tool.Toolkit;
import io.agentscope.core.util.JsonUtils;
import java.time.Duration;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Supplier;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

/** Locks the existing AgentScope core contracts that A2A HITL relies on. */
@DisplayName("Frozen AgentScope core HITL contracts")
class FrozenCoreHitlContractTest {

    private static final Duration TIMEOUT = Duration.ofSeconds(5);

    @Test
    @DisplayName("a modified confirmation ToolUseBlock changes the executed input")
    void modifiedConfirmationInputIsExecutedAfterRebuild() {
        InMemoryAgentStateStore store = new InMemoryAgentStateStore();
        ScriptedModel model =
                new ScriptedModel(
                        List.of(
                                () -> Flux.just(toolUse("confirm-1", "confirm_tool", "original")),
                                () -> Flux.just(text("confirmation-complete"))));
        Toolkit toolkit = new Toolkit();
        toolkit.registerAgentTool(new AskingTool("confirm_tool"));
        RuntimeContext context = runtime("confirm-session");

        Msg paused =
                agent(model, toolkit, store, false)
                        .call(List.of(user("start")), context)
                        .block(TIMEOUT);
        assertNotNull(paused);
        assertEquals(GenerateReason.PERMISSION_ASKING, paused.getGenerateReason());
        ToolUseBlock pending = paused.getContentBlocks(ToolUseBlock.class).get(0);

        Map<String, Object> modifiedInput = Map.of("query", "modified");
        ToolUseBlock modified =
                ToolUseBlock.builder()
                        .id(pending.getId())
                        .name(pending.getName())
                        .input(modifiedInput)
                        .content(JsonUtils.getJsonCodec().toJson(modifiedInput))
                        .metadata(pending.getMetadata())
                        .state(pending.getState())
                        .build();
        Msg resumed =
                agent(model, toolkit, store, false)
                        .call(List.of(confirm(modified)), context)
                        .block(TIMEOUT);

        assertNotNull(resumed);
        assertEquals("confirmation-complete", resumed.getTextContent());
        String stored = storedToolResultText(store, context);
        assertTrue(
                stored.contains("executed:modified"),
                "the replacement ToolUseBlock input must reach the tool executor; stored="
                        + stored);
    }

    @Test
    @DisplayName("a real ToolResultBlock resumes a SchemaOnlyTool on a rebuilt agent")
    void externalResultResumesSchemaOnlyToolAfterRebuild() {
        ExternalScenario scenario = suspendExternal("external-session", false);

        Msg resumed =
                agent(scenario.model(), scenario.toolkit(), scenario.store(), false)
                        .call(
                                List.of(
                                        externalResult(
                                                "external-1", "external_query", "external-ok")),
                                scenario.context())
                        .block(TIMEOUT);

        assertNotNull(resumed);
        assertEquals("external-complete", resumed.getTextContent());
        assertTrue(
                storedToolResultText(scenario.store(), scenario.context()).contains("external-ok"));
    }

    @Test
    @DisplayName("pending recovery never synthesizes an error over a supplied external result")
    void pendingRecoveryPreservesSuppliedExternalResult() {
        ExternalScenario scenario = suspendExternal("external-recovery-session", true);

        agent(scenario.model(), scenario.toolkit(), scenario.store(), true)
                .call(
                        List.of(externalResult("external-1", "external_query", "real-result")),
                        scenario.context())
                .block(TIMEOUT);

        String stored = storedToolResultText(scenario.store(), scenario.context());
        assertTrue(stored.contains("real-result"));
        assertFalse(
                stored.contains("Previous tool execution failed or was interrupted"),
                "pending recovery must return early when real ToolResultBlocks are supplied");
    }

    private static ExternalScenario suspendExternal(String sessionId, boolean recovery) {
        InMemoryAgentStateStore store = new InMemoryAgentStateStore();
        ScriptedModel model =
                new ScriptedModel(
                        List.of(
                                () -> Flux.just(toolUse("external-1", "external_query", "input")),
                                () -> Flux.just(text("external-complete"))));
        Toolkit toolkit = new Toolkit();
        toolkit.registerSchema(
                ToolSchema.builder()
                        .name("external_query")
                        .description("Execute outside the AgentScope process")
                        .parameters(
                                Map.of(
                                        "type",
                                        "object",
                                        "properties",
                                        Map.of("query", Map.of("type", "string"))))
                        .build());
        RuntimeContext context = runtime(sessionId);

        Msg paused =
                agent(model, toolkit, store, recovery)
                        .call(List.of(user("start")), context)
                        .block(TIMEOUT);
        assertNotNull(paused);
        assertEquals(GenerateReason.TOOL_SUSPENDED, paused.getGenerateReason());
        assertEquals("external-1", paused.getContentBlocks(ToolUseBlock.class).get(0).getId());
        return new ExternalScenario(model, toolkit, store, context);
    }

    private static ReActAgent agent(
            ScriptedModel model, Toolkit toolkit, InMemoryAgentStateStore store, boolean recovery) {
        return ReActAgent.builder()
                .name("contract-agent")
                .sysPrompt("Prove the frozen HITL contract")
                .model(model)
                .toolkit(toolkit)
                .stateStore(store)
                .enablePendingToolRecovery(recovery)
                .maxIters(3)
                .build();
    }

    private static RuntimeContext runtime(String sessionId) {
        return RuntimeContext.builder().userId("contract-user").sessionId(sessionId).build();
    }

    private static Msg user(String text) {
        return Msg.builder().name("user").role(MsgRole.USER).textContent(text).build();
    }

    private static Msg confirm(ToolUseBlock toolCall) {
        return Msg.builder()
                .name("user")
                .role(MsgRole.USER)
                .textContent("confirm")
                .metadata(
                        Map.of(
                                Msg.METADATA_CONFIRM_RESULTS,
                                List.of(new ConfirmResult(true, toolCall, null))))
                .build();
    }

    private static Msg externalResult(String id, String name, String value) {
        ToolResultBlock result =
                ToolResultBlock.text(value)
                        .withIdAndName(id, name)
                        .withState(ToolResultState.SUCCESS);
        return Msg.builder()
                .name("user")
                .role(MsgRole.TOOL)
                .content(List.<ContentBlock>of(result))
                .build();
    }

    private static String storedToolResultText(
            InMemoryAgentStateStore store, RuntimeContext context) {
        return store
                .get(context.getUserId(), context.getSessionId(), "agent_state", AgentState.class)
                .orElseThrow()
                .getContext()
                .stream()
                .flatMap(message -> message.getContentBlocks(ToolResultBlock.class).stream())
                .flatMap(result -> result.getOutput().stream())
                .filter(TextBlock.class::isInstance)
                .map(TextBlock.class::cast)
                .map(TextBlock::getText)
                .reduce("", (left, right) -> left + "\n" + right);
    }

    private static ChatResponse text(String value) {
        return ChatResponse.builder()
                .content(List.<ContentBlock>of(TextBlock.builder().text(value).build()))
                .build();
    }

    private static ChatResponse toolUse(String id, String name, String query) {
        Map<String, Object> input = new HashMap<>();
        input.put("query", query);
        return ChatResponse.builder()
                .content(
                        List.<ContentBlock>of(
                                ToolUseBlock.builder().id(id).name(name).input(input).build()))
                .build();
    }

    private record ExternalScenario(
            ScriptedModel model,
            Toolkit toolkit,
            InMemoryAgentStateStore store,
            RuntimeContext context) {}

    private static final class ScriptedModel extends ChatModelBase {
        private final List<Supplier<Flux<ChatResponse>>> scripts;
        private final AtomicInteger index = new AtomicInteger();

        private ScriptedModel(List<Supplier<Flux<ChatResponse>>> scripts) {
            this.scripts = scripts;
        }

        @Override
        public String getModelName() {
            return "frozen-core-contract";
        }

        @Override
        protected Flux<ChatResponse> doStream(
                List<Msg> messages, List<ToolSchema> tools, GenerateOptions options) {
            int next = index.getAndIncrement();
            return next < scripts.size() ? scripts.get(next).get() : Flux.just(text("done"));
        }
    }

    private static final class AskingTool extends ToolBase {
        private AskingTool(String name) {
            super(
                    name,
                    "Requires confirmation",
                    Map.of(
                            "type",
                            "object",
                            "properties",
                            Map.of("query", Map.of("type", "string"))),
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
            return Mono.just(PermissionDecision.ask("confirm"));
        }

        @Override
        public Mono<ToolResultBlock> callAsync(ToolCallParam param) {
            return Mono.just(ToolResultBlock.text("executed:" + param.getInput().get("query")));
        }
    }
}
