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
package io.agentscope.core.agent;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

import io.agentscope.core.ReActAgent;
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
import io.agentscope.core.permission.PermissionBehavior;
import io.agentscope.core.permission.PermissionContextState;
import io.agentscope.core.permission.PermissionDecision;
import io.agentscope.core.permission.PermissionRule;
import io.agentscope.core.state.AgentStateStore;
import io.agentscope.core.state.InMemoryAgentStateStore;
import io.agentscope.core.tool.ToolBase;
import io.agentscope.core.tool.ToolCallParam;
import io.agentscope.core.tool.Toolkit;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Supplier;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

/** Verifies replacement semantics for builder-provided permission contexts. */
class ReActAgentPermissionRulesTest {

    private static final String TOOL = "deploy";

    private static final class DeployTool extends ToolBase {

        private final AtomicInteger calls = new AtomicInteger();

        DeployTool() {
            super(ToolBase.builder().name(TOOL).description(TOOL).inputSchema(schema()));
        }

        int callCount() {
            return calls.get();
        }

        @Override
        public Mono<PermissionDecision> checkPermissions(
                Map<String, Object> toolInput, PermissionContextState context) {
            return Mono.just(PermissionDecision.passthrough(getName()));
        }

        @Override
        public Mono<ToolResultBlock> callAsync(ToolCallParam param) {
            calls.incrementAndGet();
            return Mono.just(ToolResultBlock.text("deployed"));
        }
    }

    private static final class ScriptedModel extends ChatModelBase {

        private final List<Supplier<Flux<ChatResponse>>> scripts;
        private int idx;

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
            int i = idx++;
            if (i >= scripts.size()) {
                return Flux.just(
                        ChatResponse.builder()
                                .content(List.of(TextBlock.builder().text("").build()))
                                .build());
            }
            return scripts.get(i).get();
        }
    }

    private static Map<String, Object> schema() {
        Map<String, Object> schema = new HashMap<>();
        schema.put("type", "object");
        Map<String, Object> props = new HashMap<>();
        props.put("env", Map.of("type", "string"));
        schema.put("properties", props);
        return schema;
    }

    private static ChatResponse toolCall(String toolCallId) {
        return ChatResponse.builder()
                .content(
                        List.of(
                                ToolUseBlock.builder()
                                        .id(toolCallId)
                                        .name(TOOL)
                                        .input(Map.of("env", "prod"))
                                        .content("{}")
                                        .build()))
                .build();
    }

    private static ChatResponse textResponse(String text) {
        return ChatResponse.builder()
                .content(List.of(TextBlock.builder().text(text).build()))
                .build();
    }

    private static ScriptedModel model(String... toolCallIds) {
        List<Supplier<Flux<ChatResponse>>> scripts = new ArrayList<>();
        for (String id : toolCallIds) {
            scripts.add(() -> Flux.just(toolCall(id)));
            scripts.add(() -> Flux.just(textResponse("done")));
        }
        return new ScriptedModel(scripts);
    }

    private static Msg userMsg(String text) {
        return Msg.builder()
                .name("user")
                .role(MsgRole.USER)
                .content(TextBlock.builder().text(text).build())
                .build();
    }

    private static PermissionContextState rules(PermissionBehavior behavior) {
        PermissionRule rule = new PermissionRule(TOOL, null, behavior, "test");
        PermissionContextState.Builder builder = PermissionContextState.builder();
        return switch (behavior) {
            case ALLOW -> builder.addAllowRule(TOOL, rule).build();
            case ASK -> builder.addAskRule(TOOL, rule).build();
            case DENY -> builder.addDenyRule(TOOL, rule).build();
            case PASSTHROUGH -> builder.build();
        };
    }

    private static ReActAgent agent(
            ChatModelBase model,
            PermissionContextState context,
            AgentStateStore store,
            DeployTool tool) {
        Toolkit toolkit = new Toolkit();
        toolkit.registerAgentTool(tool);
        return ReActAgent.builder()
                .name("asst")
                .model(model)
                .toolkit(toolkit)
                .permissionContext(context)
                .stateStore(store)
                .build();
    }

    private static RuntimeContext session(String sessionId) {
        return RuntimeContext.builder().userId("u1").sessionId(sessionId).build();
    }

    @Test
    @DisplayName("a non-null context replaces the persisted context")
    void nonNullPermissionContextReplacesPersistedContext() {
        InMemoryAgentStateStore store = new InMemoryAgentStateStore();

        agent(model("tc-v1"), rules(PermissionBehavior.ALLOW), store, new DeployTool())
                .call(List.of(userMsg("deploy?")), session("sessA"))
                .block();

        Msg replaced =
                agent(model("tc-v2"), rules(PermissionBehavior.ASK), store, new DeployTool())
                        .call(List.of(userMsg("deploy?")), session("sessA"))
                        .block();

        assertNotNull(replaced);
        assertEquals(GenerateReason.PERMISSION_ASKING, replaced.getGenerateReason());
    }

    @Test
    @DisplayName("a null context leaves the persisted context untouched")
    void nullPermissionContextLeavesPersistedContext() {
        InMemoryAgentStateStore store = new InMemoryAgentStateStore();
        DeployTool tool = new DeployTool();

        agent(model("tc-v1"), rules(PermissionBehavior.ALLOW), store, tool)
                .call(List.of(userMsg("deploy?")), session("sessA"))
                .block();

        agent(model("tc-v2"), null, store, tool)
                .call(List.of(userMsg("deploy?")), session("sessA"))
                .block();

        assertEquals(2, tool.callCount());
    }

    @Test
    @DisplayName("the builder context is not reapplied after a runtime replacement")
    void builderContextIsNotReappliedAfterRuntimeReplacement() {
        InMemoryAgentStateStore store = new InMemoryAgentStateStore();
        ReActAgent agent =
                agent(
                        model("tc-1", "tc-2"),
                        rules(PermissionBehavior.ALLOW),
                        store,
                        new DeployTool());

        agent.call(List.of(userMsg("deploy?")), session("sessA")).block();

        agent.replacePermissionContext("u1", "sessA", rules(PermissionBehavior.ASK));

        Msg replaced = agent.call(List.of(userMsg("deploy?")), session("sessA")).block();

        assertNotNull(replaced);
        assertEquals(GenerateReason.PERMISSION_ASKING, replaced.getGenerateReason());
    }

    @Test
    @DisplayName("a non-null trivial context is a replacement, not a no-op")
    void trivialPermissionContextIsReplacementNotNoOp() {
        InMemoryAgentStateStore store = new InMemoryAgentStateStore();

        agent(model("tc-v1"), rules(PermissionBehavior.DENY), store, new DeployTool())
                .call(List.of(userMsg("deploy?")), session("sessA"))
                .block();

        Msg replaced =
                agent(
                                model("tc-v2"),
                                PermissionContextState.builder().build(),
                                store,
                                new DeployTool())
                        .call(List.of(userMsg("deploy?")), session("sessA"))
                        .block();

        assertNotNull(replaced);
        assertEquals(GenerateReason.MODEL_STOP, replaced.getGenerateReason());
    }

    @Test
    @DisplayName("a rebuilt agent applies its non-null context again")
    void rebuiltAgentAppliesItsNonNullContextAgain() {
        InMemoryAgentStateStore store = new InMemoryAgentStateStore();
        ReActAgent first =
                agent(model("tc-1"), rules(PermissionBehavior.ALLOW), store, new DeployTool());
        first.call(List.of(userMsg("deploy?")), session("sessA")).block();

        first.replacePermissionContext("u1", "sessA", rules(PermissionBehavior.DENY));

        Msg replaced =
                agent(model("tc-2"), rules(PermissionBehavior.ALLOW), store, new DeployTool())
                        .call(List.of(userMsg("deploy?")), session("sessA"))
                        .block();

        assertNotNull(replaced);
        assertEquals(GenerateReason.MODEL_STOP, replaced.getGenerateReason());
    }
}
