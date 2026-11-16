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

package io.agentscope.durable.worker;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

import io.agentscope.durable.DurableJson;
import io.agentscope.durable.DurableTypes.ExecutionRequest;
import io.agentscope.durable.DurableTypes.ExecutionResult;
import io.agentscope.durable.DurableTypes.ExecutionState;
import io.agentscope.durable.DurableTypes.OutputPage;
import io.agentscope.durable.DurableTypes.RunStepRef;
import io.agentscope.durable.DurableTypes.SessionKey;
import io.agentscope.durable.DurableTypes.ThreadKey;
import io.agentscope.durable.DurableTypes.ToolCall;
import io.agentscope.durable.Executor;
import io.agentscope.extensions.durable.jdbc.JdbcRecordStore;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Base64;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class BatchExecutorTest {
    @TempDir Path directory;

    ExecutionRequest request() {
        return new ExecutionRequest(
                "batch",
                new ThreadKey(new SessionKey("tenant", "session"), "main"),
                "turn",
                new ToolCall(
                        "batch-call",
                        "batch",
                        "batch",
                        DurableJson.value(
                                Map.of(
                                        "calls",
                                        List.of(
                                                new ToolCall(
                                                        "one",
                                                        "mcp",
                                                        "first",
                                                        DurableJson.value(Map.of())),
                                                new ToolCall(
                                                        "two",
                                                        "mcp",
                                                        "second",
                                                        DurableJson.value(Map.of())))))));
    }

    @Test
    void parallelManifestBoundsOutstandingCallsAndReplaysAttributedOutputAcrossReplacement()
            throws Exception {
        var started = new HashSet<String>();
        var finished = new HashSet<String>();
        var cancelled = new HashSet<String>();
        Executor provider =
                new Executor() {
                    public Capabilities capabilities() {
                        return new Capabilities(false, true, true, true);
                    }

                    public ExecutionResult startExecution(ExecutionRequest call) {
                        assertTrue(started.add(call.call().name()), "A child was dispatched twice");
                        return query(call, null);
                    }

                    public ExecutionResult query(ExecutionRequest call, String handle) {
                        var state =
                                finished.contains(call.call().name())
                                        ? ExecutionState.SUCCEEDED
                                        : cancelled.contains(call.call().name())
                                                ? ExecutionState.CANCELLED
                                                : ExecutionState.RUNNING;
                        return new ExecutionResult(
                                state,
                                call.executionId(),
                                DurableJson.value(call.call().name()),
                                null);
                    }

                    public ExecutionResult cancel(ExecutionRequest call, String handle) {
                        cancelled.add(call.call().name());
                        return query(call, handle);
                    }

                    public OutputPage output(ExecutionRequest call, long offset) {
                        boolean terminal = query(call, null).state().terminal();
                        var data =
                                terminal
                                        ? call.call().name().getBytes(StandardCharsets.UTF_8)
                                        : new byte[0];
                        if (offset > 0)
                            return new OutputPage(offset, offset, "", offset, terminal, null);
                        return new OutputPage(
                                0,
                                data.length,
                                Base64.getEncoder().encodeToString(data),
                                data.length,
                                terminal,
                                null);
                    }
                };
        var base = request();
        var calls = new ArrayList<ToolCall>();
        for (String name : List.of("first", "second", "third", "fourth"))
            calls.add(new ToolCall(name, "mcp", name, DurableJson.value(Map.of())));
        var request =
                new ExecutionRequest(
                        base.executionId(),
                        base.thread(),
                        base.turnId(),
                        new ToolCall(
                                        "batch-call",
                                        "batch",
                                        "batch",
                                        DurableJson.value(Map.of("calls", calls)))
                                .withOrigin(new RunStepRef("parent-run", "parent-step")));
        try (var records = JdbcRecordStore.sqlite(directory.resolve("parallel.db"));
                var children =
                        WorkerRuntime.builder()
                                .recordStore(records)
                                .executor("mcp", provider)
                                .pollInterval(Duration.ofMillis(1))
                                .build()) {
            var first =
                    new BatchExecutor(
                            records, children.localExecutor(), BatchExecutor.Mode.PARALLEL, 2);
            first.startExecution(request);
            children.tick();
            assertEquals(Set.of("first", "second"), started);
            assertEquals(ExecutionState.RUNNING, first.query(request, null).state());
            assertEquals(4, first.manifest(request).size());
            first.manifest(request)
                    .forEach(child -> assertEquals(request.call().origin(), child.call().origin()));
            finished.add("first");
            Thread.sleep(5);
            children.tick();
            // The frozen admission policy survives a host changing its default concurrency.
            var replacement = new BatchExecutor(records, children.localExecutor());
            replacement.query(request, null);
            var third = replacement.manifest(request).get(2);
            assertEquals(
                    ExecutionState.PENDING, children.localExecutor().query(third, null).state());
            replacement.cancel(request, null);
            children.tick();
            var result = replacement.query(request, null);
            assertEquals(ExecutionState.CANCELLED, result.state());
            assertEquals(Set.of("first", "second"), started);
            assertEquals(
                    "SUCCEEDED",
                    result.result().path("results").get(0).path("result").path("state").asText());
            long offset = 0;
            var outputCalls = new HashSet<String>();
            for (int i = 0; i < 10; i++) {
                var page = replacement.output(request, offset);
                if (!page.data().isEmpty()) {
                    var line =
                            DurableJson.parse(
                                    new String(
                                            Base64.getDecoder().decode(page.data()),
                                            StandardCharsets.UTF_8));
                    assertEquals("durable.batch.output.v1", line.path("format").asText());
                    outputCalls.add(line.path("callId").asText());
                }
                offset = page.nextOffset();
                if (page.terminal()) break;
            }
            assertTrue(replacement.output(request, offset).terminal());
            assertEquals(Set.of("first", "second", "third"), outputCalls);
            assertEquals(result, replacement.startExecution(request));
        }
    }

    @Test
    void partialBatchSurvivesReplacementAndCancellationDoesNotStartRemainingCalls() {
        var starts = new AtomicInteger();
        Executor provider =
                new Executor() {
                    public Capabilities capabilities() {
                        return new Capabilities(false, false, false);
                    }

                    public ExecutionResult startExecution(ExecutionRequest request) {
                        starts.incrementAndGet();
                        return new ExecutionResult(
                                ExecutionState.SUCCEEDED,
                                request.executionId(),
                                DurableJson.value(request.call().name()),
                                null);
                    }

                    public ExecutionResult query(ExecutionRequest request, String handle) {
                        return ExecutionResult.unknown("uncertain");
                    }

                    public ExecutionResult cancel(ExecutionRequest request, String handle) {
                        return ExecutionResult.unknown("uncertain");
                    }
                };
        try (var records = JdbcRecordStore.sqlite(directory.resolve("batch.db"));
                var worker =
                        new WorkerRuntime(
                                new TransactionalExecutionStore(records),
                                Map.of("mcp", provider),
                                Duration.ofSeconds(5),
                                Duration.ofMillis(1),
                                1,
                                error -> fail(error))) {
            var first = new BatchExecutor(records, worker.localExecutor());
            assertEquals(ExecutionState.RUNNING, first.startExecution(request()).state());
            worker.tick();
            assertEquals(1, starts.get());
            var replacement = new BatchExecutor(records, worker.localExecutor());
            var cancelled = replacement.cancel(request(), null);
            assertEquals(ExecutionState.CANCELLED, cancelled.state());
            assertEquals(
                    ExecutionState.SUCCEEDED.name(),
                    cancelled
                            .result()
                            .path("results")
                            .get(0)
                            .path("result")
                            .path("state")
                            .asText());
            assertEquals(
                    ExecutionState.CANCELLED.name(),
                    cancelled
                            .result()
                            .path("results")
                            .get(1)
                            .path("result")
                            .path("state")
                            .asText());
            worker.tick();
            assertEquals(1, starts.get());
            assertEquals(cancelled, replacement.startExecution(request()));
        }
    }
}
