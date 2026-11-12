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
package io.agentscope.core.journal;

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
import io.agentscope.core.agent.RunOutcome;
import io.agentscope.core.agent.RuntimeContext;
import io.agentscope.core.agent.ThreadKey;
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
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import reactor.core.publisher.Flux;

class InvocationIdentityTest {
    private static final Duration WAIT = Duration.ofSeconds(10);

    private RuntimeContext context() {
        return RuntimeContext.builder().userId("u").sessionId("s").build();
    }

    private ReActAgent agent(boolean external) {
        return agent(external, true);
    }

    private ReActAgent agent(boolean external, boolean journal) {
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
        var builder = ReActAgent.builder().name("stable").model(model).toolkit(toolkit);
        if (journal) builder.threadJournalStore(new InMemoryThreadJournalStore());
        return builder.build();
    }

    @Test
    void handleLiveEventsAndNativeFactsShareExecutionIdentity() {
        var agent = agent(false);
        var context = RuntimeContext.builder(context()).runId("native-invocation").build();
        var run = agent.prepareRun(List.of(new UserMessage("hello")), context);
        var events = run.stream().collectList().block(WAIT);
        assertFalse(events.isEmpty());
        ExecutionIdentity identity = events.get(0).getExecution();
        assertEquals(context.getRunId(), run.runId());
        assertEquals(run.runId(), identity.runId());
        assertEquals("s", identity.sessionId());
        assertEquals(context.getThreadId(), identity.threadId());
        assertEquals("stable", identity.agentId());
        assertNull(identity.turnId());
        assertTrue(events.stream().allMatch(e -> identity.equals(e.getExecution())));
        assertTrue(
                agent.threadJournal(context).readAfter(0, 1000).stream()
                        .allMatch(
                                e -> run.runId().equals(e.executionRunId()) && e.turnId() == null));
        assertNotNull(context.getAgentState());
        assertNull(context.getTurnId());
        assertEquals(AgentRun.Status.COMPLETED, run.status());
        assertThrows(
                JournalException.class,
                () ->
                        agent.prepareCall(List.of(new UserMessage("duplicate")), context).stream()
                                .blockLast(WAIT));
        var nextContext = RuntimeContext.builder(context).runId(null).build();
        var call = agent.prepareCall(List.of(new UserMessage("second")), nextContext);
        assertNull(nextContext.getRunId());
        assertNotNull(call.runId());
        assertNotEquals(run.runId(), call.runId());
        call.stream().blockLast(WAIT);
        assertTrue(
                agent.threadJournal(context).readAfter(0, 1000).stream()
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
        var facts = agent.threadJournal(ctx).readAfter(0, 1000);
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
                                                || e.type().equals("run/output"))
                        .toList();
        assertFalse(outputs.isEmpty());
        outputs.forEach(e -> assertEquals(model, e.data().get("modelCallId")));
        var text = facts.stream().filter(e -> e.type().equals("presentation/text")).toList();
        assertEquals(1, text.size());
        assertEquals("done", text.get(0).data().get("text"));
        assertEquals(outputs.get(0).data().get("stepId"), text.get(0).data().get("stepId"));
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void eachDirectSubscriptionAllocatesIndependentRunWithoutInventingTurn(boolean journal) {
        var agent = agent(false, journal);
        var ctx = context();
        var stream = agent.streamEvents(List.of(new UserMessage("hello")), ctx);
        var first = stream.collectList().block(WAIT).get(0).getExecution();
        var second = stream.collectList().block(WAIT).get(0).getExecution();
        assertNotEquals(first.runId(), second.runId());
        assertNull(first.turnId());
        assertNull(second.turnId());
        assertEquals(first.threadId(), second.threadId());
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void externalContinuationPreservesOptionalHostCorrelationAndStartsNewRun(boolean managed) {
        var agent = agent(true);
        String turnId = managed ? "logical-turn" : null;
        var ctx = RuntimeContext.builder(context()).threadId("research").turnId(turnId).build();
        var first = agent.prepareRun(List.of(new UserMessage("hello")), ctx);
        var events = first.stream().collectList().block(WAIT);
        assertTrue(
                events.stream()
                        .allMatch(event -> "research".equals(event.getExecution().threadId())));
        assertEquals(AgentRun.Status.COMPLETED, first.status());
        assertEquals(RunOutcome.Status.SUSPENDED, first.outcome().block(WAIT).status());
        assertTrue(
                events.stream()
                        .filter(AgentResultEvent.class::isInstance)
                        .map(AgentResultEvent.class::cast)
                        .anyMatch(
                                e ->
                                        e.getResult().getGenerateReason()
                                                == GenerateReason.TOOL_SUSPENDED));
        var log = agent.threadJournal(ctx);
        var request = ThreadInteractions.continuationRequests(log, Set.of("tool-1")).get("tool-1");
        assertEquals(turnId, request.turnId());
        assertEquals(first.runId(), request.executionRunId());
        assertNotNull(request.data().get("stepId"));
        assertThrows(
                IllegalArgumentException.class,
                () -> ThreadInteractions.continuationRequests(log, Set.of("invented")));
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
        assertEquals(RunOutcome.Status.COMPLETED, second.outcome().block(WAIT).status());
        assertNotEquals(first.runId(), second.runId());
        var facts = log.readAfter(0, 1000);
        assertEquals(2, facts.stream().filter(e -> e.type().equals("run/start")).count());
        assertEquals(
                List.of("suspended", "completed"),
                facts.stream()
                        .filter(e -> e.type().equals("run/end"))
                        .map(e -> e.data().get("status"))
                        .toList());
        assertTrue(facts.stream().allMatch(e -> Objects.equals(turnId, e.turnId())));
        var continuation =
                facts.stream()
                        .filter(
                                e ->
                                        second.runId().equals(e.executionRunId())
                                                && "step/start".equals(e.type()))
                        .findFirst()
                        .orElseThrow();
        assertEquals("ACTING", continuation.data().get("phase"));
        assertEquals(request.data().get("stepId"), continuation.data().get("resumedFromStepId"));
        assertNotEquals(request.data().get("stepId"), continuation.data().get("stepId"));
        assertEquals(2, facts.stream().filter(e -> "model/dispatch".equals(e.type())).count());
        assertFalse(facts.stream().anyMatch(e -> e.type().startsWith("turn/")));
        assertTrue(ThreadInteractions.pending(log).isEmpty());
        // The host can accept more input before settling its Turn. Core owns invocations only.
        var third =
                agent.prepareCall(
                        List.of(new UserMessage("one more check")),
                        RuntimeContext.builder(ctx).runId(null).build());
        assertNotNull(third.stream().blockLast(WAIT));
        assertNotEquals(second.runId(), third.runId());
        assertEquals(
                3,
                log.readAfter(0, 1000).stream()
                        .filter(
                                event ->
                                        "run/start".equals(event.type())
                                                && Objects.equals(turnId, event.turnId()))
                        .count());
    }

    @Test
    void continuationRejectsMixedTurnsAndResolvedRequests() {
        var log =
                new InMemoryThreadJournalStore()
                        .open(new ThreadKey("u", "stable", "s", "main"), context());
        for (String turn : List.of("a", "b")) {
            var recorder = new InvocationRecorder(log, turn);
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
                () -> ThreadInteractions.continuationRequests(log, Set.of("a", "b")));
        var recorder = new InvocationRecorder(log, "a");
        try {
            recorder.recordNow("interaction/resolved", Map.of("requestId", "a"));
        } finally {
            recorder.close();
        }
        assertThrows(
                IllegalArgumentException.class,
                () -> ThreadInteractions.continuationRequests(log, Set.of("a")));
        assertEquals(
                "b", ThreadInteractions.continuationRequests(log, Set.of("b")).get("b").turnId());
    }

    @Test
    void identityAllowsMissingHostTurnButRejectsBlankCorrelation() {
        var identity = new ExecutionIdentity("agent", "session", "thread", null, "run");
        assertEquals(
                identity,
                JsonUtils.getJsonCodec()
                        .fromJson(
                                JsonUtils.getJsonCodec().toJson(identity),
                                ExecutionIdentity.class));
        assertThrows(
                IllegalArgumentException.class,
                () -> new ExecutionIdentity("agent", "session", "thread", " ", "run"));
    }

    @Test
    void forwardedIdentitySurvivesSerializationAndDoesNotPolluteParentRequests() {
        var child =
                new ExecutionIdentity("child", "child-session", "main", "child-turn", "child-run");
        AgentEvent event = new AgentEndEvent("reply").withExecution(child);
        event.withExecution(new ExecutionIdentity("parent", "s", "main", "t", "parent-run"));
        var decoded =
                JsonUtils.getJsonCodec()
                        .fromJson(JsonUtils.getJsonCodec().toJson(event), AgentEvent.class);
        assertEquals(child, decoded.getExecution());
        var log =
                new InMemoryThreadJournalStore()
                        .open(new ThreadKey("u", "parent", "s", "main"), context());
        var recorder = new InvocationRecorder(log, "t", "parent-run", null);
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
            assertTrue(ThreadInteractions.pending(log).isEmpty());
        } finally {
            recorder.close();
        }
    }
}
