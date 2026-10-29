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
package io.agentscope.core.tool.mcp;

import io.agentscope.core.tool.SchemaOnlyTool;
import io.agentscope.core.tool.ToolCallParam;
import io.modelcontextprotocol.spec.McpSchema;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.Mockito;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

class McpNamespacedToolTest {
    @Test
    void copiedToolkitDoesNotOwnConnectionsAndOwnerClosesOnce() {
        var toolkit = new io.agentscope.core.tool.Toolkit();
        McpClientWrapper client = Mockito.mock(McpClientWrapper.class);
        Mockito.when(client.getName()).thenReturn("crm");
        Mockito.when(client.initialize()).thenReturn(Mono.empty());
        Mockito.when(client.listTools()).thenReturn(Mono.just(java.util.List.of()));
        toolkit.registerMcpClient(client).block();
        toolkit.copy().closeMcpClients();
        Mockito.verify(client, Mockito.never()).close();
        toolkit.closeMcpClients();
        toolkit.closeMcpClients();
        Mockito.verify(client, Mockito.times(1)).close();
    }

    @Test
    void registrationKeepsSameNamedToolsFromDifferentServers() {
        var toolkit = new io.agentscope.core.tool.Toolkit();
        for (String name : java.util.List.of("crm", "issues")) {
            McpClientWrapper client = Mockito.mock(McpClientWrapper.class);
            Mockito.when(client.getName()).thenReturn(name);
            Mockito.when(client.initialize()).thenReturn(Mono.empty());
            var tool = Mockito.mock(McpSchema.Tool.class);
            Mockito.when(tool.name()).thenReturn("search");
            Mockito.when(client.listTools()).thenReturn(Mono.just(java.util.List.of(tool)));
            toolkit.registration().mcpClient(client).mcpToolNamePrefix(name + "__").apply();
        }
        Assertions.assertEquals(
                java.util.Set.of("crm__search", "issues__search"), toolkit.getToolNames());
    }

    @Test
    void prefixedModelNameIsSanitizedAndHasStableHashWithoutChangingRemoteName() {
        var toolkit = new io.agentscope.core.tool.Toolkit();
        McpClientWrapper client = McpClientWrapperTestSupport.mockWrapper("crm one", true);
        McpSchema.Tool remoteTool = Mockito.mock(McpSchema.Tool.class);
        Mockito.when(remoteTool.name()).thenReturn("search");
        Mockito.when(client.initialize()).thenReturn(Mono.empty());
        Mockito.when(client.listTools()).thenReturn(Mono.just(java.util.List.of(remoteTool)));
        Mockito.when(client.callTool(Mockito.eq("search"), Mockito.anyMap(), Mockito.anyMap()))
                .thenReturn(Mono.just(McpSchema.CallToolResult.builder().isError(false).build()));

        toolkit.registration().mcpClient(client).mcpToolNamePrefix("crm one__").apply();

        String modelName = "crm_one__search_d7d327bcfa40";
        Assertions.assertTrue(toolkit.getToolNames().contains(modelName));
        toolkit.getTool(modelName)
                .callAsync(ToolCallParam.builder().input(Map.of()).build())
                .block();
        Mockito.verify(client)
                .callTool(Mockito.eq("search"), Mockito.eq(Map.of()), Mockito.anyMap());
    }

    @Test
    void registrationWithoutPrefixKeepsTheRemoteToolName() {
        var toolkit = new io.agentscope.core.tool.Toolkit();
        McpClientWrapper client = McpClientWrapperTestSupport.mockWrapper("server", true);
        McpSchema.Tool remoteTool = Mockito.mock(McpSchema.Tool.class);
        Mockito.when(remoteTool.name()).thenReturn("lookup.v1");
        Mockito.when(client.initialize()).thenReturn(Mono.empty());
        Mockito.when(client.listTools()).thenReturn(Mono.just(java.util.List.of(remoteTool)));

        toolkit.registration().mcpClient(client).apply();

        Assertions.assertTrue(toolkit.getToolNames().contains("lookup.v1"));
    }

    @ParameterizedTest
    @ValueSource(strings = {"", "crm__"})
    void registrationRejectsLocalNameConflictBeforeAddingAnyMcpTools(String prefix) {
        var toolkit = new io.agentscope.core.tool.Toolkit();
        toolkit.registerAgentTool(new SchemaOnlyTool(prefix + "search", "Local search", Map.of()));

        McpClientWrapper client = McpClientWrapperTestSupport.mockWrapper("crm", true);
        McpSchema.Tool otherTool = Mockito.mock(McpSchema.Tool.class);
        Mockito.when(otherTool.name()).thenReturn("other");
        McpSchema.Tool conflictingTool = Mockito.mock(McpSchema.Tool.class);
        Mockito.when(conflictingTool.name()).thenReturn("search");
        Mockito.when(client.initialize()).thenReturn(Mono.empty());
        Mockito.when(client.listTools())
                .thenReturn(Mono.just(java.util.List.of(otherTool, conflictingTool)));

        Assertions.assertThrows(
                IllegalStateException.class,
                () -> toolkit.registration().mcpClient(client).mcpToolNamePrefix(prefix).apply());

        Assertions.assertEquals(java.util.Set.of(prefix + "search"), toolkit.getToolNames());
        Assertions.assertEquals(
                "Local search", toolkit.getTool(prefix + "search").getDescription());
        Mockito.verify(client).close();
    }

    @ParameterizedTest
    @ValueSource(strings = {"", "shared__"})
    void registrationRejectsCrossServerConflictWithoutReplacingExistingTools(String prefix) {
        var toolkit = new io.agentscope.core.tool.Toolkit();
        McpClientWrapper first = McpClientWrapperTestSupport.mockWrapper("first", true);
        McpSchema.Tool search = Mockito.mock(McpSchema.Tool.class);
        Mockito.when(search.name()).thenReturn("search");
        Mockito.when(first.initialize()).thenReturn(Mono.empty());
        Mockito.when(first.listTools()).thenReturn(Mono.just(java.util.List.of(search)));
        toolkit.registration().mcpClient(first).mcpToolNamePrefix(prefix).apply();
        var original = toolkit.getTool(prefix + "search");

        McpClientWrapper second = McpClientWrapperTestSupport.mockWrapper("second", true);
        McpSchema.Tool other = Mockito.mock(McpSchema.Tool.class);
        Mockito.when(other.name()).thenReturn("other");
        Mockito.when(second.initialize()).thenReturn(Mono.empty());
        Mockito.when(second.listTools()).thenReturn(Mono.just(java.util.List.of(other, search)));

        Assertions.assertThrows(
                IllegalStateException.class,
                () -> toolkit.registration().mcpClient(second).mcpToolNamePrefix(prefix).apply());

        Assertions.assertEquals(java.util.Set.of(prefix + "search"), toolkit.getToolNames());
        Assertions.assertSame(original, toolkit.getTool(prefix + "search"));
        Mockito.verify(first, Mockito.never()).close();
        Mockito.verify(second).close();
    }

    @Test
    void concurrentRegistrationsRejectTheConflictingBatchWithoutLeavingPartialTools()
            throws Exception {
        var toolkit = new io.agentscope.core.tool.Toolkit();
        var firstInRegistration = new CountDownLatch(1);
        var releaseFirst = new CountDownLatch(1);
        var secondListed = new CountDownLatch(1);
        var secondThread = new AtomicReference<Thread>();
        McpSchema.Tool firstOnly = Mockito.mock(McpSchema.Tool.class);
        Mockito.when(firstOnly.name()).thenReturn("first_only");
        Mockito.when(firstOnly.description())
                .thenAnswer(
                        invocation -> {
                            firstInRegistration.countDown();
                            Assertions.assertTrue(releaseFirst.await(5, TimeUnit.SECONDS));
                            return "First client's tool";
                        });
        McpSchema.Tool secondOnly = Mockito.mock(McpSchema.Tool.class);
        Mockito.when(secondOnly.name()).thenReturn("second_only");
        McpSchema.Tool shared = Mockito.mock(McpSchema.Tool.class);
        Mockito.when(shared.name()).thenReturn("search");
        McpClientWrapper first = McpClientWrapperTestSupport.mockWrapper("first", true);
        Mockito.when(first.initialize()).thenReturn(Mono.empty());
        Mockito.when(first.listTools()).thenReturn(Mono.just(java.util.List.of(firstOnly, shared)));
        McpClientWrapper second = McpClientWrapperTestSupport.mockWrapper("second", true);
        Mockito.when(second.initialize()).thenReturn(Mono.empty());
        Mockito.when(second.listTools())
                .thenReturn(
                        Mono.fromSupplier(
                                () -> {
                                    secondListed.countDown();
                                    return java.util.List.of(secondOnly, shared);
                                }));

        var firstRegistration =
                toolkit.registerMcpClient(first)
                        .subscribeOn(Schedulers.boundedElastic())
                        .toFuture();
        try {
            Assertions.assertTrue(firstInRegistration.await(5, TimeUnit.SECONDS));
            var secondRegistration =
                    toolkit.registerMcpClient(second)
                            .doOnSubscribe(ignored -> secondThread.set(Thread.currentThread()))
                            .subscribeOn(Schedulers.boundedElastic())
                            .toFuture();
            Assertions.assertTrue(secondListed.await(5, TimeUnit.SECONDS));
            // Let the second registration reach the registry while the first is paused.
            // A broken preflight completes here; a serialized batch waits for the writer.
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
            while (!secondRegistration.isDone()
                    && secondThread.get().getState() != Thread.State.BLOCKED
                    && System.nanoTime() < deadline) {
                Thread.yield();
            }
            Assertions.assertTrue(
                    secondRegistration.isDone()
                            || secondThread.get().getState() == Thread.State.BLOCKED);
            releaseFirst.countDown();
            firstRegistration.get(5, TimeUnit.SECONDS);
            ExecutionException rejected =
                    Assertions.assertThrows(
                            ExecutionException.class,
                            () -> secondRegistration.get(5, TimeUnit.SECONDS));
            Assertions.assertInstanceOf(IllegalStateException.class, rejected.getCause());
            Assertions.assertEquals(
                    java.util.Set.of("first_only", "search"), toolkit.getToolNames());
            Mockito.verify(first, Mockito.never()).close();
            Mockito.verify(second).close();
        } finally {
            releaseFirst.countDown();
            firstRegistration.get(5, TimeUnit.SECONDS);
            toolkit.closeMcpClients();
        }
    }

    @Test
    void reregisteringTheSameIdentityKeepsTheNormalizedName() {
        var toolkit = new io.agentscope.core.tool.Toolkit();
        for (int registration = 0; registration < 2; registration++) {
            McpClientWrapper client = McpClientWrapperTestSupport.mockWrapper("crm one", true);
            McpSchema.Tool search = Mockito.mock(McpSchema.Tool.class);
            Mockito.when(search.name()).thenReturn("search");
            Mockito.when(client.initialize()).thenReturn(Mono.empty());
            Mockito.when(client.listTools()).thenReturn(Mono.just(java.util.List.of(search)));

            toolkit.registration().mcpClient(client).mcpToolNamePrefix("crm one__").apply();

            Assertions.assertEquals(
                    java.util.Set.of("crm_one__search_d7d327bcfa40"), toolkit.getToolNames());
            toolkit.removeMcpClient("crm one").block();
            Assertions.assertTrue(toolkit.getToolNames().isEmpty());
            Mockito.verify(client).close();
        }
    }

    @Test
    void normalizedNamesRemainDistinctAndWithinProviderNameLimit() {
        var toolkit = new io.agentscope.core.tool.Toolkit();
        McpClientWrapper client = McpClientWrapperTestSupport.mockWrapper("server", true);
        McpSchema.Tool dotted = Mockito.mock(McpSchema.Tool.class);
        Mockito.when(dotted.name()).thenReturn("lookup.v1");
        McpSchema.Tool slashed = Mockito.mock(McpSchema.Tool.class);
        Mockito.when(slashed.name()).thenReturn("lookup/v1");
        Mockito.when(client.initialize()).thenReturn(Mono.empty());
        Mockito.when(client.listTools()).thenReturn(Mono.just(java.util.List.of(dotted, slashed)));

        toolkit.registration().mcpClient(client).mcpToolNamePrefix("server__").apply();

        java.util.Set<String> names = toolkit.getToolNames();
        Assertions.assertEquals(2, names.size());
        Assertions.assertTrue(names.contains("server__lookup_v1_00011a00e4c2"));
        Assertions.assertTrue(names.contains("server__lookup_v1_0d6a1ad3b361"));
        Assertions.assertTrue(names.stream().allMatch(name -> name.length() <= 64));
        Assertions.assertTrue(names.stream().allMatch(name -> name.matches("[A-Za-z0-9_-]+")));
    }

    @ParameterizedTest
    @ValueSource(strings = {"", "crm__"})
    void registrationRejectsDuplicateNamesFromOneMcpServerBeforeAddingTools(String prefix) {
        var toolkit = new io.agentscope.core.tool.Toolkit();
        McpClientWrapper client = McpClientWrapperTestSupport.mockWrapper("crm", true);
        McpSchema.Tool first = Mockito.mock(McpSchema.Tool.class);
        Mockito.when(first.name()).thenReturn("search");
        McpSchema.Tool duplicate = Mockito.mock(McpSchema.Tool.class);
        Mockito.when(duplicate.name()).thenReturn("search");
        Mockito.when(client.initialize()).thenReturn(Mono.empty());
        Mockito.when(client.listTools()).thenReturn(Mono.just(java.util.List.of(first, duplicate)));

        Assertions.assertThrows(
                IllegalArgumentException.class,
                () -> toolkit.registration().mcpClient(client).mcpToolNamePrefix(prefix).apply());

        Assertions.assertTrue(toolkit.getToolNames().isEmpty());
        Mockito.verify(client).close();
    }

    @Test
    void overlengthPrefixedNameIsTruncatedBeforeAddingStableHash() {
        var toolkit = new io.agentscope.core.tool.Toolkit();
        String serverName = "longserver" + "x".repeat(70);
        McpClientWrapper client = McpClientWrapperTestSupport.mockWrapper(serverName, true);
        McpSchema.Tool remoteTool = Mockito.mock(McpSchema.Tool.class);
        Mockito.when(remoteTool.name()).thenReturn("lookup");
        Mockito.when(client.initialize()).thenReturn(Mono.empty());
        Mockito.when(client.listTools()).thenReturn(Mono.just(java.util.List.of(remoteTool)));

        toolkit.registration().mcpClient(client).mcpToolNamePrefix(serverName + "__").apply();

        String modelName = toolkit.getToolNames().iterator().next();
        Assertions.assertEquals(64, modelName.length());
        Assertions.assertTrue(modelName.endsWith("_0c9814adf215"));
    }

    @Test
    void callsOriginalNameWithNamespacedModelName() {
        // McpTool.callAsync reads the connection-level switch live; default it to on.
        McpClientWrapper client = McpClientWrapperTestSupport.mockWrapper("crm-client", true);
        Mockito.when(client.callTool(Mockito.eq("search"), Mockito.anyMap(), Mockito.anyMap()))
                .thenReturn(Mono.just(McpSchema.CallToolResult.builder().isError(false).build()));
        McpTool tool =
                new McpTool(
                        "crm__search",
                        "search",
                        "Search CRM",
                        Map.of(),
                        null,
                        client,
                        null,
                        "crm",
                        true);
        Assertions.assertEquals("crm__search", tool.getName());
        Assertions.assertEquals("crm", tool.getMcpName());
        tool.callAsync(ToolCallParam.builder().input(Map.of("query", "customer")).build()).block();
        Mockito.verify(client)
                .callTool(
                        Mockito.eq("search"),
                        Mockito.eq(Map.of("query", "customer")),
                        Mockito.anyMap());
    }
}
