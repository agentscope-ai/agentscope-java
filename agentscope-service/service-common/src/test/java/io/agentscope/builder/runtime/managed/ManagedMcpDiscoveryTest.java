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

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import io.agentscope.core.tool.mcp.McpClientWrapper;
import io.agentscope.harness.agent.tools.McpServerConfig;
import io.agentscope.harness.agent.tools.ToolsConfig;
import io.modelcontextprotocol.spec.McpSchema;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.api.Test;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

class ManagedMcpDiscoveryTest {
    private final McpClientWrapper client = mock(McpClientWrapper.class);
    private final McpServerConfig server = new McpServerConfig();
    private final ToolsConfig tools = new ToolsConfig();

    ManagedMcpDiscoveryTest() {
        server.setTransport("http");
        server.setUrl("http://mcp.invalid");
        server.setPrefixToolNames(true);
        tools.setMcpServers(Map.of("server", server));
        when(client.initialize()).thenReturn(Mono.empty());
    }

    private McpSchema.Tool tool(String name, Boolean readOnly, Map<String, Object> meta) {
        return new McpSchema.Tool(
                name,
                null,
                "description",
                new McpSchema.JsonSchema("object", Map.of(), List.of(), null, null, null),
                null,
                readOnly == null
                        ? null
                        : new McpSchema.ToolAnnotations(null, readOnly, false, false, false, false),
                meta);
    }

    @Test
    void readOnlyDiscoveryPreservesRecoveryAndFiltersWithoutCallingTools() {
        var echo =
                tool(
                        "echo",
                        false,
                        Map.of(
                                ManagedMcpCatalog.RECOVERY_META,
                                Map.of("version", 1, "queryTool", "execution_get")));
        var lookup = tool("execution_get", true, null);
        var denied = tool("denied", false, null);
        server.setDisableTools(List.of("denied"));
        when(client.listTools()).thenReturn(Mono.just(List.of(echo, lookup, denied)));
        var discovery = new ManagedMcpDiscovery((name, config) -> Mono.just(client));
        var result = discovery.discover(tools, null, () -> false);
        assertEquals(
                List.of("server__echo"),
                result.tools().stream().map(ManagedMcpCatalog.Tool::name).toList());
        assertEquals(
                new ManagedMcpCatalog.Recovery(1, "execution_get"),
                result.tools().get(0).recovery());
        assertEquals(ManagedMcpDiscovery.descriptor("server", server, echo), result.tools().get(0));
        assertTrue(result.unavailable().isEmpty());
        verify(client, never()).callTool(anyString(), anyMap(), any());
        verify(client).close();
    }

    @Test
    void unavailableOptionalServerDegradesButRequiredServerFailsAndAlwaysCloses() {
        when(client.listTools()).thenReturn(Mono.error(new IllegalStateException("offline")));
        var discovery = new ManagedMcpDiscovery((name, config) -> Mono.just(client));
        assertEquals(List.of("server"), discovery.discover(tools, null, () -> false).unavailable());
        server.setRequired(true);
        var failure =
                assertThrows(
                        ManagedStateException.class,
                        () -> discovery.discover(tools, null, () -> false));
        assertEquals("mcp_connection_failed", failure.code());
        verify(client, times(2)).close();
    }

    @Test
    void cancellationAbortsAnInFlightRequiredDiscoveryWithoutWaitingForItsTimeout()
            throws Exception {
        server.setRequired(true);
        server.setTimeout(Duration.ofMinutes(4));
        var started = new CountDownLatch(1);
        var requestCancelled = new AtomicBoolean();
        var cancelled = new AtomicBoolean();
        when(client.listTools())
                .thenReturn(
                        Mono.<List<McpSchema.Tool>>never()
                                .doOnSubscribe(subscription -> started.countDown())
                                .doOnCancel(() -> requestCancelled.set(true)));
        var discovery = new ManagedMcpDiscovery((name, config) -> Mono.just(client));
        var executor = Executors.newSingleThreadExecutor();
        try {
            var future = executor.submit(() -> discovery.discover(tools, null, cancelled::get));
            assertTrue(started.await(2, TimeUnit.SECONDS));
            cancelled.set(true);
            assertTrue(future.get(2, TimeUnit.SECONDS).tools().isEmpty());
            assertTrue(requestCancelled.get());
            verify(client).close();
        } finally {
            executor.shutdownNow();
        }
    }

    @Test
    void discoveryHonorsTimeoutAndRejectsInvalidRecoveryLookups() {
        server.setTimeout(Duration.ofMillis(30));
        when(client.listTools()).thenReturn(Mono.never());
        var discovery = new ManagedMcpDiscovery((name, config) -> Mono.just(client));
        assertEquals(List.of("server"), discovery.discover(tools, null, () -> false).unavailable());
        server.setRequired(true);
        var echo =
                tool(
                        "echo",
                        false,
                        Map.of(
                                ManagedMcpCatalog.RECOVERY_META,
                                Map.of("version", 1, "queryTool", "missing")));
        when(client.listTools()).thenReturn(Mono.just(List.of(echo)));
        assertThrows(
                ManagedStateException.class, () -> discovery.discover(tools, null, () -> false));
        verify(client, times(2)).close();
    }

    @Test
    void nonLocalStdioAndReactorEventLoopsCannotOpenDiscoveryConnections() {
        var opened = new AtomicBoolean();
        var discovery =
                new ManagedMcpDiscovery(
                        (name, config) -> {
                            opened.set(true);
                            return Mono.just(client);
                        });
        server.setTransport("stdio");
        server.setRequired(true);
        assertThrows(
                ManagedStateException.class, () -> discovery.discover(tools, null, () -> false));
        assertFalse(opened.get());
        assertThrows(
                IllegalStateException.class,
                () ->
                        Mono.fromCallable(() -> discovery.discover(tools, null, () -> false))
                                .subscribeOn(Schedulers.parallel())
                                .block(Duration.ofSeconds(2)));
        assertFalse(opened.get());
    }
}
