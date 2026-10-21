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
package io.agentscope.builder.runtime.managed;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.agentscope.builder.web.managed.AgentSessionInput;
import io.agentscope.core.agent.Agent;
import io.agentscope.core.agent.RuntimeContext;
import io.agentscope.core.event.AgentEvent;
import io.agentscope.core.message.Msg;
import io.agentscope.core.middleware.MiddlewareBase;
import io.agentscope.core.middleware.ModelCallInput;
import io.agentscope.core.middleware.ReasoningInput;
import io.agentscope.core.session.SessionRecorder;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

/** Applies durable runtime inputs at a reasoning boundary, with checkpoint and receipt in one batch. */
public final class ManagedRuntimeInputMiddleware implements MiddlewareBase {
    private final ObjectMapper mapper;

    public ManagedRuntimeInputMiddleware(ObjectMapper mapper) {
        this.mapper = mapper;
    }

    @Override
    public Set<ExtensionPoint> activePoints() {
        return Set.of(ExtensionPoint.ON_REASONING, ExtensionPoint.ON_MODEL_CALL);
    }

    @Override
    public int order() {
        return 1000;
    }

    @Override
    public Flux<AgentEvent> onReasoning(
            Agent agent,
            RuntimeContext ctx,
            ReasoningInput input,
            Function<ReasoningInput, Flux<AgentEvent>> next) {
        var execution = ctx.get(ManagedTurnExecution.class);
        if (execution == null) return next.apply(input);
        return Mono.fromCallable(() -> apply(agent, ctx, execution))
                .subscribeOn(Schedulers.boundedElastic())
                .flatMapMany(
                        added -> {
                            var messages = new ArrayList<>(input.messages());
                            var ids = new HashSet<String>();
                            for (var message : messages) ids.add(message.getId());
                            for (var message : added)
                                if (ids.add(message.getId())) messages.add(message);
                            return next.apply(
                                    new ReasoningInput(
                                            List.copyOf(messages), input.tools(), input.options()));
                        });
    }

    @Override
    public Flux<AgentEvent> onModelCall(
            Agent agent,
            RuntimeContext ctx,
            ModelCallInput input,
            Function<ModelCallInput, Flux<AgentEvent>> next) {
        var execution = ctx.get(ManagedTurnExecution.class);
        if (execution == null) return next.apply(input);
        return Mono.fromRunnable(execution::check)
                .subscribeOn(Schedulers.boundedElastic())
                .thenMany(Flux.defer(() -> next.apply(input)));
    }

    private List<Msg> apply(Agent agent, RuntimeContext ctx, ManagedTurnExecution execution) {
        execution.check();
        var pending =
                execution
                        .admission()
                        .pendingInputs(execution.scope(), execution.turnId(), 256)
                        .stream()
                        .filter(value -> "accepted".equals(value.status()))
                        .filter(value -> !execution.inputSettled(value.id()))
                        .toList();
        if (pending.stream().anyMatch(value -> value.type() == ManagedInputInbox.Type.CANCEL))
            throw new ManagedCancellationException();
        var recorder = SessionRecorder.from(ctx);
        if (recorder == null)
            throw new IllegalStateException("Managed input requires native recorder");
        var state = RuntimeContext.resolveAgentState(ctx, agent);
        var known = new HashSet<String>();
        for (var message : state.getContext()) known.add(message.getId());
        var applied = new ArrayList<String>();
        var additions = new ArrayList<Msg>();
        for (var value : pending) {
            if (value.type() != ManagedInputInbox.Type.STEER
                    && value.type() != ManagedInputInbox.Type.INJECT) continue;
            for (Msg message :
                    AgentSessionInput.messages(
                            execution
                                    .journal()
                                    .files()
                                    .resolveInput(execution.scope(), payload(value.payload())),
                            value.id()))
                if (known.add(message.getId())) {
                    state.contextMutable().add(message);
                    additions.add(message);
                }
            recorder.append(
                    "inbox/accepted",
                    Map.of("id", value.id(), "kind", value.type().name().toLowerCase(Locale.ROOT)));
            execution.local().inputPosition(value.id(), value.sequence());
            applied.add(value.id());
        }
        if (applied.isEmpty()) return List.of();
        recorder.checkpointedRecordNow(
                state,
                "managed_input",
                "inbox/applied",
                Map.of("commandIds", List.copyOf(applied)));
        return List.copyOf(additions);
    }

    private Map<String, Object> payload(String value) {
        try {
            return mapper.readValue(value, new TypeReference<>() {});
        } catch (JsonProcessingException invalid) {
            throw new IllegalStateException("Invalid accepted Input", invalid);
        }
    }
}
