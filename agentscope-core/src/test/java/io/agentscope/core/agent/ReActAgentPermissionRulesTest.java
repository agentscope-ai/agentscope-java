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
import io.agentscope.core.permission.PermissionMode;
import io.agentscope.core.permission.PermissionRule;
import io.agentscope.core.state.AgentStateStore;
import io.agentscope.core.state.InMemoryAgentStateStore;
import io.agentscope.core.tool.ToolBase;
import io.agentscope.core.tool.ToolCallParam;
import io.agentscope.core.tool.Toolkit;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Supplier;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

/**
 * The defect reported as #3291: builder-declared permission rules are written into the session
 * state when its slot is first created, and every later call rebuilds the {@code PermissionEngine}
 * from that persisted snapshot. A rule change in code therefore never reaches a session that
 * already exists — tightening a rule silences the confirmation instead of enforcing it.
 */
class ReActAgentPermissionRulesTest {

    private static final String TOOL = "deploy";

    /** A tool that defers the decision to the engine's rules rather than asserting its own. */
    private static final class DeployTool extends ToolBase {

        private final AtomicInteger calls = new AtomicInteger();

        DeployTool() {
            super(ToolBase.builder().name(TOOL).description(TOOL).inputSchema(schema()));
        }

        /** How many times the tool actually executed, which a turn's reason alone cannot reveal. */
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

    private static Map<String, Object> schema() {
        Map<String, Object> schema = new HashMap<>();
        schema.put("type", "object");
        Map<String, Object> props = new HashMap<>();
        props.put("env", Map.of("type", "string"));
        schema.put("properties", props);
        return schema;
    }

    /** Calls the tool on the first model call, then answers with text. */
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

    private static ScriptedModel model(String toolCallId) {
        return new ScriptedModel(
                List.of(
                        () -> Flux.just(toolCall(toolCallId)),
                        () -> Flux.just(textResponse("done"))));
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
            ChatModelBase model, PermissionContextState rules, AgentStateStore store) {
        return agent(model, rules, store, false);
    }

    private static ReActAgent agent(
            ChatModelBase model,
            PermissionContextState rules,
            AgentStateStore store,
            boolean rulesAuthoritative) {
        Toolkit toolkit = new Toolkit();
        toolkit.registerAgentTool(new DeployTool());
        return ReActAgent.builder()
                .name("asst")
                .model(model)
                .toolkit(toolkit)
                .permissionContext(rules)
                .permissionRulesAuthoritative(rulesAuthoritative)
                .stateStore(store)
                .build();
    }

    private static ReActAgent agent(
            ChatModelBase model,
            PermissionContextState rules,
            AgentStateStore store,
            boolean rulesAuthoritative,
            DeployTool tool) {
        Toolkit toolkit = new Toolkit();
        toolkit.registerAgentTool(tool);
        return ReActAgent.builder()
                .name("asst")
                .model(model)
                .toolkit(toolkit)
                .permissionContext(rules)
                .permissionRulesAuthoritative(rulesAuthoritative)
                .stateStore(store)
                .build();
    }

    private static RuntimeContext session(String sessionId) {
        return RuntimeContext.builder().userId("u1").sessionId(sessionId).build();
    }

    /**
     * The repro from the report: v1 declares ALLOW, the session is used, v2 declares ASK on the
     * same store. The rule change does not reach the existing session.
     */
    @Test
    @DisplayName("default: a rule tightened in code does not reach a session that already exists")
    void tightenedRuleDoesNotReachAnExistingSessionByDefault() {
        InMemoryAgentStateStore store = new InMemoryAgentStateStore();

        Msg allowed =
                agent(model("tc-v1"), rules(PermissionBehavior.ALLOW), store)
                        .call(List.of(userMsg("deploy?")), session("sessA"))
                        .block();
        assertNotNull(allowed);
        assertEquals(
                GenerateReason.MODEL_STOP,
                allowed.getGenerateReason(),
                "v1 declares ALLOW, so the tool runs");

        Msg tightened =
                agent(model("tc-v2"), rules(PermissionBehavior.ASK), store)
                        .call(List.of(userMsg("deploy?")), session("sessA"))
                        .block();
        assertNotNull(tightened);
        assertEquals(
                GenerateReason.MODEL_STOP,
                tightened.getGenerateReason(),
                "the session keeps the ALLOW it was created with, so the tightened ASK is ignored");
    }

    /**
     * With the flag on, the same v2 declaration does reach the session that already exists — this is
     * the defect the issue reports.
     */
    @Test
    @DisplayName("with the flag: a rule tightened in code reaches a session that already exists")
    void tightenedRuleReachesAnExistingSessionWhenAuthoritative() {
        InMemoryAgentStateStore store = new InMemoryAgentStateStore();

        agent(model("tc-a1"), rules(PermissionBehavior.ALLOW), store, true)
                .call(List.of(userMsg("deploy?")), session("sessA"))
                .block();

        Msg tightened =
                agent(model("tc-a2"), rules(PermissionBehavior.ASK), store, true)
                        .call(List.of(userMsg("deploy?")), session("sessA"))
                        .block();

        assertNotNull(tightened);
        assertEquals(GenerateReason.PERMISSION_ASKING, tightened.getGenerateReason());
    }

    /**
     * The other half of the semantics: the flag replaces the session's rules only for the tools the
     * builder declares. A rule the session holds for a tool the builder says nothing about still
     * applies — without this, "ignore the persisted context entirely" would look like a fix.
     */
    @Test
    @DisplayName(
            "with the flag: the session keeps its rules for tools the builder does not declare")
    void sessionRulesSurviveForToolsTheBuilderDoesNotDeclare() {
        InMemoryAgentStateStore store = new InMemoryAgentStateStore();
        RuntimeContext sessA = session("sessA");

        ReActAgent first = agent(model("tc-p1"), rules(PermissionBehavior.PASSTHROUGH), store);
        first.call(List.of(userMsg("deploy?")), sessA).block();

        // The session acquires an ALLOW for "deploy" at runtime, as a HITL decision would.
        PermissionRule allowed = new PermissionRule(TOOL, null, PermissionBehavior.ALLOW, "hitl");
        first.replacePermissionContext(
                "u1",
                "sessA",
                PermissionContextState.builder()
                        .mode(PermissionMode.DEFAULT)
                        .addAllowRule(TOOL, allowed)
                        .build());

        // The next deployment declares rules, but only for tools other than "deploy".
        PermissionRule other = new PermissionRule("other", null, PermissionBehavior.DENY, "code");
        Msg result =
                agent(
                                model("tc-p2"),
                                PermissionContextState.builder()
                                        .mode(PermissionMode.DEFAULT)
                                        .addDenyRule("other", other)
                                        .build(),
                                store,
                                true)
                        .call(List.of(userMsg("deploy?")), sessA)
                        .block();

        assertNotNull(result);
        assertEquals(
                GenerateReason.MODEL_STOP,
                result.getGenerateReason(),
                "the session's own ALLOW for a tool the builder does not declare must still apply");
    }

    /**
     * The gate that decides whether the engine is consulted at all reads the *persisted* context.
     * A session persisted with no rules at all is trivial, so it stays on the lightweight path and
     * the declared rules are never examined — the flag would do nothing on exactly those sessions.
     */
    @Test
    @DisplayName(
            "with the flag: a session persisted with no rules at all still consults the engine")
    void declaredRulesReachASessionWhosePersistedContextIsTrivial() {
        InMemoryAgentStateStore store = new InMemoryAgentStateStore();
        RuntimeContext sessA = session("sessA");

        // v1 declares nothing, so the session is persisted with a trivial permission context.
        agent(model("tc-t1"), PermissionContextState.builder().build(), store)
                .call(List.of(userMsg("deploy?")), sessA)
                .block();

        Msg tightened =
                agent(model("tc-t2"), rules(PermissionBehavior.ASK), store, true)
                        .call(List.of(userMsg("deploy?")), sessA)
                        .block();

        assertNotNull(tightened);
        assertEquals(GenerateReason.PERMISSION_ASKING, tightened.getGenerateReason());
    }

    /**
     * Removing a rule has to reach an existing session too. The old build denies the tool; the new
     * build declares it as ALLOW only, so the stale session DENY must be dropped — if the skip were
     * keyed per rule table rather than per tool, the stale DENY would survive and, because deny is
     * evaluated first, keep winning.
     */
    @Test
    @DisplayName("with the flag: dropping a DENY reaches a session that already holds it")
    void removedDenyDoesNotSurviveWhenTheToolIsStillDeclared() {
        InMemoryAgentStateStore store = new InMemoryAgentStateStore();
        RuntimeContext sessA = session("sessA");

        agent(model("tc-d1"), rules(PermissionBehavior.DENY), store, true)
                .call(List.of(userMsg("deploy?")), sessA)
                .block();

        // The new build declares the tool with ALLOW only, so the session's stale DENY must be
        // dropped. The count is the evidence rather than the turn's reason: a denied call leaves
        // the
        // loop running and the turn still ends with the plain-text reply, so both outcomes report
        // MODEL_STOP and a reason-based assertion could not tell them apart.
        DeployTool tool = new DeployTool();
        agent(model("tc-d2"), rules(PermissionBehavior.ALLOW), store, true, tool)
                .call(List.of(userMsg("deploy?")), sessA)
                .block();

        assertEquals(
                1,
                tool.callCount(),
                "the declared ALLOW replaced the session's stale DENY, so the tool ran");
    }

    /**
     * Composition replaces the session's *rules* only. Its mode has to survive, or a runtime
     * {@code setPermissionMode} — and anything a host set at init time for that session — would be
     * silently reset by enabling the option.
     */
    @Test
    @DisplayName("with the flag: the session's mode still wins over the declared one")
    void sessionModeSurvivesComposition() {
        InMemoryAgentStateStore store = new InMemoryAgentStateStore();
        RuntimeContext sessA = session("sessA");

        // The session is created under DONT_ASK and persists that mode.
        agent(
                        model("tc-m1"),
                        PermissionContextState.builder().mode(PermissionMode.DONT_ASK).build(),
                        store)
                .call(List.of(userMsg("deploy?")), sessA)
                .block();

        // A later build declares DEFAULT and no rules at all. The session's DONT_ASK turns the
        // engine's default ask into a deny, so the tool must not run. A regression that took the
        // declared mode would leave exactly a trivial context, drop onto the lightweight path and
        // run it -- and the turn's reason would read MODEL_STOP either way, which is why the count
        // is what this asserts on.
        DeployTool tool = new DeployTool();
        agent(
                        model("tc-m2"),
                        PermissionContextState.builder().mode(PermissionMode.DEFAULT).build(),
                        store,
                        true,
                        tool)
                .call(List.of(userMsg("deploy?")), sessA)
                .block();

        assertEquals(0, tool.callCount(), "the session kept DONT_ASK, so the tool is denied");
    }

    /**
     * The flag is only meaningful next to a declaration. Setting it while declaring nothing must
     * leave the session's own rules alone rather than clearing them.
     */
    @Test
    @DisplayName("with the flag but nothing declared, the session's own rules still apply")
    void flagWithoutADeclarationLeavesTheSessionAlone() {
        InMemoryAgentStateStore store = new InMemoryAgentStateStore();
        RuntimeContext sessA = session("sessA");

        agent(model("tc-n1"), rules(PermissionBehavior.ALLOW), store)
                .call(List.of(userMsg("deploy?")), sessA)
                .block();

        // The redeploy sets the option but declares no permission context at all.
        Msg result =
                agent(model("tc-n2"), null, store, true)
                        .call(List.of(userMsg("deploy?")), sessA)
                        .block();

        assertNotNull(result);
        assertEquals(
                GenerateReason.MODEL_STOP,
                result.getGenerateReason(),
                "with nothing declared the session keeps its own ALLOW");
    }

    /** Control: the same v2 declaration does apply to a session created after it. */
    @Test
    @DisplayName("control: the same declaration applies to a session created after it")
    void tightenedRuleAppliesToANewSession() {
        InMemoryAgentStateStore store = new InMemoryAgentStateStore();

        agent(model("tc-v1"), rules(PermissionBehavior.ALLOW), store)
                .call(List.of(userMsg("deploy?")), session("sessA"))
                .block();

        Msg fresh =
                agent(model("tc-v2"), rules(PermissionBehavior.ASK), store)
                        .call(List.of(userMsg("deploy?")), session("sessB"))
                        .block();

        assertNotNull(fresh);
        assertEquals(GenerateReason.PERMISSION_ASKING, fresh.getGenerateReason());
    }
}
