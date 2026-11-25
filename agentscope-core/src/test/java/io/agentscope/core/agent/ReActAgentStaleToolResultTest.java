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
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.agentscope.core.ReActAgent;
import io.agentscope.core.event.AgentEvent;
import io.agentscope.core.event.ExternalExecutionResultEvent;
import io.agentscope.core.event.RequireExternalExecutionEvent;
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
import io.agentscope.core.tool.ToolBase;
import io.agentscope.core.tool.ToolCallParam;
import io.agentscope.core.tool.Toolkit;
import io.agentscope.core.util.JsonUtils;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.function.Supplier;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

/**
 * Resuming a suspended tool call with caller-supplied results when the caller also replays
 * results that were already consumed in an earlier round (#3043).
 *
 * <p>Transcript-replaying clients (AG-UI / CopilotKit-style) resend their whole message trail,
 * so a later resume arrives as [stale result(s), current result(s), maybe user text]. A stale
 * result belongs to no pending call: it must be ignored — never persisted, never treated as a
 * protocol error, and never read as a decision about the calls that are still pending.
 */
class ReActAgentStaleToolResultTest {

    @BeforeEach
    void resetDispatchCounter() {
        SuspendingTool.dispatches = 0;
    }

    /** Model returning one scripted {@link ChatResponse} stream per sequential call. */
    private static final class ScriptedModel extends ChatModelBase {
        private final List<Supplier<Flux<ChatResponse>>> scripts;
        private int idx;
        private final List<List<Msg>> seenPrompts = new ArrayList<>();

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
            seenPrompts.add(List.copyOf(messages));
            if (idx >= scripts.size()) {
                return Flux.just(text("fallback"));
            }
            return scripts.get(idx++).get();
        }

        /** Prompts the model received on its 2nd and later calls (i.e. after a resume). */
        List<List<Msg>> promptsAfterResume() {
            return seenPrompts.subList(1, seenPrompts.size());
        }
    }

    /** Tool that always suspends, leaving its call to be resolved by the caller. */
    private static final class SuspendingTool extends ToolBase {
        /** Total {@link #callAsync} invocations across all instances; reset per test. */
        static int dispatches;

        SuspendingTool(String name) {
            super(
                    ToolBase.builder()
                            .name(name)
                            .description(name)
                            .inputSchema(Map.of("type", "object", "properties", Map.of())));
        }

        @Override
        public Mono<PermissionDecision> checkPermissions(
                Map<String, Object> toolInput, PermissionContextState context) {
            return Mono.just(PermissionDecision.passthrough(getName()));
        }

        @Override
        public Mono<ToolResultBlock> callAsync(ToolCallParam param) {
            dispatches++;
            return Mono.just(ToolResultBlock.suspended(param.getToolUseBlock()));
        }
    }

    private static ChatResponse text(String value) {
        return ChatResponse.builder()
                .content(List.of(TextBlock.builder().text(value).build()))
                .build();
    }

    private static ChatResponse toolUses(ToolUseBlock... calls) {
        return ChatResponse.builder().content(List.of(calls)).build();
    }

    private static ToolUseBlock call(String id, String name) {
        return ToolUseBlock.builder().id(id).name(name).input(Map.of()).build();
    }

    private static ReActAgent agent(ScriptedModel model, String... toolNames) {
        Toolkit toolkit = new Toolkit();
        for (String name : toolNames) {
            toolkit.registerAgentTool(new SuspendingTool(name));
        }
        return ReActAgent.builder().name("asst").model(model).toolkit(toolkit).build();
    }

    private static Msg resultMsg(ToolResultBlock... results) {
        return Msg.builder().role(MsgRole.TOOL).content(List.of(results)).build();
    }

    private static ToolResultBlock success(String id, String name, String value) {
        return ToolResultBlock.builder()
                .id(id)
                .name(name)
                .output(TextBlock.builder().text(value).build())
                .state(ToolResultState.SUCCESS)
                .build();
    }

    private static Msg userText(String value) {
        return Msg.builder().role(MsgRole.USER).textContent(value).build();
    }

    /** Every tool-result id present in the agent's persisted context. */
    private static List<String> persistedResultIds(ReActAgent agent) {
        List<String> ids = new ArrayList<>();
        for (Msg msg : agent.getAgentState().getContext()) {
            for (ToolResultBlock block : msg.getContentBlocks(ToolResultBlock.class)) {
                ids.add(block.getId());
            }
        }
        return ids;
    }

    /** A stale id from "an earlier round" — it never was a pending call of this session. */
    /** Runs one call through {@code streamEvents} and returns everything it emitted. */
    private static List<AgentEvent> run(ReActAgent agent, List<Msg> input) {
        List<AgentEvent> events =
                agent.streamEvents(input, RuntimeContext.empty())
                        .collectList()
                        .block(Duration.ofSeconds(5));
        assertNotNull(events);
        return events;
    }

    private static <T extends AgentEvent> List<T> eventsOf(List<AgentEvent> events, Class<T> type) {
        List<T> matched = new ArrayList<>();
        for (AgentEvent event : events) {
            if (type.isInstance(event)) {
                matched.add(type.cast(event));
            }
        }
        return matched;
    }

    private static <T extends AgentEvent> T single(List<T> events) {
        assertEquals(1, events.size(), "exactly one such event is expected");
        return events.get(0);
    }

    /**
     * Suspends the given call and returns the reply id of the request emitted for it, which the
     * agent also records on the pending assistant message.
     */
    private static String suspendAndAwaitExternalResult(ReActAgent agent, String callId) {
        List<AgentEvent> first = run(agent, List.of());
        RequireExternalExecutionEvent request =
                single(eventsOf(first, RequireExternalExecutionEvent.class));
        assertEquals(
                List.of(callId), request.getToolCalls().stream().map(ToolUseBlock::getId).toList());
        assertEquals(
                request.getReplyId(),
                pendingExternalReplyId(agent),
                "the pending request is recorded under its reply id");
        return request.getReplyId();
    }

    /** The reply id of the external-execution request still pending, or {@code null}. */
    private static String pendingExternalReplyId(ReActAgent agent) {
        List<Msg> context = agent.getAgentState().getContext();
        for (int i = context.size() - 1; i >= 0; i--) {
            Msg msg = context.get(i);
            if (msg.getRole() == MsgRole.ASSISTANT) {
                Object raw =
                        msg.getMetadata() == null
                                ? null
                                : msg.getMetadata()
                                        .get(Msg.METADATA_EXTERNAL_EXECUTION_REQUEST_REPLY_ID);
                return raw instanceof String id ? id : null;
            }
        }
        return null;
    }

    private static ReActAgent agentWithRecovery(ScriptedModel model, String... toolNames) {
        Toolkit toolkit = new Toolkit();
        for (String name : toolNames) {
            toolkit.registerAgentTool(new SuspendingTool(name));
        }
        return ReActAgent.builder()
                .name("asst")
                .model(model)
                .toolkit(toolkit)
                .enablePendingToolRecovery(true)
                .build();
    }

    private static final String STALE = "call_01_staleFromEarlierRound";

    // ==================== mixed: one stale + the current result ====================

    @Test
    void staleResultBesideTheCurrentOne_isIgnoredAndNotPersisted() {
        ScriptedModel model =
                new ScriptedModel(List.of(() -> Flux.just(toolUses(call("tc1", "ext")))));
        ReActAgent agent = agent(model, "ext");
        assertEquals(
                GenerateReason.TOOL_SUSPENDED,
                agent.call(List.of()).block(Duration.ofSeconds(5)).getGenerateReason());

        Msg output =
                agent.call(
                                List.of(
                                        resultMsg(success(STALE, "ext", "old")),
                                        resultMsg(success("tc1", "ext", "fresh"))),
                                RuntimeContext.empty())
                        .block(Duration.ofSeconds(5));

        assertNotNull(output);
        List<String> persisted = persistedResultIds(agent);
        assertEquals(1, persisted.size(), "only the current result may reach the context");
        assertEquals("tc1", persisted.get(0), "the stale result must not be persisted");
    }

    @Test
    void staleResultIntheSameMessageAsTheCurrentOne_isDroppedBlockByBlock() {
        ScriptedModel model =
                new ScriptedModel(List.of(() -> Flux.just(toolUses(call("tc1", "ext")))));
        ReActAgent agent = agent(model, "ext");
        agent.call(List.of()).block(Duration.ofSeconds(5));

        agent.call(
                        List.of(
                                resultMsg(
                                        success(STALE, "ext", "old"),
                                        success("tc1", "ext", "fresh"))),
                        RuntimeContext.empty())
                .block(Duration.ofSeconds(5));

        assertEquals(List.of("tc1"), persistedResultIds(agent));
    }

    @Test
    void theCurrentResultStillReachesTheModelWithItsRealContent() {
        ScriptedModel model =
                new ScriptedModel(
                        List.of(
                                () -> Flux.just(toolUses(call("tc1", "ext"))),
                                () -> Flux.just(text("done"))));
        ReActAgent agent = agent(model, "ext");
        agent.call(List.of()).block(Duration.ofSeconds(5));

        Msg output =
                agent.call(
                                List.of(
                                        resultMsg(success(STALE, "ext", "old")),
                                        resultMsg(success("tc1", "ext", "FRESH-PAYLOAD"))),
                                RuntimeContext.empty())
                        .block(Duration.ofSeconds(5));

        assertNotNull(output);
        String prompt = JsonUtils.getJsonCodec().toJson(model.promptsAfterResume());
        assertTrue(prompt.contains("FRESH-PAYLOAD"), "the model must see the current result");
        assertTrue(
                !prompt.contains("old-from-stale") && !prompt.contains("\"stale-content\""),
                "nothing of the stale result may leak into the prompt");
    }

    // ==================== all-stale ====================

    @Test
    void allStaleResults_alone_doNotFailTheResumeAndLeaveTheCallPending() {
        ScriptedModel model =
                new ScriptedModel(List.of(() -> Flux.just(toolUses(call("tc1", "ext")))));
        ReActAgent agent = agent(model, "ext");
        agent.call(List.of()).block(Duration.ofSeconds(5));

        // Equivalent to "the caller sent no result": the pending call is simply resumed.
        Msg output =
                agent.call(List.of(resultMsg(success(STALE, "ext", "old"))), RuntimeContext.empty())
                        .block(Duration.ofSeconds(5));

        assertNotNull(output, "a replayed stale result alone must not error the resume");
        assertEquals(GenerateReason.TOOL_SUSPENDED, output.getGenerateReason());
        assertEquals(List.of(), persistedResultIds(agent), "no synthetic result may be written");
    }

    @Test
    void allStaleResultsWithUserText_doesNotCancelThePendingCallWithoutRecovery() {
        // Recovery is opt-in. Without it the framework must not invent cancellation results for
        // a call that is still legitimately pending — so the input is rejected, but with an
        // error that names the still-pending call rather than blaming the stale id.
        ScriptedModel model =
                new ScriptedModel(List.of(() -> Flux.just(toolUses(call("tc1", "ext")))));
        ReActAgent agent = agent(model, "ext");
        agent.call(List.of()).block(Duration.ofSeconds(5));

        IllegalStateException error =
                assertThrows(
                        IllegalStateException.class,
                        () ->
                                agent.call(
                                                List.of(
                                                        resultMsg(success(STALE, "ext", "old")),
                                                        userText("new instruction")),
                                                RuntimeContext.empty())
                                        .block(Duration.ofSeconds(5)));

        assertTrue(
                error.getMessage().contains("tc1"),
                "the error must point at the pending call: " + error.getMessage());
        assertTrue(
                !error.getMessage().contains(STALE),
                "the stale id is not the problem and must not be reported as invalid: "
                        + error.getMessage());
        assertEquals(List.of(), persistedResultIds(agent));
    }

    @Test
    void allStaleResultsWithUserText_recoveryOn_cancelsThePendingCallAndKeepsTheInstruction() {
        // With recovery explicitly enabled, the opt-in cancellation policy applies: the orphaned
        // call is patched, and the user's instruction reaches the model.
        ScriptedModel model =
                new ScriptedModel(
                        List.of(
                                () -> Flux.just(toolUses(call("tc1", "ext"))),
                                () -> Flux.just(text("done"))));
        Toolkit toolkit = new Toolkit();
        toolkit.registerAgentTool(new SuspendingTool("ext"));
        ReActAgent agent =
                ReActAgent.builder()
                        .name("asst")
                        .model(model)
                        .toolkit(toolkit)
                        .enablePendingToolRecovery(true)
                        .build();
        agent.call(List.of()).block(Duration.ofSeconds(5));

        Msg output =
                agent.call(
                                List.of(
                                        resultMsg(success(STALE, "ext", "old")),
                                        userText("NEW-INSTRUCTION")),
                                RuntimeContext.empty())
                        .block(Duration.ofSeconds(5));

        assertNotNull(output);
        String prompt = JsonUtils.getJsonCodec().toJson(model.promptsAfterResume());
        assertTrue(prompt.contains("NEW-INSTRUCTION"), "the user's text must reach the model");
        assertTrue(
                !prompt.contains("\"stale-content\""),
                "the stale result must not be persisted alongside it");
    }

    // ==================== a later legitimate result for a still-running call ====================

    @Test
    void aStillRunningCallAcceptsItsRealResultAfterAnAllStaleSubmission() {
        // Round 1 suspends two calls; the client first replays only stale junk (both stay
        // pending), then delivers tc1's real result — partial, so no text allowed — and
        // finally tc2's. Nothing may be lost or cancelled on the way.
        ScriptedModel model =
                new ScriptedModel(
                        List.of(
                                () -> Flux.just(toolUses(call("tc1", "ext"), call("tc2", "ext"))),
                                () -> Flux.just(text("done"))));
        ReActAgent agent = agent(model, "ext");
        agent.call(List.of()).block(Duration.ofSeconds(5));

        agent.call(List.of(resultMsg(success(STALE, "ext", "old"))), RuntimeContext.empty())
                .block(Duration.ofSeconds(5));
        assertEquals(List.of(), persistedResultIds(agent), "both calls still pending");

        // tc1 only: partial results must not carry text, and must leave tc2 pending.
        Msg partial =
                agent.call(
                                List.of(resultMsg(success("tc1", "ext", "first-real"))),
                                RuntimeContext.empty())
                        .block(Duration.ofSeconds(5));
        assertNotNull(partial);
        assertEquals(GenerateReason.TOOL_SUSPENDED, partial.getGenerateReason());
        assertEquals(List.of("tc1"), persistedResultIds(agent));

        // tc2 arrives later, beside another replayed stale result.
        Msg done =
                agent.call(
                                List.of(
                                        resultMsg(success(STALE, "ext", "old")),
                                        resultMsg(success("tc2", "ext", "second-real"))),
                                RuntimeContext.empty())
                        .block(Duration.ofSeconds(5));

        assertNotNull(done);
        assertEquals(List.of("tc1", "tc2"), persistedResultIds(agent));
        String prompt = JsonUtils.getJsonCodec().toJson(model.promptsAfterResume());
        assertTrue(prompt.contains("first-real") && prompt.contains("second-real"));
    }

    // ==================== stale input never (re)dispatches a pending call ====================

    @Test
    void replayingOnlyStaleResults_doesNotRedispatchThePendingToolCall() {
        // A pending external call was already dispatched and is awaiting its result. Replaying
        // an old result says nothing about it, so nothing may run again: the call stays pending
        // and the agent reports that it is still waiting.
        ScriptedModel model =
                new ScriptedModel(List.of(() -> Flux.just(toolUses(call("tc1", "ext")))));
        ReActAgent agent = agent(model, "ext");
        agent.call(List.of()).block(Duration.ofSeconds(5));
        assertEquals(1, SuspendingTool.dispatches, "the initial dispatch of tc1");

        for (int i = 0; i < 3; i++) {
            Msg output =
                    agent.call(
                                    List.of(resultMsg(success(STALE, "ext", "old"))),
                                    RuntimeContext.empty())
                            .block(Duration.ofSeconds(5));
            assertNotNull(output, "a replayed stale result alone must not error the resume");
            assertEquals(GenerateReason.TOOL_SUSPENDED, output.getGenerateReason());
        }

        assertEquals(
                1,
                SuspendingTool.dispatches,
                "replaying stale results must not dispatch the pending call again");
        assertEquals(List.of(), persistedResultIds(agent), "no synthetic result may be written");
    }

    // ==================== request / result bookkeeping ====================
    // A suspended external call is one pending request, identified by the reply id of its
    // RequireExternalExecutionEvent. Ignoring a replayed result must not disturb that
    // bookkeeping: the request is settled exactly once, by the real result, under the same id.

    @Test
    void mixedSubmission_publishesOneResultEventWithTheCurrentResultOnly() {
        ScriptedModel model =
                new ScriptedModel(List.of(() -> Flux.just(toolUses(call("tc1", "ext")))));
        ReActAgent agent = agent(model, "ext");
        String replyId = suspendAndAwaitExternalResult(agent, "tc1");

        List<AgentEvent> events =
                run(
                        agent,
                        List.of(
                                resultMsg(success(STALE, "ext", "old")),
                                resultMsg(success("tc1", "ext", "fresh"))));

        ExternalExecutionResultEvent result =
                single(eventsOf(events, ExternalExecutionResultEvent.class));
        assertEquals(replyId, result.getReplyId(), "must be correlated to the original request");
        assertEquals(
                List.of("tc1"),
                result.getToolResults().stream().map(ToolResultBlock::getId).toList(),
                "the replayed result must not be published as part of the outcome");
        assertNull(pendingExternalReplyId(agent), "the request is settled");
    }

    @Test
    void pureReplay_publishesNoResultEventAndKeepsTheRequestPendingUnderTheSameId() {
        ScriptedModel model =
                new ScriptedModel(List.of(() -> Flux.just(toolUses(call("tc1", "ext")))));
        ReActAgent agent = agent(model, "ext");
        String replyId = suspendAndAwaitExternalResult(agent, "tc1");

        List<AgentEvent> replayed = run(agent, List.of(resultMsg(success(STALE, "ext", "old"))));

        assertTrue(
                eventsOf(replayed, ExternalExecutionResultEvent.class).isEmpty(),
                "nothing was answered, so no result event may be published");
        assertEquals(
                replyId, pendingExternalReplyId(agent), "the request stays pending, unchanged");

        List<AgentEvent> answered =
                run(agent, List.of(resultMsg(success("tc1", "ext", "the real result"))));
        ExternalExecutionResultEvent result =
                single(eventsOf(answered, ExternalExecutionResultEvent.class));
        assertEquals(
                replyId, result.getReplyId(), "the real result still settles the original request");
        assertNull(pendingExternalReplyId(agent));
    }

    @Test
    void rejectedSubmission_leavesThePendingRequestUntouched() {
        // Stale results + user text without recovery is rejected. The rejection must not settle
        // or discard the pending request, which is still waiting for its real result.
        ScriptedModel model =
                new ScriptedModel(List.of(() -> Flux.just(toolUses(call("tc1", "ext")))));
        ReActAgent agent = agent(model, "ext");
        String replyId = suspendAndAwaitExternalResult(agent, "tc1");

        assertThrows(
                IllegalStateException.class,
                () ->
                        run(
                                agent,
                                List.of(
                                        resultMsg(success(STALE, "ext", "old")),
                                        userText("new instruction"))));

        assertEquals(replyId, pendingExternalReplyId(agent), "still the same pending request");
        List<AgentEvent> answered =
                run(agent, List.of(resultMsg(success("tc1", "ext", "the real result"))));
        assertEquals(
                replyId,
                single(eventsOf(answered, ExternalExecutionResultEvent.class)).getReplyId());
    }

    @Test
    void recoveryCancellation_isNotReportedAsAnExternalResultAndLeavesNoPendingRequest() {
        // With recovery on, a stale replay + user text cancels the pending call. That
        // cancellation is the framework's own doing: no external result arrived, so none is
        // published, and nothing stays pending afterwards.
        ScriptedModel model =
                new ScriptedModel(
                        List.of(
                                () -> Flux.just(toolUses(call("tc1", "ext"))),
                                () -> Flux.just(text("done"))));
        ReActAgent agent = agentWithRecovery(model, "ext");
        suspendAndAwaitExternalResult(agent, "tc1");

        List<AgentEvent> events =
                run(
                        agent,
                        List.of(
                                resultMsg(success(STALE, "ext", "old")),
                                userText("NEW-INSTRUCTION")));

        assertTrue(
                eventsOf(events, ExternalExecutionResultEvent.class).isEmpty(),
                "a cancellation is not an external result");
        assertNull(pendingExternalReplyId(agent), "no request survives the cancellation");
    }

    // ==================== protocol checks still hold ====================

    @Test
    void duplicateCurrentResultIdsAreStillRejected() {
        ScriptedModel model =
                new ScriptedModel(List.of(() -> Flux.just(toolUses(call("tc1", "ext")))));
        ReActAgent agent = agent(model, "ext");
        agent.call(List.of()).block(Duration.ofSeconds(5));

        IllegalStateException error =
                assertThrows(
                        IllegalStateException.class,
                        () ->
                                agent.call(
                                                List.of(
                                                        resultMsg(success("tc1", "ext", "a")),
                                                        resultMsg(success("tc1", "ext", "b"))),
                                                RuntimeContext.empty())
                                        .block(Duration.ofSeconds(5)));

        assertTrue(
                error.getMessage().contains("Duplicate"),
                "duplicate detection must be unchanged: " + error.getMessage());
    }

    @Test
    void partialCurrentResultsWithUserTextAreStillRejected() {
        ScriptedModel model =
                new ScriptedModel(
                        List.of(() -> Flux.just(toolUses(call("tc1", "ext"), call("tc2", "ext")))));
        ReActAgent agent = agent(model, "ext");
        agent.call(List.of()).block(Duration.ofSeconds(5));

        IllegalStateException error =
                assertThrows(
                        IllegalStateException.class,
                        () ->
                                agent.call(
                                                List.of(
                                                        resultMsg(success("tc1", "ext", "a")),
                                                        userText("and also do this")),
                                                RuntimeContext.empty())
                                        .block(Duration.ofSeconds(5)));

        assertTrue(
                error.getMessage().contains("partial"),
                "partial-results-with-text must still be rejected: " + error.getMessage());
    }

    /** Unrelated role/message-shape sanity: a USER message carrying a tool result still counts. */
    @Test
    void userMessageWithTextAndNoResultsWhilePending_isNotMistakenForAStaleSubmission() {
        ScriptedModel model =
                new ScriptedModel(List.of(() -> Flux.just(toolUses(call("tc1", "ext")))));
        ReActAgent agent = agent(model, "ext");
        agent.call(List.of()).block(Duration.ofSeconds(5));

        // No tool results at all: this is the pre-existing "pending tools + input" behaviour
        // and must be untouched by the stale-result handling.
        IllegalStateException error =
                assertThrows(
                        IllegalStateException.class,
                        () ->
                                agent.call(List.of(userText("hello")), RuntimeContext.empty())
                                        .block(Duration.ofSeconds(5)));
        assertNotNull(error);
    }
}
