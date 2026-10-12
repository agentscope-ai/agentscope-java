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
package io.agentscope.core.tool;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import io.agentscope.core.message.ToolResultBlock;
import io.agentscope.core.tool.mcp.McpClientWrapper;
import io.agentscope.core.tool.mcp.McpTool;
import io.modelcontextprotocol.spec.McpSchema;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import reactor.core.Disposable;
import reactor.core.publisher.Mono;
import reactor.core.publisher.Sinks;

class McpClientManagerTest {

    private McpClientManager manager;
    private Method shouldRegisterToolMethod;

    @BeforeEach
    void setUp() throws Exception {
        ToolRegistry toolRegistry = new ToolRegistry();
        ToolGroupManager groupManager = new ToolGroupManager();

        // Create manager with a no-op callback
        manager =
                new McpClientManager(
                        toolRegistry,
                        groupManager,
                        (tool, groupName, mcpClientName, presetParams) -> {
                            // no-op callback for testing
                        });

        // Get the private method using reflection
        shouldRegisterToolMethod =
                McpClientManager.class.getDeclaredMethod(
                        "shouldRegisterTool", String.class, List.class, List.class);
        shouldRegisterToolMethod.setAccessible(true);
    }

    private boolean invokeShouldRegisterTool(
            String toolName, List<String> enableTools, List<String> disableTools) throws Exception {
        return (boolean)
                shouldRegisterToolMethod.invoke(manager, toolName, enableTools, disableTools);
    }

    // ==================== Tests for null/empty lists ====================

    @Test
    void testShouldRegisterTool_BothListsNull_ReturnsTrue() throws Exception {
        // When both lists are null, all tools should be registered
        assertTrue(invokeShouldRegisterTool("anyTool", null, null));
    }

    @Test
    void testShouldRegisterTool_BothListsEmpty_ReturnsTrue() throws Exception {
        // When both lists are empty, all tools should be registered
        assertTrue(
                invokeShouldRegisterTool(
                        "anyTool", Collections.emptyList(), Collections.emptyList()));
    }

    // ==================== Tests for disableTools only ====================

    @Test
    void testShouldRegisterTool_DisableToolsContainsTool_ReturnsFalse() throws Exception {
        // When tool is in disableTools, it should not be registered
        List<String> disableTools = Arrays.asList("tool1", "tool2", "tool3");
        assertFalse(invokeShouldRegisterTool("tool2", null, disableTools));
    }

    @Test
    void testShouldRegisterTool_DisableToolsDoesNotContainTool_ReturnsTrue() throws Exception {
        // When tool is NOT in disableTools, it should be registered
        List<String> disableTools = Arrays.asList("tool1", "tool2", "tool3");
        assertTrue(invokeShouldRegisterTool("tool4", null, disableTools));
    }

    // ==================== Tests for enableTools only ====================

    @Test
    void testShouldRegisterTool_EnableToolsContainsTool_ReturnsTrue() throws Exception {
        // When tool is in enableTools, it should be registered
        List<String> enableTools = Arrays.asList("tool1", "tool2", "tool3");
        assertTrue(invokeShouldRegisterTool("tool2", enableTools, null));
    }

    @Test
    void testShouldRegisterTool_EnableToolsDoesNotContainTool_ReturnsFalse() throws Exception {
        // When tool is NOT in enableTools, it should not be registered
        List<String> enableTools = Arrays.asList("tool1", "tool2", "tool3");
        assertFalse(invokeShouldRegisterTool("tool4", enableTools, null));
    }

    // ==================== Tests for both lists specified ====================

    @Test
    void testShouldRegisterTool_BothListsSpecified_EnableToolsTakesPrecedence() throws Exception {
        // enableTools is checked last, so it takes precedence
        List<String> enableTools = Arrays.asList("tool1", "tool2");
        List<String> disableTools = Arrays.asList("tool2", "tool3");

        // tool1: not in disableTools, in enableTools -> true
        assertTrue(invokeShouldRegisterTool("tool1", enableTools, disableTools));

        // tool2: in disableTools (would be false), but in enableTools -> true (enableTools wins)
        assertTrue(invokeShouldRegisterTool("tool2", enableTools, disableTools));

        // tool3: in disableTools, not in enableTools -> false
        assertFalse(invokeShouldRegisterTool("tool3", enableTools, disableTools));

        // tool4: not in either list, but enableTools is specified -> false
        assertFalse(invokeShouldRegisterTool("tool4", enableTools, disableTools));
    }

    // Test for preset parameters functionality in McpClientManager
    @Test
    void testRegisterMcpClient_WithPresetParametersMapping() {
        // Create mocks
        ToolRegistry toolRegistry = mock(ToolRegistry.class);
        ToolGroupManager groupManager = mock(ToolGroupManager.class);
        McpClientWrapper clientWrapper = mock(McpClientWrapper.class);

        // Create a manager with a tracking callback
        boolean[] callbackCalled = {false};
        McpClientManager manager =
                new McpClientManager(
                        toolRegistry,
                        groupManager,
                        (tool, groupName, mcpClientName, presetParams) -> {
                            callbackCalled[0] = true;
                            // Verify that presetParams is passed correctly
                            assertNotNull(presetParams);
                            assertTrue(presetParams.containsKey("param3"));
                            assertEquals("preset_value", presetParams.get("param3"));
                        });

        // Setup client wrapper
        when(clientWrapper.getName()).thenReturn("test-client");
        when(clientWrapper.initialize()).thenReturn(Mono.empty());

        // Create a mock MCP tool
        McpSchema.Tool mockMcpTool = mock(McpSchema.Tool.class);
        when(mockMcpTool.name()).thenReturn("test-tool");
        when(mockMcpTool.description()).thenReturn("Test tool description");

        // Create schema with properties
        Map<String, Object> properties = new HashMap<>();
        properties.put("param1", Map.of("type", "string"));
        properties.put("param2", Map.of("type", "number"));
        List<String> required = List.of("param1");

        McpSchema.JsonSchema schema =
                new McpSchema.JsonSchema("object", properties, required, null, null, null);
        when(mockMcpTool.inputSchema()).thenReturn(schema);

        when(clientWrapper.listTools()).thenReturn(Mono.just(List.of(mockMcpTool)));

        // Define preset parameters mapping
        Map<String, Map<String, Object>> presetParamsMapping = new HashMap<>();
        Map<String, Object> toolPresetParams = new HashMap<>();
        toolPresetParams.put("param3", "preset_value");
        toolPresetParams.put("param4", 42);
        presetParamsMapping.put("test-tool", toolPresetParams);

        // Execute registration
        manager.registerMcpClient(clientWrapper, null, null, null, presetParamsMapping).block();

        // Verify interactions
        verify(clientWrapper).initialize();
        verify(clientWrapper).listTools();
        assertTrue(callbackCalled[0]);
    }

    @Test
    void testRegisterMcpClient_WithPresetParametersKeySetExclusion() {
        // Create mocks
        ToolRegistry toolRegistry = mock(ToolRegistry.class);
        ToolGroupManager groupManager = mock(ToolGroupManager.class);
        McpClientWrapper clientWrapper = mock(McpClientWrapper.class);

        // Create a manager with a tracking callback
        boolean[] callbackCalled = {false};
        McpClientManager manager =
                new McpClientManager(
                        toolRegistry,
                        groupManager,
                        (tool, groupName, mcpClientName, presetParams) -> {
                            callbackCalled[0] = true;
                            // Verify that presetParams is passed correctly
                            assertNotNull(presetParams);
                            assertTrue(presetParams.containsKey("units"));
                            assertEquals("celsius", presetParams.get("units"));
                        });

        // Setup client wrapper
        when(clientWrapper.getName()).thenReturn("test-client");
        when(clientWrapper.initialize()).thenReturn(Mono.empty());

        // Create a mock MCP tool with schema that has some parameters
        McpSchema.Tool mockMcpTool = mock(McpSchema.Tool.class);
        when(mockMcpTool.name()).thenReturn("weather-tool");
        when(mockMcpTool.description()).thenReturn("Weather tool");

        // Create schema with multiple parameters
        Map<String, Object> properties = new HashMap<>();
        properties.put("city", Map.of("type", "string", "description", "City name"));
        properties.put("units", Map.of("type", "string", "description", "Temperature units"));
        properties.put("forecast_days", Map.of("type", "number", "description", "Number of days"));

        List<String> required = List.of("city");

        McpSchema.JsonSchema schema =
                new McpSchema.JsonSchema("object", properties, required, null, null, null);
        when(mockMcpTool.inputSchema()).thenReturn(schema);

        when(clientWrapper.listTools()).thenReturn(Mono.just(List.of(mockMcpTool)));

        // Define preset parameters that should be excluded from schema
        Map<String, Map<String, Object>> presetParamsMapping = new HashMap<>();
        Map<String, Object> toolPresetParams = new HashMap<>();
        toolPresetParams.put("units", "celsius"); // This should be excluded from schema
        toolPresetParams.put("forecast_days", 5); // This should be excluded from schema
        presetParamsMapping.put("weather-tool", toolPresetParams);

        // Execute registration - this should exercise the keySet exclusion logic
        manager.registerMcpClient(clientWrapper, null, null, null, presetParamsMapping).block();

        // Verify interactions occurred
        verify(clientWrapper).initialize();
        verify(clientWrapper).listTools();
        assertTrue(callbackCalled[0]);
    }

    @Test
    void testRegisterMcpClient_WithEmptyPresetParameters() {
        // Create mocks
        ToolRegistry toolRegistry = mock(ToolRegistry.class);
        ToolGroupManager groupManager = mock(ToolGroupManager.class);
        McpClientWrapper clientWrapper = mock(McpClientWrapper.class);

        // Create a manager with a tracking callback
        boolean[] callbackCalled = {false};
        McpClientManager manager =
                new McpClientManager(
                        toolRegistry,
                        groupManager,
                        (tool, groupName, mcpClientName, presetParams) -> {
                            callbackCalled[0] = true;
                        });

        // Setup client wrapper
        when(clientWrapper.getName()).thenReturn("test-client");
        when(clientWrapper.initialize()).thenReturn(Mono.empty());

        // Create a mock MCP tool
        McpSchema.Tool mockMcpTool = mock(McpSchema.Tool.class);
        when(mockMcpTool.name()).thenReturn("simple-tool");
        when(mockMcpTool.description()).thenReturn("Simple tool");

        when(mockMcpTool.inputSchema())
                .thenReturn(
                        new McpSchema.JsonSchema(
                                "object", new HashMap<>(), new ArrayList<>(), null, null, null));

        when(clientWrapper.listTools()).thenReturn(Mono.just(List.of(mockMcpTool)));

        // Use empty preset parameters mapping
        Map<String, Map<String, Object>> presetParamsMapping = new HashMap<>();
        presetParamsMapping.put("simple-tool", new HashMap<>()); // Empty preset params

        // Execute registration
        manager.registerMcpClient(clientWrapper, null, null, null, presetParamsMapping).block();

        verify(clientWrapper).initialize();
        verify(clientWrapper).listTools();
        assert callbackCalled[0];
    }

    @Test
    void testRegisterMcpClient_WithNullPresetParametersForTool() {
        // Create mocks
        ToolRegistry toolRegistry = mock(ToolRegistry.class);
        ToolGroupManager groupManager = mock(ToolGroupManager.class);
        McpClientWrapper clientWrapper = mock(McpClientWrapper.class);

        // Create a manager with a tracking callback
        boolean[] callbackCalled = {false};
        McpClientManager manager =
                new McpClientManager(
                        toolRegistry,
                        groupManager,
                        (tool, groupName, mcpClientName, presetParams) -> {
                            callbackCalled[0] = true;
                        });

        // Setup client wrapper
        when(clientWrapper.getName()).thenReturn("test-client");
        when(clientWrapper.initialize()).thenReturn(Mono.empty());

        // Create a mock MCP tool
        McpSchema.Tool mockMcpTool = mock(McpSchema.Tool.class);
        when(mockMcpTool.name()).thenReturn("null-param-tool");
        when(mockMcpTool.description()).thenReturn("Tool with null preset params");

        when(mockMcpTool.inputSchema())
                .thenReturn(
                        new McpSchema.JsonSchema(
                                "object", new HashMap<>(), new ArrayList<>(), null, null, null));

        when(clientWrapper.listTools()).thenReturn(Mono.just(List.of(mockMcpTool)));

        // Use null preset parameters mapping entirely
        // This exercises the null check in the presetParametersMapping != null condition
        manager.registerMcpClient(clientWrapper, null, null, null, null).block();

        verify(clientWrapper).initialize();
        verify(clientWrapper).listTools();
        assertTrue(callbackCalled[0]);
    }

    @Test
    void testRegisterMcpClient_PreservesOutputSchemaInRegisteredTool() {
        ToolRegistry toolRegistry = mock(ToolRegistry.class);
        ToolGroupManager groupManager = mock(ToolGroupManager.class);
        McpClientWrapper clientWrapper = mock(McpClientWrapper.class);

        AgentTool[] registeredTool = new AgentTool[1];
        String[] registeredGroupName = new String[1];
        String[] registeredClientName = new String[1];
        Map<String, Object>[] registeredPresetParams = new Map[1];

        McpClientManager manager =
                new McpClientManager(
                        toolRegistry,
                        groupManager,
                        (tool, groupName, mcpClientName, presetParams) -> {
                            registeredTool[0] = tool;
                            registeredGroupName[0] = groupName;
                            registeredClientName[0] = mcpClientName;
                            registeredPresetParams[0] = presetParams;
                        });

        when(clientWrapper.getName()).thenReturn("test-client");
        when(clientWrapper.initialize()).thenReturn(Mono.empty());

        McpSchema.Tool mockMcpTool = mock(McpSchema.Tool.class);
        when(mockMcpTool.name()).thenReturn("structured-tool");
        when(mockMcpTool.description()).thenReturn("Tool with output schema");
        when(mockMcpTool.inputSchema())
                .thenReturn(
                        new McpSchema.JsonSchema(
                                "object",
                                Map.of("query", Map.of("type", "string")),
                                List.of("query"),
                                null,
                                null,
                                null));

        Map<String, Object> outputSchema =
                Map.of(
                        "type",
                        "object",
                        "properties",
                        Map.of(
                                "result",
                                Map.of("type", "string"),
                                "confidence",
                                Map.of("type", "number")));
        when(mockMcpTool.outputSchema()).thenReturn(outputSchema);
        when(clientWrapper.listTools()).thenReturn(Mono.just(List.of(mockMcpTool)));

        Map<String, Object> toolPresetParams = Map.of("temperature", 0.2);
        Map<String, Map<String, Object>> presetParamsMapping =
                Map.of("structured-tool", toolPresetParams);

        manager.registerMcpClient(clientWrapper, null, null, "mcp-group", presetParamsMapping)
                .block();

        verify(clientWrapper).initialize();
        verify(clientWrapper).listTools();
        assertNotNull(registeredTool[0]);
        assertEquals(outputSchema, registeredTool[0].getOutputSchema());
        assertTrue(registeredTool[0] instanceof McpTool);
        assertNull(((McpTool) registeredTool[0]).getPresetArguments());
        assertEquals("mcp-group", registeredGroupName[0]);
        assertEquals("test-client", registeredClientName[0]);
        assertEquals(toolPresetParams, registeredPresetParams[0]);
    }

    @Test
    void testRegisterMcpClient_PropagatesInitializationFailure() {
        McpClientWrapper clientWrapper = mock(McpClientWrapper.class);
        IllegalStateException failure = new IllegalStateException("initialize failure");
        when(clientWrapper.getName()).thenReturn("broken-client");
        when(clientWrapper.initialize()).thenReturn(Mono.error(failure));

        IllegalStateException thrown =
                assertThrows(
                        IllegalStateException.class,
                        () -> manager.registerMcpClient(clientWrapper).block());

        assertSame(failure, thrown);
        verify(clientWrapper).initialize();
        verify(clientWrapper, never()).listTools();
    }

    @Test
    void testRegisterMcpClient_PropagatesListToolsFailure() {
        McpClientWrapper clientWrapper = mock(McpClientWrapper.class);
        IllegalStateException failure = new IllegalStateException("list tools failure");
        when(clientWrapper.getName()).thenReturn("broken-client");
        when(clientWrapper.initialize()).thenReturn(Mono.empty());
        when(clientWrapper.listTools()).thenReturn(Mono.error(failure));

        IllegalStateException thrown =
                assertThrows(
                        IllegalStateException.class,
                        () -> manager.registerMcpClient(clientWrapper).block());

        assertSame(failure, thrown);
        verify(clientWrapper).initialize();
        verify(clientWrapper).listTools();
    }

    // ==================== Tests for metadata propagation ====================

    private McpClientManager newCapturingManager(AgentTool[] registeredTool) {
        ToolRegistry toolRegistry = mock(ToolRegistry.class);
        ToolGroupManager groupManager = mock(ToolGroupManager.class);
        return new McpClientManager(
                toolRegistry,
                groupManager,
                (tool, groupName, mcpClientName, presetParams) -> registeredTool[0] = tool);
    }

    private McpClientWrapper newMetaMockWrapper() {
        McpClientWrapper clientWrapper = mock(McpClientWrapper.class);
        when(clientWrapper.getName()).thenReturn("meta-client");
        when(clientWrapper.initialize()).thenReturn(Mono.empty());

        McpSchema.Tool mockMcpTool = mock(McpSchema.Tool.class);
        when(mockMcpTool.name()).thenReturn("meta-tool");
        when(mockMcpTool.description()).thenReturn("Tool for meta propagation tests");
        when(mockMcpTool.inputSchema())
                .thenReturn(
                        new McpSchema.JsonSchema("object", Map.of(), List.of(), null, null, null));
        when(clientWrapper.listTools()).thenReturn(Mono.just(List.of(mockMcpTool)));
        return clientWrapper;
    }

    @Test
    void testRegisterMcpClient_ToolFlagDefaultsTrueRegardlessOfWrapper() {
        // The wrapper's isPropagateMeta() is unstubbed (Mockito default: false). The manager
        // must NOT read it: the per-tool flag only carries registration configuration and
        // defaults to true; the connection-level switch applies live at call time instead.
        AgentTool[] registeredTool = new AgentTool[1];
        McpClientManager metaManager = newCapturingManager(registeredTool);
        McpClientWrapper clientWrapper = newMetaMockWrapper();

        metaManager.registerMcpClient(clientWrapper).block();

        assertNotNull(registeredTool[0]);
        assertTrue(registeredTool[0] instanceof McpTool);
        assertTrue(((McpTool) registeredTool[0]).isPropagateMeta());
        verify(clientWrapper, never()).isPropagateMeta();
    }

    @Test
    void testRegisterMcpClient_PropagateMetaOverrideAppliedToTool() {
        McpClientWrapper clientWrapper = newMetaMockWrapper();

        AgentTool[] offTool = new AgentTool[1];
        McpClientManager offManager = newCapturingManager(offTool);
        offManager
                .registerMcpClient(clientWrapper, null, null, null, null, null, Boolean.FALSE)
                .block();
        assertNotNull(offTool[0]);
        assertFalse(((McpTool) offTool[0]).isPropagateMeta());

        AgentTool[] onTool = new AgentTool[1];
        McpClientManager onManager = newCapturingManager(onTool);
        onManager
                .registerMcpClient(clientWrapper, null, null, null, null, null, Boolean.TRUE)
                .block();
        assertNotNull(onTool[0]);
        assertTrue(((McpTool) onTool[0]).isPropagateMeta());
    }

    @Test
    void testRegisterMcpClient_PerToolOverrideWinsOverRegistrationDefault() {
        McpClientWrapper clientWrapper = newMetaMockWrapper();

        // Per-tool entry (false) wins over the registration-level default (true)
        AgentTool[] restricted = new AgentTool[1];
        McpClientManager restrictedManager = newCapturingManager(restricted);
        restrictedManager
                .registerMcpClient(
                        clientWrapper,
                        null,
                        null,
                        null,
                        null,
                        null,
                        Boolean.TRUE,
                        Map.of("meta-tool", false))
                .block();
        assertNotNull(restricted[0]);
        assertFalse(((McpTool) restricted[0]).isPropagateMeta());

        // Entries for other tool names do not affect this tool
        AgentTool[] unaffected = new AgentTool[1];
        McpClientManager unaffectedManager = newCapturingManager(unaffected);
        unaffectedManager
                .registerMcpClient(
                        clientWrapper,
                        null,
                        null,
                        null,
                        null,
                        null,
                        Boolean.TRUE,
                        Map.of("meta-tool", true))
                .block();
        assertNotNull(unaffected[0]);
        assertTrue(((McpTool) unaffected[0]).isPropagateMeta());
        // A successful registration keeps the wrapper open (and managed via mcpClients).
        verify(clientWrapper, never()).close();
    }

    @Test
    void testRegisterMcpClient_UnknownPerToolOverrideNameFailsRegistration() {
        // A silencing override for a tool the server does not expose must fail loudly at
        // registration instead of being silently dropped: the tool would otherwise keep
        // propagating metadata, exactly the leak this switch is meant to prevent.
        McpClientWrapper clientWrapper = newMetaMockWrapper();

        McpClientManager metaManager = newCapturingManager(new AgentTool[1]);
        IllegalArgumentException thrown =
                assertThrows(
                        IllegalArgumentException.class,
                        () ->
                                metaManager
                                        .registerMcpClient(
                                                clientWrapper,
                                                null,
                                                null,
                                                null,
                                                null,
                                                null,
                                                Boolean.TRUE,
                                                Map.of("some-other-tool", false))
                                        .block());
        assertTrue(thrown.getMessage().contains("some-other-tool"));
        // The client was initialized before the check failed; nobody else can close it.
        verify(clientWrapper).close();
    }

    @Test
    void testRegisterMcpClient_OverrideForFilteredOutToolFailsRegistration() {
        // An override for a tool excluded by the enable/disable filter is equally dead
        // configuration and must fail loudly at registration.
        McpClientWrapper clientWrapper = newMetaMockWrapper();

        McpClientManager metaManager = newCapturingManager(new AgentTool[1]);
        IllegalArgumentException thrown =
                assertThrows(
                        IllegalArgumentException.class,
                        () ->
                                metaManager
                                        .registerMcpClient(
                                                clientWrapper,
                                                null,
                                                List.of("meta-tool"),
                                                null,
                                                null,
                                                null,
                                                Boolean.TRUE,
                                                Map.of("meta-tool", false))
                                        .block());
        assertTrue(thrown.getMessage().contains("meta-tool"));
        // The client was initialized before the check failed; nobody else can close it.
        verify(clientWrapper).close();
    }

    @Test
    void testRegisterMcpClient_ReadOnlyHintAppliedToTool() {
        // The MCP server's annotations.readOnlyHint drives ToolBase.readOnly, which in turn
        // controls whether the tool may run without explicit permission (McpTool).
        McpClientWrapper clientWrapper = mock(McpClientWrapper.class);
        when(clientWrapper.getName()).thenReturn("annotated-client");
        when(clientWrapper.initialize()).thenReturn(Mono.empty());

        McpSchema.Tool readOnlyTool = mock(McpSchema.Tool.class);
        when(readOnlyTool.name()).thenReturn("readonly_tool");
        when(readOnlyTool.description()).thenReturn("Read-only tool");
        when(readOnlyTool.inputSchema())
                .thenReturn(
                        new McpSchema.JsonSchema("object", Map.of(), List.of(), null, null, null));
        when(readOnlyTool.annotations())
                .thenReturn(new McpSchema.ToolAnnotations(null, true, null, null, null, null));

        McpSchema.Tool writableTool = mock(McpSchema.Tool.class);
        when(writableTool.name()).thenReturn("writable_tool");
        when(writableTool.description()).thenReturn("Writable tool");
        when(writableTool.inputSchema())
                .thenReturn(
                        new McpSchema.JsonSchema("object", Map.of(), List.of(), null, null, null));
        when(writableTool.annotations())
                .thenReturn(new McpSchema.ToolAnnotations(null, false, null, null, null, null));

        when(clientWrapper.listTools()).thenReturn(Mono.just(List.of(readOnlyTool, writableTool)));

        AgentTool[] registered = new AgentTool[2];
        McpClientManager annotatedManager =
                new McpClientManager(
                        mock(ToolRegistry.class),
                        mock(ToolGroupManager.class),
                        (tool, groupName, mcpClientName, presetParams) -> {
                            if ("readonly_tool".equals(tool.getName())) {
                                registered[0] = tool;
                            } else {
                                registered[1] = tool;
                            }
                        });
        annotatedManager.registerMcpClient(clientWrapper).block();

        assertTrue(registered[0] instanceof McpTool);
        assertTrue(((McpTool) registered[0]).isReadOnly());
        assertTrue(registered[1] instanceof McpTool);
        assertFalse(((McpTool) registered[1]).isReadOnly());
    }

    // ==================== Client-name reservation (issue #3328 review) ====================

    @Test
    void testRegisterMcpClient_DuplicateNameFromDistinctWrapperFailsBeforeInitialize() {
        McpClientWrapper first = mock(McpClientWrapper.class);
        when(first.getName()).thenReturn("slot-client");
        when(first.initialize()).thenReturn(Mono.empty());
        when(first.listTools()).thenReturn(Mono.just(Collections.emptyList()));
        manager.registerMcpClient(first).block();

        // A different wrapper reusing the tracked name may point at a different server; taking
        // it over would leave the first wrapper untracked and never closed.
        McpClientWrapper second = mock(McpClientWrapper.class);
        when(second.getName()).thenReturn("slot-client");
        when(second.initialize()).thenReturn(Mono.empty());
        when(second.listTools()).thenReturn(Mono.just(Collections.emptyList()));

        IllegalStateException ex =
                assertThrows(
                        IllegalStateException.class,
                        () -> manager.registerMcpClient(second).block());
        assertTrue(ex.getMessage().contains("slot-client"), ex.getMessage());
        // Rejected at the name reservation: no connection work for the refused wrapper.
        verify(second, never()).initialize();
        assertSame(first, manager.getMcpClient("slot-client"), "the first wrapper stays tracked");
    }

    @Test
    void testRegisterMcpClient_FailedRegistrationReleasesNameReservation() {
        McpClientWrapper failing = mock(McpClientWrapper.class);
        when(failing.getName()).thenReturn("slot-client");
        IllegalStateException failure = new IllegalStateException("initialize failure");
        when(failing.initialize()).thenReturn(Mono.error(failure));

        assertSame(
                failure,
                assertThrows(
                        IllegalStateException.class,
                        () -> manager.registerMcpClient(failing).block()));
        assertFalse(manager.getMcpClientNames().contains("slot-client"));

        // A later registration under the same name by a different wrapper must succeed: the
        // failed attempt released its reservation instead of blocking the name forever.
        McpClientWrapper healthy = mock(McpClientWrapper.class);
        when(healthy.getName()).thenReturn("slot-client");
        when(healthy.initialize()).thenReturn(Mono.empty());
        when(healthy.listTools()).thenReturn(Mono.just(Collections.emptyList()));
        manager.registerMcpClient(healthy).block();

        assertSame(healthy, manager.getMcpClient("slot-client"));
    }

    @Test
    void testRegisterMcpClient_SameWrapperMayReregisterUnderItsOwnName() {
        McpClientWrapper wrapper = mock(McpClientWrapper.class);
        when(wrapper.getName()).thenReturn("slot-client");
        when(wrapper.initialize()).thenReturn(Mono.empty());
        when(wrapper.listTools()).thenReturn(Mono.just(Collections.emptyList()));

        manager.registerMcpClient(wrapper).block();
        // Tool-list refresh with the same wrapper instance stays an idempotent re-registration.
        manager.registerMcpClient(wrapper).block();

        assertSame(wrapper, manager.getMcpClient("slot-client"));
    }

    @Test
    void testRegisterMcpClient_RefreshFailureKeepsExistingRegistrationTracked() {
        McpClientWrapper wrapper = mock(McpClientWrapper.class);
        when(wrapper.getName()).thenReturn("slot-client");
        when(wrapper.initialize()).thenReturn(Mono.empty());
        when(wrapper.listTools())
                .thenReturn(Mono.just(Collections.emptyList()))
                .thenReturn(Mono.error(new IllegalStateException("refresh failure")));

        manager.registerMcpClient(wrapper).block();
        assertThrows(IllegalStateException.class, () -> manager.registerMcpClient(wrapper).block());

        // The failed refresh must not untrack the live registration it was refreshing: only a
        // reservation created by this very call is released on failure.
        assertSame(wrapper, manager.getMcpClient("slot-client"));
        // ...and it must not close it either: the tracked registration still serves its tools
        // through this wrapper, so a refresh failure leaves the connection tracked AND open.
        verify(wrapper, never()).close();
    }

    @Test
    void testRegisterMcpClient_NonSubscribedMonoReservesNothing() {
        McpClientWrapper first = mock(McpClientWrapper.class);
        when(first.getName()).thenReturn("slot-client");
        when(first.initialize()).thenReturn(Mono.empty());
        when(first.listTools()).thenReturn(Mono.just(Collections.emptyList()));

        // Build the registration Mono but NEVER subscribe: admission runs at subscription time,
        // so an abandoned attempt must strand neither the name nor a connection.
        manager.registerMcpClient(first);

        McpClientWrapper second = mock(McpClientWrapper.class);
        when(second.getName()).thenReturn("slot-client");
        when(second.initialize()).thenReturn(Mono.empty());
        when(second.listTools()).thenReturn(Mono.just(Collections.emptyList()));
        manager.registerMcpClient(second).block();

        assertSame(second, manager.getMcpClient("slot-client"));
        verify(first, never()).initialize();
    }

    @Test
    void testRegisterMcpClient_CancelledRegistrationReleasesNameAndCloses() {
        McpClientWrapper stalling = mock(McpClientWrapper.class);
        when(stalling.getName()).thenReturn("slot-client");
        when(stalling.initialize()).thenReturn(Mono.never());

        Disposable subscription = manager.registerMcpClient(stalling).subscribe();
        // In flight, the name is claimed...
        assertSame(stalling, manager.getMcpClient("slot-client"));

        // ...and cancelling (not an error signal) must still release the claim and close the
        // already-initialized wrapper instead of stranding both.
        subscription.dispose();

        verify(stalling).close();

        McpClientWrapper next = mock(McpClientWrapper.class);
        when(next.getName()).thenReturn("slot-client");
        when(next.initialize()).thenReturn(Mono.empty());
        when(next.listTools()).thenReturn(Mono.just(Collections.emptyList()));
        manager.registerMcpClient(next).block();

        assertSame(next, manager.getMcpClient("slot-client"));
    }

    @Test
    void testRegisterMcpClient_FailedFirstAttemptDoesNotUntrackConcurrentRefresh() {
        McpClientWrapper wrapper = mock(McpClientWrapper.class);
        when(wrapper.getName()).thenReturn("slot-client");
        when(wrapper.listTools()).thenReturn(Mono.just(Collections.emptyList()));
        AtomicInteger initializeCalls = new AtomicInteger();
        Sinks.One<Void> stalledInitialize = Sinks.one();
        when(wrapper.initialize())
                .thenAnswer(
                        invocation ->
                                initializeCalls.incrementAndGet() == 1
                                        ? stalledInitialize.asMono()
                                        : Mono.empty());

        // Attempt A (first registration) claims the name and stalls inside initialize().
        manager.registerMcpClient(wrapper).subscribe(value -> {}, error -> {});
        // Attempt B is a concurrent refresh of the same wrapper; it completes and re-points the
        // claim to its own admission token.
        manager.registerMcpClient(wrapper).block();

        // A now fails. Its release is a CAS against its own token: it must neither untrack the
        // registration B completed nor close the wrapper B is actively serving.
        stalledInitialize.tryEmitError(new IllegalStateException("attempt A failed"));

        assertSame(
                wrapper,
                manager.getMcpClient("slot-client"),
                "B's completed registration survives");
        verify(wrapper, never()).close();
    }

    @Test
    void testRegisterMcpClient_CloseFailureDuringCleanupDoesNotMaskOriginalError() {
        McpClientWrapper failing = mock(McpClientWrapper.class);
        when(failing.getName()).thenReturn("slot-client");
        IllegalStateException original = new IllegalStateException("initialize failure");
        when(failing.initialize()).thenReturn(Mono.error(original));
        doThrow(new RuntimeException("close failure")).when(failing).close();

        // The best-effort close throwing must surface the ORIGINAL error, not the cleanup one.
        assertSame(
                original,
                assertThrows(
                        IllegalStateException.class,
                        () -> manager.registerMcpClient(failing).block()));
        assertFalse(manager.getMcpClientNames().contains("slot-client"));
    }

    @Test
    void testRegisterMcpClient_CancelledRefreshLeavesLiveRegistrationTrackedAndOpen() {
        McpClientWrapper wrapper = mock(McpClientWrapper.class);
        when(wrapper.getName()).thenReturn("slot-client");
        when(wrapper.initialize()).thenReturn(Mono.empty());
        when(wrapper.listTools()).thenReturn(Mono.just(Collections.emptyList()));
        manager.registerMcpClient(wrapper).block();

        // A refresh of the already-tracked wrapper that is cancelled must release nothing: the
        // live registration (same wrapper) owns the claim.
        AtomicInteger calls = new AtomicInteger();
        when(wrapper.initialize())
                .thenAnswer(inv -> calls.incrementAndGet() == 1 ? Mono.never() : Mono.empty());
        Disposable cancelled = manager.registerMcpClient(wrapper).subscribe(v -> {}, e -> {});
        cancelled.dispose();

        assertSame(wrapper, manager.getMcpClient("slot-client"));
        verify(wrapper, never()).close();
    }

    @Test
    void testRegisterMcpClient_SynchronousInitializeThrowStillReleasesAndCloses() {
        McpClientWrapper throwing = mock(McpClientWrapper.class);
        when(throwing.getName()).thenReturn("slot-client");
        when(throwing.initialize()).thenThrow(new RuntimeException("synchronous boom"));

        // initialize() throwing SYNCHRONOUSLY (external wrapper subclasses may) must still
        // surface as an onError signal so the claim is released and the wrapper closed —
        // otherwise the name would strand after the throw escaped the pipeline.
        RuntimeException thrown =
                assertThrows(
                        RuntimeException.class, () -> manager.registerMcpClient(throwing).block());
        assertEquals("synchronous boom", thrown.getMessage());
        assertFalse(manager.getMcpClientNames().contains("slot-client"));
        verify(throwing).close();
    }

    @Test
    void testRegisterMcpClient_TwoFailedAttemptsDoNotStrandTheClaim() {
        McpClientWrapper wrapper = mock(McpClientWrapper.class);
        when(wrapper.getName()).thenReturn("slot-client");
        when(wrapper.listTools()).thenReturn(Mono.just(Collections.emptyList()));
        AtomicInteger calls = new AtomicInteger();
        Sinks.One<Void> firstInitialize = Sinks.one();
        when(wrapper.initialize())
                .thenAnswer(
                        inv ->
                                calls.incrementAndGet() == 1
                                        ? firstInitialize.asMono()
                                        : Mono.error(new IllegalStateException("refresh boom")));

        // Attempt A (first registration) claims the name and stalls inside initialize().
        manager.registerMcpClient(wrapper).subscribe(v -> {}, e -> {});
        // Attempt B (same-wrapper refresh) fails. It never created the claim, so it must not
        // release — and must not steal the release responsibility from A either.
        assertThrows(IllegalStateException.class, () -> manager.registerMcpClient(wrapper).block());
        assertSame(wrapper, manager.getMcpClient("slot-client"), "B's failure keeps A's claim");
        verify(wrapper, never()).close();

        // A now fails too: the claim it created is released and the wrapper closed — exactly
        // once, by the attempt that created it. Two failing attempts can never mutually
        // excuse each other into a stranded, un-closed wrapper.
        firstInitialize.tryEmitError(new IllegalStateException("first boom"));

        assertFalse(manager.getMcpClientNames().contains("slot-client"));
        verify(wrapper, times(1)).close();
    }

    @Test
    void testRegisterMcpClient_RemovedDuringFlightIsNotResurrectedOnSuccess() {
        McpClientWrapper wrapper = mock(McpClientWrapper.class);
        when(wrapper.getName()).thenReturn("slot-client");
        Sinks.One<Void> initializeGate = Sinks.one();
        when(wrapper.initialize()).thenReturn(initializeGate.asMono());
        when(wrapper.listTools()).thenReturn(Mono.just(Collections.emptyList()));

        manager.registerMcpClient(wrapper).subscribe(v -> {}, e -> {});
        // The client is explicitly removed (and closed) while the registration is still in
        // flight...
        manager.removeMcpClient("slot-client").block();

        // ...so the success hook must NOT resurrect the tracking entry.
        initializeGate.tryEmitEmpty();

        assertFalse(manager.getMcpClientNames().contains("slot-client"));
    }

    @Test
    void testRegisterMcpClient_OwnerFailureAfterRemovalDoesNotDoubleClose() {
        McpClientWrapper wrapper = mock(McpClientWrapper.class);
        when(wrapper.getName()).thenReturn("slot-client");
        Sinks.One<Void> initializeGate = Sinks.one();
        when(wrapper.initialize()).thenReturn(initializeGate.asMono());
        when(wrapper.listTools()).thenReturn(Mono.just(Collections.emptyList()));

        manager.registerMcpClient(wrapper).subscribe(v -> {}, e -> {});
        // The tracked entry is removed (and the wrapper closed) while the owning attempt is
        // still in flight...
        manager.removeMcpClient("slot-client").block();

        // ...so when that attempt fails late, its release finds no claim: it must skip both
        // the untrack and a SECOND close.
        initializeGate.tryEmitError(new IllegalStateException("late failure"));

        assertFalse(manager.getMcpClientNames().contains("slot-client"));
        verify(wrapper, times(1)).close();
    }

    @Test
    void testRegisterMcpClient_LateSuccessDoesNotMarkForeignClaim() {
        McpClientWrapper first = mock(McpClientWrapper.class);
        when(first.getName()).thenReturn("slot-client");
        Sinks.One<Void> gate = Sinks.one();
        when(first.initialize()).thenReturn(gate.asMono());
        when(first.listTools()).thenReturn(Mono.just(Collections.emptyList()));
        manager.registerMcpClient(first).subscribe(v -> {}, e -> {});

        // The in-flight attempt's name is removed and re-taken by ANOTHER wrapper...
        manager.removeMcpClient("slot-client").block();
        McpClientWrapper second = mock(McpClientWrapper.class);
        when(second.getName()).thenReturn("slot-client");
        when(second.initialize()).thenReturn(Mono.empty());
        when(second.listTools()).thenReturn(Mono.just(Collections.emptyList()));
        manager.registerMcpClient(second).block();

        // ...so when the first attempt succeeds late, its completion hook must neither mark the
        // foreign claim completed nor overwrite it.
        gate.tryEmitEmpty();

        assertSame(second, manager.getMcpClient("slot-client"));
    }

    @Test
    void testRemoveMcpClientAndLookupOfUnknownNameAreNoops() {
        assertDoesNotThrow(() -> manager.removeMcpClient("ghost").block());
        assertNull(manager.getMcpClient("ghost"));

        McpClientWrapper wrapper = mock(McpClientWrapper.class);
        when(wrapper.getName()).thenReturn("slot-client");
        when(wrapper.initialize()).thenReturn(Mono.empty());
        when(wrapper.listTools()).thenReturn(Mono.just(Collections.emptyList()));
        manager.registerMcpClient(wrapper).block();

        assertSame(wrapper, manager.getMcpClient("slot-client"));
        manager.removeMcpClient("slot-client").block();
        assertFalse(manager.getMcpClientNames().contains("slot-client"));
        assertNull(manager.getMcpClient("slot-client"));
        verify(wrapper).close();
    }

    @Test
    void testRegisterMcpClient_StaleLateSuccessDoesNotProtectRecreatedClaimOfSameInstance() {
        McpClientWrapper wrapper = mock(McpClientWrapper.class);
        when(wrapper.getName()).thenReturn("slot-client");
        when(wrapper.listTools()).thenReturn(Mono.just(Collections.emptyList()));
        AtomicInteger initCalls = new AtomicInteger();
        Sinks.One<Void> firstGate = Sinks.one();
        Sinks.One<Void> secondGate = Sinks.one();
        when(wrapper.initialize())
                .thenAnswer(
                        inv ->
                                initCalls.incrementAndGet() == 1
                                        ? firstGate.asMono()
                                        : secondGate.asMono());

        // Attempt 1 (creator) is in flight; the client is then removed and the SAME wrapper
        // instance re-registered as attempt 2, creating a fresh claim.
        manager.registerMcpClient(wrapper).subscribe(v -> {}, e -> {});
        manager.removeMcpClient("slot-client").block();
        manager.registerMcpClient(wrapper).subscribe(v -> {}, e -> {});

        // The stale attempt 1 succeeds late: its claim object is gone, so it must not mark
        // the re-created claim completed — otherwise attempt 2's own failure could never
        // release it (a permanent strand, exactly what this logic exists to prevent).
        firstGate.tryEmitEmpty();

        // Attempt 2 fails: it is the last one out of an uncompleted claim it created — release.
        secondGate.tryEmitError(new IllegalStateException("second boom"));
        assertFalse(manager.getMcpClientNames().contains("slot-client"));
    }

    @Test
    void testRegisterMcpClient_FailedFirstRegistrationRollsBackItsTools() {
        ToolRegistry registry = new ToolRegistry();
        ToolGroupManager groups = new ToolGroupManager();
        groups.createToolGroup("mcp-group", "MCP tools", true);
        McpClientManager rollbackManager =
                new McpClientManager(
                        registry,
                        groups,
                        (tool, groupName, mcpClientName, presetParams) -> {
                            registry.registerTool(
                                    tool.getName(),
                                    tool,
                                    new RegisteredToolFunction(tool, null, mcpClientName));
                            if (groupName != null) {
                                groups.addToolToGroup(groupName, tool.getName());
                            }
                        });

        // A local tool already owns "beta": registering a client serving [alpha, beta] must
        // fail mid-stream AFTER alpha was registered — and roll alpha back with the release.
        AgentTool localBeta =
                new AgentTool() {
                    @Override
                    public String getName() {
                        return "beta";
                    }

                    @Override
                    public String getDescription() {
                        return "local beta keeps the name";
                    }

                    @Override
                    public Map<String, Object> getParameters() {
                        return Map.of("type", "object", "properties", Map.of());
                    }

                    @Override
                    public Mono<ToolResultBlock> callAsync(ToolCallParam param) {
                        return Mono.just(ToolResultBlock.text("beta"));
                    }
                };
        registry.registerTool("beta", localBeta, new RegisteredToolFunction(localBeta, null, null));

        McpClientWrapper wrapper = mock(McpClientWrapper.class);
        when(wrapper.getName()).thenReturn("rollback-client");
        when(wrapper.initialize()).thenReturn(Mono.empty());
        McpSchema.Tool alpha = remoteToolMock("alpha");
        McpSchema.Tool beta = remoteToolMock("beta");
        when(wrapper.listTools()).thenReturn(Mono.just(List.of(alpha, beta)));

        assertThrows(
                IllegalStateException.class,
                () ->
                        rollbackManager
                                .registerMcpClient(wrapper, null, null, "mcp-group", null)
                                .block());

        assertNull(registry.getTool("alpha"), "partial tools rolled back with the failed claim");
        assertFalse(
                groups.isGroupedTool("alpha"), "rollback must not leave ghost group membership");
        assertSame(localBeta, registry.getTool("beta"), "the pre-existing local tool survives");
        assertFalse(rollbackManager.getMcpClientNames().contains("rollback-client"));
        verify(wrapper).close();
    }

    @Test
    void testRegisterMcpClient_ClientRemovedMidRegistrationFailsLoudOnNextTool() {
        ToolRegistry registry = new ToolRegistry();
        AtomicReference<McpClientManager> holder = new AtomicReference<>();
        McpClientManager midManager =
                new McpClientManager(
                        registry,
                        new ToolGroupManager(),
                        (tool, groupName, mcpClientName, presetParams) -> {
                            registry.registerTool(
                                    tool.getName(),
                                    tool,
                                    new RegisteredToolFunction(tool, null, mcpClientName));
                            // The client is removed mid-registration, right after the first
                            // tool landed: the second tool write must fail loud instead of
                            // orphaning onto the closing connection.
                            holder.get().removeMcpClient("mid-client").block();
                        });
        holder.set(midManager);

        McpClientWrapper wrapper = mock(McpClientWrapper.class);
        when(wrapper.getName()).thenReturn("mid-client");
        when(wrapper.initialize()).thenReturn(Mono.empty());
        McpSchema.Tool alpha = remoteToolMock("alpha");
        McpSchema.Tool beta = remoteToolMock("beta");
        when(wrapper.listTools()).thenReturn(Mono.just(List.of(alpha, beta)));

        IllegalStateException ex =
                assertThrows(
                        IllegalStateException.class,
                        () -> midManager.registerMcpClient(wrapper).block());
        assertTrue(ex.getMessage().contains("in flight"), ex.getMessage());

        assertNull(registry.getTool("alpha"));
        assertNull(registry.getTool("beta"), "the aborted tool never reached the registry");
        assertFalse(midManager.getMcpClientNames().contains("mid-client"));
        // Closed exactly once, by the removal — the aborted attempt does not double-close.
        verify(wrapper, times(1)).close();
    }

    @Test
    void testRegisterMcpClient_ToolWrittenAfterRemovalRollsBackOnStaleError() {
        ToolRegistry registry = new ToolRegistry();
        AtomicReference<McpClientManager> holder = new AtomicReference<>();
        McpClientManager lateWriteManager =
                new McpClientManager(
                        registry,
                        new ToolGroupManager(),
                        (tool, groupName, mcpClientName, presetParams) -> {
                            // The removal lands between the liveness check and this write:
                            // the name-sweep inside removeMcpClient cannot see a tool that is
                            // not registered yet — so the attempt's own stale-error rollback
                            // must remove it, or it would linger bound to the closed wrapper.
                            holder.get().removeMcpClient("late-write-client").block();
                            registry.registerTool(
                                    tool.getName(),
                                    tool,
                                    new RegisteredToolFunction(tool, null, mcpClientName));
                        });
        holder.set(lateWriteManager);

        McpClientWrapper wrapper = mock(McpClientWrapper.class);
        when(wrapper.getName()).thenReturn("late-write-client");
        when(wrapper.initialize()).thenReturn(Mono.empty());
        McpSchema.Tool alpha = remoteToolMock("alpha");
        McpSchema.Tool beta = remoteToolMock("beta");
        when(wrapper.listTools()).thenReturn(Mono.just(List.of(alpha, beta)));

        // alpha's write happens AFTER the removal (orphan window), beta aborts on the liveness
        // check, and the attempt finishes ON_ERROR with a claim that is no longer current.
        assertThrows(
                IllegalStateException.class,
                () -> lateWriteManager.registerMcpClient(wrapper).block());

        assertNull(
                registry.getTool("alpha"),
                "the stale-error path must roll back tools the removal sweep missed");
        assertNull(registry.getTool("beta"));
        verify(wrapper, times(1)).close();
    }

    @Test
    void testRegisterMcpClient_StaleCompletionRollsBackItsToolsWithoutDoubleClose() {
        ToolRegistry registry = new ToolRegistry();
        AtomicReference<McpClientManager> holder = new AtomicReference<>();
        McpClientManager staleManager =
                new McpClientManager(
                        registry,
                        new ToolGroupManager(),
                        (tool, groupName, mcpClientName, presetParams) -> {
                            registry.registerTool(
                                    tool.getName(),
                                    tool,
                                    new RegisteredToolFunction(tool, null, mcpClientName));
                            // Removal happens after the LAST tool write, before completion:
                            // the attempt finishes as a stale success and must not resurrect
                            // the tracking entry nor mark a foreign claim.
                            holder.get().removeMcpClient("stale-client").block();
                        });
        holder.set(staleManager);

        McpClientWrapper wrapper = mock(McpClientWrapper.class);
        when(wrapper.getName()).thenReturn("stale-client");
        when(wrapper.initialize()).thenReturn(Mono.empty());
        McpSchema.Tool alpha = remoteToolMock("alpha");
        when(wrapper.listTools()).thenReturn(Mono.just(List.of(alpha)));

        // Completes normally (single tool; removal rides inside its callback).
        staleManager.registerMcpClient(wrapper).block();

        assertFalse(staleManager.getMcpClientNames().contains("stale-client"));
        assertNull(registry.getTool("alpha"), "no resurrection after the removal");
        verify(wrapper, times(1)).close();
    }

    @Test
    void testRegisterMcpClient_CreatorFailureWhileRefreshInFlightLeavesConnectionOpen() {
        McpClientWrapper wrapper = mock(McpClientWrapper.class);
        when(wrapper.getName()).thenReturn("slot-client");
        when(wrapper.listTools()).thenReturn(Mono.just(Collections.emptyList()));
        AtomicInteger initCalls = new AtomicInteger();
        Sinks.One<Void> firstGate = Sinks.one();
        Sinks.One<Void> secondGate = Sinks.one();
        when(wrapper.initialize())
                .thenAnswer(
                        inv ->
                                initCalls.incrementAndGet() == 1
                                        ? firstGate.asMono()
                                        : secondGate.asMono());

        // A (creator) and B (same-wrapper refresh) are both in flight on one shared claim.
        manager.registerMcpClient(wrapper).subscribe(v -> {}, e -> {});
        manager.registerMcpClient(wrapper).subscribe(v -> {}, e -> {});

        // A fails while B is still running: B is using this very connection — it must stay
        // tracked AND open.
        firstGate.tryEmitError(new IllegalStateException("A failed"));
        assertSame(wrapper, manager.getMcpClient("slot-client"));
        verify(wrapper, never()).close();

        // B then fails too: last one out of a claim that never completed — release and close.
        secondGate.tryEmitError(new IllegalStateException("B failed"));
        assertFalse(manager.getMcpClientNames().contains("slot-client"));
        verify(wrapper, times(1)).close();
    }

    private static McpSchema.Tool remoteToolMock(String name) {
        McpSchema.Tool tool = mock(McpSchema.Tool.class);
        when(tool.name()).thenReturn(name);
        when(tool.description()).thenReturn("remote " + name);
        when(tool.inputSchema())
                .thenReturn(
                        new McpSchema.JsonSchema("object", Map.of(), List.of(), null, null, null));
        return tool;
    }
}
