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

package io.agentscope.extensions.durable.mcp;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.agentscope.core.tool.mcp.McpClientWrapper;
import io.agentscope.durable.DurableException;
import io.agentscope.durable.DurableJson;
import io.agentscope.durable.DurableTypes.ExecutionRequest;
import io.agentscope.durable.DurableTypes.ExecutionState;
import io.agentscope.durable.DurableTypes.SessionKey;
import io.agentscope.durable.DurableTypes.ThreadKey;
import io.agentscope.durable.ExecutionMessages;
import io.agentscope.durable.ToolBinding;
import io.agentscope.durable.worker.TransactionalExecutionStore;
import io.agentscope.durable.worker.WorkerRuntime;
import io.agentscope.extensions.durable.jdbc.JdbcRecordStore;
import io.modelcontextprotocol.spec.McpSchema;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import reactor.core.publisher.Mono;

class McpRecoveryTest {
    @TempDir Path directory;

    static McpSchema.Tool descriptor(
            String name, boolean readOnly, boolean recoverable, boolean idempotent) {
        var value =
                (ObjectNode)
                        DurableJson.value(
                                Map.of(
                                        "name",
                                        name,
                                        "description",
                                        name,
                                        "inputSchema",
                                        Map.of("type", "object"),
                                        "annotations",
                                        Map.of("readOnlyHint", readOnly)));
        if (recoverable)
            value.set(
                    "_meta",
                    DurableJson.value(
                            Map.of(
                                    McpRecovery.META_KEY,
                                    Map.of(
                                            "version",
                                            1,
                                            "queryTool",
                                            "query",
                                            "cancelTool",
                                            "cancel",
                                            "idempotentStart",
                                            idempotent))));
        return DurableJson.convert(value, McpSchema.Tool.class);
    }

    static class Fixture implements AutoCloseable {
        final McpClientWrapper client = mock(McpClientWrapper.class);
        final AtomicReference<List<McpSchema.Tool>> tools;
        final McpConnections pool;
        final ToolBinding binding;
        final ExecutionRequest request;
        final AtomicInteger starts = new AtomicInteger();
        final AtomicReference<McpSchema.CallToolResult> observation = new AtomicReference<>();

        Fixture(boolean idempotent) {
            var job = descriptor("job", false, true, idempotent);
            tools =
                    new AtomicReference<>(
                            List.of(
                                    job,
                                    descriptor("query", true, false, false),
                                    descriptor("cancel", false, false, false)));
            when(client.isInitialized()).thenReturn(true);
            when(client.isPropagateMeta()).thenReturn(true);
            when(client.listTools()).thenAnswer(invocation -> Mono.just(tools.get()));
            pool = new McpConnections(2, Duration.ofMinutes(1), config -> client);
            binding =
                    McpExecutor.binding(
                            "alias",
                            "v1",
                            "mcp",
                            DurableJson.value(Map.of("name", "server")),
                            job,
                            tools.get());
            request =
                    new ExecutionRequest(
                            "execution",
                            new ThreadKey(new SessionKey("tenant", "session"), "main"),
                            "turn",
                            binding.call("call", DurableJson.value(Map.of())));
            when(client.callTool(eq("job"), anyMap(), anyMap()))
                    .thenAnswer(
                            invocation -> {
                                starts.incrementAndGet();
                                var meta =
                                        DurableJson.value(invocation.getArgument(2))
                                                .required(McpRecovery.META_KEY);
                                assertEquals(
                                        ExecutionMessages.identity(request),
                                        meta.path("executionId").asText());
                                assertEquals(
                                        DurableJson.hash(request),
                                        meta.path("requestDigest").asText());
                                return Mono.error(
                                        new IllegalStateException("effect admitted, reply lost"));
                            });
            for (String name : List.of("query", "cancel"))
                when(client.callTool(eq(name), anyMap(), anyMap()))
                        .thenAnswer(
                                invocation -> {
                                    var args = DurableJson.value(invocation.getArgument(1));
                                    assertEquals(
                                            ExecutionMessages.identity(request),
                                            args.path("executionId").asText());
                                    assertEquals(
                                            DurableJson.hash(request),
                                            args.path("requestDigest").asText());
                                    return Mono.just(observation.get());
                                });
            observation.set(result(ExecutionState.RUNNING, null));
        }

        McpExecutor executor() {
            return new McpExecutor(
                    binding,
                    request ->
                            McpExecutor.Target.leased(
                                    pool.borrow("tenant/server", DurableJson.value(Map.of())),
                                    "job",
                                    Map.of()),
                    Duration.ofSeconds(1));
        }

        McpSchema.CallToolResult result(ExecutionState state, JsonNode terminal) {
            var content =
                    (ObjectNode)
                            DurableJson.value(
                                    Map.of(
                                            "version",
                                            1,
                                            "executionId",
                                            ExecutionMessages.identity(request),
                                            "requestDigest",
                                            DurableJson.hash(request),
                                            "state",
                                            state.name()));
            if (terminal != null) content.set("result", terminal);
            return DurableJson.convert(
                    DurableJson.value(
                            Map.of(
                                    "content",
                                    List.of(),
                                    "isError",
                                    false,
                                    "structuredContent",
                                    content)),
                    McpSchema.CallToolResult.class);
        }

        public void close() {
            pool.close();
        }
    }

    @Test
    void workerReplacementRecoversOriginalCallAfterLostReplyAndPreservesStructuredResult()
            throws Exception {
        try (var fixture = new Fixture(true);
                var records = JdbcRecordStore.sqlite(directory.resolve("mcp.db"))) {
            var store = new TransactionalExecutionStore(records);
            try (var worker =
                    WorkerRuntime.builder()
                            .executionStore(store)
                            .executor("mcp", fixture.executor())
                            .pollInterval(Duration.ofMillis(1))
                            .build()) {
                worker.localExecutor().startExecution(fixture.request);
                worker.tick();
                assertEquals(
                        ExecutionState.UNKNOWN,
                        worker.localExecutor().query(fixture.request, null).state());
                assertEquals(0, fixture.pool.status().borrowed());
            }
            fixture.observation.set(
                    fixture.result(
                            ExecutionState.SUCCEEDED,
                            DurableJson.value(
                                    Map.of(
                                            "content",
                                            List.of(Map.of("type", "text", "text", "done")),
                                            "isError",
                                            false,
                                            "structuredContent",
                                            Map.of("answer", 42)))));
            Thread.sleep(5);
            try (var replacement =
                    WorkerRuntime.builder()
                            .executionStore(store)
                            .executor("mcp", fixture.executor())
                            .pollInterval(Duration.ofMillis(1))
                            .build()) {
                replacement.tick();
                var outcome = replacement.localExecutor().query(fixture.request, null);
                assertEquals(ExecutionState.SUCCEEDED, outcome.state());
                assertEquals(
                        42,
                        outcome.result()
                                .path("result")
                                .path("structuredContent")
                                .path("answer")
                                .asInt());
                assertEquals(1, fixture.starts.get());
                assertEquals(0, fixture.pool.status().borrowed());
            }
        }
    }

    @Test
    void descriptorDriftBlocksNewStartButNotFrozenCancellationAndQuery() {
        try (var fixture = new Fixture(true)) {
            var executor = fixture.executor();
            executor.validateStart(fixture.request);
            fixture.tools.set(
                    List.of(
                            descriptor("job", false, false, false),
                            descriptor("query", true, false, false),
                            descriptor("cancel", false, false, false)));
            assertEquals(
                    DurableException.Code.INCOMPATIBLE,
                    assertThrows(
                                    DurableException.class,
                                    () -> executor.validateStart(fixture.request))
                            .code());
            assertEquals(ExecutionState.RUNNING, executor.cancel(fixture.request, null).state());
            fixture.observation.set(fixture.result(ExecutionState.CANCELLED, null));
            assertEquals(ExecutionState.CANCELLED, executor.query(fixture.request, null).state());
            assertThrows(
                    DurableException.class,
                    () -> executor.query(fixture.request, "another-operation"));
            assertEquals(0, fixture.starts.get());
            assertEquals(0, fixture.pool.status().borrowed());
        }
    }

    @Test
    void untrustedOrInconsistentRecoveryDoesNotBecomeAFailureOrARepeatedExecution() {
        try (var fixture = new Fixture(false)) {
            var executor = fixture.executor();
            assertFalse(executor.capabilities().idempotentStart());
            when(fixture.client.isPropagateMeta()).thenReturn(false);
            assertThrows(DurableException.class, () -> executor.validateStart(fixture.request));
            verify(fixture.client, never()).callTool(eq("job"), anyMap(), anyMap());
            when(fixture.client.isPropagateMeta()).thenReturn(true);
            var malformed =
                    (ObjectNode)
                            DurableJson.value(
                                    fixture.result(
                                            ExecutionState.SUCCEEDED,
                                            DurableJson.value(
                                                    Map.of(
                                                            "content", List.of(), "isError",
                                                            false))));
            ((ObjectNode) malformed.required("structuredContent"))
                    .put("executionId", "another-operation");
            fixture.observation.set(DurableJson.convert(malformed, McpSchema.CallToolResult.class));
            assertEquals(ExecutionState.UNKNOWN, executor.query(fixture.request, null).state());
            fixture.observation.set(
                    fixture.result(
                            ExecutionState.FAILED,
                            DurableJson.value(Map.of("content", List.of(), "isError", false))));
            assertEquals(ExecutionState.UNKNOWN, executor.query(fixture.request, null).state());
            fixture.observation.set(fixture.result(ExecutionState.NOT_FOUND, null));
            assertEquals(ExecutionState.NOT_FOUND, executor.query(fixture.request, null).state());
            assertEquals(0, fixture.starts.get());
        }
    }

    @Test
    void discoveryRequiresAnExplicitReadOnlyLookupAndFrozenControlDescriptors() {
        var job = descriptor("job", false, true, true);
        assertThrows(
                IllegalArgumentException.class,
                () ->
                        McpExecutor.binding(
                                "alias",
                                "v1",
                                "mcp",
                                DurableJson.value(Map.of()),
                                job,
                                List.of(
                                        job,
                                        descriptor("query", false, false, false),
                                        descriptor("cancel", false, false, false))));
        try (var fixture = new Fixture(true)) {
            fixture.tools.set(
                    List.of(
                            job,
                            descriptor("query", false, false, false),
                            descriptor("cancel", false, false, false)));
            assertThrows(
                    DurableException.class, () -> fixture.executor().query(fixture.request, null));
            assertEquals(0, fixture.pool.status().borrowed());
            assertTrue(fixture.executor().capabilities().lookupByExecutionId());
        }
    }
}
