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

package io.agentscope.extensions.judge.jev.integration;

import io.agentscope.core.agent.Agent;
import io.agentscope.core.agent.RuntimeContext;
import io.agentscope.core.event.AgentEvent;
import io.agentscope.core.event.TextBlockDeltaEvent;
import io.agentscope.core.event.ToolCallStartEvent;
import io.agentscope.core.message.ContentBlock;
import io.agentscope.core.message.Msg;
import io.agentscope.core.message.TextBlock;
import io.agentscope.core.message.ToolResultBlock;
import io.agentscope.core.message.ToolUseBlock;
import io.agentscope.core.message.UserMessage;
import io.agentscope.core.middleware.MiddlewareBase;
import io.agentscope.core.middleware.ModelCallInput;
import io.agentscope.core.util.JsonUtils;
import io.agentscope.extensions.judge.jev.JevExecution;
import io.agentscope.extensions.judge.jev.JevGuardrail;
import io.agentscope.extensions.judge.jev.JevJudge;
import io.agentscope.extensions.judge.jev.evaluation.JevTrace;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.function.Function;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

/** Screens model turns before events are released; refines only tool-free final drafts. */
public final class JevResponseMiddleware implements MiddlewareBase {
    public static final class Rejected extends RuntimeException {
        public Rejected(String reason) {
            super("JEV response withheld: " + reason);
        }
    }

    public record Revision(ModelCallInput input, String draft, JevJudge.Result review) {}

    private final JevGuardrail inputPolicy, outputPolicy;
    private final JevExecution safety, quality;
    private final JevJudge judge;
    private final JevJudge.Definition definition;
    private final Function<Revision, Flux<String>> reviser;
    private final int maxChars, maxEvents, maxRevisions;
    private final boolean blockOnReview;
    private final Duration roundBudget;

    private JevResponseMiddleware(Builder b) {
        inputPolicy = b.input;
        outputPolicy = b.output;
        judge = b.judge;
        definition = b.definition;
        reviser = b.reviser;
        safety = new JevExecution("content", b.safety);
        quality = new JevExecution("quality", b.quality);
        maxChars = b.maxChars;
        maxEvents = b.maxEvents;
        maxRevisions = b.maxRevisions;
        blockOnReview = b.blockOnReview;
        roundBudget = b.roundBudget;
        if (maxChars < 1
                || maxEvents < 1
                || maxRevisions < 0
                || maxRevisions > 10
                || roundBudget.isNegative()
                || roundBudget.isZero())
            throw new IllegalArgumentException("invalid response limits");
        if (safety.mode() != JevExecution.Mode.OFF && inputPolicy == null && outputPolicy == null)
            throw new IllegalArgumentException("safety policy required");
        if (quality.mode() != JevExecution.Mode.OFF && (judge == null || definition == null))
            throw new IllegalArgumentException("quality judge/definition required");
    }

    public static Builder builder() {
        return new Builder();
    }

    @Override
    public int order() {
        return 200;
    }

    @Override
    public Flux<AgentEvent> onModelCall(
            Agent agent,
            RuntimeContext ctx,
            ModelCallInput input,
            Function<ModelCallInput, Flux<AgentEvent>> next) {
        return Flux.defer(
                () -> {
                    if (safety.mode() == JevExecution.Mode.OFF
                            && quality.mode() == JevExecution.Mode.OFF) return next.apply(input);
                    boolean enforce =
                            safety.mode() == JevExecution.Mode.ENFORCE
                                    || quality.mode() == JevExecution.Mode.ENFORCE;
                    if (!enforce) {
                        // Observe without delaying/replacing downstream events. A cap stops
                        // capture, not the turn.
                        Capture capture = new Capture();
                        return screen(ctx, inputPolicy, messages(input.messages()), "input")
                                .thenMany(
                                        Flux.defer(() -> next.apply(input))
                                                .doOnNext(capture::shadowAdd))
                                .concatWith(
                                        Flux.defer(
                                                () ->
                                                        capture.overflow
                                                                ? Flux.empty()
                                                                : review(ctx, input, capture)
                                                                        .thenMany(Flux.empty())));
                    }
                    return screen(ctx, inputPolicy, messages(input.messages()), "input")
                            .then(
                                    Mono.defer(
                                            () -> {
                                                Capture capture = new Capture();
                                                return Flux.defer(() -> next.apply(input))
                                                        .doOnNext(capture::add)
                                                        .then(Mono.just(capture));
                                            }))
                            .flatMap(
                                    c ->
                                            review(ctx, input, c)
                                                    .map(
                                                            text ->
                                                                    Objects.equals(
                                                                                    text,
                                                                                    c.text
                                                                                            .toString())
                                                                            ? c.events
                                                                            : replaceText(
                                                                                    c.events,
                                                                                    text)))
                            .flatMapMany(Flux::fromIterable)
                            .timeout(roundBudget);
                });
    }

    private Mono<Void> screen(
            RuntimeContext ctx, JevGuardrail policy, String text, String direction) {
        if (policy == null || safety.mode() == JevExecution.Mode.OFF || text.isBlank())
            return Mono.empty();
        return safety.execute(
                        ctx,
                        () -> {
                            if (text.length() > maxChars)
                                return Mono.error(new Rejected("INPUT_LIMIT"));
                            return policy.screen(text)
                                    .map(
                                            v ->
                                                    new JevExecution.Decision<>(
                                                            JevExecution.Status.DECIDED,
                                                            v,
                                                            direction.toUpperCase()
                                                                    + "_"
                                                                    + v.outcome(),
                                                            Map.of(
                                                                    "direction",
                                                                    direction,
                                                                    "outcome",
                                                                    v.outcome().name())));
                        })
                .flatMap(
                        d ->
                                safety.mode() == JevExecution.Mode.ENFORCE
                                                && (d.value() == null
                                                        || d.value().blocked(blockOnReview))
                                        ? Mono.error(new Rejected(d.reason()))
                                        : Mono.empty());
    }

    private Mono<String> review(RuntimeContext ctx, ModelCallInput input, Capture c) {
        String draft = c.text.toString();
        return screen(ctx, outputPolicy, draft, "output")
                .then(
                        Mono.defer(
                                () -> {
                                    if (c.tools
                                            || draft.isBlank()
                                            || quality.mode() == JevExecution.Mode.OFF)
                                        return Mono.just(draft);
                                    return quality.execute(ctx, () -> assess(ctx, input, draft, 0))
                                            .flatMap(
                                                    d -> {
                                                        if (quality.mode()
                                                                != JevExecution.Mode.ENFORCE)
                                                            return Mono.just(draft);
                                                        if (d.status()
                                                                        != JevExecution.Status
                                                                                .DECIDED
                                                                || d.value() == null)
                                                            return Mono.error(
                                                                    new Rejected(d.reason()));
                                                        return Mono.just(d.value());
                                                    });
                                }));
    }

    private Mono<JevExecution.Decision<String>> assess(
            RuntimeContext ctx, ModelCallInput input, String draft, int attempt) {
        var state = qualityState(input, draft);
        if (!((List<?>) state.get("trace_issues")).isEmpty())
            return Mono.just(JevExecution.Decision.uncertain("INCOMPLETE_JUDGING_EVIDENCE"));
        if (JsonUtils.getJsonCodec().toJson(state).length() > maxChars)
            return Mono.just(JevExecution.Decision.uncertain("JUDGE_STATE_LIMIT"));
        return judge.judge(state, definition)
                .flatMap(
                        v -> {
                            if (v.status() == JevJudge.Status.PASS)
                                return Mono.just(
                                        new JevExecution.Decision<>(
                                                JevExecution.Status.DECIDED,
                                                draft,
                                                "QUALITY_PASS",
                                                Map.of("revisions", String.valueOf(attempt))));
                            if (v.status() != JevJudge.Status.FAIL
                                    || quality.mode() != JevExecution.Mode.ENFORCE
                                    || attempt >= maxRevisions)
                                return Mono.just(
                                        new JevExecution.Decision<>(
                                                v.status() == JevJudge.Status.ERROR
                                                        ? JevExecution.Status.ERROR
                                                        : JevExecution.Status.INCONCLUSIVE,
                                                null,
                                                "QUALITY_" + v.status(),
                                                Map.of("revisions", String.valueOf(attempt))));
                            return collectText(
                                            Flux.defer(
                                                    () ->
                                                            reviser.apply(
                                                                    new Revision(input, draft, v))))
                                    .flatMap(
                                            revised ->
                                                    revised.isBlank() || revised.equals(draft)
                                                            ? Mono.just(
                                                                    JevExecution.Decision
                                                                            .<String>uncertain(
                                                                                    "UNCHANGED_DRAFT"))
                                                            : screen(
                                                                            ctx,
                                                                            outputPolicy,
                                                                            revised,
                                                                            "revision")
                                                                    .then(
                                                                            Mono.defer(
                                                                                    () ->
                                                                                            assess(
                                                                                                    ctx,
                                                                                                    input,
                                                                                                    revised,
                                                                                                    attempt
                                                                                                            + 1))));
                        });
    }

    /** Named question, answer and paired tool evidence; legacy prompt/answer fields stay compatible. */
    public static Map<String, Object> qualityState(ModelCallInput input, String draft) {
        var trace = JevTrace.fromMessages("answer-review", input.messages(), List.of());
        var state = new LinkedHashMap<String, Object>();
        String conversation = messages(input.messages());
        state.put("prompt", conversation);
        state.put("answer", draft);
        state.put(
                "user_question",
                input.messages().stream()
                        .filter(m -> !m.getTextContent().isBlank())
                        .map(m -> m.getRole() + ": " + m.getTextContent())
                        .collect(java.util.stream.Collectors.joining("\n")));
        state.put("assistant_answer", draft);
        state.put("tool_calls", trace.list("tool_calls"));
        state.put(
                "supporting_context",
                trace.list("tool_results").stream()
                        .filter(
                                value ->
                                        value instanceof Map<?, ?> m
                                                && "SUCCESS".equals(m.get("result_state")))
                        .map(value -> ((Map<?, ?>) value).get("result"))
                        .toList());
        state.put("trace_issues", trace.issues());
        return new JevTrace("answer-review", state).fields();
    }

    private Mono<String> collectText(Flux<String> source) {
        return Mono.defer(
                () -> {
                    StringBuilder text = new StringBuilder();
                    int[] events = {0};
                    return source.doOnNext(
                                    s -> {
                                        if (++events[0] > maxEvents
                                                || s.length() > maxChars - text.length())
                                            throw new Rejected("BUFFER_LIMIT");
                                        text.append(s);
                                    })
                            .then(Mono.fromSupplier(text::toString));
                });
    }

    /** Default revision uses the chosen model directly with no tools; never re-enters the Agent. */
    public static Flux<String> reviseWithModel(Revision r) {
        var messages = new ArrayList<Msg>(r.input().messages());
        messages.add(
                new UserMessage(
                        "Revise only the following draft. Do not request or execute tools. Retain"
                                + " claims supported by the conversation. Failed criteria: "
                                + r.review().findings()
                                + "\nDraft:\n"
                                + r.draft()));
        return Flux.defer(() -> r.input().model().stream(messages, List.of(), r.input().options()))
                .concatMap(response -> Flux.fromIterable(response.getContent()))
                .handle(
                        (block, sink) -> {
                            if (block instanceof ToolUseBlock)
                                sink.error(new Rejected("REVISION_TOOL_CALL"));
                            else if (block instanceof TextBlock t) sink.next(t.getText());
                        });
    }

    private static String messages(List<Msg> msgs) {
        StringBuilder out = new StringBuilder();
        for (Msg m : msgs) {
            out.append(m.getRole()).append(": ").append(m.getTextContent()).append('\n');
            for (ContentBlock b : m.getContent())
                if (b instanceof ToolResultBlock result)
                    for (ContentBlock value : result.getOutput())
                        if (value instanceof TextBlock t)
                            out.append("tool evidence: ").append(t.getText()).append('\n');
        }
        return out.toString();
    }

    private static List<AgentEvent> replaceText(List<AgentEvent> events, String text) {
        List<AgentEvent> result = new ArrayList<>();
        boolean emitted = false;
        for (AgentEvent e : events) {
            if (e instanceof TextBlockDeltaEvent d) {
                if (!emitted) {
                    result.add(new TextBlockDeltaEvent(d.getReplyId(), d.getBlockId(), text));
                    emitted = true;
                }
            } else result.add(e);
        }
        if (!emitted) throw new Rejected("NO_TEXT_BLOCK");
        return result;
    }

    private final class Capture {
        final List<AgentEvent> events = new ArrayList<>();
        final StringBuilder text = new StringBuilder();
        boolean tools, overflow;

        void add(AgentEvent e) {
            if (events.size() >= maxEvents) throw new Rejected("EVENT_LIMIT");
            if (e instanceof TextBlockDeltaEvent d && d.getDelta() != null) {
                if (d.getDelta().length() > maxChars - text.length())
                    throw new Rejected("TEXT_LIMIT");
                text.append(d.getDelta());
            }
            if (e instanceof ToolCallStartEvent) tools = true;
            events.add(e);
        }

        void shadowAdd(AgentEvent e) {
            if (overflow) return;
            try {
                add(e);
            } catch (Rejected ex) {
                overflow = true;
                events.clear();
                text.setLength(0);
            }
        }
    }

    public static final class Builder {
        private JevGuardrail input, output;
        private JevJudge judge;
        private JevJudge.Definition definition;
        private JevExecution.Options safety = JevExecution.Options.disabled(),
                quality = JevExecution.Options.disabled();
        private Function<Revision, Flux<String>> reviser = JevResponseMiddleware::reviseWithModel;
        private int maxChars = 64000, maxEvents = 8192, maxRevisions = 1;
        private boolean blockOnReview;
        private Duration roundBudget = Duration.ofSeconds(120);

        public Builder guardrails(
                JevGuardrail input, JevGuardrail output, JevExecution.Options execution) {
            this.input = input;
            this.output = output;
            safety = Objects.requireNonNull(execution);
            return this;
        }

        public Builder quality(
                JevJudge judge, JevJudge.Definition definition, JevExecution.Options execution) {
            this.judge = judge;
            this.definition = definition;
            quality = Objects.requireNonNull(execution);
            return this;
        }

        public Builder reviser(Function<Revision, Flux<String>> reviser) {
            this.reviser = Objects.requireNonNull(reviser);
            return this;
        }

        public Builder maxChars(int value) {
            maxChars = value;
            return this;
        }

        public Builder maxEvents(int value) {
            maxEvents = value;
            return this;
        }

        public Builder maxRevisions(int value) {
            maxRevisions = value;
            return this;
        }

        public Builder blockOnReview(boolean value) {
            blockOnReview = value;
            return this;
        }

        public Builder roundBudget(Duration value) {
            roundBudget = Objects.requireNonNull(value);
            return this;
        }

        public JevResponseMiddleware build() {
            return new JevResponseMiddleware(this);
        }
    }
}
