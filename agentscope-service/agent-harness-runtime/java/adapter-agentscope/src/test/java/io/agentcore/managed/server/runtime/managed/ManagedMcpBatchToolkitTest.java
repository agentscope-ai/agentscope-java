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

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.agentscope.core.agent.RuntimeContext;
import io.agentscope.core.message.TextBlock;
import io.agentscope.core.message.ToolResultState;
import io.agentscope.core.message.ToolUseBlock;
import io.agentscope.core.tool.AgentTool;
import io.agentscope.core.tool.ToolMergeMode;
import io.agentscope.core.tool.ToolRequestConfig;
import io.agentscope.core.tool.Toolkit;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import reactor.core.publisher.Mono;

class ManagedMcpBatchToolkitTest {
    @Test
    void evaluationEnvelopePreservesNestedStructuredContentErrorsAndLongText() throws Exception {
        var tool = (ManagedMcpTool) toolkit.getTool(call.getName());
        var mapper = new ObjectMapper();
        var original = mapper.createObjectNode();
        original.put("isError", true);
        original.putArray("content")
                .addObject()
                .put("type", "text")
                .put("text", "x".repeat(50_000));
        original.putObject("structuredContent").putObject("nested").putNull("empty");
        var result = tool.evaluationResult(original.toString());
        assertEquals(ToolResultState.ERROR, result.getState());
        assertEquals(original, mapper.readTree(((TextBlock) result.getOutput().get(0)).getText()));
        var empty = tool.evaluationResult("{\"content\":[],\"isError\":false}");
        assertEquals(ToolResultState.SUCCESS, empty.getState());
        assertEquals(
                0,
                mapper.readTree(((TextBlock) empty.getOutput().get(0)).getText())
                        .get("content")
                        .size());
    }

    final Toolkit delegate = spy(new Toolkit());
    final ManagedMcpBatchToolkit toolkit = new ManagedMcpBatchToolkit(delegate);
    final AtomicReference<RuntimeContext> observed = new AtomicReference<>();
    final RuntimeContext context =
            RuntimeContext.builder()
                    .put(ManagedTurnExecution.class, mock(ManagedTurnExecution.class))
                    .build();
    final ToolUseBlock call =
            ToolUseBlock.builder()
                    .id("call")
                    .name("mcp__echo")
                    .input(Map.of("value", "hello"))
                    .content("{\"value\":\"hello\"}")
                    .build();

    @BeforeEach
    void setup() {
        toolkit.registerTool(
                new ManagedMcpTool(
                        new ManagedMcpCatalog.Tool(
                                "mcp",
                                "mcp__echo",
                                "echo",
                                "Echo",
                                Map.of(
                                        "type",
                                        "object",
                                        "properties",
                                        Map.of("value", Map.of("type", "string")),
                                        "required",
                                        List.of("value")),
                                null,
                                true),
                        mock(ManagedToolRequests.class),
                        new ObjectMapper()));
        doAnswer(
                        invocation -> {
                            observed.set(invocation.getArgument(3));
                            return Mono.just(List.of());
                        })
                .when(delegate)
                .callTools(
                        anyList(),
                        isNull(),
                        isNull(),
                        any(RuntimeContext.class),
                        any(ToolRequestConfig.class),
                        isNull());
    }

    void invoke(List<ToolUseBlock> calls, ToolRequestConfig config) {
        toolkit.callTools(calls, null, null, context, config, null).block();
    }

    @Test
    void batchesOnlyTheApprovedListPassedIntoToolkit() {
        invoke(List.of(call), ToolRequestConfig.NONE);
        assertNotNull(observed.get().get(AgentScopeMcpBatch.class));
        assertNull(context.get(AgentScopeMcpBatch.class));
        assertInstanceOf(ManagedMcpBatchToolkit.class, toolkit.copy());
    }

    @Test
    void anInactiveToolCannotBeExecutedAheadOfSdkAvailabilityChecks() {
        toolkit.createToolGroup("private", "Private", false);
        toolkit.addToolToGroup("private", call.getName());
        invoke(List.of(call), ToolRequestConfig.NONE);
        assertNull(observed.get().get(AgentScopeMcpBatch.class));
    }

    @Test
    void hiddenBackendAndInvalidArgumentsKeepTheSdkValidationPath() {
        invoke(List.of(call), new ToolRequestConfig(Map.of(), ToolMergeMode.EXTERNAL_ONLY));
        assertNull(observed.get().get(AgentScopeMcpBatch.class));
        var invalid =
                ToolUseBlock.builder()
                        .id("invalid")
                        .name(call.getName())
                        .input(Map.of())
                        .content("{}")
                        .build();
        invoke(List.of(call, invalid), ToolRequestConfig.NONE);
        assertNull(observed.get().get(AgentScopeMcpBatch.class));
    }

    @Test
    void skillBuilderRegistrationDoesNotDisableManagedMcpBatches() {
        AgentTool skill = mock(AgentTool.class);
        when(skill.getName()).thenReturn("load_skill");
        when(skill.getDescription()).thenReturn("Load a skill");
        when(skill.getParameters()).thenReturn(Map.of("type", "object"));
        toolkit.registration().agentTool(skill).apply();
        invoke(List.of(call), ToolRequestConfig.NONE);
        assertNotNull(observed.get().get(AgentScopeMcpBatch.class));
        assertNotNull(toolkit.copy().getTool(call.getName()));
    }

    @Test
    void opaqueMcpPresetRegistrationStillUsesSdkInjection() {
        toolkit.registration()
                .agentTool(toolkit.getTool(call.getName()))
                .presetParameters(Map.of(call.getName(), Map.of("value", "preset")))
                .apply();
        invoke(List.of(call), ToolRequestConfig.NONE);
        assertNull(observed.get().get(AgentScopeMcpBatch.class));
    }

    @Test
    void mixedCallsAndPresetsKeepSdkOrderingAndParameterInjection() {
        invoke(
                List.of(
                        call,
                        ToolUseBlock.builder()
                                .id("other")
                                .name("other")
                                .input(Map.of())
                                .content("{}")
                                .build()),
                ToolRequestConfig.NONE);
        assertNull(observed.get().get(AgentScopeMcpBatch.class));
        toolkit.updateToolPresetParameters(call.getName(), Map.of("value", "preset"));
        invoke(List.of(call), ToolRequestConfig.NONE);
        assertNull(observed.get().get(AgentScopeMcpBatch.class));
    }
}
