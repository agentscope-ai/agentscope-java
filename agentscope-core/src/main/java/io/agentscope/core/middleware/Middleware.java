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
import io.agentscope.core.event.AgentEvent;
import java.util.function.Function;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

/**
 * Middleware provides interception mechanisms at 5 key execution points
 * in the Agent lifecycle.
 *
 * <p><b>Onion Pattern</b> (4 hooks — wrap execution with before/after logic):
 * <ul>
 *   <li>{@link #onReply} — intercepts the entire reply process</li>
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
 * <p>Each hook has a default implementation that delegates directly to
 * {@code next}, so subclasses only need to override the hooks they care about.
 *
 * <p><b>Example:</b>
 * <pre>{@code
 * Middleware logging = new Middleware() {
 *     @Override
 *     public Flux<AgentEvent> onReasoning(
 *             Agent agent, ReasoningInput input,
 *             Function<ReasoningInput, Flux<AgentEvent>> next) {
 *         System.out.println("Before reasoning");
 *         return next.apply(input)
 *             .doOnComplete(() -> System.out.println("After reasoning"));
 *     }
 * };
 *
 * ReActAgent agent = ReActAgent.builder()
 *     .middleware(logging)
 *     .build();
 * }</pre>
 */
public interface Middleware {

    /**
     * Intercept the entire reply process.
     *
     * @param agent the agent instance
     * @param input reply input (messages)
     * @param next  calls the next middleware or the core reply logic
     * @return event stream from the reply
     */
    default Flux<AgentEvent> onReply(
            Agent agent, ReplyInput input, Function<ReplyInput, Flux<AgentEvent>> next) {
        return next.apply(input);
    }

    /**
     * Intercept the reasoning phase (LLM call + streaming output parsing).
     *
     * @param agent the agent instance
     * @param input reasoning input (messages, tools, options)
     * @param next  calls the next middleware or the core reasoning logic
     * @return event stream from reasoning
     */
    default Flux<AgentEvent> onReasoning(
            Agent agent, ReasoningInput input, Function<ReasoningInput, Flux<AgentEvent>> next) {
        return next.apply(input);
    }

    /**
     * Intercept the tool-call execution phase.
     *
     * @param agent the agent instance
     * @param input acting input (the tool calls)
     * @param next  calls the next middleware or the core acting logic
     * @return event stream from acting
     */
    default Flux<AgentEvent> onActing(
            Agent agent, ActingInput input, Function<ActingInput, Flux<AgentEvent>> next) {
        return next.apply(input);
    }

    /**
     * Intercept the raw model API call.
     *
     * @param agent the agent instance
     * @param input model-call input (messages, tools, options, model)
     * @param next  calls the next middleware or the actual model invocation
     * @return event stream from the model call
     */
    default Flux<AgentEvent> onModelCall(
            Agent agent, ModelCallInput input, Function<ModelCallInput, Flux<AgentEvent>> next) {
        return next.apply(input);
    }

    /**
     * Transform the system prompt string (pipeline pattern).
     *
     * <p>Multiple middlewares are applied sequentially; each receives the
     * output of the previous one.
     *
     * @param agent         the agent instance
     * @param currentPrompt the current system prompt
     * @return the (possibly transformed) system prompt
     */
    default Mono<String> onSystemPrompt(Agent agent, String currentPrompt) {
        return Mono.just(currentPrompt);
    }
}
