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

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.agentscope.core.ReActAgent;
import io.agentscope.core.event.AgentEvent;
import io.agentscope.core.event.RequestStopEvent;
import io.agentscope.core.event.RequireUserConfirmEvent;
import io.agentscope.core.message.ContentBlock;
import io.agentscope.core.message.GenerateReason;
import io.agentscope.core.message.Msg;
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
import io.agentscope.core.state.AgentState;
import io.agentscope.core.state.InMemoryAgentStateStore;
import io.agentscope.core.tool.ToolBase;
import io.agentscope.core.tool.ToolCallParam;
import io.agentscope.core.tool.Toolkit;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Supplier;
import org.junit.jupiter.api.Test;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

/**
 * Acceptance test for defect D-08 live gating gap: a {@link ReActAgent} built with a non-trivial
 * initial {@link PermissionContextState} must still gate a passthrough tool ({@code write_file}-like)
 * on a <em>stale persisted session</em> whose stored permission context is trivial.
 *
 * <p>The companion unit test {@code PermissionContextFactoryTest} (admin-service) only drives the
 * engine in isolation and so passes regardless of whether the live agent actually loads the
 * non-trivial context for a stale session. This test closes that gap by exercising the real
 * {@link ReActAgent} slot-activation + permission-engine load path against a pre-seeded trivial
 * session state.
 */
class ReActAgentStalePermissionContextTest {

    /**
     * Real-world file-write tool stand-in: {@code checkPermissions} returns {@code passthrough}
     * (exactly like the harness {@code FilesystemTool} for non-readOnly tools), so under a trivial
     * context the lightweight path allows it unchecked; under a non-trivial context with no allow
     * rule it falls through to {@code defaultDecisionAsk} → ASK → {@link RequireUserConfirmEvent}.
     */
    private static final class PassthroughWriteTool extends ToolBase {
        PassthroughWriteTool() {
            super(
                    "write_file",
                    "write a file",
                    schemaFor(),
                    false, // not readOnly
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
            Map<String, Object> path = new HashMap<>();
            path.put("type", "string");
            props.put("path", path);
            Map<String, Object> content = new HashMap<>();
            content.put("type", "string");
            props.put("content", content);
            schema.put("properties", props);
            return schema;
        }

        @Override
        public Mono<PermissionDecision> checkPermissions(
                Map<String, Object> toolInput, PermissionContextState context) {
            return Mono.just(PermissionDecision.passthrough("passthrough: " + getName()));
        }

        @Override
        public Mono<ToolResultBlock> callAsync(ToolCallParam param) {
            return Mono.just(ToolResultBlock.text("written"));
        }
    }

    /**
     * A non-trivial DEFAULT context equivalent to what {@code PermissionContextFactory.build(DEFAULT)}
     * produces on the admin-service side: read-only built-ins ALLOWed (which is what makes the
     * context non-trivial), write_file has no allow rule so the engine's {@code defaultDecisionAsk}
     * fires.
     */
    private static PermissionContextState defaultContext() {
        PermissionContextState.Builder b =
                PermissionContextState.builder().mode(PermissionMode.DEFAULT);
        b.addAllowRule(
                "read_file",
                new PermissionRule("read_file", null, PermissionBehavior.ALLOW, "test"));
        return b.build();
    }

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
    }

    private static ChatResponse textResponse(String text) {
        return ChatResponse.builder()
                .content(List.<ContentBlock>of(TextBlock.builder().text(text).build()))
                .build();
    }

    private static ChatResponse writeToolResponse(String toolId) {
        Map<String, Object> input = new HashMap<>();
        input.put("path", "stale.txt");
        input.put("content", "hi");
        return ChatResponse.builder()
                .content(
                        List.<ContentBlock>of(
                                ToolUseBlock.builder()
                                        .id(toolId)
                                        .name("write_file")
                                        .input(input)
                                        .build()))
                .build();
    }

    private static Toolkit toolkitWith(ToolBase... tools) {
        Toolkit tk = new Toolkit();
        for (ToolBase t : tools) {
            tk.registerAgentTool(t);
        }
        return tk;
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
    void staleTrivialSession_isUpgradedToInitialContext_andGatesWriteFile() {
        InMemoryAgentStateStore store = new InMemoryAgentStateStore();
        // Pre-seed a STALE session with a TRIVIAL permission context — this is the live failure
        // scenario: a session persisted before the agent seeded a non-trivial context.
        String userId = "u1";
        String sessionId = "stale-session";
        AgentState stale =
                AgentState.builder().sessionId(sessionId).userId(userId).build(); // trivial ctx
        store.save(userId, sessionId, "agent_state", stale);

        ReActAgent agent =
                ReActAgent.builder()
                        .name("asst")
                        .defaultSessionId(sessionId)
                        .stateStore(store)
                        .permissionContext(defaultContext())
                        .toolkit(toolkitWith(new PassthroughWriteTool()))
                        .model(
                                new ScriptedModel(
                                        List.of(() -> Flux.just(writeToolResponse("tc1")))))
                        .build();

        Msg result =
                agent.call(
                                List.of(),
                                RuntimeContext.builder()
                                        .userId(userId)
                                        .sessionId(sessionId)
                                        .build())
                        .block();
        assertNotNull(result);
        // Without the merge fix the result would be a normal completion (write_file ran). With the
        // fix the agent pauses with PERMISSION_ASKING.
        assertTrue(
                result.getGenerateReason() == GenerateReason.PERMISSION_ASKING,
                "expected PERMISSION_ASKING after stale-session upgrade, got "
                        + result.getGenerateReason());
    }

    @Test
    void staleTrivialSession_emitsRequireUserConfirmEvent() {
        InMemoryAgentStateStore store = new InMemoryAgentStateStore();
        String userId = "u1";
        String sessionId = "stale-session-2";
        store.save(
                userId,
                sessionId,
                "agent_state",
                AgentState.builder().sessionId(sessionId).userId(userId).build());

        ReActAgent agent =
                ReActAgent.builder()
                        .name("asst")
                        .defaultSessionId(sessionId)
                        .stateStore(store)
                        .permissionContext(defaultContext())
                        .toolkit(toolkitWith(new PassthroughWriteTool()))
                        .model(
                                new ScriptedModel(
                                        List.of(() -> Flux.just(writeToolResponse("tc1")))))
                        .build();

        List<AgentEvent> events =
                agent.streamEvents(
                                List.of(),
                                RuntimeContext.builder()
                                        .userId(userId)
                                        .sessionId(sessionId)
                                        .build())
                        .collectList()
                        .block();
        assertNotNull(events);

        int iReq = indexOf(events, RequireUserConfirmEvent.class);
        int iStop = indexOf(events, RequestStopEvent.class);
        assertTrue(iReq >= 0, "RequireUserConfirmEvent must be emitted on stale session");
        assertTrue(iStop > iReq, "RequestStopEvent must follow RequireUserConfirmEvent");
        RequireUserConfirmEvent req = (RequireUserConfirmEvent) events.get(iReq);
        assertTrue(
                req.getToolCalls().stream().anyMatch(tc -> "write_file".equals(tc.getName())),
                "RequireUserConfirmEvent must name write_file");
        RequestStopEvent stop = (RequestStopEvent) events.get(iStop);
        assertTrue(
                stop.getGenerateReason() == GenerateReason.PERMISSION_ASKING,
                "RequestStopEvent must carry PERMISSION_ASKING");
    }
}
