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

import io.agentscope.core.ReActAgent;
import io.agentscope.core.agent.AgentExecutionHost;
import io.agentscope.core.agent.RunOutcome;
import io.agentscope.core.agent.RuntimeContext;
import io.agentscope.core.event.AgentEvent;
import io.agentscope.core.event.ConfirmResult;
import io.agentscope.core.journal.JournalProjection;
import io.agentscope.core.journal.ModelInvocation;
import io.agentscope.core.journal.ThreadInteractions;
import io.agentscope.core.journal.ThreadJournal;
import io.agentscope.core.journal.ThreadJournalStore;
import io.agentscope.core.message.ContentBlock;
import io.agentscope.core.message.GenerateReason;
import io.agentscope.core.message.Msg;
import io.agentscope.core.message.ToolCallState;
import io.agentscope.core.message.ToolResultBlock;
import io.agentscope.core.message.ToolResultMessage;
import io.agentscope.core.message.ToolResultState;
import io.agentscope.core.message.ToolUseBlock;
import io.agentscope.core.message.UserMessage;
import io.agentscope.core.model.ExecutionConfig;
import io.agentscope.core.state.AgentState;
import io.agentscope.core.tool.ToolBase;
import io.agentscope.core.tool.ToolCallParam;
import io.agentscope.core.tool.Toolkit;
import io.agentscope.core.util.JsonUtils;
import io.agentscope.durable.DurableException;
import io.agentscope.durable.DurableJson;
import io.agentscope.durable.DurableTypes.Commit;
import io.agentscope.durable.DurableTypes.Frame;
import io.agentscope.durable.DurableTypes.ThreadSpec;
import io.agentscope.durable.DurableTypes.TurnStatus;
import io.agentscope.durable.DurableTypes.WaitCondition;
import io.agentscope.durable.HarnessAdapter;
import io.agentscope.durable.HarnessContext;
import io.agentscope.durable.ToolBinding;
import io.agentscope.durable.codec.agentscope.AgentScopeCheckpointCodec;
import io.agentscope.harness.agent.HarnessAgent;
import io.agentscope.harness.agent.middleware.MemoryFlushMiddleware;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.function.Function;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

/**
 * Embeds the native AgentScope loop. Factories return a fresh Agent builder; the adapter installs
 * invocation-scoped persistence before building the Agent. Register externally executed tools as
 * schema-only tools; native permission checks still turn first.
 */
public final class AgentScopeAdapter implements HarnessAdapter {

    public record Binding(
            ThreadSpec definition, RuntimeContext context, DurableWorkspaceStore workspace) {}

    private record InvocationBinding(Binding binding, ThreadJournalStore journalStore) {}

    private interface NativeAgent extends AutoCloseable {
        ThreadJournal log(RuntimeContext context);

        Flux<AgentEvent> stream(List<Msg> input, RuntimeContext context);

        @Override
        void close();
    }

    private final Function<InvocationBinding, NativeAgent> factory;
    private final Map<String, String> executors;
    private final Map<String, ToolBinding> bindings;

    private AgentScopeAdapter(
            Function<InvocationBinding, NativeAgent> factory,
            Map<String, String> executors,
            Map<String, ToolBinding> bindings) {
        this.factory = factory;
        this.executors = Map.copyOf(executors);
        this.bindings = Map.copyOf(bindings);
    }

    /** Registers schemas from the same immutable definitions used by the remote worker. */
    public static AgentScopeAdapter forReAct(
            Function<Binding, ReActAgent.Builder> factory, Collection<ToolBinding> tools) {
        var frozen = ToolBinding.catalog(tools);
        return createReAct(factory, ToolBinding.routes(frozen.values()), frozen);
    }

    public static AgentScopeAdapter forHarness(
            Function<Binding, HarnessAgent.Builder> factory, Collection<ToolBinding> tools) {
        var frozen = ToolBinding.catalog(tools);
        return createHarness(factory, ToolBinding.routes(frozen.values()), frozen);
    }

    public static AgentScopeAdapter forReAct(
            Function<Binding, ReActAgent.Builder> factory, Map<String, String> executors) {
        return createReAct(factory, executors, Map.of());
    }

    private static AgentScopeAdapter createReAct(
            Function<Binding, ReActAgent.Builder> factory,
            Map<String, String> executors,
            Map<String, ToolBinding> bindings) {
        Objects.requireNonNull(factory);
        return new AgentScopeAdapter(
                invocation -> {
                    var builder =
                            Objects.requireNonNull(
                                    factory.apply(invocation.binding()),
                                    "ReAct factory returned null builder");
                    var agent =
                            builder.journalEnabled(true)
                                    .threadJournalStore(invocation.journalStore())
                                    .build();
                    try {
                        AgentScopeToolExecutor.registerSchemas(
                                agent.getToolkit(), bindings.values());
                        route(agent.getToolkit(), executors.keySet());
                    } catch (RuntimeException error) {
                        agent.close();
                        throw error;
                    }
                    return new NativeAgent() {
                        public ThreadJournal log(RuntimeContext context) {
                            return agent.threadJournal(context);
                        }

                        public Flux<AgentEvent> stream(List<Msg> input, RuntimeContext context) {
                            return agent.streamEvents(input, context);
                        }

                        public void close() {
                            agent.close();
                        }
                    };
                },
                executors,
                bindings);
    }

    public static AgentScopeAdapter forHarness(
            Function<Binding, HarnessAgent.Builder> factory, Map<String, String> executors) {
        return createHarness(factory, executors, Map.of());
    }

    private static AgentScopeAdapter createHarness(
            Function<Binding, HarnessAgent.Builder> factory,
            Map<String, String> executors,
            Map<String, ToolBinding> bindings) {
        Objects.requireNonNull(factory);
        return new AgentScopeAdapter(
                invocation -> {
                    var binding = invocation.binding();
                    // Default Harness memory work must finish inside the recoverable native phase.
                    binding.context().put(MemoryFlushMiddleware.INLINE_CONTEXT_KEY, true);
                    var builder =
                            Objects.requireNonNull(
                                    factory.apply(binding),
                                    "Harness factory returned null builder");
                    var agent =
                            builder.journalEnabled(true)
                                    .threadJournalStore(invocation.journalStore())
                                    .build();
                    try {
                        AgentScopeToolExecutor.registerSchemas(
                                agent.getToolkit(), bindings.values());
                        route(agent.getToolkit(), executors.keySet());
                    } catch (RuntimeException error) {
                        agent.close();
                        throw error;
                    }
                    return new NativeAgent() {
                        public ThreadJournal log(RuntimeContext context) {
                            return agent.threadJournal(context);
                        }

                        public Flux<AgentEvent> stream(List<Msg> input, RuntimeContext context) {
                            return agent.streamEvents(input, context);
                        }

                        public void close() {
                            agent.close();
                        }
                    };
                },
                executors,
                bindings);
    }

    private static void route(Toolkit toolkit, Set<String> names) {
        for (var name : names) {
            var tool = toolkit.getTool(name);
            if (tool == null)
                throw new DurableException(
                        DurableException.Code.INCOMPATIBLE,
                        "Missing schema for routed tool: " + name);
            if (toolkit.isExternalTool(name)) continue;
            toolkit.replaceAgentTool(
                    new ToolBase(
                            ToolBase.builder()
                                    .name(tool.getName())
                                    .description(tool.getDescription())
                                    .inputSchema(tool.getParameters())
                                    .externalTool(true)
                                    .readOnly(tool.isReadOnly())
                                    .returnDirect(tool.isReturnDirect())
                                    .concurrencySafe(true)) {
                        @Override
                        public Boolean getStrict() {
                            return tool.getStrict();
                        }

                        @Override
                        public Boolean getDeferLoading() {
                            return tool.getDeferLoading();
                        }

                        @Override
                        public Mono<ToolResultBlock> callAsync(ToolCallParam param) {
                            return Mono.just(ToolResultBlock.suspended(param.getToolUseBlock()));
                        }

                        @Override
                        public Map<String, Object> getOutputSchema() {
                            return tool.getOutputSchema();
                        }
                    });
        }
    }

    @Override
    public Capabilities capabilities() {
        return new Capabilities(
                AgentScopeCheckpointCodec.STATE_VERSION,
                RecoveryBoundary.TOOL_BOUNDARY,
                EventDelivery.DELTA,
                true,
                List.of("text", "image", "audio", "video"));
    }

    @Override
    public void execute(HarnessContext context) {
        if (pauseIfRequested(context)) return;
        var log = new BackendThreadJournal(context, executors, bindings);
        var host = new AgentScopeExecutionHost(context, log);
        if (host.pending()) host.restoreEntry();
        else if (completeInvocation(context, log)) return;
        var frame = context.frame();
        var input = new ArrayList<Msg>();
        var results = new ArrayList<ToolResultBlock>();
        var confirmations = new ArrayList<ConfirmResult>();
        var consumed = new ArrayList<String>();
        if (frame.turn().waiting() != null && "continue".equals(frame.turn().waiting().key()))
            frame.signals().stream()
                    .filter(signal -> "continue".equals(signal.key()))
                    .forEach(signal -> consumed.add(signal.id()));
        for (var request : ThreadInteractions.pending(log).values()) {
            if (!frame.turn().turnId().equals(request.turnId())) continue;
            String id = String.valueOf(request.data().get("requestId"));
            var call =
                    JsonUtils.getJsonCodec()
                            .convertValue(request.data().get("call"), ToolUseBlock.class);
            var execution =
                    frame.tools().stream()
                            .filter(t -> t.request().call().callId().equals(call.getId()))
                            .findFirst();
            if ("external_execution".equals(request.data().get("kind")) && execution.isPresent()) {
                var result = execution.get().result();
                if (!result.state().terminal()) {
                    waitFor(context, null);
                    return;
                }
                var block = AgentScopeToolResults.convert(call, result);
                results.add(block);
                continue;
            }
            String signalKey = "interaction:" + id;
            var signal =
                    frame.signals().stream().filter(s -> s.key().equals(signalKey)).findFirst();
            if (signal.isEmpty()) {
                waitFor(context, new WaitCondition(signalKey, 0));
                return;
            }
            if ("confirmation".equals(request.data().get("kind"))) {
                if (!signal.get().value().isBoolean())
                    throw new DurableException(
                            DurableException.Code.INVALID, "Confirmation signal must be boolean");
                confirmations.add(new ConfirmResult(signal.get().value().asBoolean(), call));
            } else if ("external_execution".equals(request.data().get("kind"))) {
                results.add(
                        ToolResultBlock.text(
                                        signal.get().value().isTextual()
                                                ? signal.get().value().asText()
                                                : signal.get().value().toString())
                                .withIdAndName(call.getId(), call.getName())
                                .withState(ToolResultState.SUCCESS));
            } else
                throw new DurableException(
                        DurableException.Code.INCOMPATIBLE, "Unknown native interaction kind");
            consumed.add(signal.get().id());
        }
        if (!results.isEmpty())
            input.add(
                    ToolResultMessage.builder()
                            .id(
                                    "results:"
                                            + DurableJson.hash(
                                                    results.stream()
                                                            .map(ToolResultBlock::getId)
                                                            .toList()))
                            .results(results)
                            .build());
        if (!confirmations.isEmpty())
            input.add(
                    UserMessage.builder()
                            .id("confirmation:" + DurableJson.hash(consumed))
                            .textContent("")
                            .metadata(Map.of(Msg.METADATA_CONFIRM_RESULTS, confirmations))
                            .build());
        if (!hasInitialInput(log, frame.turn().turnId()))
            input.add(AgentScopeMessages.input(frame.input(), "input:" + frame.turn().turnId()));
        log.consuming(consumed);
        var runtimeContext =
                RuntimeContext.builder()
                        .userId(frame.definition().key().session().namespace())
                        .sessionId(frame.definition().key().session().sessionId())
                        .threadId(frame.definition().key().threadId())
                        .runId(context.prepareRun().runId())
                        .turnId(frame.turn().turnId())
                        .put(HarnessContext.class, context)
                        .put(AgentExecutionHost.class, host)
                        .put(ModelInvocation.class, host)
                        .build();
        ThreadJournalStore logs =
                (key, ignored) -> {
                    if (!Objects.equals(
                                    key.userId(), frame.definition().key().session().namespace())
                            || !key.sessionId()
                                    .equals(frame.definition().key().session().sessionId())
                            || !key.threadId().equals(frame.definition().key().threadId()))
                        throw new DurableException(
                                DurableException.Code.INVALID,
                                "Agent tried to open a different Thread");
                    return log;
                };
        try (var agent =
                factory.apply(
                        new InvocationBinding(
                                new Binding(
                                        frame.definition(),
                                        runtimeContext,
                                        new DurableWorkspaceStore(
                                                context,
                                                host::resourceIdentity,
                                                host::resourceTimestamp)),
                                logs))) {
            if (agent.log(runtimeContext) != log)
                throw new DurableException(
                        DurableException.Code.INCOMPATIBLE,
                        "Adapter did not install the invocation journal");
            agent.stream(List.copyOf(input), runtimeContext).blockLast();
        } catch (DurableException failure) {
            throw failure;
        } catch (RuntimeException failure) {
            // Use Core's provider-aware classification (429/5xx, transport/timeouts). The
            // framework Runtime stays independent of Core and still owns the retry budget.
            throw new DurableException(
                    ExecutionConfig.RETRYABLE_ERRORS.test(failure)
                            ? DurableException.Code.UNAVAILABLE
                            : DurableException.Code.INVALID,
                    failure.getMessage() == null
                            ? "Native invocation failed"
                            : failure.getMessage(),
                    failure);
        }
        if (pauseIfRequested(context)) return;
        if (host.pending())
            throw new DurableException(
                    DurableException.Code.UNAVAILABLE, "Native finalization is incomplete");
        if (completeInvocation(context, log)) return;
        var invocation = latestInvocation(log, context.lease().turnId());
        if (invocation != null
                && invocation.outcome().status() == RunOutcome.Status.COMPLETED
                && hasPendingInput(context.frame())) {
            var latest = context.frame();
            context.commit(
                    new Commit(
                            "continue:" + invocation.runId(),
                            latest.turn().revision(),
                            latest.checkpoint(),
                            List.of(),
                            List.of(),
                            List.of(),
                            List.of(),
                            TurnStatus.RUNNING,
                            null,
                            null,
                            null));
            return;
        }
        var pending =
                ThreadInteractions.pending(log).values().stream()
                        .filter(e -> context.lease().turnId().equals(e.turnId()))
                        .findFirst();
        if (pending.isPresent()) {
            var request = pending.get();
            var call =
                    JsonUtils.getJsonCodec()
                            .convertValue(request.data().get("call"), ToolUseBlock.class);
            boolean automatic =
                    "external_execution".equals(request.data().get("kind"))
                            && executors.containsKey(call.getName());
            waitFor(
                    context,
                    automatic
                            ? null
                            : new WaitCondition(
                                    "interaction:" + request.data().get("requestId"), 0));
        } else {
            waitFor(context, new WaitCondition("continue", 0));
        }
    }

    private record NativeInvocation(String runId, RunOutcome outcome, Msg output) {}

    /** Reads invocation facts only; native journals do not own the durable Turn state. */
    private static NativeInvocation latestInvocation(ThreadJournal log, String turnId) {
        String runId = null;
        RunOutcome outcome = null;
        Msg output = null;
        for (var event : log.scanMetadata(0, log.head().seq())) {
            if ("state/restored".equals(event.type())) {
                runId = null;
                outcome = null;
                output = null;
            }
            if (!turnId.equals(event.turnId())) continue;
            if ("run/start".equals(event.type())) {
                runId = event.executionRunId();
                outcome = null;
                output = null;
            }
            if (!Objects.equals(runId, event.executionRunId())) continue;
            if ("run/output".equals(event.type()))
                output =
                        JsonUtils.getJsonCodec()
                                .convertValue(
                                        log.materialize(event).data().get("message"), Msg.class);
            if ("run/end".equals(event.type()))
                outcome = JsonUtils.getJsonCodec().convertValue(event.data(), RunOutcome.class);
        }
        return outcome == null ? null : new NativeInvocation(runId, outcome, output);
    }

    private static boolean hasInitialInput(ThreadJournal log, String turnId) {
        boolean received = false;
        for (var event : log.scanMetadata(0, log.head().seq())) {
            if ("state/restored".equals(event.type())) received = false;
            if ("input/received".equals(event.type()) && turnId.equals(event.turnId()))
                received = true;
        }
        return received;
    }

    private static boolean hasPendingInput(Frame frame) {
        return frame.signals().stream()
                .anyMatch(signal -> Set.of("input:steer", "input:inject").contains(signal.key()));
    }

    private static boolean completeInvocation(HarnessContext context, BackendThreadJournal log) {
        var frame = context.frame();
        var invocation = latestInvocation(log, frame.turn().turnId());
        if (invocation == null) return false;
        var outcome = invocation.outcome();
        if (outcome.status() != RunOutcome.Status.COMPLETED
                && outcome.status() != RunOutcome.Status.FAILED) return false;
        // A failed invocation can be retried by its host. Only explicit semantic results end a
        // task.
        if (outcome.status() == RunOutcome.Status.FAILED
                && !GenerateReason.MAX_ITERATIONS.name().equals(outcome.reason())
                && !GenerateReason.ALL_TOOLS_DENIED.name().equals(outcome.reason())) return false;
        // A late accepted input belongs to the same task and needs another invocation.
        if (outcome.status() == RunOutcome.Status.COMPLETED && hasPendingInput(frame)) return false;
        var status =
                outcome.status() == RunOutcome.Status.COMPLETED
                        ? TurnStatus.SUCCEEDED
                        : TurnStatus.FAILED;
        try {
            context.commit(
                    new Commit(
                            "native-finished:" + invocation.runId(),
                            frame.turn().revision(),
                            frame.checkpoint(),
                            List.of(),
                            List.of(),
                            List.of(),
                            List.of(),
                            status,
                            null,
                            DurableJson.value(
                                    invocation.output() == null
                                            ? null
                                            : AgentScopeMessages.output(
                                                    invocation.output(),
                                                    log.messageId(
                                                            frame.turn().turnId(),
                                                            invocation.output()))),
                            status == TurnStatus.FAILED ? outcome.reason() : null));
        } catch (DurableException conflict) {
            if (conflict.code() != DurableException.Code.CONFLICT) throw conflict;
            var latest = context.frame();
            if (outcome.status() == RunOutcome.Status.COMPLETED && hasPendingInput(latest))
                return false;
            if (latest.turn().revision() == frame.turn().revision()) throw conflict;
            throw new DurableException(
                    DurableException.Code.UNAVAILABLE,
                    "Turn changed while settling the invocation; retry from current state",
                    conflict);
        }
        return true;
    }

    private static boolean pauseIfRequested(HarnessContext context) {
        var frame = context.frame();
        if (frame.turn().status() != TurnStatus.PAUSING) return false;
        context.commit(
                new Commit(
                        "paused:" + UUID.randomUUID(),
                        frame.turn().revision(),
                        frame.checkpoint(),
                        List.of(),
                        List.of(),
                        List.of(),
                        List.of(),
                        TurnStatus.PAUSED,
                        frame.turn().waiting(),
                        null,
                        null));
        return true;
    }

    private static void waitFor(HarnessContext context, WaitCondition condition) {
        var frame = context.frame();
        context.commit(
                new Commit(
                        "yield:" + UUID.randomUUID(),
                        frame.turn().revision(),
                        frame.checkpoint(),
                        List.of(),
                        List.of(),
                        List.of(),
                        List.of(),
                        TurnStatus.WAITING,
                        condition,
                        null,
                        null));
    }

    @Override
    public void settle(HarnessContext context, TurnStatus status, String error) {
        if (status != TurnStatus.CANCELLED && status != TurnStatus.FAILED)
            throw new IllegalArgumentException("Settlement requires FAILED or CANCELLED");
        String reason = status == TurnStatus.CANCELLED ? "cancelled" : "failed";
        var frame = context.frame();
        if (frame.tools().stream().anyMatch(tool -> !tool.result().state().terminal()))
            throw new DurableException(
                    DurableException.Code.CONFLICT,
                    "External tool outcome requires reconciliation");
        var log = new BackendThreadJournal(context, executors, bindings);
        var projection = JournalProjection.read(log);
        var reconciled =
                frame.tools().stream().map(tool -> tool.request().call().callId()).toList();
        if (projection.uncertainToolCalls().stream().anyMatch(id -> !reconciled.contains(id)))
            throw new DurableException(
                    DurableException.Code.CONFLICT, "Native tool outcome requires reconciliation");
        var state = projection.restore();
        if (state == null) state = AgentState.builder().build();
        var facts = new ArrayList<Map.Entry<String, Object>>();
        var calls = new HashSet<String>();
        for (var request : ThreadInteractions.pending(log).values()) {
            if (!context.lease().turnId().equals(request.turnId())) continue;
            var call =
                    JsonUtils.getJsonCodec()
                            .convertValue(request.data().get("call"), ToolUseBlock.class);
            calls.add(call.getId());
            var execution =
                    frame.tools().stream()
                            .filter(tool -> tool.request().call().callId().equals(call.getId()))
                            .findFirst();
            var result =
                    execution.isPresent()
                            ? AgentScopeToolResults.convert(call, execution.get().result())
                            : ToolResultBlock.text(
                                            "Pending interaction closed after task " + reason + ".")
                                    .withIdAndName(call.getId(), call.getName())
                                    .withState(ToolResultState.INTERRUPTED);
            var message =
                    ToolResultMessage.builder()
                            .id("settled:" + call.getId())
                            .result(result)
                            .build();
            state.contextMutable().add(message);
            facts.add(
                    Map.entry(
                            "interaction/resolved",
                            Map.of(
                                    "requestId",
                                    request.data().get("requestId"),
                                    "kind",
                                    request.data().get("kind"),
                                    "status",
                                    execution.isPresent() ? "resolved" : "cancelled")));
            facts.add(
                    Map.entry(
                            "tool/result",
                            Map.of("message", message, "synthetic", execution.isEmpty())));
        }
        var messages = state.contextMutable();
        for (int i = 0; i < messages.size(); i++) {
            var content = new ArrayList<ContentBlock>();
            for (var block : messages.get(i).getContent())
                content.add(
                        block instanceof ToolUseBlock call && calls.contains(call.getId())
                                ? call.withState(ToolCallState.FINISHED)
                                : block);
            messages.set(i, messages.get(i).withContent(content));
        }
        facts.add(
                Map.entry(
                        "state/checkpoint",
                        Map.of(
                                "stateJson",
                                state.toJson(),
                                "projectorVersion",
                                1,
                                "reason",
                                reason)));
        if (!projection.activeRuns().isEmpty() || !projection.uncertainToolCalls().isEmpty())
            facts.add(
                    Map.entry(
                            "recovery/applied",
                            Map.of(
                                    "interruptedRuns",
                                    projection.activeRuns(),
                                    "resolvedToolCallIds",
                                    reconciled,
                                    "reason",
                                    reason)));
        log.append(context.lease().turnId(), facts, status, error);
    }
}
