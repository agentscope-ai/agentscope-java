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
package io.agentscope.core.middleware;

import io.agentscope.core.agent.Agent;
import io.agentscope.core.agent.RuntimeContext;
import io.agentscope.core.event.AgentEvent;
import io.agentscope.core.message.Msg;
import io.agentscope.core.state.AgentState;
import java.util.List;
import java.util.Set;
import java.util.function.Function;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

/**
 * Middleware provides interception mechanisms at 7 key execution points
 * in the Agent lifecycle.
 *
 * <p><b>Onion Pattern</b> (4 hooks — wrap execution with before/after logic):
 * <ul>
 *   <li>{@link #onAgent} — intercepts the entire agent invocation</li>
 *   <li>{@link #onReasoning} — intercepts the reasoning/model-call phase</li>
 *   <li>{@link #onActing} — intercepts individual tool-call execution</li>
 *   <li>{@link #onModelCall} — intercepts the raw model API call</li>
 * </ul>
 *
 * <p><b>Transformer/Pipeline Pattern</b> (1 hook — sequential transform):
 * <ul>
 *   <li>{@link #onSystemPrompt} — transforms the system prompt string</li>
 * </ul>
 *
 * <p><b>Notification Pattern</b> (one-way synchronous notification, no {@code next}
 * delegation):
 * <ul>
 *   <li>{@link #onAgentStateReady} — notified once this call's AgentState is ready</li>
 * </ul>
 *
 * <p><b>Gate Pattern</b> (1 hook — verdict on the final answer):
 * <ul>
 *   <li>{@link #onFinalAnswer} — decides what becomes this call's final answer</li>
 * </ul>
 *
 * <p>Each onion/transformer hook has a default implementation that delegates
 * directly to {@code next}, so subclasses only need to override the hooks they
 * care about; {@link #onAgentStateReady} defaults to a no-op; {@link
 * #onFinalAnswer} defaults to an empty completion that accepts the candidate,
 * reachable only through an explicit {@code ON_FINAL_ANSWER} declaration. Every
 * implementation should additionally declare the points it participates in via
 * {@link #activePoints()}; an undeclared middleware (returning {@code null})
 * has its participation set inferred from the hooks its class actually
 * implements.
 *
 * <p><b>Example:</b>
 * <pre>{@code
 * MiddlewareBase logging = new MiddlewareBase() {
 *     @Override
 *     public Set<ExtensionPoint> activePoints() {
 *         return EnumSet.of(ExtensionPoint.ON_REASONING);
 *     }
 *
 *     @Override
 *     public Flux<AgentEvent> onReasoning(
 *             Agent agent, RuntimeContext ctx, ReasoningInput input,
 *             Function<ReasoningInput, Flux<AgentEvent>> next) {
 *         System.out.println("Before reasoning, session=" + ctx.getSessionId());
 *         return next.apply(input)
 *             .doOnComplete(() -> System.out.println("After reasoning"));
 *     }
 * };
 * }</pre>
 */
public interface MiddlewareBase {

    /**
     * Middleware extension points. Adding a point adds one constant here; the
     * grouping mechanism stays unchanged. A middleware participates at a new
     * point only when it declares the point or implements the new hook.
     *
     * <p>{@link #ON_FINAL_ANSWER} is the first side-effect point — activating
     * it changes the whole agent's delivery behavior (live answer content is
     * withheld until the gate chain certifies it). This is safe exactly
     * because participation is never vacuous: the auto-detected path lights
     * up only for classes that factually implement {@link #onFinalAnswer}
     * (writing the hook is writing gate logic), and the only other path is an
     * explicit declaration — both are statements of intent. A future
     * side-effect point must preserve this property: participation must not
     * be reachable without either the fact or the declaration.
     *
     * <p>Each constant names the hook method it pairs with; auto-detection of
     * undeclared ({@code null}) middlewares resolves participation from these
     * pairings.
     */
    enum ExtensionPoint {
        ON_AGENT("onAgent"),
        ON_REASONING("onReasoning"),
        ON_ACTING("onActing"),
        ON_MODEL_CALL("onModelCall"),
        ON_SYSTEM_PROMPT("onSystemPrompt"),
        ON_AGENT_STATE_READY("onAgentStateReady"),
        ON_FINAL_ANSWER("onFinalAnswer");

        private final String hookMethod;

        ExtensionPoint(String hookMethod) {
            this.hookMethod = hookMethod;
        }

        /** Name of the hook method this point pairs with (auto-detection support). */
        String hookMethod() {
            return hookMethod;
        }
    }

    /**
     * Returns the extension points where this middleware is active — the sole
     * source of its participation set. For an explicit declaration this is a
     * participation switch, not a statement about which methods are
     * overridden: a point declared active but not overridden simply runs its
     * default (pass-through / no-op), and a point omitted is never invoked —
     * even if overridden.
     *
     * <p><b>Every implementation should override this method</b> and declare
     * exactly the points it hooks — e.g. {@code EnumSet.of(ExtensionPoint.ON_REASONING)}
     * — or {@code EnumSet.allOf(ExtensionPoint.class)} when participating at
     * every point is intended. The declaration is authoritative and is read
     * once at agent construction. An empty set
     * ({@code EnumSet.noneOf(ExtensionPoint.class)}) keeps this middleware
     * registered while participating at no point — useful as a runtime
     * switch.
     *
     * <p>Returning {@code null} — the default — delegates the participation
     * set to the framework: it infers, per class and computed once, exactly
     * the points whose hook methods this middleware implements
     * (implementations inherited from superclasses count). The {@code null}
     * default exists for API compatibility with middlewares written before
     * this contract and may change in a future release — do not rely on it.
     * Inference is a fallback, not a statement of intent: a hook overridden
     * only by a pass-through base class still lights up its point, and an
     * intentional opt-out is expressible only through an explicit
     * declaration.
     *
     * <p>Activation is decided once at agent construction; mutating the
     * returned set (or changing later results) has no effect on an
     * already-built agent.
     *
     * @return the set of extension points this middleware participates in;
     *         {@code null} lets the framework infer the set from the hooks
     *         this class implements
     */
    default Set<ExtensionPoint> activePoints() {
        return null;
    }

    /**
     * Returns this middleware's execution order.
     *
     * <p>A larger number means a higher priority and places the middleware closer to the outside
     * of the onion chain: for example, {@code 2} runs before {@code 1}, and {@code 0} runs after
     * {@code 1}. Middlewares with the same order retain their builder registration order. The
     * default value is {@code 1}.
     *
     * @return the execution order; higher values execute first before delegating to {@code next}
     */
    default int order() {
        return 1;
    }

    /**
     * Intercept the entire agent invocation.
     *
     * @param agent the agent instance
     * @param ctx   per-call runtime context (session, user, attributes)
     * @param input agent input (messages)
     * @param next  calls the next middleware or the core agent logic
     * @return event stream from the agent invocation
     */
    default Flux<AgentEvent> onAgent(
            Agent agent,
            RuntimeContext ctx,
            AgentInput input,
            Function<AgentInput, Flux<AgentEvent>> next) {
        return next.apply(input);
    }

    /**
     * Intercept the reasoning phase (LLM call + streaming output parsing).
     *
     * @param agent the agent instance
     * @param ctx   per-call runtime context (session, user, attributes)
     * @param input reasoning input (messages, tools, options)
     * @param next  calls the next middleware or the core reasoning logic
     * @return event stream from reasoning
     */
    default Flux<AgentEvent> onReasoning(
            Agent agent,
            RuntimeContext ctx,
            ReasoningInput input,
            Function<ReasoningInput, Flux<AgentEvent>> next) {
        return next.apply(input);
    }

    /**
     * Intercept the tool-call execution phase.
     *
     * @param agent the agent instance
     * @param ctx   per-call runtime context (session, user, attributes)
     * @param input acting input (the tool calls)
     * @param next  calls the next middleware or the core acting logic
     * @return event stream from acting
     */
    default Flux<AgentEvent> onActing(
            Agent agent,
            RuntimeContext ctx,
            ActingInput input,
            Function<ActingInput, Flux<AgentEvent>> next) {
        return next.apply(input);
    }

    /**
     * Intercept the raw model API call.
     *
     * <p>Transformations to {@link io.agentscope.core.event.TextBlockDeltaEvent} instances in the
     * returned stream are reflected in the final response message. This allows middleware to
     * normalize model text before consumers, including native structured-output parsing, use it.
     *
     * @param agent the agent instance
     * @param ctx   per-call runtime context (session, user, attributes)
     * @param input model-call input (messages, tools, options, model)
     * @param next  calls the next middleware or the actual model invocation
     * @return event stream from the model call
     */
    default Flux<AgentEvent> onModelCall(
            Agent agent,
            RuntimeContext ctx,
            ModelCallInput input,
            Function<ModelCallInput, Flux<AgentEvent>> next) {
        return next.apply(input);
    }

    /**
     * Transform the system prompt string (pipeline pattern).
     *
     * <p>Multiple middlewares are applied sequentially; each receives the
     * output of the previous one.
     *
     * @param agent         the agent instance
     * @param ctx           per-call runtime context (session, user, attributes)
     * @param currentPrompt the current system prompt
     * @return the (possibly transformed) system prompt
     */
    default Mono<String> onSystemPrompt(Agent agent, RuntimeContext ctx, String currentPrompt) {
        return Mono.just(currentPrompt);
    }

    /**
     * Notified once per lifecycle execution (subscription), right after this call's
     * {@link AgentState} is bound to the {@link RuntimeContext} — resolved from the
     * {@code (userId, sessionId)} slot or freshly created for a brand-new session — and
     * before the input enters the pipeline (memory, reasoning).
     *
     * <p>This is a one-way synchronous notification without {@code next} delegation:
     * middlewares run in {@link #order()} sequence (higher first) and must be non-blocking —
     * never re-enter the same session's agent, which would deadlock on its call gate. A
     * thrown exception fails the call: it propagates to the caller unchanged and the
     * remaining middlewares are not invoked.
     *
     * <p>Lifecycle window: the notification runs after this call has been admitted and
     * registered for interruption, but before the request-state binding, the tracing
     * envelope, and the error-event chain are in place. An interrupt issued while the
     * notification runs is honored; a thrown exception, however, reaches the caller
     * directly without ErrorEvent hooks or tracing spans — keep implementations
     * non-blocking and fast-failing.
     *
     * @param agent         the agent instance
     * @param ctx           per-call runtime context (session, user, attributes)
     * @param state         this call's just-ready state, same instance as
     *                      {@code ctx.getAgentState()}, never {@code null}; a brand-new
     *                      session starts with an empty context
     * @param inputMessages this call's input (after {@code onAgent} middleware, not yet
     *                      merged with memory) as a private mutable copy; in-place changes
     *                      apply to the whole call, the caller's original list is unaffected
     */
    default void onAgentStateReady(
            Agent agent, RuntimeContext ctx, AgentState state, List<Msg> inputMessages) {}

    /**
     * Decide whether an output becomes this call's final answer — the last word
     * on what gets delivered. Consulted at every final-answer exit (normal
     * finish, the forced summary after {@code maxIters}, and the
     * return-direct short-circuit, distinguished by {@link FinishReason});
     * interrupt-like exits never reach this hook.
     *
     * <p>Unlike the other hooks, this one never receives the agent: a verdict
     * needs only the per-call context and the gate input, so a single
     * stateless gate can serve any number of agents and concurrent calls.
     * Cross-round state belongs in {@link RuntimeContext} attributes.
     *
     * <p>This is a <b>side-effect point</b>: the moment any middleware
     * participates here, the agent switches to certified delivery — live text
     * and thinking of every round are withheld from the public stream, and
     * only the chain-certified content is projected after the verdict.
     * Participation is resolved by the {@link #activePoints()} contract and
     * is never vacuous (see {@link ExtensionPoint#ON_FINAL_ANSWER}).
     * Purely observational uses (logging, metrics) should not implement this
     * hook — the event stream and session recording already serve them, and
     * a gate sacrifices real-time streaming.
     *
     * <p>The default completes empty, which counts as accepting the candidate
     * — reachable only through an explicit {@code ON_FINAL_ANSWER} declaration
     * without an override.
     *
     * <p><b>Chain semantics.</b> Gates are consulted sequentially in {@link
     * #order()} order (higher first; registration order within ties). Each
     * gate receives the current working draft: the chain head sees the
     * model's original, later gates see their predecessors' replacements.
     * Vetoes do not short-circuit: every gate is consulted, all feedback of
     * a round is collected in chain order, and one round with multiple
     * vetoes counts as a single retry — the retry writes the model's
     * original draft plus the merged feedback to memory, never a gate's
     * rewrite. The framework honors at most 10 retries per call (not
     * configurable): the 11th veto fails the whole call closed — an error,
     * zero delivery, the rejected draft still reaching the session-recording
     * audit. Graceful exhaustion fallback is a gate's own job: watch {@link
     * FinalAnswerInput#retryCount()} and switch to {@link
     * FinalAnswerDecision#accept(Msg)} with fallback text before hitting the
     * limit. An {@code accept(replacement)} replacement is memorized and
     * delivered as the chain's output; at the {@code MAX_ITERATIONS} and
     * {@code RETURN_DIRECT} exits the framework re-applies the exit's {@code
     * GenerateReason} onto it. A gate error fails the call, never a silent
     * pass.
     *
     * @param ctx per-call runtime context (session, user, attributes)
     * @param input gate input: the working draft this gate receives, retries
     *     already honored, cumulative usage, and the exit that produced the
     *     candidate
     * @return exactly one decision — accept (optionally with a replacement)
     *     or retry with injected feedback; an empty Mono counts as a plain
     *     accept
     */
    default Mono<FinalAnswerDecision> onFinalAnswer(RuntimeContext ctx, FinalAnswerInput input) {
        return Mono.empty();
    }
}
