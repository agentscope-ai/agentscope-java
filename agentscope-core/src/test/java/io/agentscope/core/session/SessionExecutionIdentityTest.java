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
package io.agentscope.core.session;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.agentscope.core.ReActAgent;
import io.agentscope.core.agent.AgentRun;
import io.agentscope.core.agent.ExecutionIdentity;
import io.agentscope.core.agent.RuntimeContext;
import io.agentscope.core.event.AgentEndEvent;
import io.agentscope.core.event.AgentEvent;
import io.agentscope.core.event.AgentResultEvent;
import io.agentscope.core.event.RequireExternalExecutionEvent;
import io.agentscope.core.message.ContentBlock;
import io.agentscope.core.message.GenerateReason;
import io.agentscope.core.message.Msg;
import io.agentscope.core.message.TextBlock;
import io.agentscope.core.message.ToolResultBlock;
import io.agentscope.core.message.ToolResultMessage;
import io.agentscope.core.message.ToolUseBlock;
import io.agentscope.core.message.UserMessage;
import io.agentscope.core.model.ChatResponse;
import io.agentscope.core.model.GenerateOptions;
import io.agentscope.core.model.Model;
import io.agentscope.core.model.ToolSchema;
import io.agentscope.core.tool.Toolkit;
import io.agentscope.core.util.JsonUtils;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import reactor.core.publisher.Flux;

class SessionExecutionIdentityTest {
    private static final Duration WAIT = Duration.ofSeconds(10);

    private RuntimeContext context() {
        return RuntimeContext.builder().userId("u").sessionId("s").build();
    }

    private ReActAgent agent(boolean external) {
        AtomicInteger calls = new AtomicInteger();
        Model model =
                new Model() {
                    public String getModelName() {
                        return "identity-model";
                    }

                    public Flux<ChatResponse> stream(
                            List<Msg> messages, List<ToolSchema> tools, GenerateOptions options) {
                        ContentBlock content =
                                external && calls.getAndIncrement() == 0
                                        ? ToolUseBlock.builder()
                                                .id("tool-1")
                                                .name("external")
                                                .input(Map.of())
                                                .build()
                                        : TextBlock.builder().text("done").build();
                        return Flux.just(ChatResponse.builder().content(List.of(content)).build());
                    }
                };
        var toolkit = new Toolkit();
        toolkit.registerSchema(
                ToolSchema.builder()
                        .name("external")
                        .description("external")
                        .parameters(Map.of("type", "object", "properties", Map.of()))
                        .build());
        return ReActAgent.builder()
                .name("stable")
                .model(model)
                .toolkit(toolkit)
                .sessionLogStore(new InMemorySessionLogStore())
                .build();
    }

    @Test
    void handleLiveEventsAndNativeFactsShareExecutionIdentity() {
        var agent = agent(false);
        var context = context();
        var run = agent.prepareRun(List.of(new UserMessage("hello")), context);
        var events = run.stream().collectList().block(WAIT);
        assertFalse(events.isEmpty());
        ExecutionIdentity identity = events.get(0).getExecution();
        assertEquals(context.getRunId(), run.runId());
        assertEquals(run.runId(), identity.runId());
        assertEquals("s", identity.sessionId());
        assertEquals("stable", identity.agentId());
        assertTrue(events.stream().allMatch(e -> identity.equals(e.getExecution())));
        assertTrue(
                agent.sessionLog(context).readAfter(0, 1000).stream()
                        .allMatch(
                                e ->
                                        run.runId().equals(e.executionRunId())
                                                && identity.turnId().equals(e.turnId())));
        assertNotNull(context.getAgentState());
        assertNull(context.get(SessionRecorder.TURN_ID_KEY));
        assertEquals(AgentRun.Status.COMPLETED, run.status());
        assertThrows(
                SessionLogException.class,
                () ->
                        agent.prepareCall(List.of(new UserMessage("duplicate")), context).stream()
                                .blockLast(WAIT));
        var nextContext = RuntimeContext.builder(context).runId(null).build();
        var call = agent.prepareCall(List.of(new UserMessage("second")), nextContext);
        assertEquals(nextContext.getRunId(), call.runId());
        assertNotEquals(run.runId(), call.runId());
        call.stream().blockLast(WAIT);
        assertTrue(
                agent.sessionLog(context).readAfter(0, 1000).stream()
                        .anyMatch(
                                e ->
                                        e.type().equals("run/end")
                                                && e.executionRunId().equals(call.runId())));
    }

    @Test
    void committedAssistantOutputKeepsItsModelCallIdentity() {
        var agent = agent(false);
        var ctx = context();
        agent.call(List.of(new UserMessage("hello")), ctx).block(WAIT);
        var facts = agent.sessionLog(ctx).readAfter(0, 1000);
        String model =
                facts.stream()
                        .filter(e -> e.type().equals("model/chunk"))
                        .map(e -> (String) e.data().get("modelCallId"))
                        .findFirst()
                        .orElseThrow();
        var outputs =
                facts.stream()
                        .filter(
                                e ->
                                        e.type().equals("message/assistant")
                                                || e.type().equals("turn/output"))
                        .toList();
        assertFalse(outputs.isEmpty());
        outputs.forEach(e -> assertEquals(model, e.data().get("modelCallId")));
    }

    @Test
    void eachDirectSubscriptionAllocatesIndependentRunAndDefaultTurn() {
        var agent = agent(false);
        var ctx = context();
        var stream = agent.streamEvents(List.of(new UserMessage("hello")), ctx);
        var first = stream.collectList().block(WAIT).get(0).getExecution();
        var second = stream.collectList().block(WAIT).get(0).getExecution();
        assertNotEquals(first.runId(), second.runId());
        assertNotEquals(first.turnId(), second.turnId());
    }

    @Test
    void externalContinuationPreservesTurnButStartsNewRun() {
        var agent = agent(true);
        var ctx = context();
        ctx.put(SessionRecorder.TURN_ID_KEY, "logical-turn");
        var first = agent.prepareRun(List.of(new UserMessage("hello")), ctx);
        var events = first.stream().collectList().block(WAIT);
        assertEquals(AgentRun.Status.COMPLETED, first.status());
        assertTrue(
                events.stream()
                        .filter(AgentResultEvent.class::isInstance)
                        .map(AgentResultEvent.class::cast)
                        .anyMatch(
                                e ->
                                        e.getResult().getGenerateReason()
                                                == GenerateReason.TOOL_SUSPENDED));
        var log = agent.sessionLog(ctx);
        assertEquals("logical-turn", SessionInteractions.continuationTurn(log, Set.of("tool-1")));
        assertThrows(
                IllegalArgumentException.class,
                () -> SessionInteractions.continuationTurn(log, Set.of("invented")));
        var second =
                agent.prepareRun(
                        List.of(
                                ToolResultMessage.builder()
                                        .result(
                                                ToolResultBlock.of(
                                                        "tool-1",
                                                        "external",
                                                        TextBlock.builder().text("ok").build()))
                                        .build()),
                        RuntimeContext.builder(ctx).runId(null).build());
        second.stream().blockLast(WAIT);
        assertNotEquals(first.runId(), second.runId());
        var facts = log.readAfter(0, 1000);
        for (String type :
                List.of("turn/start", "turn/resumed", "turn/suspended", "turn/completed"))
            assertEquals(1, facts.stream().filter(e -> e.type().equals(type)).count(), type);
        assertTrue(facts.stream().allMatch(e -> "logical-turn".equals(e.turnId())));
        assertFalse(facts.stream().anyMatch(e -> e.type().equals("turn/end")));
        assertTrue(SessionInteractions.pending(log).isEmpty());
        assertThrows(
                RuntimeException.class,
                () ->
                        agent
                                .prepareCall(
                                        List.of(), RuntimeContext.builder(ctx).runId(null).build())
                                .stream()
                                .blockLast(WAIT));
    }

    @Test
    void continuationRejectsMixedTurnsAndResolvedRequests() {
        var log = new InMemorySessionLogStore().open(new SessionKey("u", "stable", "s"), context());
        for (String turn : List.of("a", "b")) {
            var recorder = new SessionRecorder(log, turn);
            try {
                recorder.recordNow(
                        "interaction/requested",
                        Map.of("requestId", turn, "kind", "external_execution"));
            } finally {
                recorder.close();
            }
        }
        assertThrows(
                IllegalArgumentException.class,
                () -> SessionInteractions.continuationTurn(log, Set.of("a", "b")));
        var recorder = new SessionRecorder(log, "a");
        try {
            recorder.recordNow("interaction/resolved", Map.of("requestId", "a"));
        } finally {
            recorder.close();
        }
        assertThrows(
                IllegalArgumentException.class,
                () -> SessionInteractions.continuationTurn(log, Set.of("a")));
        assertEquals("b", SessionInteractions.continuationTurn(log, Set.of("b")));
    }

    @Test
    void forwardedIdentitySurvivesSerializationAndDoesNotPolluteParentRequests() {
        var child = new ExecutionIdentity("child", "child-session", "child-turn", "child-run");
        AgentEvent event = new AgentEndEvent("reply").withExecution(child);
        event.withExecution(new ExecutionIdentity("parent", "s", "t", "parent-run"));
        var decoded =
                JsonUtils.getJsonCodec()
                        .fromJson(JsonUtils.getJsonCodec().toJson(event), AgentEvent.class);
        assertEquals(child, decoded.getExecution());
        var log = new InMemorySessionLogStore().open(new SessionKey("u", "parent", "s"), context());
        var recorder = new SessionRecorder(log, "t", "parent-run", null);
        try {
            var request =
                    new RequireExternalExecutionEvent(
                                    "reply",
                                    List.of(
                                            ToolUseBlock.builder()
                                                    .id("child-tool")
                                                    .name("external")
                                                    .input(Map.of())
                                                    .build()))
                            .withExecution(child);
            recorder.observe(request);
            recorder.flushNow();
            assertTrue(SessionInteractions.pending(log).isEmpty());
        } finally {
            recorder.close();
        }
    }
}
