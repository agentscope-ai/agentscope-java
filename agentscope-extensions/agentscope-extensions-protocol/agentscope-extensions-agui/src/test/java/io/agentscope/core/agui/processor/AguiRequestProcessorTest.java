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
package io.agentscope.core.agui.processor;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import io.agentscope.core.ReActAgent;
import io.agentscope.core.agent.Agent;
import io.agentscope.core.agent.RuntimeContext;
import io.agentscope.core.agui.AguiException;
import io.agentscope.core.agui.adapter.AguiAdapterConfig;
import io.agentscope.core.agui.model.AguiMessage;
import io.agentscope.core.agui.model.RunAgentInput;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import reactor.core.publisher.Flux;

/** Unit tests for AguiRequestProcessor. */
class AguiRequestProcessorTest {

    @Test
    void extractLatestUserMessagePreservesFullRunInputMetadata() {
        AguiRequestProcessor processor =
                AguiRequestProcessor.builder().agentResolver(mock(AgentResolver.class)).build();
        AguiMessage firstUser = AguiMessage.userMessage("msg-1", "first");
        AguiMessage lastUser = AguiMessage.userMessage("msg-3", "last");
        RunAgentInput input =
                RunAgentInput.builder()
                        .threadId("thread-1")
                        .runId("run-1")
                        .messages(
                                List.of(
                                        firstUser,
                                        AguiMessage.assistantMessage("msg-2", "ok"),
                                        lastUser))
                        .state(Map.of("cursor", 8))
                        .forwardedProps(Map.of("agentId", "agent-a"))
                        .build();

        RunAgentInput extracted = processor.extractLatestUserMessage(input);

        assertEquals(List.of(lastUser), extracted.getMessages());
        assertEquals(input.getState(), extracted.getState());
        assertEquals(input.getForwardedProps(), extracted.getForwardedProps());
    }

    @Test
    void processThrowsAguiExceptionWhenAgentNotReActAgent() {
        AgentResolver agentResolver = mock(AgentResolver.class);
        Agent mockAgent = mock(Agent.class);
        when(agentResolver.resolveAgent(anyString(), anyString())).thenReturn(mockAgent);

        AguiRequestProcessor processor =
                AguiRequestProcessor.builder().agentResolver(agentResolver).build();

        RunAgentInput input =
                RunAgentInput.builder()
                        .threadId("thread-1")
                        .runId("run-1")
                        .messages(List.of(AguiMessage.userMessage("msg-1", "Hello")))
                        .build();

        assertThrows(
                AguiException.class,
                () -> processor.process(input, null, null),
                "Should throw AguiException for non-ReActAgent");
    }

    @Test
    void processWithReActAgentReturnsProcessResult() {
        AgentResolver agentResolver = mock(AgentResolver.class);
        ReActAgent reactAgent = mock(ReActAgent.class);
        when(agentResolver.resolveAgent(anyString(), anyString())).thenReturn(reactAgent);
        when(reactAgent.streamEvents(anyList(), any(RuntimeContext.class)))
                .thenReturn(Flux.empty());

        AguiRequestProcessor processor =
                AguiRequestProcessor.builder().agentResolver(agentResolver).build();

        RunAgentInput input =
                RunAgentInput.builder()
                        .threadId("thread-1")
                        .runId("run-1")
                        .messages(List.of(AguiMessage.userMessage("msg-1", "Hello")))
                        .build();

        AguiRequestProcessor.ProcessResult result = processor.process(input, null, null);

        assertNotNull(result);
        assertSame(reactAgent, result.agent());
        assertNotNull(result.events());
    }

    // --- resolveAgentId: pathAgentId (highest priority) ---

    @Test
    void resolveAgentIdUsesPathAgentId() {
        AguiRequestProcessor processor =
                AguiRequestProcessor.builder().agentResolver(mock(AgentResolver.class)).build();

        RunAgentInput input =
                RunAgentInput.builder()
                        .threadId("thread-1")
                        .runId("run-1")
                        .messages(List.of(AguiMessage.userMessage("msg-1", "Hello")))
                        .forwardedProps(Map.of("agentId", "from-props"))
                        .build();

        String result = processor.resolveAgentId(input, "from-header", "from-path");
        assertEquals("from-path", result);
    }

    @Test
    void resolveAgentIdSkipsEmptyPathAgentId() {
        AguiRequestProcessor processor =
                AguiRequestProcessor.builder().agentResolver(mock(AgentResolver.class)).build();

        RunAgentInput input =
                RunAgentInput.builder()
                        .threadId("thread-1")
                        .runId("run-1")
                        .messages(List.of(AguiMessage.userMessage("msg-1", "Hello")))
                        .build();

        String result = processor.resolveAgentId(input, null, "");
        assertEquals("default", result, "Empty pathAgentId should be skipped");
    }

    // --- resolveAgentId: headerAgentId ---

    @Test
    void resolveAgentIdUsesHeaderAgentIdWhenNoPath() {
        AguiRequestProcessor processor =
                AguiRequestProcessor.builder().agentResolver(mock(AgentResolver.class)).build();

        RunAgentInput input =
                RunAgentInput.builder()
                        .threadId("thread-1")
                        .runId("run-1")
                        .messages(List.of(AguiMessage.userMessage("msg-1", "Hello")))
                        .forwardedProps(Map.of("agentId", "from-props"))
                        .build();

        String result = processor.resolveAgentId(input, "from-header", null);
        assertEquals("from-header", result);
    }

    @Test
    void resolveAgentIdSkipsEmptyHeaderAgentId() {
        AguiRequestProcessor processor =
                AguiRequestProcessor.builder().agentResolver(mock(AgentResolver.class)).build();

        RunAgentInput input =
                RunAgentInput.builder()
                        .threadId("thread-1")
                        .runId("run-1")
                        .messages(List.of(AguiMessage.userMessage("msg-1", "Hello")))
                        .forwardedProps(Map.of("agentId", "from-props"))
                        .build();

        String result = processor.resolveAgentId(input, "", null);
        assertEquals("from-props", result, "Empty headerAgentId should skip to forwardedProps");
    }

    // --- resolveAgentId: forwardedProps.agentId ---

    @Test
    void resolveAgentIdUsesForwardedPropsAgentId() {
        AguiRequestProcessor processor =
                AguiRequestProcessor.builder().agentResolver(mock(AgentResolver.class)).build();

        RunAgentInput input =
                RunAgentInput.builder()
                        .threadId("thread-1")
                        .runId("run-1")
                        .messages(List.of(AguiMessage.userMessage("msg-1", "Hello")))
                        .forwardedProps(Map.of("agentId", "props-agent"))
                        .build();

        String result = processor.resolveAgentId(input, null, null);
        assertEquals("props-agent", result);
    }

    // --- resolveAgentId: config default agent ID ---

    @Test
    void resolveAgentIdUsesConfigDefaultAgentId() {
        AguiAdapterConfig config =
                AguiAdapterConfig.builder().defaultAgentId("config-default").build();
        AguiRequestProcessor processor =
                AguiRequestProcessor.builder()
                        .agentResolver(mock(AgentResolver.class))
                        .config(config)
                        .build();

        RunAgentInput input =
                RunAgentInput.builder()
                        .threadId("thread-1")
                        .runId("run-1")
                        .messages(List.of(AguiMessage.userMessage("msg-1", "Hello")))
                        .build();

        String result = processor.resolveAgentId(input, null, null);
        assertEquals("config-default", result);
    }

    // --- resolveAgentId: "default" fallback ---

    @Test
    void resolveAgentIdFallsBackToDefaultLiteral() {
        AguiRequestProcessor processor =
                AguiRequestProcessor.builder().agentResolver(mock(AgentResolver.class)).build();

        RunAgentInput input =
                RunAgentInput.builder()
                        .threadId("thread-1")
                        .runId("run-1")
                        .messages(List.of(AguiMessage.userMessage("msg-1", "Hello")))
                        .build();

        String result = processor.resolveAgentId(input, null, null);
        assertEquals("default", result);
    }

    // --- extractLatestUserMessage: null messages ---

    @Test
    void extractLatestUserMessageWithNullMessagesReturnsInput() {
        AguiRequestProcessor processor =
                AguiRequestProcessor.builder().agentResolver(mock(AgentResolver.class)).build();

        RunAgentInput input =
                RunAgentInput.builder()
                        .threadId("thread-null")
                        .runId("run-null")
                        .messages(null)
                        .state(Map.of("key", "val"))
                        .build();

        RunAgentInput result = processor.extractLatestUserMessage(input);
        assertSame(input, result, "Null messages should return original input unchanged");
    }

    // --- extractLatestUserMessage: empty messages ---

    @Test
    void extractLatestUserMessageWithEmptyMessagesReturnsInput() {
        AguiRequestProcessor processor =
                AguiRequestProcessor.builder().agentResolver(mock(AgentResolver.class)).build();

        RunAgentInput input =
                RunAgentInput.builder()
                        .threadId("thread-empty")
                        .runId("run-empty")
                        .messages(Collections.emptyList())
                        .build();

        RunAgentInput result = processor.extractLatestUserMessage(input);
        assertSame(input, result, "Empty messages should return original input unchanged");
    }

    // --- extractLatestUserMessage: no user message in list ---

    @Test
    void extractLatestUserMessageWithNoUserRoleReturnsInput() {
        AguiRequestProcessor processor =
                AguiRequestProcessor.builder().agentResolver(mock(AgentResolver.class)).build();

        RunAgentInput input =
                RunAgentInput.builder()
                        .threadId("thread-no-user")
                        .runId("run-no-user")
                        .messages(
                                List.of(
                                        AguiMessage.systemMessage("msg-1", "system"),
                                        AguiMessage.assistantMessage("msg-2", "assistant"),
                                        AguiMessage.toolMessage("msg-3", "tc-1", "tool result")))
                        .state(Map.of("key", "val"))
                        .build();

        RunAgentInput result = processor.extractLatestUserMessage(input);
        assertSame(input, result, "No user message should return original input unchanged");
    }

    // --- process: hasMemory=true branch ---

    @Test
    void processWithMemoryExtractsLatestUserMessage() {
        AgentResolver agentResolver = mock(AgentResolver.class);
        ReActAgent reactAgent = mock(ReActAgent.class);
        when(agentResolver.resolveAgent(anyString(), anyString())).thenReturn(reactAgent);
        when(agentResolver.hasMemory("thread-mem")).thenReturn(true);
        when(reactAgent.streamEvents(anyList(), any(RuntimeContext.class)))
                .thenReturn(Flux.empty());

        AguiRequestProcessor processor =
                AguiRequestProcessor.builder().agentResolver(agentResolver).build();

        RunAgentInput input =
                RunAgentInput.builder()
                        .threadId("thread-mem")
                        .runId("run-mem")
                        .messages(
                                List.of(
                                        AguiMessage.userMessage("msg-1", "old"),
                                        AguiMessage.assistantMessage("msg-2", "reply"),
                                        AguiMessage.userMessage("msg-3", "latest")))
                        .build();

        AguiRequestProcessor.ProcessResult result = processor.process(input, null, null);

        assertNotNull(result);
        assertNotNull(result.events());
    }

    // --- process: with custom config ---

    @Test
    void processWithCustomConfigReturnsProcessResult() {
        AgentResolver agentResolver = mock(AgentResolver.class);
        ReActAgent reactAgent = mock(ReActAgent.class);
        when(agentResolver.resolveAgent(anyString(), anyString())).thenReturn(reactAgent);
        when(reactAgent.streamEvents(anyList(), any(RuntimeContext.class)))
                .thenReturn(Flux.empty());

        AguiAdapterConfig config = AguiAdapterConfig.builder().emitStateEvents(false).build();
        AguiRequestProcessor processor =
                AguiRequestProcessor.builder().agentResolver(agentResolver).config(config).build();

        RunAgentInput input =
                RunAgentInput.builder()
                        .threadId("thread-config")
                        .runId("run-config")
                        .messages(List.of(AguiMessage.userMessage("msg-1", "Hello")))
                        .build();

        AguiRequestProcessor.ProcessResult result = processor.process(input, null, null);

        assertNotNull(result);
        assertNotNull(result.events());
    }
}
