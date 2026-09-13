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

import io.agentscope.core.tool.ToolCallParam;
import io.modelcontextprotocol.spec.McpSchema;
import java.util.Map;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import reactor.core.publisher.Mono;

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
    void callsOriginalNameWithNamespacedModelName() {
        McpClientWrapper client = Mockito.mock(McpClientWrapper.class);
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
