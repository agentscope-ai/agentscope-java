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
package io.agentscope.core.agui.processor;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.same;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import io.agentscope.core.agent.Agent;
import io.agentscope.core.agent.Event;
import io.agentscope.core.agent.EventType;
import io.agentscope.core.agent.RuntimeContext;
import io.agentscope.core.agent.StreamOptions;
import io.agentscope.core.agui.adapter.AguiAdapterConfig;
import io.agentscope.core.agui.event.AguiEvent;
import io.agentscope.core.agui.model.AguiMessage;
import io.agentscope.core.agui.model.RunAgentInput;
import io.agentscope.core.message.Msg;
import io.agentscope.core.message.MsgRole;
import io.agentscope.core.message.TextBlock;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import reactor.core.publisher.Flux;

class AguiRequestProcessorTest {

    @Test
    void processSeedsRuntimeContextFromThreadIdWhenAbsent() {
        AgentResolver resolver = mock(AgentResolver.class);
        Agent agent = mock(Agent.class);
        AguiRequestProcessor processor =
                AguiRequestProcessor.builder()
                        .agentResolver(resolver)
                        .config(AguiAdapterConfig.defaultConfig())
                        .build();

        RunAgentInput input =
                RunAgentInput.builder()
                        .threadId("thread-1")
                        .runId("run-1")
                        .messages(List.of(AguiMessage.userMessage("msg-1", "Hello")))
                        .build();

        when(resolver.resolveAgent("default", "thread-1")).thenReturn(agent);
        when(resolver.hasMemory("thread-1")).thenReturn(false);

        Msg assistantMsg =
                Msg.builder()
                        .id("assistant-1")
                        .role(MsgRole.ASSISTANT)
                        .content(TextBlock.builder().text("ok").build())
                        .build();
        Event event = new Event(EventType.REASONING, assistantMsg, true);
        when(agent.stream(anyList(), any(StreamOptions.class), any(RuntimeContext.class)))
                .thenReturn(Flux.just(event));

        AguiRequestProcessor.ProcessResult result = processor.process(input, null, null);
        List<AguiEvent> events = result.events().collectList().block();

        assertNotNull(events);
        assertEquals(4, events.size());

        ArgumentCaptor<RuntimeContext> captor = ArgumentCaptor.forClass(RuntimeContext.class);
        verify(agent).stream(anyList(), any(StreamOptions.class), captor.capture());
        assertEquals("thread-1", captor.getValue().getSessionId());
        assertNull(captor.getValue().getUserId());
    }

    @Test
    void processUsesProvidedRuntimeContext() {
        AgentResolver resolver = mock(AgentResolver.class);
        Agent agent = mock(Agent.class);
        AguiRequestProcessor processor =
                AguiRequestProcessor.builder()
                        .agentResolver(resolver)
                        .config(AguiAdapterConfig.defaultConfig())
                        .build();

        RuntimeContext runtimeContext =
                RuntimeContext.builder().sessionId("session-override").userId("alice").build();

        RunAgentInput input =
                RunAgentInput.builder()
                        .threadId("thread-1")
                        .runId("run-1")
                        .messages(List.of(AguiMessage.userMessage("msg-1", "Hello")))
                        .build();

        when(resolver.resolveAgent("default", "thread-1")).thenReturn(agent);
        when(resolver.hasMemory("thread-1")).thenReturn(false);
        when(agent.stream(anyList(), any(StreamOptions.class), same(runtimeContext)))
                .thenReturn(Flux.empty());

        AguiRequestProcessor.ProcessResult result =
                processor.process(input, null, null, runtimeContext);
        List<AguiEvent> events = result.events().collectList().block();

        assertNotNull(events);
        assertEquals(2, events.size());
        verify(agent).stream(anyList(), any(StreamOptions.class), same(runtimeContext));
    }
}
