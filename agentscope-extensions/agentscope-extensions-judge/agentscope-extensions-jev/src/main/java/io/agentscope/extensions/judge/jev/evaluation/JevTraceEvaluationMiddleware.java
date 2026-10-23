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

package io.agentscope.extensions.judge.jev.evaluation;

import io.agentscope.core.agent.Agent;
import io.agentscope.core.agent.RuntimeContext;
import io.agentscope.core.event.AgentEvent;
import io.agentscope.core.event.AgentResultEvent;
import io.agentscope.core.message.Msg;
import io.agentscope.core.middleware.AgentInput;
import io.agentscope.core.middleware.MiddlewareBase;
import io.agentscope.core.middleware.ModelCallInput;
import io.agentscope.extensions.judge.jev.JevExecution;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.function.BiConsumer;
import java.util.function.Function;
import reactor.core.publisher.Flux;

/**
 * Invocation-scoped, post-run observation using exactly the offline metric definitions.
 * Events and tool execution pass through. A bounded evaluation delays completion only.
 * ENFORCE is rejected: post-run observation cannot revoke already executed actions.
 */
public final class JevTraceEvaluationMiddleware implements MiddlewareBase {
    private final Object captureKey = new Object();
    private final JevTraceEvaluator evaluator;
    private final JevExecution execution;
    private final BiConsumer<RuntimeContext, JevTraceEvaluator.Report> observer;
    private final int maxCaptureChars;

    public JevTraceEvaluationMiddleware(JevTraceEvaluator evaluator) {
        this(evaluator, JevExecution.Options.disabled(), 100000, (ctx, report) -> {});
    }

    public JevTraceEvaluationMiddleware(
            JevTraceEvaluator evaluator,
            JevExecution.Options options,
            int maxCaptureChars,
            BiConsumer<RuntimeContext, JevTraceEvaluator.Report> observer) {
        this.evaluator = Objects.requireNonNull(evaluator);
        this.execution = new JevExecution("trace_evaluation", options);
        this.observer = Objects.requireNonNull(observer);
        if (options.mode() == JevExecution.Mode.ENFORCE)
            throw new IllegalArgumentException(
                    "post-run trace evaluation supports OFF/SHADOW; use pre-execution guards for"
                            + " enforcement");
        if (maxCaptureChars < 1)
            throw new IllegalArgumentException("positive capture limit required");
        this.maxCaptureChars = maxCaptureChars;
    }

    @Override
    public int order() {
        return 300;
    }

    @Override
    public Flux<AgentEvent> onAgent(
            Agent agent,
            RuntimeContext ctx,
            AgentInput input,
            Function<AgentInput, Flux<AgentEvent>> next) {
        return Flux.defer(
                () -> {
                    if (execution.mode() == JevExecution.Mode.OFF) return next.apply(input);
                    Capture capture = new Capture(input.msgs());
                    return Flux.defer(() -> next.apply(input))
                            .doOnNext(
                                    event -> {
                                        if (event instanceof AgentResultEvent result)
                                            capture.finish(result.getResult());
                                    })
                            .concatWith(
                                    Flux.defer(
                                            () ->
                                                    execution
                                                            .execute(
                                                                    ctx,
                                                                    () ->
                                                                            evaluator
                                                                                    .evaluate(
                                                                                            capture
                                                                                                    .trace())
                                                                                    .doOnNext(
                                                                                            report -> {
                                                                                                try {
                                                                                                    observer
                                                                                                            .accept(
                                                                                                                    ctx,
                                                                                                                    report);
                                                                                                } catch (
                                                                                                        RuntimeException
                                                                                                                ignored) {
                                                                                                    /* observation is isolated */
                                                                                                }
                                                                                            })
                                                                                    .map(
                                                                                            report ->
                                                                                                    new JevExecution
                                                                                                            .Decision<>(
                                                                                                            JevExecution
                                                                                                                    .Status
                                                                                                                    .DECIDED,
                                                                                                            report,
                                                                                                            "EVALUATED",
                                                                                                            Map
                                                                                                                    .of(
                                                                                                                            "metrics",
                                                                                                                            Integer
                                                                                                                                    .toString(
                                                                                                                                            report.results()
                                                                                                                                                    .size()),
                                                                                                                            "passed",
                                                                                                                            Boolean
                                                                                                                                    .toString(
                                                                                                                                            report
                                                                                                                                                    .passed())))))
                                                            .thenMany(Flux.empty())))
                            .contextWrite(context -> context.put(captureKey, capture));
                });
    }

    @Override
    public Flux<AgentEvent> onModelCall(
            Agent agent,
            RuntimeContext ctx,
            ModelCallInput input,
            Function<ModelCallInput, Flux<AgentEvent>> next) {
        return Flux.deferContextual(
                context -> {
                    if (context.hasKey(captureKey)) {
                        Capture capture = context.get(captureKey);
                        capture.observe(input);
                    }
                    return next.apply(input);
                });
    }

    private final class Capture {
        final String id = UUID.randomUUID().toString();
        final List<String> inputIds;
        final List<Object> toolSnapshots = new ArrayList<>();
        JevTrace latest;
        String stopped;
        boolean finished;

        Capture(List<Msg> input) {
            inputIds = input.stream().map(Msg::getId).toList();
        }

        synchronized void observe(ModelCallInput input) {
            if (stopped != null) return;
            try {
                int begin = -1;
                for (int i = 0; i < input.messages().size(); i++)
                    if (inputIds.contains(input.messages().get(i).getId())) {
                        begin = i;
                        break;
                    }
                if (begin < 0) {
                    stop("INVOCATION_BOUNDARY_MISSING");
                    return;
                }
                var messages = input.messages().subList(begin, input.messages().size());
                var snapshot = JevTrace.fromMessages(id, messages, input.tools());
                if (latest != null
                        && !snapshot.list("tool_calls").containsAll(latest.list("tool_calls"))) {
                    stop("TRACE_CHANGED_OR_COMPACTED");
                    return;
                }
                toolSnapshots.add(
                        Map.of(
                                "after_message_count",
                                messages.size(),
                                "tools",
                                snapshot.fields().get("available_tools")));
                snapshot = snapshot.with("available_tool_snapshots", toolSnapshots);
                if (TraceData.size(snapshot.fields()) > maxCaptureChars) {
                    stop("CAPTURE_LIMIT");
                    return;
                }
                latest = snapshot;
            } catch (RuntimeException e) {
                stop("CAPTURE_ERROR");
            }
        }

        synchronized void finish(Msg result) {
            finished = true;
            if (stopped != null || latest == null) return;
            try {
                var finalPart = JevTrace.fromMessages(id, List.of(result), List.of());
                if (!finalPart.issues().isEmpty()) {
                    stop("UNSUPPORTED_FINAL_RESULT");
                    return;
                }
                latest = latest.with("final_answer", result.getTextContent());
                if (TraceData.size(latest.fields()) > maxCaptureChars) stop("CAPTURE_LIMIT");
            } catch (RuntimeException e) {
                stop("CAPTURE_ERROR");
            }
        }

        private void stop(String reason) {
            stopped = reason;
            latest = null;
            toolSnapshots.clear();
        }

        synchronized JevTrace trace() {
            if (stopped != null) return new JevTrace(id, Map.of(), List.of(stopped));
            if (!finished || latest == null)
                return new JevTrace(id, Map.of(), List.of("INCOMPLETE_INVOCATION"));
            return latest;
        }
    }
}
