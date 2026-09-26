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
import io.agentscope.core.event.ModelCallEndEvent;
import io.agentscope.core.event.ModelCallStartEvent;
import io.agentscope.core.event.TextBlockDeltaEvent;
import io.agentscope.core.event.TextBlockEndEvent;
import io.agentscope.core.event.TextBlockStartEvent;
import io.agentscope.core.event.ToolCallStartEvent;
import io.agentscope.core.internal.stream.ReplyLifecycleTracker;
import io.agentscope.core.internal.stream.ReplyLifecycleTracker.Observation;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Function;
import reactor.core.publisher.Flux;

/**
 * Exposes only the text from the final reasoning round of a ReAct stream.
 *
 * <p>Text events are buffered until the current model call completes. If the model produces a
 * tool call during that round, the buffered and subsequent text events are suppressed. Otherwise,
 * the buffered text events are emitted before the corresponding {@link ModelCallEndEvent}.
 *
 * <p>This middleware is opt-in and does not change the default behavior of {@code ReActAgent}.
 * Because a round can only be identified as intermediate after a tool call is observed, text from
 * the current round is not emitted until the model call completes or a tool call is detected.
 */
public class FinalAnswerFilterMiddleware implements MiddlewareBase {

    @Override
    public Flux<AgentEvent> onReasoning(
            Agent agent,
            RuntimeContext ctx,
            ReasoningInput input,
            Function<ReasoningInput, Flux<AgentEvent>> next) {
        return Flux.defer(
                () -> {
                    RoundState state = new RoundState();
                    return next.apply(input)
                            .concatMap(state::handle)
                            .doFinally(signal -> state.clear());
                });
    }

    private static boolean isTextBlockEvent(AgentEvent event) {
        return event instanceof TextBlockStartEvent
                || event instanceof TextBlockDeltaEvent
                || event instanceof TextBlockEndEvent;
    }

    private static final class RoundState {
        private final List<AgentEvent> bufferedTextEvents = new ArrayList<>();
        private final ReplyLifecycleTracker tracker = new ReplyLifecycleTracker();

        private Flux<AgentEvent> handle(AgentEvent event) {
            Observation observation = tracker.observe(event);

            if (event instanceof ModelCallStartEvent) {
                bufferedTextEvents.clear();
                return Flux.just(event);
            }

            if (isTextBlockEvent(event)) {
                if (observation.currentReplyEvent() && !observation.after().toolCallSeen()) {
                    bufferedTextEvents.add(event);
                    return Flux.empty();
                }
                if (observation.currentReplyEvent()) {
                    return Flux.empty();
                }
                return Flux.just(event);
            }

            if (event instanceof ToolCallStartEvent && observation.currentReplyEvent()) {
                bufferedTextEvents.clear();
                return Flux.just(event);
            }

            if (event instanceof ModelCallEndEvent && observation.currentReplyEvent()) {
                if (observation.after().toolCallSeen()) {
                    clear();
                    return Flux.just(event);
                }

                List<AgentEvent> finalEvents = new ArrayList<>(bufferedTextEvents);
                finalEvents.add(event);
                clear();
                return Flux.fromIterable(finalEvents);
            }

            return Flux.just(event);
        }

        private void clear() {
            bufferedTextEvents.clear();
            tracker.clear();
        }
    }
}
