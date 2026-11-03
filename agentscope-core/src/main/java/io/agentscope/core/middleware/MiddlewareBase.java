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
import java.util.EnumSet;
import java.util.List;
import java.util.Set;
import java.util.function.Function;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

/**
 * Middleware provides interception mechanisms at 6 key execution points
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
 * <p>Each onion/transformer hook has a default implementation that delegates
 * directly to {@code next}, so subclasses only need to override the hooks they
 * care about; {@link #onAgentStateReady} defaults to a no-op.
 *
 * <p><b>Example:</b>
 * <pre>{@code
 * MiddlewareBase logging = new MiddlewareBase() {
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
     * grouping mechanism stays unchanged.
     */
    enum ExtensionPoint {
        ON_AGENT,
        ON_REASONING,
        ON_ACTING,
        ON_MODEL_CALL,
        ON_SYSTEM_PROMPT,
        ON_AGENT_STATE_READY
    }

    /**
     * Returns the extension points where this middleware is active. This is
     * a participation switch, not a statement about which methods are
     * overridden: a point declared active but not overridden simply runs
     * its default (pass-through / no-op), and a point omitted is never
     * invoked — even if overridden.
     *
     * <p>The default is active at every extension point, including ones
     * added in future releases, so middlewares that do not override this
     * method keep today's behavior unchanged. Once overridden, the active
     * set is exactly the returned set; an empty set disables this
     * middleware at every point while keeping its registration.
     *
     * <p>Activation is decided once at agent construction; mutating the
     * returned set (or changing later results) has no effect on an
     * already-built agent.
     *
     * @return the set of extension points this middleware participates in;
     *         {@code null} is treated as the full set; elements that do not equal
     *         an {@link ExtensionPoint} constant never activate any point
     */
    default Set<ExtensionPoint> activePoints() {
        return EnumSet.allOf(ExtensionPoint.class);
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
}
