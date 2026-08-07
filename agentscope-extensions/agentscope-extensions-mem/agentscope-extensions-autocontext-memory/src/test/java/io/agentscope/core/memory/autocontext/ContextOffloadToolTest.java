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
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.agentscope.core.ReActAgent;
import io.agentscope.core.agent.RuntimeContext;
import io.agentscope.core.message.Msg;
import io.agentscope.core.state.AgentState;
import java.util.List;
import org.junit.jupiter.api.Test;

class ContextOffloadToolTest {

    @Test
    void reloadUsesDirectContextOffLoaderWhenProvided() {
        ContextOffloadTool tool =
                new ContextOffloadTool(
                        new ContextOffLoader() {
                            @Override
                            public void offload(String uuid, List<Msg> messages) {}

                            @Override
                            public List<Msg> reload(String uuid) {
                                return List.of(
                                        AutoContextTestSupport.userMessage("direct-one"),
                                        AutoContextTestSupport.assistantMessage("direct-two"));
                            }

                            @Override
                            public void clear(String uuid) {}
                        });

        List<Msg> messages = tool.reload("direct-uuid");

        assertEquals(2, messages.size());
        assertEquals("direct-one", messages.get(0).getTextContent());
        assertEquals("direct-two", messages.get(1).getTextContent());
    }

    @Test
    void reloadUsesHookRuntimeContextWhenAgentAndStateAreAvailable() {
        AutoContextHook hook = new AutoContextHook();
        ContextOffloadTool tool = new ContextOffloadTool(hook);
        ReActAgent agent =
                ReActAgent.builder()
                        .name("offload-test")
                        .sysPrompt("system")
                        .model(AutoContextTestSupport.noopModel())
                        .build();

        AgentState state =
                AutoContextTestSupport.runtimeState(
                        "session-1",
                        "alice",
                        List.of(AutoContextTestSupport.userMessage("context")));
        RuntimeContext runtimeContext = AutoContextTestSupport.runtimeContext(state);
        String uuid = "offload-uuid";

        hook.memoryFor(agent, runtimeContext)
                .offload(
                        uuid,
                        List.of(
                                AutoContextTestSupport.userMessage("offloaded-one"),
                                AutoContextTestSupport.assistantMessage("offloaded-two")));

        List<Msg> messages = tool.reload(uuid, agent, runtimeContext);

        assertEquals(2, messages.size());
        assertTrue(messages.get(0).getTextContent().contains("offloaded-one"));
        assertTrue(messages.get(1).getTextContent().contains("offloaded-two"));
    }

    @Test
    void reloadReturnsErrorMessagesForInvalidOrUnavailableContext() {
        ContextOffloadTool noLoaderTool = new ContextOffloadTool((ContextOffLoader) null);
        List<Msg> blankUuidMessages = noLoaderTool.reload("  ");
        assertEquals(1, blankUuidMessages.size());
        assertTrue(blankUuidMessages.get(0).getTextContent().contains("UUID cannot be null"));

        List<Msg> unavailableMessages = noLoaderTool.reload("uuid-1");
        assertEquals(1, unavailableMessages.size());
        assertTrue(
                unavailableMessages
                        .get(0)
                        .getTextContent()
                        .contains("Context offloader is not available"));
    }

    @Test
    void reloadReturnsErrorMessageWhenLoaderThrowsOrNothingFound() {
        ContextOffloadTool throwingTool =
                new ContextOffloadTool(
                        new ContextOffLoader() {
                            @Override
                            public void offload(String uuid, List<Msg> messages) {}

                            @Override
                            public List<Msg> reload(String uuid) {
                                throw new IllegalStateException("boom");
                            }

                            @Override
                            public void clear(String uuid) {}
                        });

        List<Msg> thrown = throwingTool.reload("uuid-2");
        assertEquals(1, thrown.size());
        assertTrue(thrown.get(0).getTextContent().contains("boom"));

        ContextOffloadTool emptyTool =
                new ContextOffloadTool(
                        new ContextOffLoader() {
                            @Override
                            public void offload(String uuid, List<Msg> messages) {}

                            @Override
                            public List<Msg> reload(String uuid) {
                                return List.of();
                            }

                            @Override
                            public void clear(String uuid) {}
                        });
        List<Msg> missing = emptyTool.reload("uuid-3");
        assertEquals(1, missing.size());
        assertTrue(missing.get(0).getTextContent().contains("No messages found for UUID"));
    }

    @Test
    void reloadWithoutAgentUsesHookBackedMemoryLookup() {
        AutoContextHook hook = new AutoContextHook();
        ContextOffloadTool tool = new ContextOffloadTool(hook);
        ReActAgent agent =
                ReActAgent.builder()
                        .name("hook-default")
                        .sysPrompt("system")
                        .model(AutoContextTestSupport.noopModel())
                        .build();
        RuntimeContext runtimeContext =
                RuntimeContext.builder().sessionId("session-2").userId("bob").build();
        List<Msg> expected = List.of(AutoContextTestSupport.userMessage("hook-only"));

        hook.memoryFor(agent, runtimeContext).offload("hook-uuid", expected);

        List<Msg> reloaded = tool.reload("hook-uuid", agent, runtimeContext);
        assertEquals(1, reloaded.size());
        assertSame(expected.get(0), reloaded.get(0));
    }
}
