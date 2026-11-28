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
package io.agentscope.durable.agentscope;

import com.fasterxml.jackson.databind.JsonNode;
import io.agentscope.core.agent.AgentExecutionHost.ToolPlan;
import io.agentscope.core.agent.RuntimeContext;
import io.agentscope.core.journal.InvocationRecorder;
import io.agentscope.core.message.ContentBlock;
import io.agentscope.core.message.Msg;
import io.agentscope.core.message.ToolResultBlock;
import io.agentscope.core.message.ToolUseBlock;
import io.agentscope.core.state.AgentState;
import io.agentscope.core.util.MessageUtils;
import io.agentscope.durable.DurableException;
import io.agentscope.durable.DurableJson;
import io.agentscope.durable.DurableTypes.ThreadSpec;
import io.agentscope.durable.TransactionalSessionBackend;
import io.agentscope.durable.agentscope.AgentScopeToolHooks.Decision;
import io.agentscope.durable.agentscope.AgentScopeToolHooks.Effect;
import io.agentscope.durable.agentscope.AgentScopeToolHooks.Normalizer;
import io.agentscope.durable.agentscope.AgentScopeToolHooks.PolicyInput;
import io.agentscope.durable.agentscope.AgentScopeToolHooks.ResultInput;
import io.agentscope.durable.codec.agentscope.DurableEventSchemas;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Consumer;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

/** Resolves tool hooks against immutable decisions in the native Turn journal. */
final class ToolHookJournal {
    private static final String NORMALIZED_RECOVERY = "agentscope.durable.normalized_recovery";

    record PolicyFact(
            String callId,
            String toolName,
            String version,
            String sourceDigest,
            JsonNode arguments,
            Effect effect) {}

    record ResultFact(String executionId, String version, String sourceDigest, JsonNode output) {}

    private final AgentScopeToolHooks hooks;
    private final ThreadSpec definition;
    private final String turnId;
    private final Map<String, PolicyFact> policies = new HashMap<>();
    private final Map<String, ResultFact> results = new HashMap<>();
    private final Set<String> completedLocalCalls = new HashSet<>();

    ToolHookJournal(
            AgentScopeToolHooks hooks,
            ThreadSpec definition,
            String turnId,
            BackendThreadJournal log) {
        this.hooks = hooks;
        this.definition = definition;
        this.turnId = turnId;
        for (var event : log.scan(0, log.head().seq())) {
            if (!turnId.equals(event.turnId())) continue;
            if (DurableEventSchemas.TOOL_POLICY.equals(event.type())) {
                for (var value : DurableJson.value(event.data()).required("decisions")) {
                    var decision = DurableJson.convert(value, PolicyFact.class);
                    policies.put(decision.callId(), decision);
                }
            } else if (DurableEventSchemas.TOOL_NORMALIZED.equals(event.type())) {
                var result = DurableJson.convert(DurableJson.value(event.data()), ResultFact.class);
                results.put(resultKey(result.executionId(), result.sourceDigest()), result);
            } else if ("action/end".equals(event.type()) && event.data().get("result") != null) {
                completedLocalCalls.add(String.valueOf(event.data().get("toolCallId")));
            }
        }
    }

    Mono<ToolPlan> prepare(RuntimeContext runtime, AgentState state, List<ToolUseBlock> granted) {
        if (hooks.policies().isEmpty() && policies.isEmpty())
            return Mono.just(new ToolPlan(granted, Set.of(), Set.of()));
        return Flux.fromIterable(granted)
                .concatMap(this::policy)
                .collectList()
                .flatMap(
                        facts ->
                                Mono.fromCallable(() -> freeze(runtime, state, granted, facts))
                                        .subscribeOn(Schedulers.boundedElastic()));
    }

    private Mono<PolicyFact> policy(ToolUseBlock call) {
        var input = arguments(call);
        String digest = DurableJson.hash(input);
        var prior = policies.get(call.getId());
        if (prior != null) {
            version(prior.version());
            if (!call.getName().equals(prior.toolName())
                    || !digest.equals(prior.sourceDigest())
                            && !digest.equals(DurableJson.hash(prior.arguments())))
                return Mono.error(
                        new DurableException(
                                DurableException.Code.CONFLICT, "Frozen tool input differs"));
            return Mono.just(prior);
        }
        Mono<Decision> decision = Mono.just(new Decision(Effect.ALLOW, input));
        for (var policy : hooks.policies()) {
            decision =
                    decision.flatMap(
                            current -> {
                                if (current.effect() == Effect.DENY) return Mono.just(current);
                                var adjusted = withArguments(call, current.arguments());
                                return Mono.fromCallable(
                                                () ->
                                                        policy.evaluate(
                                                                new PolicyInput(
                                                                        definition,
                                                                        turnId,
                                                                        adjusted)))
                                        .subscribeOn(Schedulers.boundedElastic())
                                        .timeout(hooks.timeout())
                                        .switchIfEmpty(
                                                Mono.error(
                                                        new IllegalArgumentException(
                                                                "Missing tool policy decision")))
                                        .map(
                                                next ->
                                                        new Decision(
                                                                current.effect().ordinal()
                                                                                > next.effect()
                                                                                        .ordinal()
                                                                        ? current.effect()
                                                                        : next.effect(),
                                                                next.arguments() == null
                                                                        ? current.arguments()
                                                                        : next.arguments()))
                                        .onErrorReturn(
                                                new Decision(Effect.DENY, current.arguments()));
                            });
        }
        return decision.map(
                value ->
                        new PolicyFact(
                                call.getId(),
                                call.getName(),
                                hooks.version(),
                                digest,
                                value.arguments(),
                                value.effect()));
    }

    private ToolPlan freeze(
            RuntimeContext runtime,
            AgentState state,
            List<ToolUseBlock> granted,
            List<PolicyFact> facts) {
        var fresh = new ArrayList<PolicyFact>();
        var calls = new ArrayList<ToolUseBlock>();
        var replacements = new HashMap<String, ToolUseBlock>();
        var confirmation = new HashSet<String>();
        var denied = new HashSet<String>();
        for (int i = 0; i < granted.size(); i++) {
            var fact = facts.get(i);
            var use = withArguments(granted.get(i), fact.arguments());
            calls.add(use);
            if (use != granted.get(i)) replacements.put(use.getId(), use);
            if (!policies.containsKey(fact.callId())) fresh.add(fact);
            if (fact.effect() == Effect.CONFIRM) confirmation.add(use.getId());
            if (fact.effect() == Effect.DENY) denied.add(use.getId());
        }
        MessageUtils.replaceToolUseBlocks(state.contextMutable(), replacements);
        if (!fresh.isEmpty())
            InvocationRecorder.from(runtime)
                    .checkpointedRecordNow(
                            state,
                            "durable_tool_plan",
                            DurableEventSchemas.TOOL_POLICY,
                            Map.of("decisions", fresh));
        fresh.forEach(fact -> policies.put(fact.callId(), fact));
        return new ToolPlan(calls, confirmation, denied);
    }

    Mono<ToolResultBlock> normalize(
            String executionId,
            ToolUseBlock call,
            ToolResultBlock source,
            Consumer<ResultFact> persist) {
        if (source.isSuspended() || hooks.normalizers().isEmpty() && results.isEmpty())
            return Mono.just(source);
        return normalize(executionId, call, source, DurableJson.hash(source), persist);
    }

    Mono<ToolResultBlock> normalize(
            String executionId,
            ToolUseBlock call,
            ToolResultBlock source,
            String digest,
            Consumer<ResultFact> persist) {
        if (source.isSuspended() || hooks.normalizers().isEmpty() && results.isEmpty())
            return Mono.just(source);
        String key = resultKey(executionId, digest);
        var prior = results.get(key);
        if (prior != null) {
            version(prior.version());
            return Mono.just(
                    withOutput(
                            source,
                            Arrays.asList(
                                    DurableJson.convert(prior.output(), ContentBlock[].class))));
        }
        if (hooks.normalizers().isEmpty()) return Mono.just(source);
        Mono<ToolResultBlock> result = Mono.just(source);
        for (var normalizer : hooks.normalizers()) {
            result =
                    result.flatMap(
                            current ->
                                    normalizeWith(
                                            normalizer,
                                            new ResultInput(
                                                    definition,
                                                    turnId,
                                                    executionId,
                                                    call,
                                                    current)));
        }
        return result.flatMap(
                value ->
                        Mono.fromCallable(
                                        () -> {
                                            var fact =
                                                    new ResultFact(
                                                            executionId,
                                                            hooks.version(),
                                                            digest,
                                                            DurableJson.value(value)
                                                                    .required("output"));
                                            persist.accept(fact);
                                            results.put(key, fact);
                                            return value;
                                        })
                                .subscribeOn(Schedulers.boundedElastic()));
    }

    private Mono<ToolResultBlock> normalizeWith(Normalizer normalizer, ResultInput input) {
        return Mono.fromCallable(
                        () -> withOutput(input.result(), List.copyOf(normalizer.normalize(input))))
                .subscribeOn(Schedulers.boundedElastic())
                .timeout(hooks.timeout())
                .onErrorMap(
                        error ->
                                new DurableException(
                                        DurableException.Code.UNAVAILABLE,
                                        "Tool result hook failed",
                                        error));
    }

    Mono<Boolean> normalizeRecovered(RuntimeContext runtime, AgentState state) {
        var recorder = InvocationRecorder.from(runtime);
        return normalizeRecovered(
                        state,
                        fact -> recorder.recordNow(DurableEventSchemas.TOOL_NORMALIZED, fact))
                .flatMap(
                        normalized ->
                                Mono.fromRunnable(
                                                () ->
                                                        recorder.checkpointNow(
                                                                state, "durable_recovered_results"))
                                        .subscribeOn(Schedulers.boundedElastic())
                                        .thenReturn(normalized));
    }

    Mono<Boolean> normalizeRecovered(AgentState state, Consumer<ResultFact> persist) {
        if (hooks.normalizers().isEmpty() && results.isEmpty()) return Mono.empty();
        var messages = state.contextMutable();
        var calls = new HashMap<String, ToolUseBlock>();
        var recovered = new ArrayList<Integer>();
        for (int i = 0; i < messages.size(); i++) {
            var message = messages.get(i);
            message.getContentBlocks(ToolUseBlock.class)
                    .forEach(call -> calls.put(call.getId(), call));
            var raw = recoveredResult(message);
            if (raw != null && completedLocalCalls.contains(raw.getId())) recovered.add(i);
        }
        if (recovered.isEmpty()) return Mono.empty();
        return Flux.fromIterable(recovered)
                .concatMap(
                        index -> {
                            var message = messages.get(index);
                            var source = recoveredResult(message);
                            var call = calls.get(source.getId());
                            if (call == null) return Mono.empty();
                            String execution =
                                    TransactionalSessionBackend.executionId(
                                            definition.key(), turnId, call.getId());
                            return normalize(execution, call, source, persist)
                                    .doOnNext(
                                            value -> {
                                                var metadata = new HashMap<String, Object>();
                                                if (message.getMetadata() != null)
                                                    metadata.putAll(message.getMetadata());
                                                metadata.put(NORMALIZED_RECOVERY, true);
                                                messages.set(
                                                        index,
                                                        message.withContent(List.of(value))
                                                                .withMetadata(metadata));
                                            });
                        })
                .then(Mono.just(true));
    }

    private static ToolResultBlock recoveredResult(Msg message) {
        if (message.getId() == null
                || !message.getId().startsWith("recovered_")
                || message.getMetadata() != null
                        && Boolean.TRUE.equals(message.getMetadata().get(NORMALIZED_RECOVERY)))
            return null;
        var blocks = message.getContentBlocks(ToolResultBlock.class);
        if (blocks.size() != 1) return null;
        var result = blocks.get(0);
        return message.getId().equals("recovered_" + result.getId()) ? result : null;
    }

    private void version(String version) {
        if (!hooks.version().equals(version))
            throw new DurableException(
                    DurableException.Code.INCOMPATIBLE, "Pinned tool hook version differs");
    }

    private static String resultKey(String executionId, String digest) {
        return executionId + ":" + digest;
    }

    private static JsonNode arguments(ToolUseBlock call) {
        return DurableJson.value(call.getInput() == null ? Map.of() : call.getInput());
    }

    @SuppressWarnings("unchecked")
    private static ToolUseBlock withArguments(ToolUseBlock call, JsonNode args) {
        if (arguments(call).equals(args)) return call;
        return ToolUseBlock.builder()
                .id(call.getId())
                .name(call.getName())
                .input(DurableJson.convert(args, Map.class))
                .content(args.toString())
                .metadata(call.getMetadata())
                .state(call.getState())
                .build();
    }

    private static ToolResultBlock withOutput(ToolResultBlock result, List<ContentBlock> output) {
        return new ToolResultBlock(
                result.getId(),
                result.getName(),
                output,
                result.getMetadata(),
                result.getState(),
                result.getExecutionDetails());
    }
}
