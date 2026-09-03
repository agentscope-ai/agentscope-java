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
package io.agentscope.harness.agent.tools;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import io.agentscope.core.tool.Toolkit;
import io.agentscope.core.tool.mcp.McpClientBuilder;
import io.agentscope.core.tool.mcp.McpClientWrapper;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.mockito.MockedStatic;
import reactor.core.publisher.Mono;

class McpServerRegistrarTest {

    @Test
    void legacyRegister_keepsBestEffortBehavior() {
        McpServerConfig invalid = new McpServerConfig();
        invalid.setTransport("unsupported");

        assertDoesNotThrow(
                () -> McpServerRegistrar.register(new Toolkit(), Map.of("invalid", invalid)));
    }

    @Test
    void register_reportsFailedAndSkippedEntriesInOrder() {
        McpServerConfig skipped = new McpServerConfig();
        skipped.setTransport("stdio");
        McpServerConfig failed = new McpServerConfig();
        failed.setTransport("unsupported");
        Map<String, McpServerConfig> servers = new LinkedHashMap<>();
        servers.put(" ", skipped);
        servers.put("missing", null);
        servers.put("failed", failed);
        List<McpServerRegistrationResult> results = new ArrayList<>();

        McpServerRegistrar.register(new Toolkit(), servers, results::add);

        assertEquals(3, results.size());
        assertEquals(McpServerRegistrationResult.Status.SKIPPED, results.get(0).status());
        assertEquals(" ", results.get(0).serverName());
        assertEquals("stdio", results.get(0).transport());
        assertEquals(McpServerRegistrationResult.Status.SKIPPED, results.get(1).status());
        assertEquals("missing", results.get(1).serverName());
        assertNull(results.get(1).transport());
        assertEquals(McpServerRegistrationResult.Status.FAILED, results.get(2).status());
        assertEquals("failed", results.get(2).serverName());
        assertEquals("unsupported", results.get(2).transport());
        assertInstanceOf(IllegalArgumentException.class, results.get(2).cause());
        results.forEach(result -> assertNotNull(result.completedAt()));
    }

    @Test
    void register_reportsEveryRequiredTransportFieldFailure() {
        McpServerConfig missingTransport = new McpServerConfig();
        McpServerConfig missingCommand = new McpServerConfig();
        missingCommand.setTransport("stdio");
        McpServerConfig missingSseUrl = new McpServerConfig();
        missingSseUrl.setTransport("sse");
        McpServerConfig missingHttpUrl = new McpServerConfig();
        missingHttpUrl.setTransport("http");
        Map<String, McpServerConfig> servers = new LinkedHashMap<>();
        servers.put("missing-transport", missingTransport);
        servers.put("missing-command", missingCommand);
        servers.put("missing-sse-url", missingSseUrl);
        servers.put("missing-http-url", missingHttpUrl);
        List<McpServerRegistrationResult> results = new ArrayList<>();

        McpServerRegistrar.register(new Toolkit(), servers, results::add);

        assertEquals(4, results.size());
        assertEquals(
                List.of(
                        "missing-transport",
                        "missing-command",
                        "missing-sse-url",
                        "missing-http-url"),
                results.stream().map(McpServerRegistrationResult::serverName).toList());
        results.forEach(
                result -> {
                    assertEquals(McpServerRegistrationResult.Status.FAILED, result.status());
                    assertInstanceOf(IllegalArgumentException.class, result.cause());
                    assertNotNull(result.completedAt());
                });
    }

    @Test
    void register_reportsSuccessfulEntry() {
        McpServerConfig config = new McpServerConfig();
        config.setTransport("stdio");
        config.setCommand("test-command");
        McpClientBuilder builder = mock(McpClientBuilder.class);
        McpClientWrapper wrapper = mock(McpClientWrapper.class);
        Toolkit toolkit = mock(Toolkit.class);
        Toolkit.ToolRegistration registration = mock(Toolkit.ToolRegistration.class);
        when(builder.stdioTransport("test-command", List.of(), Map.of())).thenReturn(builder);
        when(builder.buildAsync()).thenReturn(Mono.just(wrapper));
        when(toolkit.registration()).thenReturn(registration);
        when(registration.mcpClient(wrapper)).thenReturn(registration);
        List<McpServerRegistrationResult> results = new ArrayList<>();

        try (MockedStatic<McpClientBuilder> builders = mockStatic(McpClientBuilder.class)) {
            builders.when(() -> McpClientBuilder.create("healthy")).thenReturn(builder);

            McpServerRegistrar.register(toolkit, Map.of("healthy", config), results::add);
        }

        assertEquals(1, results.size());
        McpServerRegistrationResult result = results.get(0);
        assertEquals(McpServerRegistrationResult.Status.SUCCESS, result.status());
        assertEquals("healthy", result.serverName());
        assertEquals("stdio", result.transport());
        assertNull(result.cause());
        verify(registration).apply();
        verify(wrapper, never()).close();
    }

    @Test
    void registrationFailure_closesWrapperAndPreservesCloseFailureAsSuppressed() {
        McpServerConfig config = new McpServerConfig();
        config.setTransport("stdio");
        config.setCommand("test-command");
        McpClientBuilder builder = mock(McpClientBuilder.class);
        McpClientWrapper wrapper = mock(McpClientWrapper.class);
        Toolkit toolkit = mock(Toolkit.class);
        Toolkit.ToolRegistration registration = mock(Toolkit.ToolRegistration.class);
        IllegalStateException registrationFailure =
                new IllegalStateException("registration failure");
        IllegalArgumentException closeFailure = new IllegalArgumentException("close failure");
        when(builder.stdioTransport("test-command", List.of(), Map.of())).thenReturn(builder);
        when(builder.buildAsync()).thenReturn(Mono.just(wrapper));
        when(toolkit.registration()).thenReturn(registration);
        when(registration.mcpClient(wrapper)).thenReturn(registration);
        doThrow(registrationFailure).when(registration).apply();
        doThrow(closeFailure).when(wrapper).close();
        List<McpServerRegistrationResult> results = new ArrayList<>();

        try (MockedStatic<McpClientBuilder> builders = mockStatic(McpClientBuilder.class)) {
            builders.when(() -> McpClientBuilder.create("broken")).thenReturn(builder);

            McpServerRegistrar.register(toolkit, Map.of("broken", config), results::add);
        }

        assertEquals(1, results.size());
        McpServerRegistrationResult result = results.get(0);
        assertEquals(McpServerRegistrationResult.Status.FAILED, result.status());
        assertSame(registrationFailure, result.cause());
        assertEquals(1, registrationFailure.getSuppressed().length);
        assertSame(closeFailure, registrationFailure.getSuppressed()[0]);
        verify(wrapper).close();
    }

    @Test
    void listenerFailure_doesNotChangeRegistrationOrStopLaterEntries() {
        McpServerConfig first = new McpServerConfig();
        first.setTransport("unsupported-one");
        McpServerConfig second = new McpServerConfig();
        second.setTransport("unsupported-two");
        Map<String, McpServerConfig> servers = new LinkedHashMap<>();
        servers.put("first", first);
        servers.put("second", second);
        List<String> notified = new ArrayList<>();

        assertDoesNotThrow(
                () ->
                        McpServerRegistrar.register(
                                new Toolkit(),
                                servers,
                                result -> {
                                    notified.add(result.serverName());
                                    if ("first".equals(result.serverName())) {
                                        throw new IllegalStateException("listener failure");
                                    }
                                }));

        assertEquals(List.of("first", "second"), notified);
    }
}
