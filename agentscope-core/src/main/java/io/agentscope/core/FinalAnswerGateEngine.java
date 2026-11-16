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
package io.agentscope.core;

import io.agentscope.core.event.AgentEvent;
import io.agentscope.core.event.CustomEvent;
import io.agentscope.core.event.TextBlockDeltaEvent;
import io.agentscope.core.event.TextBlockEndEvent;
import io.agentscope.core.event.TextBlockStartEvent;
import io.agentscope.core.event.ThinkingBlockDeltaEvent;
import io.agentscope.core.event.ThinkingBlockEndEvent;
import io.agentscope.core.event.ThinkingBlockStartEvent;
import io.agentscope.core.message.GenerateReason;
import io.agentscope.core.message.Msg;
import io.agentscope.core.message.TextBlock;
import io.agentscope.core.message.ThinkingBlock;
import io.agentscope.core.middleware.FinalAnswerDecision;
import io.agentscope.core.middleware.FinalAnswerInput;
import io.agentscope.core.middleware.FinishReason;
import io.agentscope.core.middleware.MiddlewareBase;
import io.agentscope.core.model.ChatUsage;
import io.agentscope.core.session.SessionRecorder;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Function;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import reactor.core.publisher.Mono;

/**
 * Per-call terminal stage of every agent call: referees the registered gate
 * chain — never itself a gate, hosting the accept landing for all three exits
 * (an empty chain releases immediately). Carries the chain's per-call state
 * and logic — retry count, cumulative usage, round correlation, and the
 * certified-delivery pipeline (chain consultation, retry re-entry, audit,
 * projection).
 *
 * <p>Holds exactly two dependencies, both explicit at construction: the frozen gate
 * chain — its working data, the same immutable {@code middlewaresAt(ON_FINAL_ANSWER)}
 * list, never copied, held nowhere else in the call — and the {@code CallExecution},
 * its per-call services (context, memory, event publication, reasoning re-entry). No
 * agent reference — binding runs one way. This instance's state is confined to one
 * call, so concurrent calls on one agent stay isolated, and the gate middlewares it
 * consults are invoked as agent-agnostic singletons with a fresh {@link
 * FinalAnswerInput} per round.
 *
 * <p>Owns every certified-delivery mechanic: the public-stream guard
 * ({@link #publishEvent}), consultation, retry re-entry,
 * audit, the single accept landing, and the post-verdict projection.
 *
 * <p>Package-private on purpose: this is {@code ReActAgent}'s internal engine, not
 * public API — gates are written against {@link MiddlewareBase#onFinalAnswer}.
 *
 * @author shanhongyu
 */
final class FinalAnswerGateEngine {

    private static final Logger log = LoggerFactory.getLogger(FinalAnswerGateEngine.class);

    /**
     * The frozen gate chain ({@code ON_FINAL_ANSWER} participants in chain order) — the
     * engine's working data. The same immutable {@code middlewaresAt(ON_FINAL_ANSWER)}
     * list the agent built, held by reference (never copied); the engine is its only
     * per-call home, because only the engine consumes it.
     */
    private final List<MiddlewareBase> gates;

    /**
     * The call's {@code CallExecution} — the engine's only reach into this call's
     * services: context, memory, event publication, reasoning re-entry.
     */
    private final ReActAgent.CallExecution execution;

    /**
     * Retries honored in this call (a round with multiple vetoes counts once). The
     * 11th veto trips the fuse and fails the whole call closed — zero delivery,
     * the rejected draft still reaching the session-recording audit — so a looping
     * gate can never spin the call forever nor force uncertified content out. Not
     * configurable on purpose; graceful exhaustion fallback is a gate's own job.
     */
    int retryCount;

    /** Usage accumulated across all model calls of this call, fed to the gates. */
    ChatUsage cumulativeUsage;

    /**
     * Reply id of the most recent model call, correlating the certified projection
     * with its round.
     */
    String lastReplyId;

    /** Hard cap on honored retries per call; the 11th veto fails closed. */
    private static final int RETRY_LIMIT = 10;

    /** Placeholder event emitted at the start of each final-answer consultation. */
    private static final String REVIEWING_EVENT = "final_answer.reviewing";

    /** Observation event name emitted per honored retry. */
    private static final String RETRY_EVENT = "final_answer.retry";

    /** Audit event name observed (session recording only) per rejected candidate. */
    private static final String REJECTED_EVENT = "final_answer.rejected";

    /**
     * Creates the per-call engine.
     *
     * @param gates the frozen {@code ON_FINAL_ANSWER} chain in chain order; held by
     *     reference, never copied
     * @param execution the call's execution providing the engine's per-call services
     */
    FinalAnswerGateEngine(List<MiddlewareBase> gates, ReActAgent.CallExecution execution) {
        this.gates = gates;
        this.execution = execution;
    }

    /**
     * Returns whether any middleware participates at {@code ON_FINAL_ANSWER} for this
     * call — read from the engine's own chain reference, never cached.
     */
    private boolean active() {
        return !gates.isEmpty();
    }

    /**
     * Forwards one event to the public stream, withholding live answer content while
     * the gate is active: {@code TextBlock*} / {@code ThinkingBlock*} events are
     * dropped, not buffered — nothing can leak on cancel or error, and no round
     * (terminal or intermediate) is distinguishable at publish time. Certified content
     * reaches the public stream only through the post-verdict projection ({@link
     * #project}), which publishes via the underlying {@code execution.publishEvent}
     * directly, not through this guard. {@code CustomEvent}s pass through: by
     * convention the {@code CUSTOM} channel carries narrative progress only, never
     * answer content.
     *
     * @param event the event about to reach the public stream
     */
    void publishEvent(AgentEvent event) {
        if (active() && isLiveAnswerContent(event)) {
            return;
        }
        execution.publishEvent(event);
    }

    /**
     * Returns whether the event is live answer content — a {@code TextBlock*} or
     * {@code ThinkingBlock*} lifecycle event — the kinds the public-stream guard
     * withholds while the gate is active.
     *
     * @param event the event to test
     */
    private static boolean isLiveAnswerContent(AgentEvent event) {
        return event instanceof TextBlockStartEvent
                || event instanceof TextBlockDeltaEvent
                || event instanceof TextBlockEndEvent
                || event instanceof ThinkingBlockStartEvent
                || event instanceof ThinkingBlockDeltaEvent
                || event instanceof ThinkingBlockEndEvent;
    }

    /** Records the reply id of the round's model call, from {@code modelCallStart}. */
    void recordReplyId(String replyId) {
        lastReplyId = replyId;
    }

    /**
     * Consults the gate chain about {@code candidate}. Gates run sequentially in chain order ({@code order()}
     * descending), each receiving the current working draft — {@code
     * accept(replacement)} feeds the replacement forward, even on a dead vetoed
     * round. Vetoes never short-circuit: every gate is consulted and all feedback
     * of the round is collected in chain order, so one round with multiple vetoes
     * counts as a single retry. A gate completing empty counts as a plain accept.
     * The fuse is checked per veto: once {@link #RETRY_LIMIT} retries are already
     * honored, the next veto fails the call closed (the rejected draft still
     * reaches the audit trail).
     *
     * @param candidate the final-answer candidate to consult about
     * @param finishReason which exit produced the candidate
     * @return the round's combined decision: retry with the merged feedback, or
     *     accept of the chain's working draft (carried as the replacement when
     *     any gate transformed it)
     */
    private Mono<FinalAnswerDecision> ask(Msg candidate, FinishReason finishReason) {
        if (!active()) {
            return Mono.just(FinalAnswerDecision.accept());
        }
        execution.publishEvent(new CustomEvent(REVIEWING_EVENT));
        // Defer so a gate throwing synchronously surfaces as onError inside the
        // returned chain (fail-closed through the callers' error mapping), not as
        // an assembly-time exception that bypasses it.
        return Mono.defer(
                () -> consult(0, candidate, false, new ArrayList<>(), candidate, finishReason));
    }

    /**
     * Consults gate {@code index} and recurses through the rest of the chain.
     *
     * @param working the working draft the next gate receives
     * @param vetoed whether an earlier gate already vetoed this round
     * @param feedback retry messages collected so far, in chain order
     * @param original the model's original draft; what a retry writes to memory
     */
    private Mono<FinalAnswerDecision> consult(
            int index,
            Msg working,
            boolean vetoed,
            List<Msg> feedback,
            Msg original,
            FinishReason finishReason) {
        if (index >= gates.size()) {
            if (vetoed) {
                return Mono.just(FinalAnswerDecision.retry(List.copyOf(feedback)));
            }
            return Mono.just(
                    working == original
                            ? FinalAnswerDecision.accept()
                            : FinalAnswerDecision.accept(working));
        }
        FinalAnswerInput input =
                new FinalAnswerInput(working, retryCount, cumulativeUsage, finishReason);
        return gates.get(index)
                .onFinalAnswer(execution.rc, input)
                .onErrorResume(
                        error -> {
                            // A failing gate leaves the same audit trace as a veto or a
                            // tripped fuse: the candidate it was certifying.
                            observeRejected(original);
                            return Mono.error(error);
                        })
                .defaultIfEmpty(FinalAnswerDecision.accept())
                .flatMap(
                        decision -> {
                            if (decision.isRetry()) {
                                if (retryCount >= RETRY_LIMIT) {
                                    log.warn(
                                            "Final-answer retry limit ({}) reached; failing"
                                                    + " closed",
                                            RETRY_LIMIT);
                                    observeRejected(original);
                                    return Mono.error(
                                            new IllegalStateException(
                                                    "Final-answer retry limit ("
                                                            + RETRY_LIMIT
                                                            + ") exceeded; failing"
                                                            + " closed"));
                                }
                                feedback.addAll(decision.retryMessages());
                                return consult(
                                        index + 1, working, true, feedback, original, finishReason);
                            }
                            Msg next =
                                    decision.replacement() != null
                                            ? decision.replacement()
                                            : working;
                            return consult(
                                    index + 1, next, vetoed, feedback, original, finishReason);
                        });
    }

    /**
     * Applies the final-answer gate: on accept the chain's output (normalized for
     * the exit) is written to memory, its certified content is projected to the
     * public stream, and the message is returned; on retry the original candidate
     * plus the merged feedback are written and reasoning re-enters with {@code
     * ignoreMaxIters} — the retry fuse is the framework's only iteration bound on
     * that path. Gate errors fail the call (fail closed).
     *
     * @param candidate the final-answer candidate
     * @param iter the reasoning iteration the candidate came from; a retry
     *     re-enters at {@code iter + 1}
     * @param finishReason which exit produced the candidate
     * @return the accepted (possibly replaced) message
     */
    Mono<Msg> gate(Msg candidate, int iter, FinishReason finishReason) {
        return gate(candidate, iter, finishReason, Mono::just, null);
    }

    /**
     * Same as {@link #gate(Msg, int, FinishReason)}, with an async transform applied
     * to the accepted message before it is memorized, projected, and returned — and
     * a {@code replyId} correlating the certified projection (the returnDirect exit
     * passes its own; {@code null} means the current round's model-call reply id).
     * The transform runs after the verdict, before the memory write: the returnDirect
     * exit stamps its close contract onto the message, the summary exit runs its
     * PostSummary hook and re-stamps the exit's {@code GenerateReason}; the
     * transformed message — not the raw accepted one — is what the session keeps,
     * the public stream is projected, and the caller receives.
     *
     * <p>Gate-raised failures (consultation and retry re-entry) at the {@code
     * MAX_ITERATIONS} exit are marked as gate errors so {@code handleSummaryError}
     * rethrows them instead of degrading the call into an error summary — the other
     * exits have no such fallback to escape. Transform failures are never marked:
     * they keep their exit's own error semantics.
     *
     * @param candidate the final-answer candidate
     * @param iter the reasoning iteration the candidate came from
     * @param finishReason which exit produced the candidate
     * @param acceptTransform async transform of the accepted message; emits
     *     exactly one message
     * @param replyId reply id correlating the certified projection, or {@code null}
     * @return the accepted (transformed, possibly replaced) message
     */
    Mono<Msg> gate(
            Msg candidate,
            int iter,
            FinishReason finishReason,
            Function<Msg, Mono<Msg>> acceptTransform,
            String replyId) {
        // Verdict boundary: an interrupt issued while a gate deliberated is honored here,
        // before any verdict is applied or anything ships.
        return markedForExit(
                        ask(candidate, finishReason)
                                .flatMap(
                                        decision ->
                                                execution.checkInterrupted().thenReturn(decision)),
                        finishReason)
                .flatMap(
                        decision -> {
                            if (decision.isRetry()) {
                                return markedForExit(
                                        applyRetry(candidate, decision, iter, finishReason),
                                        finishReason);
                            }
                            Msg accepted =
                                    decision.replacement() != null
                                            ? decision.replacement()
                                            : candidate;
                            return acceptTransform
                                    .apply(accepted)
                                    .flatMap(
                                            // The landing is gate-owned: at the summary exit
                                            // its failures are marked so they never degrade
                                            // into an error summary. Transform failures stay
                                            // unmarked and keep the exit's own semantics.
                                            result ->
                                                    markedForExit(
                                                            Mono.fromCallable(
                                                                    () ->
                                                                            land(
                                                                                    result,
                                                                                    finishReason,
                                                                                    replyId)),
                                                            finishReason));
                        });
    }

    /**
     * Lands an accepted message — the single accept landing of every exit: writes
     * it to memory, projects its certified content, and returns it.
     *
     * @param result the transformed accepted message to land
     * @param finishReason which exit produced the candidate
     * @param replyId reply id correlating the certified projection, or {@code null}
     * @return the landed message
     */
    private Msg land(Msg result, FinishReason finishReason, String replyId) {
        execution.state.contextMutable().add(result);
        if (finishReason == FinishReason.RETURN_DIRECT) {
            // returnDirect closing text never streamed live: always project
            // post-gate, keeping the audit marker.
            project(
                    result,
                    replyId,
                    Map.of(
                            AgentEvent.METADATA_GENERATE_REASON,
                            GenerateReason.TOOL_RETURN_DIRECT.name()));
        } else if (active()) {
            // Real-time text was withheld by the guard: project the certified
            // content. Unactivated agents already streamed it — projecting again
            // would duplicate.
            project(result, lastReplyId, null);
        }
        return result;
    }

    /**
     * Returns whether gate-raised errors at this exit must be marked as {@link
     * GateError}: the {@code MAX_ITERATIONS} exit is the only one whose errors a
     * fallback ({@code handleSummaryError}) would otherwise consume into an error
     * summary; the other exits have no such handler, so their errors keep
     * propagating unwrapped.
     */
    private static boolean marksGateErrors(FinishReason finishReason) {
        return finishReason == FinishReason.MAX_ITERATIONS;
    }

    /** Marks a gate-owned stage's errors when the exit requires it. */
    private static <T> Mono<T> markedForExit(Mono<T> stage, FinishReason finishReason) {
        return marksGateErrors(finishReason)
                ? stage.onErrorMap(FinalAnswerGateEngine::markGateError)
                : stage;
    }

    /**
     * Applies a retry decision: counts the retry (a multi-veto round counts
     * once), emits the observation event, audits the rejected original to the
     * session recording, writes the original — never a gate's rewrite — plus the
     * merged feedback (chain order) to memory, and re-enters reasoning.
     *
     * @param candidate the rejected final-answer candidate
     * @param decision the retry decision carrying the merged feedback
     * @param iter the reasoning iteration to re-enter from
     * @param finishReason exit that produced the rejected candidate
     * @return the final answer produced by the re-entered reasoning
     */
    private Mono<Msg> applyRetry(
            Msg candidate, FinalAnswerDecision decision, int iter, FinishReason finishReason) {
        retryCount++;
        log.info(
                "Final answer rejected by gate middleware (retry {}, finishReason={})",
                retryCount,
                finishReason);
        execution.publishEvent(
                new CustomEvent(
                        RETRY_EVENT,
                        Map.of("retryCount", retryCount, "finishReason", finishReason.name())));
        observeRejected(candidate);
        execution.state.contextMutable().add(candidate);
        execution.state.contextMutable().addAll(decision.retryMessages());
        return execution.reasoning(iter + 1, true);
    }

    /**
     * Audits a rejected candidate to the session recording only — the public
     * stream and telemetry payloads never carry it.
     *
     * @param candidate the rejected final-answer candidate
     */
    private void observeRejected(Msg candidate) {
        SessionRecorder recorder = SessionRecorder.from(execution.rc);
        if (recorder == null) {
            return;
        }
        String text = candidate.getTextContent();
        recorder.observe(
                new CustomEvent(
                        REJECTED_EVENT,
                        Map.of("retryCount", retryCount, "text", text == null ? "" : text)));
    }

    /**
     * Projects the certified message's content — thinking blocks first, then
     * text blocks, each as a complete block lifecycle — into the public stream,
     * so clients assemble the answer exactly as they do a live stream. Published
     * via the underlying {@code execution.publishEvent} directly, bypassing the
     * public guard: the projection is the certified content itself, never a replay
     * of candidate deltas. Non-text blocks emit nothing and keep travelling via the
     * {@code AgentResultEvent}.
     *
     * @param certified the accepted message whose content is projected
     * @param replyId reply id correlating the projection with its round
     * @param deltaMetadata metadata attached to the projected deltas, or {@code null}
     */
    private void project(Msg certified, String replyId, Map<String, Object> deltaMetadata) {
        String effectiveReplyId =
                replyId == null || replyId.isEmpty()
                        ? UUID.randomUUID().toString().replace("-", "")
                        : replyId;
        for (ThinkingBlock block : certified.getContentBlocks(ThinkingBlock.class)) {
            String blockId = UUID.randomUUID().toString().replace("-", "");
            execution.publishEvent(new ThinkingBlockStartEvent(effectiveReplyId, blockId));
            ThinkingBlockDeltaEvent delta =
                    new ThinkingBlockDeltaEvent(effectiveReplyId, blockId, block.getThinking());
            if (deltaMetadata != null) {
                delta.withMetadata(deltaMetadata);
            }
            execution.publishEvent(delta);
            execution.publishEvent(new ThinkingBlockEndEvent(effectiveReplyId, blockId));
        }
        for (TextBlock block : certified.getContentBlocks(TextBlock.class)) {
            String blockId = UUID.randomUUID().toString().replace("-", "");
            execution.publishEvent(new TextBlockStartEvent(effectiveReplyId, blockId));
            TextBlockDeltaEvent delta =
                    new TextBlockDeltaEvent(effectiveReplyId, blockId, block.getText());
            if (deltaMetadata != null) {
                delta.withMetadata(deltaMetadata);
            }
            execution.publishEvent(delta);
            execution.publishEvent(new TextBlockEndEvent(effectiveReplyId, blockId));
        }
    }

    /**
     * Sums one reasoning round's usage into the per-call cumulative gate usage.
     *
     * @param roundUsage the round's usage; {@code null} is ignored
     */
    void accumulateUsage(ChatUsage roundUsage) {
        if (roundUsage != null) {
            cumulativeUsage = plusUsage(cumulativeUsage, roundUsage);
        }
    }

    /**
     * Returns the sum of two usages.
     *
     * @param total the accumulated usage so far; {@code null} yields {@code addend}
     * @param addend the usage to add
     * @return a new usage with all token counts and time summed
     */
    private static ChatUsage plusUsage(ChatUsage total, ChatUsage addend) {
        if (total == null) {
            return addend;
        }
        return ChatUsage.builder()
                .inputTokens(total.getInputTokens() + addend.getInputTokens())
                .outputTokens(total.getOutputTokens() + addend.getOutputTokens())
                .cachedTokens(total.getCachedTokens() + addend.getCachedTokens())
                .cacheCreationTokens(
                        total.getCacheCreationTokens() + addend.getCacheCreationTokens())
                .reasoningTokens(total.getReasoningTokens() + addend.getReasoningTokens())
                .toolUsePromptTokens(
                        total.getToolUsePromptTokens() + addend.getToolUsePromptTokens())
                .time(total.getTime() + addend.getTime())
                .build();
    }

    /**
     * Marks a summary-path error as gate-raised so {@code handleSummaryError}
     * rethrows it instead of degrading the call into an error summary.
     * Idempotent: a gate-raised error (middleware failure, tripped fuse, failed
     * retry re-entry) is never wrapped twice.
     *
     * @param error the error to mark
     * @return the same error when already gate-raised, otherwise the wrapped error
     */
    private static Throwable markGateError(Throwable error) {
        return error instanceof GateError ? error : new GateError(error);
    }

    /**
     * Wraps errors raised by the gate at the summary exit so {@code
     * handleSummaryError} rethrows them instead of degrading the call into an
     * error summary.
     */
    static final class GateError extends RuntimeException {
        /** Wraps the gate-raised cause. */
        GateError(Throwable cause) {
            super(cause);
        }
    }
}
