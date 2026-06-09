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
package io.agentscope.core.memory.autocontext;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.agentscope.core.ReActAgent;
import io.agentscope.core.agent.RuntimeContext;
import io.agentscope.core.hook.PreCallEvent;
import io.agentscope.core.hook.PreReasoningEvent;
import io.agentscope.core.state.AgentState;
import java.lang.reflect.Field;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import reactor.core.scheduler.Schedulers;
import reactor.test.StepVerifier;

class AutoContextHookTest {

    @Test
    void handlePreCallRegistersContextReloadTool() {
        AutoContextHook hook = new AutoContextHook();
        ReActAgent agent =
                ReActAgent.builder()
                        .name("hook-test")
                        .sysPrompt("system")
                        .model(AutoContextTestSupport.noopModel())
                        .build();
        PreCallEvent event =
                new PreCallEvent(agent, List.of(AutoContextTestSupport.userMessage("hello")));

        StepVerifier.create(hook.handlePreCall(event)).expectNext(event).verifyComplete();

        assertTrue(agent.getToolkit().getToolNames().contains("context_reload"));
        assertEquals(1, event.getInputMessages().size());
    }

    @Test
    void handlePreReasoningCompressesWithoutBlockingAndRewritesState() {
        AtomicReference<String> threadName = new AtomicReference<>();
        AutoContextHook hook =
                new AutoContextHook(
                        AutoContextConfig.builder()
                                .msgThreshold(2)
                                .lastKeep(0)
                                .minCompressionTokenThreshold(1)
                                .build(),
                        AutoContextTestSupport.recordingModel("compressed", threadName));
        ReActAgent agent =
                ReActAgent.builder()
                        .name("reasoning-test")
                        .sysPrompt("system")
                        .model(AutoContextTestSupport.noopModel())
                        .build();

        AgentState state =
                AutoContextTestSupport.runtimeState(
                        "session-1",
                        "alice",
                        List.of(
                                AutoContextTestSupport.userMessage("first"),
                                AutoContextTestSupport.assistantMessage("second")));
        RuntimeContext runtimeContext = AutoContextTestSupport.runtimeContext(state);
        setActiveRuntimeContext(agent, runtimeContext);

        PreReasoningEvent event =
                new PreReasoningEvent(agent, "recording", null, state.getContext());

        StepVerifier.create(hook.handlePreReasoning(event).subscribeOn(Schedulers.parallel()))
                .assertNext(
                        returned -> {
                            assertSame(event, returned);
                            assertNotNull(returned.getSystemMessage());
                            assertTrue(
                                    returned.getSystemMessage()
                                            .getTextContent()
                                            .contains("context_reload"));
                            assertEquals(1, returned.getInputMessages().size());
                            assertEquals(
                                    "compressed",
                                    returned.getInputMessages().get(0).getTextContent());
                            assertEquals(1, state.getContext().size());
                            assertEquals("compressed", state.getContext().get(0).getTextContent());
                        })
                .verifyComplete();

        assertNotNull(threadName.get());
        assertTrue(threadName.get().contains("boundedElastic"));
    }

    private static void setActiveRuntimeContext(ReActAgent agent, RuntimeContext runtimeContext) {
        try {
            Field field = ReActAgent.class.getDeclaredField("activeRc");
            field.setAccessible(true);
            field.set(agent, runtimeContext);
        } catch (ReflectiveOperationException e) {
            throw new AssertionError(e);
        }
    }
}
