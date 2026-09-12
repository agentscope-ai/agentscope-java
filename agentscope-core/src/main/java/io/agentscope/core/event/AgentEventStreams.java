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
package io.agentscope.core.event;

import io.agentscope.core.internal.stream.ReplyLifecycleTracker;
import io.agentscope.core.internal.stream.ReplyLifecycleTracker.Observation;
import io.agentscope.core.internal.stream.ReplyLifecycleTracker.ReplySnapshot;
import io.agentscope.core.internal.stream.ReplyLifecycleTracker.SourceKey;
import io.agentscope.core.message.GenerateReason;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import reactor.core.publisher.Flux;

/** Utilities for deriving optional lifecycle signals from an {@link AgentEvent} stream. */
public final class AgentEventStreams {

    private AgentEventStreams() {}

    /**
     * Adds text output disposition events without changing the source stream itself.
     *
     * <p>State is isolated per subscription and per {@code source + taskId}. The authoritative
     * invocation result remains {@link AgentResultEvent}; a terminal disposition only closes the
     * last visible reply before a normally completed {@link AgentEndEvent}.
     *
     * <p>The wrapper is purely additive and never fails the source stream. An {@link
     * AgentEndEvent} is forwarded as soon as it is observed, preceded by the derived terminal
     * disposition when one applies, and any event that arrives after the end of a source is
     * forwarded unchanged rather than rejected.
     *
     * <p>A top-level terminal requires an authoritative {@link AgentResultEvent} for the same
     * source. A subagent terminal additionally requires a synthesized {@link AgentEndEvent} that
     * explicitly reports {@link AgentEndEvent#OUTCOME_SUCCESS}: an unknown or abnormal outcome never
     * closes the reply.
     *
     * @param source source event stream
     * @return a deferred stream containing the original events and derived disposition events
     */
    public static Flux<AgentEvent> withTextOutputDisposition(Flux<AgentEvent> source) {
        Objects.requireNonNull(source, "source");
        return Flux.defer(() -> new DispositionAnnotator().apply(source));
    }

    private static final Logger log = LoggerFactory.getLogger(AgentEventStreams.class);

    private static final class DispositionAnnotator {

        private final ReplyLifecycleTracker tracker = new ReplyLifecycleTracker();
        private final Map<SourceKey, AgentResultEvent> authoritativeResults = new LinkedHashMap<>();
        private final Set<SourceKey> endedSources = new HashSet<>();

        private Flux<AgentEvent> apply(Flux<AgentEvent> source) {
            return source.concatMap(event -> Flux.fromIterable(process(event)), 1);
        }

        private List<AgentEvent> process(AgentEvent event) {
            SourceKey sourceKey = tracker.sourceKey(event);
            if (endedSources.contains(sourceKey)) {
                if (event instanceof ModelCallStartEvent) {
                    // A new model call on a closed source starts a fresh invocation.
                    endedSources.remove(sourceKey);
                } else {
                    // A disposition is an opt-in, additive signal, so an ordering anomaly must
                    // never fail the wrapped stream: forward the event without classifying it.
                    log.warn(
                            "Received {} after AgentEndEvent for source {}; forwarding it without "
                                    + "a disposition classification",
                            event.getType(),
                            sourceKey);
                    return List.of(event);
                }
            }

            Observation observation = tracker.observe(event);
            return switch (observation.kind()) {
                case MODEL_CALL_START -> onModelCallStart(event, observation);
                case TOOL_CALL_START -> onToolCallStart(event, observation);
                case TEXT_BLOCK_END -> onTextBlockEnd(event, observation);
                case AGENT_RESULT -> onAgentResult((AgentResultEvent) event, observation);
                case AGENT_END -> onAgentEnd((AgentEndEvent) event, observation);
                default -> List.of(event);
            };
        }

        private List<AgentEvent> onAgentResult(AgentResultEvent event, Observation observation) {
            authoritativeResults.put(observation.sourceKey(), event);
            return List.of(event);
        }

        private List<AgentEvent> onModelCallStart(AgentEvent event, Observation observation) {
            ReplySnapshot previous = observation.before();
            if (hasUnclassifiedText(previous)) {
                return List.of(
                        disposition(
                                previous.replyId(),
                                TextOutputDisposition.INTERMEDIATE,
                                null,
                                event),
                        event);
            }
            return List.of(event);
        }

        private List<AgentEvent> onToolCallStart(AgentEvent event, Observation observation) {
            ReplySnapshot current = observation.after();
            if (observation.currentReplyEvent() && hasUnclassifiedText(current)) {
                tracker.markDispositionEmitted(observation.sourceKey());
                return List.of(
                        disposition(
                                current.replyId(), TextOutputDisposition.INTERMEDIATE, null, event),
                        event);
            }
            return List.of(event);
        }

        private List<AgentEvent> onTextBlockEnd(AgentEvent event, Observation observation) {
            ReplySnapshot current = observation.after();
            if (observation.currentReplyEvent()
                    && current.toolCallSeen()
                    && hasUnclassifiedText(current)) {
                tracker.markDispositionEmitted(observation.sourceKey());
                return List.of(
                        event,
                        disposition(
                                current.replyId(),
                                TextOutputDisposition.INTERMEDIATE,
                                null,
                                event));
            }
            return List.of(event);
        }

        private List<AgentEvent> onAgentEnd(AgentEndEvent event, Observation observation) {
            SourceKey sourceKey = observation.sourceKey();
            endedSources.add(sourceKey);

            ReplySnapshot current = observation.after();
            AgentResultEvent result = authoritativeResults.remove(sourceKey);
            List<AgentEvent> output = new ArrayList<>(2);
            if (hasUnclassifiedText(current)) {
                if (sourceKey.isTopLevel()) {
                    // ReActAgent emits the authoritative result immediately before the end event,
                    // so the top-level terminal disposition can be derived inline as well.
                    if (!isAbnormalEnd(event) && result != null && result.getResult() != null) {
                        output.add(
                                disposition(
                                        current.replyId(),
                                        TextOutputDisposition.TERMINAL,
                                        result.getResult().getGenerateReason(),
                                        event));
                        tracker.markDispositionEmitted(sourceKey);
                    }
                } else if (isSuccessfulChildEnd(event)) {
                    // A synthesized child end only closes the reply when it explicitly reports
                    // success, so an unknown or abnormal outcome never yields a terminal.
                    output.add(
                            disposition(
                                    current.replyId(),
                                    TextOutputDisposition.TERMINAL,
                                    null,
                                    event));
                    tracker.markDispositionEmitted(sourceKey);
                }
            }
            output.add(event);
            tracker.clearSource(sourceKey);
            return output;
        }

        private static Object invocationOutcome(AgentEndEvent end) {
            return end.getMetadata() == null
                    ? null
                    : end.getMetadata().get(AgentEndEvent.METADATA_INVOCATION_OUTCOME);
        }

        private static boolean isSuccessfulChildEnd(AgentEndEvent end) {
            Object outcome = invocationOutcome(end);
            return outcome != null && AgentEndEvent.OUTCOME_SUCCESS.equals(outcome.toString());
        }

        private static boolean isAbnormalEnd(AgentEndEvent end) {
            Object outcome = invocationOutcome(end);
            return outcome != null && !AgentEndEvent.OUTCOME_SUCCESS.equals(outcome.toString());
        }

        private static boolean hasUnclassifiedText(ReplySnapshot snapshot) {
            return snapshot.replyId() != null
                    && snapshot.textSeen()
                    && !snapshot.dispositionEmitted();
        }

        private static TextOutputDispositionEvent disposition(
                String replyId,
                TextOutputDisposition disposition,
                GenerateReason generateReason,
                AgentEvent trigger) {
            TextOutputDispositionEvent event =
                    new TextOutputDispositionEvent(replyId, disposition, generateReason);
            event.withSource(trigger.getSource()).withMetadata(trigger.getMetadata());
            return event;
        }
    }
}
