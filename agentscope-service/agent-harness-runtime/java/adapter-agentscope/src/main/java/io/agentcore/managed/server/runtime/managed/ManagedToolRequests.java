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
package io.agentcore.managed.server.runtime.managed;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import io.agentcore.managed.server.runtime.managed.ManagedMessageBus.Message;
import io.agentcore.managed.server.runtime.managed.ManagedRuntimeBatch.ToolIntent;
import io.agentscope.core.agent.RuntimeContext;
import io.agentscope.core.message.ToolUseBlock;
import io.agentscope.core.session.SessionLogException;
import io.micrometer.core.instrument.Metrics;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** HarnessRuntime-side durable request bridge. Sandbox execution stays on Tool Worker. */
public final class ManagedToolRequests {
    private static final Logger log = LoggerFactory.getLogger(ManagedToolRequests.class);

    public static final class Operations {
        private final Map<String, AtomicInteger> sequence = new ConcurrentHashMap<>();
        private final boolean recoveryOnly;

        public Operations() {
            this(false);
        }

        public Operations(boolean recoveryOnly) {
            this.recoveryOnly = recoveryOnly;
        }

        int next(String call) {
            return sequence.computeIfAbsent(call, ignored -> new AtomicInteger()).getAndIncrement();
        }
    }

    private final ObjectMapper mapper;
    private final Duration inlineWait;
    private final ManagedToolResultDelivery results;
    private final ManagedBatchCodec codec;

    public ManagedToolRequests(
            ObjectMapper mapper, ManagedToolResultDelivery results, ManagedBatchCodec codec) {
        this(mapper, Duration.ofSeconds(5), results, codec);
    }

    public ManagedToolRequests(
            ObjectMapper mapper,
            Duration inlineWait,
            ManagedToolResultDelivery results,
            ManagedBatchCodec codec) {
        this.mapper = mapper.copy().enable(SerializationFeature.ORDER_MAP_ENTRIES_BY_KEYS);
        ManagedMessageBus.positiveMillis(inlineWait);
        this.inlineWait = inlineWait;
        this.results = results;
        this.codec = codec;
    }

    public <T> T request(
            RuntimeContext context,
            String operation,
            Map<String, Object> parameters,
            Class<T> resultType) {
        var execution = context == null ? null : context.get(ManagedTurnExecution.class);
        if (execution == null)
            throw new IllegalStateException(
                    "Tool Worker request requires active Managed execution");
        var call = context.get(ToolUseBlock.class);
        var counter = context.get(Operations.class);
        if (counter == null)
            throw new IllegalStateException(
                    "Tool Worker request requires call-scoped operation identity");
        String nativeCall = call == null ? execution.local().frameworkCall() : call.getId();
        String callId = nativeCall + "_" + counter.next(nativeCall);
        String taskId =
                "tool_"
                        + ManagedScope.fingerprint(
                                execution.scope().messageGroup(), execution.turnId(), callId);
        var payload = new LinkedHashMap<String, Object>(parameters);
        payload.put(
                "native_tool_call_id",
                ManagedMcpBatch.OPERATION.equals(operation)
                        ? mapper.convertValue(parameters, ManagedMcpBatch.Manifest.class)
                                .calls()
                                .get(0)
                                .callId()
                        : nativeCall);
        String arguments = json(payload);
        try {
            execution.check();
            var original =
                    call == null ? execution.local().frameworkTask(nativeCall, taskId) : null;
            if (counter.recoveryOnly || original != null) {
                var task =
                        original != null
                                ? original
                                : execution
                                        .journal()
                                        .tool(execution.scope(), taskId)
                                        .orElseThrow(
                                                () ->
                                                        new ManagedStateException(
                                                                "tool_reconciliation_required",
                                                                "Original Tool Worker operation was"
                                                                    + " not durably dispatched"));
                if (!operation.equals(task.name()) || !arguments.equals(task.arguments()))
                    throw new ManagedStateException(
                            "tool_content_conflict",
                            "Recovered operation differs from the original");
                if (task.result() != null) {
                    T value = mapper.readValue(task.result(), resultType);
                    execution.toolCompleted(taskId, task.result());
                    return value;
                }
            } else {
                execution.boundary(
                        "intent_" + taskId,
                        List.of(),
                        List.of(new ToolIntent(callId, operation, arguments)),
                        List.of(),
                        null);
            }
            long deadline = System.nanoTime() + inlineWait.toNanos();
            while (true) {
                execution.guard().check();
                if (System.nanoTime() >= deadline) throw new ManagedToolWaiting(taskId);
                long remainingMillis =
                        Math.max(1, Duration.ofNanos(deadline - System.nanoTime()).toMillis());
                Optional<Message> message;
                try {
                    message =
                            results.await(
                                    execution.scope(),
                                    taskId,
                                    Duration.ofMillis(Math.min(200, remainingMillis)));
                } catch (ManagedReadCapacityException unavailable) {
                    // The intent is already durable. Lack of optional inline-read capacity is
                    // a normal yield; the suspension commit still checks the execution fence.
                    execution.guard().check();
                    Metrics.counter("managed.agents.runtime.inline.read_capacity_yields")
                            .increment();
                    ManagedRuntimeLog.info(
                            log,
                            "harness_runtime.tool.inline_yield",
                            execution.scope(),
                            execution.turnId(),
                            "task_id",
                            taskId,
                            "reason",
                            "read_capacity");
                    throw new ManagedToolWaiting(taskId);
                }
                if (message.isPresent()) {
                    var result = codec.decodeToolResult(message.get());
                    if (!execution.scope().equals(result.scope())
                            || !taskId.equals(result.taskId())
                            || !taskId.equals(result.executionId()))
                        throw new ManagedStateException(
                                "tool_execution_mismatch",
                                "Received result belongs to another operation");
                    T value = mapper.readValue(result.result(), resultType);
                    execution.toolCompleted(taskId, result.result());
                    return value;
                }
                if (Thread.currentThread().isInterrupted())
                    throw new ManagedTransportException(
                            "Tool Worker request interrupted before confirmed outcome");
            }
        } catch (RuntimeException | JsonProcessingException unknown) {
            var waiting = ManagedToolWaiting.find(unknown);
            if (waiting != null) throw waiting;
            execution.guard().invalidate("Tool Worker operation outcome is unconfirmed", unknown);
            throw new SessionLogException(
                    "Tool Worker operation cannot safely advance inference", unknown);
        }
    }

    private String json(Object value) {
        try {
            return mapper.writeValueAsString(value);
        } catch (JsonProcessingException invalid) {
            throw new IllegalArgumentException("Cannot encode Tool Worker request", invalid);
        }
    }
}
