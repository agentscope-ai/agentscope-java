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
package io.agentscope.core.observation;

import io.agentscope.core.agent.RuntimeContext;
import io.agentscope.core.event.AgentEventEmitter;
import io.agentscope.core.event.CustomEvent;
import io.agentscope.core.message.ToolResultBlock;
import io.agentscope.core.message.ToolResultState;
import io.agentscope.core.tool.ToolCallParam;
import java.util.UUID;
import java.util.function.Supplier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import reactor.core.publisher.Mono;

/** Shared boundary for registered tools and runtime-owned synthetic tool paths. */
public final class ActionObservations {
    private static final Logger log = LoggerFactory.getLogger(ActionObservations.class);

    private ActionObservations() {}

    public static Mono<ToolResultBlock> observe(
            ToolCallParam param, Supplier<Mono<ToolResultBlock>> execution) {
        return Mono.deferContextual(
                subscriber -> {
                    RuntimeContext context = param.getRuntimeContext();
                    Object configured =
                            context == null ? null : context.get(ActionObserver.CONTEXT_KEY);
                    if (!(configured instanceof ActionObserver observer))
                        return Mono.defer(execution);
                    var use = param.getToolUseBlock();
                    var state = RuntimeContext.resolveAgentState(context, param.getAgent());
                    var started =
                            new ActionObservation(
                                    UUID.randomUUID().toString(),
                                    use.getId(),
                                    use.getName(),
                                    param.getAgent() == null ? null : param.getAgent().getAgentId(),
                                    context.getUserId(),
                                    context.getSessionId(),
                                    System.currentTimeMillis(),
                                    null,
                                    ActionObservation.Status.STARTED,
                                    null,
                                    null,
                                    state == null
                                            ? null
                                            : state.getTasksContext().getEvidenceBinding());
                    var emitter =
                            AgentEventEmitter.fromForwardingContext(subscriber)
                                    .or(() -> AgentEventEmitter.fromContext(subscriber));
                    ActionObserver acknowledged =
                            (observation, result) ->
                                    Mono.defer(() -> observer.record(observation, result))
                                            .onErrorMap(
                                                    error -> new ActionObservationException(error))
                                            .doOnSuccess(
                                                    ignored ->
                                                            emitter.ifPresent(
                                                                    value ->
                                                                            emitSafely(
                                                                                    value,
                                                                                    observation)));
                    return Mono.usingWhen(
                            acknowledged.record(started, null).thenReturn(started),
                            acquired ->
                                    Mono.defer(execution)
                                            .switchIfEmpty(
                                                    Mono.error(
                                                            new IllegalStateException(
                                                                    "Tool returned no result")))
                                            .flatMap(
                                                    result -> {
                                                        ToolResultBlock identified =
                                                                result.withIdAndName(
                                                                        use.getId(), use.getName());
                                                        return acknowledged
                                                                .record(
                                                                        acquired.settled(
                                                                                status(result),
                                                                                null,
                                                                                result
                                                                                        .getExecutionDetails()),
                                                                        identified)
                                                                .thenReturn(identified);
                                                    }),
                            acquired -> Mono.empty(),
                            (acquired, error) ->
                                    ActionObservationException.causedBy(error)
                                            ? Mono.empty()
                                            : acknowledged.record(
                                                    acquired.settled(
                                                            ActionObservation.Status.FAILED,
                                                            error.getClass().getSimpleName(),
                                                            null),
                                                    null),
                            acquired ->
                                    acknowledged.record(
                                            acquired.settled(
                                                    ActionObservation.Status.INTERRUPTED,
                                                    null,
                                                    null),
                                            null));
                });
    }

    private static void emitSafely(AgentEventEmitter emitter, ActionObservation observation) {
        try {
            emitter.emit(new CustomEvent(ActionObserver.EVENT_NAME, observation.eventPayload()));
        } catch (RuntimeException error) {
            log.warn(
                    "Action observation persisted but event delivery failed: {}",
                    observation.actionId());
        }
    }

    private static ActionObservation.Status status(ToolResultBlock result) {
        if (result.isSuspended()) return ActionObservation.Status.SUSPENDED;
        if (result.getState() == ToolResultState.DENIED) return ActionObservation.Status.DENIED;
        if (result.getState() == ToolResultState.INTERRUPTED)
            return ActionObservation.Status.INTERRUPTED;
        if (result.getState() == ToolResultState.ERROR) return ActionObservation.Status.FAILED;
        return ActionObservation.Status.RETURNED;
    }
}
