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
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import io.agentscope.core.ReActAgent;
import io.agentscope.core.agent.Agent;
import io.agentscope.core.agent.RuntimeContext;
import io.agentscope.core.agui.adapter.AguiAdapterConfig;
import io.agentscope.core.agui.adapter.AguiAgentAdapter;
import io.agentscope.core.agui.model.AguiMessage;
import io.agentscope.core.agui.model.RunAgentInput;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
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
    void processPassesCustomRuntimeContextThroughAdapterWithoutLosingAguiMetadata() {
        AgentResolver resolver = mock(AgentResolver.class);
        ReActAgent agent = mock(ReActAgent.class);
        ArgumentCaptor<RuntimeContext> contextCaptor =
                ArgumentCaptor.forClass(RuntimeContext.class);
        when(resolver.resolveAgent("default", "thread-1")).thenReturn(agent);
        when(agent.streamEvents(anyList(), contextCaptor.capture())).thenReturn(Flux.empty());
        RuntimeContext callerContext =
                RuntimeContext.builder()
                        .sessionId("caller-session")
                        .userId("user-1")
                        .put("tenant", "tenant-a")
                        .build();
        RunAgentInput input =
                RunAgentInput.builder()
                        .threadId("thread-1")
                        .runId("run-1")
                        .messages(List.of(AguiMessage.userMessage("msg-1", "hello")))
                        .build();
        AguiRequestProcessor processor =
                AguiRequestProcessor.builder().agentResolver(resolver).build();

        processor.process(input, null, null, callerContext).events().collectList().block();

        RuntimeContext context = contextCaptor.getValue();
        assertEquals("thread-1", context.getSessionId());
        assertEquals("user-1", context.getUserId());
        assertEquals("tenant-a", context.get("tenant"));
        assertEquals(input, context.get(RunAgentInput.class));
        assertEquals("thread-1", context.get(AguiAgentAdapter.RUNTIME_CONTEXT_THREAD_ID_KEY));
        assertEquals("run-1", context.get(AguiAgentAdapter.RUNTIME_CONTEXT_RUN_ID_KEY));
    }

    @Test
    void processUsesCustomAdapterFactory() {
        AgentResolver resolver = mock(AgentResolver.class);
        ReActAgent agent = mock(ReActAgent.class);
        ArgumentCaptor<RuntimeContext> contextCaptor =
                ArgumentCaptor.forClass(RuntimeContext.class);
        when(resolver.resolveAgent("default", "thread-1")).thenReturn(agent);
        when(agent.streamEvents(anyList(), contextCaptor.capture())).thenReturn(Flux.empty());
        RunAgentInput input =
                RunAgentInput.builder()
                        .threadId("thread-1")
                        .runId("run-1")
                        .messages(List.of(AguiMessage.userMessage("msg-1", "hello")))
                        .build();
        AguiRequestProcessor processor =
                AguiRequestProcessor.builder()
                        .agentResolver(resolver)
                        .adapterFactory(CustomAdapter::new)
                        .build();

        processor.process(input, null, null).events().collectList().block();

        RuntimeContext context = contextCaptor.getValue();
        assertEquals("custom-adapter", context.get("adapter"));
        assertEquals("thread-1", context.getSessionId());
        assertEquals("run-1", context.get(AguiAgentAdapter.RUNTIME_CONTEXT_RUN_ID_KEY));
    }

    private static final class CustomAdapter extends AguiAgentAdapter {

        private CustomAdapter(Agent agent, AguiAdapterConfig config) {
            super(agent, config);
        }

        @Override
        protected RuntimeContext buildRuntimeContext(
                RunAgentInput input, RuntimeContext runtimeContext) {
            return RuntimeContext.builder(super.buildRuntimeContext(input, runtimeContext))
                    .put("adapter", "custom-adapter")
                    .build();
        }
    }
}
