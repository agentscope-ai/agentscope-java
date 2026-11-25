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
import static org.junit.jupiter.api.Assertions.assertThrows;

import io.agentscope.core.ReActAgent;
import io.agentscope.core.agent.test.MockModel;
import io.agentscope.core.event.AgentEvent;
import io.agentscope.core.event.AgentResultEvent;
import io.agentscope.core.event.TextBlockDeltaEvent;
import io.agentscope.core.journal.InMemoryThreadJournalStore;
import io.agentscope.core.journal.ModelInvocation;
import io.agentscope.core.message.AssistantMessage;
import io.agentscope.core.message.GenerateReason;
import io.agentscope.core.message.Msg;
import io.agentscope.core.message.TextBlock;
import io.agentscope.core.message.UserMessage;
import io.agentscope.core.middleware.FinalAnswerDecision;
import io.agentscope.core.middleware.FinalAnswerInput;
import io.agentscope.core.middleware.MiddlewareBase;
import io.agentscope.core.model.ChatResponse;
import io.agentscope.core.model.GenerateOptions;
import io.agentscope.core.model.ToolSchema;
import java.time.Duration;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Function;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

/**
 * Behavior tests for the final-answer gate over a handed-off model call — a reasoning call
 * executed away from the agent and resumed later, possibly on another agent instance. The
 * gate's verdict decides what the invocation journal treats as the call's settled outcome:
 * the certified message, never the pre-verdict candidate, is what a resumed call is
 * checkpointed with and preserves for re-entry, and a veto settles the handoff before the
 * retry round needs the model again.
 *
 * @author shanhongyu
 */
@DisplayName("ReActAgent final-answer gate over a handed-off model call")
class ReActAgentFinalAnswerGateHandoffTest {

    private static final Duration TIMEOUT = Duration.ofSeconds(5);

    /** Agent and session share a name: the journal thread is keyed by both. */
    private static final String THREAD = "gate-handoff";

    /** The Turn the handed-off call belongs to; a resumed call must stay on it. */
    private static final String TURN = "turn-a";

    /** A later Turn, reachable only once the handed-off call has settled. */
    private static final String NEXT_TURN = "turn-b";

    /** Answer the mock model gives whenever it is called directly. */
    private static final String DIRECT_ANSWER = "direct";

    /** Valid payload for {@link Weather}, the typed result of the native structured-output exit. */
    private static final String WEATHER_JSON = "{\"location\":\"Paris\",\"temperature\":\"21C\"}";

    /**
     * Host of one attempt over handed-off model calls. A resuming host continues the call it
     * was handed with fixed responses; every other model call is handed off again or answered
     * directly. Routing keys on the call id, not on a consultation count: core consults the
     * host twice for a directly answered call, because the recorded model is wrapped at two
     * layers.
     */
    private static final class ScriptedHost implements ModelInvocation {

        /** Call id this host handed off; {@code null} until a handoff is dispatched. */
        private final AtomicReference<String> handedOff = new AtomicReference<>();

        /** Call to resume, or {@code null} when the attempt starts with a handoff. */
        private final String resumeCallId;

        /** Responses for the resumed call; {@code null} when nothing is resumed. */
        private final ChatResponse resumeResponse;

        /** Whether calls other than the resumed one are handed off rather than answered directly. */
        private final boolean shouldHandOffOthers;

        /** Whether the resumed call has been continued already. */
        private final AtomicBoolean resumed = new AtomicBoolean();

        private ScriptedHost(
                String resumeCallId, ChatResponse resumeResponse, boolean shouldHandOffOthers) {
            this.resumeCallId = resumeCallId;
            this.resumeResponse = resumeResponse;
            this.shouldHandOffOthers = shouldHandOffOthers;
        }

        /** Hands every model call off, suspending the run until a reply arrives. */
        static ScriptedHost handOff() {
            return new ScriptedHost(null, null, true);
        }

        /** Resumes {@code callId} with {@code response}, then hands later calls off again. */
        static ScriptedHost resumeAndHandOff(String callId, ChatResponse response) {
            return new ScriptedHost(callId, response, true);
        }

        /** Resumes {@code callId} with {@code response}, then lets the model answer directly. */
        static ScriptedHost resumeThenDirect(String callId, ChatResponse response) {
            return new ScriptedHost(callId, response, false);
        }

        /** Lets the model answer directly. */
        static ScriptedHost direct() {
            return new ScriptedHost(null, null, false);
        }

        /** The call id this host handed off. */
        String handedOff() {
            return handedOff.get();
        }

        @Override
        public Flux<ChatResponse> stream(
                String model,
                String purpose,
                List<Msg> messages,
                List<ToolSchema> tools,
                GenerateOptions options,
                Function<String, Flux<ChatResponse>> next) {
            boolean isResuming = resumeCallId != null && resumed.compareAndSet(false, true);
            // The resumed call keeps the identity it was handed off under.
            return next.apply(isResuming ? resumeCallId : UUID.randomUUID().toString());
        }

        @Override
        public Route route(Call call) {
            if (call.callId().equals(resumeCallId)) {
                return new Resume(Flux.just(resumeResponse));
            }
            if (!shouldHandOffOthers) {
                return new Direct();
            }
            // Cold: core subscribes only once the handoff is admitted and checkpointed.
            return new Suspend(Mono.fromRunnable(() -> handedOff.set(call.callId())));
        }
    }

    /** Gate recording every consultation; decisions come from the policy. */
    private static final class RecordingGate implements MiddlewareBase {

        private final List<FinalAnswerInput> inputs = new CopyOnWriteArrayList<>();

        private final Function<FinalAnswerInput, FinalAnswerDecision> policy;

        RecordingGate(Function<FinalAnswerInput, FinalAnswerDecision> policy) {
            this.policy = policy;
        }

        @Override
        public Mono<FinalAnswerDecision> onFinalAnswer(RuntimeContext ctx, FinalAnswerInput input) {
            inputs.add(input);
            return Mono.just(policy.apply(input));
        }

        /** Text of every consulted candidate, in consultation order. */
        List<String> candidates() {
            return inputs.stream().map(input -> input.candidate().getTextContent()).toList();
        }

        /** Retry counters the gate was consulted with, in consultation order. */
        List<Integer> retryCounts() {
            return inputs.stream().map(FinalAnswerInput::retryCount).toList();
        }
    }

    /** Completion hook failing the first run it sees: the run is lost after its answer landed. */
    private static final class FailingFirstFinish implements MiddlewareBase {

        private final AtomicInteger runs = new AtomicInteger();

        @Override
        public Mono<Void> onAgentFinish(Agent agent, RuntimeContext ctx, Msg result) {
            return runs.getAndIncrement() == 0
                    ? Mono.error(new IllegalStateException("runtime lost after the answer landed"))
                    : Mono.empty();
        }
    }

    /** Mock model advertising native structured output, for the typed-call exit. */
    private static final class NativeMockModel extends MockModel {

        NativeMockModel(String response) {
            super(response);
        }

        @Override
        public boolean supportsNativeStructuredOutput() {
            return true;
        }
    }

    /** Typed result of the native structured-output variant. */
    static class Weather {
        public String location;
        public String temperature;
    }

    @Test
    @DisplayName("a handed-off answer is gated on resume: the replacement lands and ships")
    void handedOffAnswerIsCertifiedOnResume() {
        InMemoryThreadJournalStore store = new InMemoryThreadJournalStore();
        MockModel model = new MockModel(DIRECT_ANSWER);
        RecordingGate gate = new RecordingGate(stamping());
        ScriptedHost first = handOff(model, store, false, gate);

        List<AgentEvent> events;
        ScriptedHost second = ScriptedHost.resumeThenDirect(first.handedOff(), text("raw draft"));
        try (ReActAgent agent = agent(model, store, gate)) {
            events = agent.streamEvents(List.of(), ctx(TURN, second)).collectList().block(TIMEOUT);
        }

        assertEquals(List.of("raw draft (certified)"), textDeltas(events));
        assertEquals("raw draft (certified)", result(events).getTextContent());
        assertEquals(List.of("raw draft"), gate.candidates());
        assertEquals(0, model.getCallCount(), "the reply came from the worker, not the model");
        assertNextTurnSettled(model, store, gate, false, "direct (certified)");
    }

    @Test
    @DisplayName("a run lost after landing preserves the certified answer, not the draft")
    void lostRunPreservesTheCertifiedAnswerForReentry() {
        InMemoryThreadJournalStore store = new InMemoryThreadJournalStore();
        MockModel model = new MockModel(DIRECT_ANSWER);
        RecordingGate gate = new RecordingGate(stamping());
        ScriptedHost first = handOff(model, store, false, gate);

        ScriptedHost second = ScriptedHost.resumeThenDirect(first.handedOff(), text("raw draft"));
        try (ReActAgent agent = agent(model, store, gate, new FailingFirstFinish())) {
            assertThrows(
                    IllegalStateException.class,
                    () -> call(agent, List.of(), ctx(TURN, second), false));
        }

        Msg reentered;
        try (ReActAgent agent = agent(model, store, gate)) {
            reentered = call(agent, List.of(), ctx(TURN, ScriptedHost.direct()), false);
        }

        assertEquals("raw draft (certified)", reentered.getTextContent());
        assertEquals(
                0,
                model.getCallCount(),
                "re-entry returns the preserved certified answer without a model call");
        assertEquals(
                List.of("raw draft"),
                gate.candidates(),
                "the certified answer is not put before the gate again");
    }

    @ParameterizedTest(name = "native structured output = {0}")
    @ValueSource(booleans = {false, true})
    @DisplayName("a vetoed handed-off answer releases the call for the retry round")
    void vetoedHandedOffAnswerReleasesTheCallForTheRetryRound(boolean isNativeStructured) {
        InMemoryThreadJournalStore store = new InMemoryThreadJournalStore();
        MockModel model =
                isNativeStructured ? new NativeMockModel(WEATHER_JSON) : new MockModel("fixed");
        RecordingGate gate = new RecordingGate(vetting());
        ScriptedHost first = handOff(model, store, isNativeStructured, gate);

        Msg certified;
        ScriptedHost second = ScriptedHost.resumeThenDirect(first.handedOff(), text("bad draft"));
        try (ReActAgent agent = agent(model, store, gate)) {
            certified = call(agent, List.of(), ctx(TURN, second), isNativeStructured);
        }

        assertEquals(
                isNativeStructured ? WEATHER_JSON : "fixed",
                certified.getTextContent(),
                "the retry round's answer is the one delivered");
        assertEquals(1, model.getCallCount(), "only the retry round reaches the model");
        assertEquals(
                List.of("bad draft", isNativeStructured ? WEATHER_JSON : "fixed"),
                gate.candidates());
        assertEquals(List.of(0, 1), gate.retryCounts());
        assertNextTurnSettled(
                model,
                store,
                gate,
                isNativeStructured,
                isNativeStructured ? WEATHER_JSON : "fixed");
    }

    @Test
    @DisplayName("a rejected draft is never the preserved answer of a resumed call")
    void rejectedDraftIsNeverThePreservedAnswer() {
        InMemoryThreadJournalStore store = new InMemoryThreadJournalStore();
        MockModel model = new MockModel(DIRECT_ANSWER);
        RecordingGate gate = new RecordingGate(vetting());
        ScriptedHost first = handOff(model, store, false, gate);

        // The veto's retry round is handed off in turn, so the run suspends again with
        // the rejected draft already in memory.
        ScriptedHost second = ScriptedHost.resumeAndHandOff(first.handedOff(), text("bad draft"));
        try (ReActAgent agent = agent(model, store, gate)) {
            Msg paused = call(agent, List.of(), ctx(TURN, second), false);
            assertEquals(GenerateReason.HOST_PAUSED, paused.getGenerateReason());
        }

        Msg certified;
        ScriptedHost third = ScriptedHost.resumeThenDirect(second.handedOff(), text("fixed"));
        try (ReActAgent agent = agent(model, store, gate)) {
            certified = call(agent, List.of(), ctx(TURN, third), false);
        }

        assertEquals("fixed", certified.getTextContent());
        assertEquals(List.of("bad draft", "fixed"), gate.candidates());
        assertEquals(0, model.getCallCount());
    }

    // ==== Gate policies ====

    /** Vetoes a draft carrying {@code "bad"} once per call, then accepts whatever comes back. */
    private static Function<FinalAnswerInput, FinalAnswerDecision> vetting() {
        return input ->
                input.candidate().getTextContent().contains("bad") && input.retryCount() == 0
                        ? FinalAnswerDecision.retry(reminder("Please fix the answer."))
                        : FinalAnswerDecision.accept();
    }

    /** Accepts every answer with its text stamped as certified. */
    private static Function<FinalAnswerInput, FinalAnswerDecision> stamping() {
        return input ->
                FinalAnswerDecision.accept(
                        assistantText(input.candidate().getTextContent() + " (certified)"));
    }

    // ==== Helpers ====

    /**
     * Hands the first reasoning call of {@link #TURN} off on a fresh agent.
     *
     * @param model the agent's model
     * @param store the journal shared by every attempt
     * @param isStructured whether the call goes through native structured output
     * @param middlewares the agent's middlewares
     * @return the host that dispatched the call
     */
    private static ScriptedHost handOff(
            MockModel model,
            InMemoryThreadJournalStore store,
            boolean isStructured,
            MiddlewareBase... middlewares) {
        ScriptedHost host = ScriptedHost.handOff();
        try (ReActAgent agent = agent(model, store, middlewares)) {
            Msg paused = call(agent, List.of(new UserMessage("hi")), ctx(TURN, host), isStructured);
            assertEquals(GenerateReason.HOST_PAUSED, paused.getGenerateReason());
        }
        return host;
    }

    /**
     * Runs a later Turn with new input and no host, which the thread accepts only once the
     * handed-off call has settled.
     *
     * @param model the agent's model
     * @param store the journal shared by every attempt
     * @param gate the gate every attempt shares
     * @param isStructured whether the call goes through native structured output
     * @param expectedText the text the later Turn must deliver
     */
    private static void assertNextTurnSettled(
            MockModel model,
            InMemoryThreadJournalStore store,
            RecordingGate gate,
            boolean isStructured,
            String expectedText) {
        try (ReActAgent agent = agent(model, store, gate)) {
            Msg later =
                    call(
                            agent,
                            List.of(new UserMessage("next")),
                            ctx(NEXT_TURN, null),
                            isStructured);
            assertEquals(expectedText, later.getTextContent());
        }
    }

    /** Runs one attempt; a typed call routes it through native structured output. */
    private static Msg call(
            ReActAgent agent, List<Msg> input, RuntimeContext ctx, boolean isStructured) {
        return isStructured
                ? agent.call(input, Weather.class, ctx).block(TIMEOUT)
                : agent.call(input, ctx).block(TIMEOUT);
    }

    private static RuntimeContext ctx(String turn, ScriptedHost host) {
        var builder = RuntimeContext.builder().sessionId(THREAD).turnId(turn);
        if (host != null) builder.put(ModelInvocation.class, host);
        return builder.build();
    }

    private static ReActAgent agent(
            MockModel model, InMemoryThreadJournalStore store, MiddlewareBase... middlewares) {
        ReActAgent.Builder builder =
                ReActAgent.builder().name(THREAD).model(model).threadJournalStore(store);
        for (MiddlewareBase middleware : middlewares) {
            builder.middleware(middleware);
        }
        return builder.build();
    }

    /** The message the event stream delivered as its result. */
    private static Msg result(List<AgentEvent> events) {
        return events.stream()
                .filter(AgentResultEvent.class::isInstance)
                .map(AgentResultEvent.class::cast)
                .map(AgentResultEvent::getResult)
                .findFirst()
                .orElseThrow(() -> new AssertionError("No result event was delivered"));
    }

    private static List<String> textDeltas(List<AgentEvent> events) {
        return events.stream()
                .filter(TextBlockDeltaEvent.class::isInstance)
                .map(TextBlockDeltaEvent.class::cast)
                .map(TextBlockDeltaEvent::getDelta)
                .toList();
    }

    private static ChatResponse text(String text) {
        return ChatResponse.builder()
                .content(List.of(TextBlock.builder().text(text).build()))
                .build();
    }

    private static Msg assistantText(String text) {
        return AssistantMessage.builder()
                .name("asst")
                .content(List.of(TextBlock.builder().text(text).build()))
                .build();
    }

    private static Msg reminder(String text) {
        return UserMessage.builder()
                .name("user")
                .content(List.of(TextBlock.builder().text(text).build()))
                .build();
    }
}
