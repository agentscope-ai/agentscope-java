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
package io.agentscope.builder.hands;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.agentscope.builder.control.SessionResolveResult;
import io.agentscope.builder.runtime.managed.InMemoryManagedInputInbox;
import io.agentscope.builder.runtime.managed.InMemoryManagedLeaseStore;
import io.agentscope.builder.runtime.managed.InMemoryManagedMessageBus;
import io.agentscope.builder.runtime.managed.ManagedAdmissionStore;
import io.agentscope.builder.runtime.managed.ManagedBatchCodec;
import io.agentscope.builder.runtime.managed.ManagedDeliverySuspended;
import io.agentscope.builder.runtime.managed.ManagedHandsCommands;
import io.agentscope.builder.runtime.managed.ManagedHandsStore;
import io.agentscope.builder.runtime.managed.ManagedJournalStore.ToolTask;
import io.agentscope.builder.runtime.managed.ManagedMcpBatch;
import io.agentscope.builder.runtime.managed.ManagedMcpCatalog;
import io.agentscope.builder.runtime.managed.ManagedMcpCatalog.Recovery;
import io.agentscope.builder.runtime.managed.ManagedMessageBus.Channel;
import io.agentscope.builder.runtime.managed.ManagedPushDelivery;
import io.agentscope.builder.runtime.managed.ManagedScope;
import io.agentscope.builder.runtime.managed.ManagedStateException;
import io.agentscope.builder.runtime.managed.ManagedToolCommand;
import io.agentscope.builder.runtime.managed.ManagedToolCommitGate.Decision;
import io.agentscope.builder.runtime.managed.ManagedToolExecutions;
import io.agentscope.builder.runtime.managed.ManagedToolResult;
import io.agentscope.builder.runtime.managed.ManagedToolResultPublisher;
import io.agentscope.builder.runtime.managed.ManagedToolTarget;
import io.agentscope.builder.web.managed.service.VaultCrypto;
import io.agentscope.core.tool.mcp.McpClientWrapper;
import io.agentscope.core.tool.mcp.McpTool;
import io.agentscope.harness.agent.tools.McpServerConfig;
import io.agentscope.harness.agent.tools.ToolsConfig;
import io.modelcontextprotocol.spec.McpSchema;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BooleanSupplier;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.mock.env.MockEnvironment;
import reactor.core.publisher.Mono;
import reactor.core.publisher.MonoSink;

/** Controlled MCP endpoint, durable Hands facts, and replacements without a second external call. */
@Timeout(15)
class ManagedMcpExecutorsTest {
    @TempDir Path root;
    final ObjectMapper mapper = new ObjectMapper().findAndRegisterModules();
    final ManagedScope scope = new ManagedScope("tenant", "namespace", "session");
    final InMemoryManagedMessageBus bus =
            new InMemoryManagedMessageBus(System::currentTimeMillis, 5);
    final InMemoryManagedLeaseStore leases =
            new InMemoryManagedLeaseStore(System::currentTimeMillis);
    final ScheduledExecutorService renewals = Executors.newScheduledThreadPool(2);
    final McpClientWrapper client = mock(McpClientWrapper.class);
    final VaultCrypto crypto =
            new VaultCrypto(new MockEnvironment(), "01234567890123456789012345678901");
    ManagedHandsStore store;
    ManagedToolResultPublisher results;
    ManagedMcpExecutors mcp;
    ManagedBatchCodec codec;
    String sealed;
    McpSchema.Tool remote;
    ManagedMcpCatalog.Tool descriptor;

    @BeforeEach
    void setup() throws Exception {
        var source =
                new DriverManagerDataSource(
                        "jdbc:h2:mem:mcp-hands-"
                                + UUID.randomUUID()
                                + ";MODE=PostgreSQL;DB_CLOSE_DELAY=-1",
                        "sa",
                        "");
        ManagedAdmissionStore.initialize(source);
        store = new ManagedHandsStore(source, mapper);
        codec = new ManagedBatchCodec(mapper, root);
        results = new ManagedToolResultPublisher(store, bus, codec);
        var config = new McpServerConfig();
        config.setTransport("http");
        config.setUrl("http://mcp.invalid");
        config.setHeaders(Map.of("Authorization", "Bearer private-test-token"));
        config.setPrefixToolNames(true);
        var tools = new ToolsConfig();
        tools.setMcpServers(Map.of("server", config));
        var minimal =
                new SessionResolveResult(
                        null, Map.of(), null, null, null, Map.of(), null, List.of(), List.of(),
                        null, Map.of());
        sealed = crypto.encrypt(mapper.writeValueAsString(new ManagedToolTarget(minimal, tools)));
        remote =
                new McpSchema.Tool(
                        "echo",
                        null,
                        "Echo",
                        new McpSchema.JsonSchema(
                                "object",
                                Map.of("value", Map.of("type", "string")),
                                List.of("value"),
                                null,
                                null,
                                null),
                        null,
                        null,
                        null);
        descriptor =
                new ManagedMcpCatalog.Tool(
                        "server",
                        "server__echo",
                        "echo",
                        "Echo",
                        McpTool.convertMcpSchemaToParameters(remote.inputSchema(), Set.of()),
                        null,
                        false);
        when(client.listTools()).thenReturn(Mono.just(List.of(remote)));
        when(client.getCachedTool("echo")).thenReturn(remote);
        when(client.isPropagateMeta()).thenReturn(true);
        mcp = new ManagedMcpExecutors(store, results, mapper, crypto, (name, cfg) -> client);
    }

    @AfterEach
    void stop() {
        mcp.close();
        results.close();
        renewals.shutdownNow();
        bus.close();
    }

    ToolTask task(String id, String operation, String hash) throws Exception {
        String args =
                operation.equals("mcp.describe")
                        ? "{}"
                        : mapper.writeValueAsString(
                                Map.of(
                                        "server",
                                        "server",
                                        "tool",
                                        "echo",
                                        "descriptor_hash",
                                        hash,
                                        "arguments",
                                        Map.of("value", "hello"),
                                        "meta",
                                        Map.of("trace", "original")));
        if (operation.equals("mcp.call") && descriptor.recovery() != null) {
            var enriched = mapper.readValue(args, LinkedHashMap.class);
            enriched.put("recovery", descriptor.recovery());
            args = mapper.writeValueAsString(enriched);
        }
        var value = new ToolTask(id, "turn", id, id, operation, args, 1, "ready", null, null, null);
        store.receive(new ManagedToolCommand(scope, "execute_" + id, "execute", value, sealed));
        return value;
    }

    ManagedHandsCommands driver(ManagedMcpExecutors executor) {
        var work =
                new ManagedToolExecutions(
                        leases,
                        new InMemoryManagedInputInbox(System::currentTimeMillis),
                        store,
                        results,
                        renewals,
                        Duration.ofSeconds(5),
                        Duration.ofMillis(500),
                        Duration.ofMillis(10));
        // Provider tests drive bounded collection segments explicitly; production uses the durable
        // loop.
        return new ManagedHandsCommands(
                store,
                codec,
                results,
                (owner, taskId) -> {
                    var provider = spy(executor.executor(owner, taskId));
                    doReturn(false).when(provider).waitForCompletion(any());
                    work.execute(owner, taskId, provider);
                },
                ignoredCommand -> Decision.COMMITTED);
    }

    void segment(ManagedHandsCommands driver, ToolTask task) {
        var command =
                new ManagedToolCommand(scope, "execute_" + task.id(), "execute", task, sealed);
        try {
            driver.consume(
                    new ManagedPushDelivery(
                            "delivery",
                            "consumer",
                            Channel.TOOL_EXECUTE,
                            "topic",
                            "group",
                            "broker",
                            1,
                            codec.encode(command)));
        } catch (ManagedDeliverySuspended waiting) {
            /* Expected normal transport wait. */
        }
    }

    ToolTask batch(String id, int size) throws Exception {
        var calls = new ArrayList<ManagedMcpBatch.Call>();
        for (int i = 0; i < size; i++) {
            var parameters = new LinkedHashMap<String, Object>();
            parameters.put("server", "server");
            parameters.put("tool", "echo");
            parameters.put("descriptor_hash", ManagedMcpCatalog.descriptorHash(descriptor, mapper));
            parameters.put("arguments", Map.of("value", "value-" + i));
            parameters.put("meta", Map.of());
            if (descriptor.recovery() != null) parameters.put("recovery", descriptor.recovery());
            calls.add(new ManagedMcpBatch.Call("call-" + i, "server__echo", parameters));
        }
        var task =
                new ToolTask(
                        id,
                        "turn",
                        id,
                        id,
                        ManagedMcpBatch.OPERATION,
                        mapper.writeValueAsString(new ManagedMcpBatch.Manifest(calls)),
                        1,
                        "ready",
                        null,
                        null,
                        null);
        store.receive(new ManagedToolCommand(scope, "execute_" + id, "execute", task, sealed));
        return task;
    }

    @Test
    void batchReplacementReusesPartialResultAndQueriesOriginalRunningCall() throws Exception {
        recoverable();
        var task = batch("batch-failover", 2);
        var first =
                new McpSchema.CallToolResult(
                        List.of(new McpSchema.TextContent("first-original")), false);
        var second =
                new McpSchema.CallToolResult(
                        List.of(new McpSchema.TextContent("second-original")), false);
        when(client.callTool(eq("echo"), anyMap(), anyMap()))
                .thenAnswer(
                        invocation -> {
                            Map<String, Object> arguments = invocation.getArgument(1);
                            return "value-0".equals(arguments.get("value"))
                                    ? Mono.just(first)
                                    : Mono.never();
                        });
        var driver = driver(mcp);
        await(
                () -> {
                    segment(driver, task);
                    return store.tool(
                                    scope,
                                    "mcp_child_" + ManagedScope.fingerprint(task.id(), "call-1"))
                            .map(child -> "running".equals(child.status()))
                            .orElse(false);
                });
        String secondId = "mcp_child_" + ManagedScope.fingerprint(task.id(), "call-1");
        var running = store.tool(scope, secondId).orElseThrow();
        assertEquals(
                0,
                store.pendingExecutions(256, Long.MAX_VALUE, 1, 1).stream()
                        .filter(ref -> ref.taskId().startsWith("mcp_child_"))
                        .count());
        assertEquals(
                0,
                store.pendingToolResultPublications(256, Long.MAX_VALUE).stream()
                        .filter(ref -> ref.taskId().startsWith("mcp_child_"))
                        .count());
        replace();
        when(client.callTool(eq("execution_get"), anyMap(), isNull()))
                .thenReturn(Mono.just(lookup(running, "running", null)));
        segment(driver(mcp), task);
        assertTrue(store.stagedToolResult(scope, task.id()).isEmpty());
        when(client.callTool(eq("execution_get"), anyMap(), isNull()))
                .thenReturn(Mono.just(lookup(running, "succeeded", second)));
        segment(driver(mcp), task);
        assertEquals("succeeded", store.tool(scope, task.id()).orElseThrow().status());
        var aggregate =
                ManagedMcpBatch.result(
                        store.stagedToolResult(scope, task.id()).orElseThrow().result());
        assertTrue(aggregate.require("call-0").result().contains("first-original"));
        assertTrue(aggregate.require("call-1").result().contains("second-original"));
        verify(client, times(2)).callTool(eq("echo"), anyMap(), anyMap());
        assertTrue(store.toolEvents().cursor(scope, running.id()).isEmpty());
        assertTrue(store.toolEvents().cursor(scope, task.id()).orElseThrow().captureComplete());
    }

    @Test
    void batchCancellationPreservesCompletedAndRunningResultsAndSkipsUnstartedCall()
            throws Exception {
        var task = batch("batch-cancel", 3);
        var secondSink = new AtomicReference<MonoSink<McpSchema.CallToolResult>>();
        var response =
                new McpSchema.CallToolResult(
                        List.of(new McpSchema.TextContent("completed")), false);
        when(client.callTool(eq("echo"), anyMap(), anyMap()))
                .thenAnswer(
                        invocation -> {
                            Map<String, Object> arguments = invocation.getArgument(1);
                            return "value-0".equals(arguments.get("value"))
                                    ? Mono.just(response)
                                    : Mono.create(secondSink::set);
                        });
        var driver = driver(mcp);
        await(
                () -> {
                    segment(driver, task);
                    return secondSink.get() != null;
                });
        store.receive(new ManagedToolCommand(scope, "cancel_batch", "cancel", task, null));
        secondSink.get().success(response);
        await(
                () -> {
                    segment(driver, task);
                    return store.stagedToolResult(scope, task.id()).isPresent();
                });
        segment(driver, task);
        var aggregate =
                ManagedMcpBatch.result(
                        store.stagedToolResult(scope, task.id()).orElseThrow().result());
        assertEquals("succeeded", aggregate.require("call-0").status());
        assertEquals("succeeded", aggregate.require("call-1").status());
        assertEquals("cancelled", aggregate.require("call-2").status());
        assertEquals("cancelled", store.tool(scope, task.id()).orElseThrow().status());
        verify(client, times(2)).callTool(eq("echo"), anyMap(), anyMap());
    }

    @Test
    void batchRetainsAnIndividualErrorAndStillCollectsTheNextCall() throws Exception {
        var task = batch("batch-error", 2);
        when(client.callTool(eq("echo"), anyMap(), anyMap()))
                .thenAnswer(
                        invocation -> {
                            Map<String, Object> arguments = invocation.getArgument(1);
                            return Mono.just(
                                    new McpSchema.CallToolResult(
                                            List.of(
                                                    new McpSchema.TextContent(
                                                            arguments.get("value").toString())),
                                            "value-0".equals(arguments.get("value"))));
                        });
        var driver = driver(mcp);
        await(
                () -> {
                    segment(driver, task);
                    return store.stagedToolResult(scope, task.id()).isPresent();
                });
        segment(driver, task);
        var aggregate =
                ManagedMcpBatch.result(
                        store.stagedToolResult(scope, task.id()).orElseThrow().result());
        assertEquals("failed", aggregate.require("call-0").status());
        assertEquals("succeeded", aggregate.require("call-1").status());
        assertEquals("succeeded", store.tool(scope, task.id()).orElseThrow().status());
        verify(client, times(1)).callTool(eq("echo"), eq(Map.of("value", "value-0")), anyMap());
        verify(client, times(1)).callTool(eq("echo"), eq(Map.of("value", "value-1")), anyMap());
    }

    @Test
    void retiredBatchOwnerCannotAdvanceAnyUnstartedCall() throws Exception {
        var task = batch("batch-fence", 2);
        var old = store.claimTool(scope, task.id(), "worker-old").task();
        var executor = mcp.executor(scope, task.id());
        executor.start(scope, old);
        store.claimTool(scope, task.id(), "worker-new");
        var failure = assertThrows(ManagedStateException.class, () -> executor.query(scope, old));
        assertEquals("stale_hands", failure.code());
        verify(client, never()).callTool(anyString(), anyMap(), anyMap());
    }

    @Test
    void discoveryAndInvocationUseHandsAndOriginalResultSurvivesReplacement() throws Exception {
        var describe = task("catalog_test", "mcp.describe", "");
        var driver = driver(mcp);
        segment(driver, describe);
        await(() -> store.stagedToolResult(scope, describe.id()).isPresent());
        segment(driver, describe);
        var directory =
                mapper.readValue(
                        store.stagedToolResult(scope, describe.id()).orElseThrow().result(),
                        ManagedMcpCatalog.Description.class);
        assertEquals(List.of(descriptor), directory.tools());
        assertFalse(mapper.writeValueAsString(directory).contains("private-test-token"));
        var response =
                McpSchema.CallToolResult.builder()
                        .content(List.of(new McpSchema.TextContent("original")))
                        .isError(false)
                        .build();
        when(client.callTool(eq("echo"), anyMap(), anyMap())).thenReturn(Mono.just(response));
        var task = task("invoke", "mcp.call", ManagedMcpCatalog.descriptorHash(descriptor, mapper));
        segment(driver, task);
        await(() -> store.stagedToolResult(scope, task.id()).isPresent());
        segment(driver, task);
        assertEquals("succeeded", store.tool(scope, task.id()).orElseThrow().status());
        mcp.close();
        mcp =
                new ManagedMcpExecutors(
                        store,
                        results,
                        mapper,
                        crypto,
                        (name, cfg) -> {
                            throw new AssertionError("Replacement must reuse the captured result");
                        });
        segment(driver(mcp), task);
        verify(client, times(1))
                .callTool(
                        eq("echo"), eq(Map.of("value", "hello")), eq(Map.of("trace", "original")));
        assertEquals("succeeded", store.tool(scope, task.id()).orElseThrow().status());
    }

    @Test
    void anotherWorkerCanCollectTheLateOriginalResponseAfterObservingUnknown() throws Exception {
        var pending = new AtomicReference<MonoSink<McpSchema.CallToolResult>>();
        when(client.callTool(eq("echo"), anyMap(), anyMap())).thenReturn(Mono.create(pending::set));
        var task =
                task(
                        "live-worker-switch",
                        "mcp.call",
                        ManagedMcpCatalog.descriptorHash(descriptor, mapper));
        segment(driver(mcp), task);
        await(() -> pending.get() != null);

        try (var peer =
                new ManagedMcpExecutors(
                        store,
                        results,
                        mapper,
                        crypto,
                        (name, cfg) -> {
                            throw new AssertionError("The peer must not repeat the original call");
                        })) {
            var collector = driver(peer);
            segment(collector, task);
            assertEquals("unknown", store.tool(scope, task.id()).orElseThrow().status());
            assertTrue(store.stagedToolResult(scope, task.id()).isEmpty());

            // Only the launching worker has the RPC in memory. Its late response must be
            // captured even though a peer has claimed the task and changed its status.
            pending.get()
                    .success(
                            new McpSchema.CallToolResult(
                                    List.of(new McpSchema.TextContent("late-original-result")),
                                    false));
            await(() -> store.stagedToolResult(scope, task.id()).isPresent());
            segment(collector, task);

            var completed = store.tool(scope, task.id()).orElseThrow();
            assertEquals("succeeded", completed.status());
            assertEquals(task.executionId(), completed.executionId());
            assertTrue(completed.result().contains("late-original-result"));
        }
        verify(client, times(1)).callTool(eq("echo"), anyMap(), anyMap());
    }

    @Test
    void inFlightHttpResponsesDoNotOccupyTheConnectionPreparationPool() throws Exception {
        var invoked = new AtomicInteger();
        when(client.callTool(eq("echo"), anyMap(), anyMap()))
                .thenAnswer(
                        call -> {
                            invoked.incrementAndGet();
                            return Mono.never();
                        });
        var driver = driver(mcp);
        for (int i = 0; i < 17; i++) {
            var task =
                    task(
                            "async-response-" + i,
                            "mcp.call",
                            ManagedMcpCatalog.descriptorHash(descriptor, mapper));
            segment(driver, task);
        }
        // There are only 16 preparation threads; all 17 requests must be issued while none
        // of their responses has completed. The previous blocking call exhausted that pool.
        await(() -> invoked.get() == 17);
        verify(client, times(17)).callTool(eq("echo"), anyMap(), anyMap());
    }

    @Test
    void lostRpcOutcomeDoesNotBecomeAnOrdinaryFailureOrASecondCall() throws Exception {
        when(client.callTool(eq("echo"), anyMap(), anyMap()))
                .thenReturn(
                        Mono.error(new IllegalStateException("connection lost after dispatch")));
        var task =
                task("uncertain", "mcp.call", ManagedMcpCatalog.descriptorHash(descriptor, mapper));
        var driver = driver(mcp);
        segment(driver, task);
        await(
                () -> {
                    segment(driver, task);
                    return "unknown".equals(store.tool(scope, task.id()).orElseThrow().status());
                });
        mcp.close();
        mcp =
                new ManagedMcpExecutors(
                        store,
                        results,
                        mapper,
                        crypto,
                        (name, cfg) -> {
                            throw new AssertionError("An unknown call cannot be launched again");
                        });
        segment(driver(mcp), task);
        assertTrue(store.stagedToolResult(scope, task.id()).isEmpty());
        verify(client, times(1)).callTool(eq("echo"), anyMap(), anyMap());
    }

    @Test
    void changedDescriptorIsRejectedBeforeAnyExternalSideEffect() throws Exception {
        var task = task("changed", "mcp.call", "wrong-hash");
        var driver = driver(mcp);
        segment(driver, task);
        await(() -> store.stagedToolResult(scope, task.id()).isPresent());
        segment(driver, task);
        assertEquals("failed", store.tool(scope, task.id()).orElseThrow().status());
        verify(client, never()).callTool(anyString(), anyMap(), any());
    }

    @Test
    void confirmedResultIsRetriedAfterDatabaseCaptureFailsWithoutCallingMcpAgain()
            throws Exception {
        mcp.close();
        var unreliable = spy(results);
        doThrow(new DataAccessResourceFailureException("Injected temporary DB outage"))
                .doCallRealMethod()
                .when(unreliable)
                .capture(any());
        mcp = new ManagedMcpExecutors(store, unreliable, mapper, crypto, (name, cfg) -> client);
        var response =
                McpSchema.CallToolResult.builder()
                        .content(List.of(new McpSchema.TextContent("original")))
                        .isError(false)
                        .build();
        when(client.callTool(eq("echo"), anyMap(), anyMap())).thenReturn(Mono.just(response));
        var value =
                task(
                        "capture_retry",
                        "mcp.call",
                        ManagedMcpCatalog.descriptorHash(descriptor, mapper));
        var driver = driver(mcp);
        segment(driver, value);
        await(
                () -> {
                    segment(driver, value);
                    return "succeeded".equals(store.tool(scope, value.id()).orElseThrow().status());
                });
        verify(client, times(1)).callTool(eq("echo"), anyMap(), anyMap());
        assertTrue(
                store.stagedToolResult(scope, value.id())
                        .orElseThrow()
                        .result()
                        .contains("original"));
    }

    @Test
    void aBusyConnectionIsNeverEvictedByCachePressure() throws Exception {
        mcp.close();
        mcp = new ManagedMcpExecutors(store, results, mapper, crypto, (name, cfg) -> client, 1);
        var entered = new CountDownLatch(1);
        var release = new CountDownLatch(1);
        var response =
                McpSchema.CallToolResult.builder()
                        .content(List.of(new McpSchema.TextContent("original")))
                        .isError(false)
                        .build();
        when(client.callTool(eq("echo"), anyMap(), anyMap()))
                .thenReturn(
                        Mono.fromCallable(
                                () -> {
                                    entered.countDown();
                                    assertTrue(release.await(5, TimeUnit.SECONDS));
                                    return response;
                                }));
        var first = task("busy", "mcp.call", ManagedMcpCatalog.descriptorHash(descriptor, mapper));
        var driver = driver(mcp);
        segment(driver, first);
        assertTrue(entered.await(2, TimeUnit.SECONDS));
        try {
            var target = mapper.readValue(crypto.decrypt(sealed), ManagedToolTarget.class);
            target.tools().getMcpServers().get("server").setUrl("http://another-mcp.invalid");
            sealed = crypto.encrypt(mapper.writeValueAsString(target));
            var second =
                    task(
                            "pressure",
                            "mcp.call",
                            ManagedMcpCatalog.descriptorHash(descriptor, mapper));
            segment(driver, second);
            await(() -> store.stagedToolResult(scope, second.id()).isPresent());
            segment(driver, second);
            assertEquals("failed", store.tool(scope, second.id()).orElseThrow().status());
            verify(client, never()).close();
            verify(client, times(1)).callTool(eq("echo"), anyMap(), anyMap());
        } finally {
            release.countDown();
        }
        await(() -> store.stagedToolResult(scope, first.id()).isPresent());
        segment(driver, first);
        assertEquals("succeeded", store.tool(scope, first.id()).orElseThrow().status());
    }

    @Test
    void cancellationBeforeMcpInvocationPreservesTheMcpResultContract() throws Exception {
        var value =
                task(
                        "cancelled_mcp",
                        "mcp.call",
                        ManagedMcpCatalog.descriptorHash(descriptor, mapper));
        store.receive(new ManagedToolCommand(scope, "cancel_mcp", "cancel", value, null));
        segment(driver(mcp), value);
        var result = store.stagedToolResult(scope, value.id()).orElseThrow();
        assertEquals("cancelled", result.status());
        assertTrue(mapper.readTree(result.result()).path("isError").asBoolean());
        verify(client, never()).callTool(anyString(), anyMap(), any());
    }

    @Test
    void aCancelledDiscoveryHasAValidEmptyDescriptorResult() throws Exception {
        var value = task("catalog_cancelled", "mcp.describe", "");
        store.receive(new ManagedToolCommand(scope, "cancel_discovery", "cancel", value, null));
        segment(driver(mcp), value);
        var result = store.stagedToolResult(scope, value.id()).orElseThrow();
        assertEquals("cancelled", result.status());
        assertTrue(
                mapper.readValue(result.result(), ManagedMcpCatalog.Description.class)
                        .tools()
                        .isEmpty());
        verify(client, never()).listTools();
    }

    void recoverable() {
        var recovery = new Recovery(1, "execution_get");
        remote =
                new McpSchema.Tool(
                        remote.name(),
                        remote.title(),
                        remote.description(),
                        remote.inputSchema(),
                        remote.outputSchema(),
                        remote.annotations(),
                        Map.of(ManagedMcpCatalog.RECOVERY_META, recovery));
        var query =
                new McpSchema.Tool(
                        "execution_get",
                        null,
                        "Read prior execution",
                        remote.inputSchema(),
                        null,
                        new McpSchema.ToolAnnotations(null, true, false, true, false, false),
                        null);
        when(client.listTools()).thenReturn(Mono.just(List.of(remote, query)));
        when(client.getCachedTool("echo")).thenReturn(remote);
        descriptor =
                new ManagedMcpCatalog.Tool(
                        descriptor.server(),
                        descriptor.name(),
                        descriptor.remoteName(),
                        descriptor.description(),
                        descriptor.inputSchema(),
                        descriptor.outputSchema(),
                        descriptor.readOnly(),
                        recovery);
    }

    String externalId(ToolTask task) {
        return "mcp_exec_" + ManagedScope.fingerprint(scope.messageGroup(), task.executionId());
    }

    McpSchema.CallToolResult lookup(
            ToolTask task, String state, McpSchema.CallToolResult original) {
        var body = new LinkedHashMap<String, Object>();
        body.put("version", 1);
        body.put("execution_id", externalId(task));
        body.put("state", state);
        if (original != null) body.put("result", original);
        return new McpSchema.CallToolResult(List.of(), false, body, null);
    }

    void replace() {
        mcp.close();
        mcp = new ManagedMcpExecutors(store, results, mapper, crypto, (name, cfg) -> client);
    }

    @Test
    void replacementQueriesRunningThenCompletedOriginalExecutionWithoutRepeatingCall()
            throws Exception {
        recoverable();
        var task =
                task(
                        "recover-original",
                        "mcp.call",
                        ManagedMcpCatalog.descriptorHash(descriptor, mapper));
        when(client.callTool(eq("echo"), anyMap(), anyMap()))
                .thenReturn(Mono.error(new IllegalStateException("lost RPC")));
        when(client.callTool(eq("execution_get"), anyMap(), isNull()))
                .thenReturn(Mono.just(lookup(task, "unknown", null)));
        var originalDriver = driver(mcp);
        await(
                () -> {
                    segment(originalDriver, task);
                    return "unknown".equals(store.tool(scope, task.id()).orElseThrow().status());
                });
        replace();
        when(client.callTool(eq("execution_get"), anyMap(), isNull()))
                .thenReturn(Mono.just(lookup(task, "running", null)));
        segment(driver(mcp), task);
        assertTrue(store.stagedToolResult(scope, task.id()).isEmpty());
        var result =
                new McpSchema.CallToolResult(
                        List.of(new McpSchema.TextContent("original-result")), false);
        when(client.callTool(eq("execution_get"), anyMap(), isNull()))
                .thenReturn(Mono.just(lookup(task, "succeeded", result)));
        segment(driver(mcp), task);
        assertEquals("succeeded", store.tool(scope, task.id()).orElseThrow().status());
        assertTrue(
                store.stagedToolResult(scope, task.id())
                        .orElseThrow()
                        .result()
                        .contains("original-result"));
        verify(client, times(1))
                .callTool(
                        eq("echo"),
                        anyMap(),
                        eq(
                                Map.of(
                                        "trace",
                                        "original",
                                        ManagedMcpCatalog.RECOVERY_META,
                                        Map.of("version", 1, "execution_id", externalId(task)))));
    }

    @Test
    void missingExecutionCanResubmitOnlyWithTheSameAdmittedIdempotencyIdentity() throws Exception {
        recoverable();
        var task =
                task(
                        "lost-admission",
                        "mcp.call",
                        ManagedMcpCatalog.descriptorHash(descriptor, mapper));
        var result =
                new McpSchema.CallToolResult(
                        List.of(new McpSchema.TextContent("one-effect")), false);
        when(client.callTool(eq("echo"), anyMap(), anyMap()))
                .thenReturn(
                        Mono.error(new IllegalStateException("request never arrived")),
                        Mono.just(result));
        when(client.callTool(eq("execution_get"), anyMap(), isNull()))
                .thenReturn(Mono.just(lookup(task, "not_found", null)));
        var driver = driver(mcp);
        await(
                () -> {
                    segment(driver, task);
                    return "succeeded".equals(store.tool(scope, task.id()).orElseThrow().status());
                });
        verify(client, times(2))
                .callTool(
                        eq("echo"),
                        anyMap(),
                        eq(
                                Map.of(
                                        "trace",
                                        "original",
                                        ManagedMcpCatalog.RECOVERY_META,
                                        Map.of("version", 1, "execution_id", externalId(task)))));
    }

    @Test
    void foreignExecutionAndUnavailableLookupNeverPermitAnotherInvocation() throws Exception {
        recoverable();
        var task =
                task(
                        "bad-lookup",
                        "mcp.call",
                        ManagedMcpCatalog.descriptorHash(descriptor, mapper));
        when(client.callTool(eq("echo"), anyMap(), anyMap()))
                .thenReturn(Mono.error(new IllegalStateException("lost RPC")));
        when(client.callTool(eq("execution_get"), anyMap(), isNull()))
                .thenReturn(
                        Mono.just(
                                new McpSchema.CallToolResult(
                                        List.of(),
                                        false,
                                        Map.of(
                                                "version",
                                                1,
                                                "execution_id",
                                                "different-task",
                                                "state",
                                                "not_found"),
                                        null)));
        var driver = driver(mcp);
        await(
                () -> {
                    segment(driver, task);
                    return "unknown".equals(store.tool(scope, task.id()).orElseThrow().status());
                });
        replace();
        when(client.callTool(eq("execution_get"), anyMap(), isNull()))
                .thenReturn(Mono.error(new IllegalStateException("offline")));
        segment(driver(mcp), task);
        assertTrue(store.stagedToolResult(scope, task.id()).isEmpty());
        verify(client, times(1)).callTool(eq("echo"), anyMap(), anyMap());
    }

    @Test
    void recoveryRequiresMetadataAndKeepsLookupToolsOutOfTheModelCatalog() throws Exception {
        recoverable();
        var discovery = task("recovery-catalog", "mcp.describe", "");
        var driver = driver(mcp);
        await(
                () -> {
                    segment(driver, discovery);
                    return store.stagedToolResult(scope, discovery.id()).isPresent();
                });
        var catalog =
                mapper.readValue(
                        store.stagedToolResult(scope, discovery.id()).orElseThrow().result(),
                        ManagedMcpCatalog.Description.class);
        assertEquals(List.of(descriptor), catalog.tools());
        when(client.isPropagateMeta()).thenReturn(false);
        var call =
                task("no-meta", "mcp.call", ManagedMcpCatalog.descriptorHash(descriptor, mapper));
        await(
                () -> {
                    segment(driver, call);
                    return store.stagedToolResult(scope, call.id()).isPresent();
                });
        assertEquals("failed", store.stagedToolResult(scope, call.id()).orElseThrow().status());
        verify(client, never()).callTool(anyString(), anyMap(), any());
    }

    @Test
    void recoveredFailureAndCancellationKeepTheirOriginalTerminalStatus() throws Exception {
        recoverable();
        for (String state : List.of("failed", "cancelled")) {
            var task =
                    task(
                            "terminal-" + state,
                            "mcp.call",
                            ManagedMcpCatalog.descriptorHash(descriptor, mapper));
            store.claimTool(scope, task.id(), "old-worker");
            store.recordToolHandle(scope, task.id(), "old-worker", task.executionId());
            var result =
                    new McpSchema.CallToolResult(
                            List.of(new McpSchema.TextContent("original-" + state)), true);
            when(client.callTool(eq("execution_get"), anyMap(), isNull()))
                    .thenReturn(Mono.just(lookup(task, state, result)));
            segment(driver(mcp), task);
            assertEquals(state, store.tool(scope, task.id()).orElseThrow().status());
        }
        verify(client, never()).callTool(eq("echo"), anyMap(), any());
    }

    @Test
    void originalResponseAndRemoteLookupHaveIdenticalFactsDespiteJsonObjectKeyOrder()
            throws Exception {
        recoverable();
        results = spy(results);
        replace();
        var captured = new CopyOnWriteArrayList<ManagedToolResult>();
        doAnswer(
                        invocation -> {
                            captured.add(invocation.getArgument(0));
                            return invocation.callRealMethod();
                        })
                .when(results)
                .capture(any());
        var pending = new AtomicReference<MonoSink<McpSchema.CallToolResult>>();
        when(client.callTool(eq("echo"), anyMap(), anyMap())).thenReturn(Mono.create(pending::set));
        var task =
                task(
                        "canonical-result",
                        "mcp.call",
                        ManagedMcpCatalog.descriptorHash(descriptor, mapper));
        segment(driver(mcp), task);
        await(() -> pending.get() != null);
        var firstOrder = new LinkedHashMap<String, Object>();
        firstOrder.put("marker", "same");
        firstOrder.put("call_id", "one-call");
        var secondOrder = new LinkedHashMap<String, Object>();
        secondOrder.put("call_id", "one-call");
        secondOrder.put("marker", "same");
        var content = List.<McpSchema.Content>of(new McpSchema.TextContent("immutable"));
        var fromLookup = new McpSchema.CallToolResult(content, false, secondOrder, null);
        when(client.callTool(eq("execution_get"), anyMap(), isNull()))
                .thenReturn(Mono.just(lookup(task, "succeeded", fromLookup)));
        try (var peer =
                new ManagedMcpExecutors(store, results, mapper, crypto, (name, cfg) -> client)) {
            segment(driver(peer), task);
            int beforeOriginalResponse = captured.size();
            pending.get().success(new McpSchema.CallToolResult(content, false, firstOrder, null));
            await(() -> captured.size() > beforeOriginalResponse);
            assertEquals(captured.get(0).result(), captured.get(beforeOriginalResponse).result());
            assertEquals("succeeded", store.tool(scope, task.id()).orElseThrow().status());
        }
        verify(client, times(1)).callTool(eq("echo"), anyMap(), anyMap());
    }

    void await(BooleanSupplier condition) throws Exception {
        long deadline = System.nanoTime() + Duration.ofSeconds(5).toNanos();
        while (!condition.getAsBoolean() && System.nanoTime() < deadline) Thread.sleep(10);
        assertTrue(condition.getAsBoolean());
    }
}
