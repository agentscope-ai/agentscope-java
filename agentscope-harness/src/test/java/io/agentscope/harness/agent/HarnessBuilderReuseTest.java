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
package io.agentscope.harness.agent;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import io.agentscope.core.agent.RuntimeContext;
import io.agentscope.core.hook.Hook;
import io.agentscope.core.hook.HookEvent;
import io.agentscope.core.message.Msg;
import io.agentscope.core.message.TextBlock;
import io.agentscope.core.message.UserMessage;
import io.agentscope.core.model.ChatResponse;
import io.agentscope.core.model.Model;
import io.agentscope.core.session.SessionHistoryMode;
import io.agentscope.core.state.InMemoryAgentStateStore;
import io.agentscope.core.tool.AgentTool;
import io.agentscope.harness.agent.filesystem.remote.store.BaseStore;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Callable;
import java.util.concurrent.Executors;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

class HarnessBuilderReuseTest {
    @TempDir Path workspace;

    private HarnessAgent.Builder builder(Model model) {
        return HarnessAgent.builder()
                .name("request-agent")
                .agentId("stable-agent")
                .model(model)
                .workspace(workspace)
                .disableMemoryHooks();
    }

    @Test
    void repeatedBuildHasFreshRuntimeMiddlewareAndFilesystemBindings() {
        var builder = builder(mock(Model.class));
        try (var first = builder.build();
                var second = builder.build()) {
            var firstChain = first.getDelegate().getMiddlewares();
            var secondChain = second.getDelegate().getMiddlewares();
            assertEquals(
                    firstChain.stream().map(Object::getClass).toList(),
                    secondChain.stream().map(Object::getClass).toList());
            for (int i = 0; i < firstChain.size(); i++) {
                assertNotSame(firstChain.get(i), secondChain.get(i));
            }
            assertNotSame(first.getToolkit(), second.getToolkit());
            assertNull(builder.messageBus);
            assertNull(builder.asyncToolRegistry);
        }
    }

    @Test
    void declaredHookAllowlistIsScopedToEachBuild() {
        AgentTool allowed = mock(AgentTool.class);
        AgentTool denied = mock(AgentTool.class);
        when(allowed.getName()).thenReturn("hook_allowed");
        when(denied.getName()).thenReturn("hook_denied");
        Hook hook =
                new Hook() {
                    @Override
                    public <T extends HookEvent> Mono<T> onEvent(T event) {
                        return Mono.just(event);
                    }

                    @Override
                    public List<Object> tools() {
                        return List.of(allowed, denied);
                    }
                };
        var builder = builder(mock(Model.class)).hook(hook);
        try (var first = builder.build(List.of("hook_allowed"));
                var second = builder.build(List.of("hook_denied"));
                var unfiltered = builder.build()) {
            assertSame(allowed, first.getToolkit().getTool("hook_allowed"));
            assertNull(first.getToolkit().getTool("hook_denied"));
            assertNull(second.getToolkit().getTool("hook_allowed"));
            assertSame(denied, second.getToolkit().getTool("hook_denied"));
            assertSame(allowed, unfiltered.getToolkit().getTool("hook_allowed"));
            assertSame(denied, unfiltered.getToolkit().getTool("hook_denied"));
            assertEquals(
                    first.getDelegate().getMiddlewares().stream().map(Object::getClass).toList(),
                    second.getDelegate().getMiddlewares().stream().map(Object::getClass).toList());
            assertNull(builder.messageBus);
            assertNull(builder.asyncToolRegistry);
        }
    }

    @Test
    void repeatedBuildPreservesDistributedStateStoreWiring() {
        var stateStore = new InMemoryAgentStateStore();
        var distributedStore = mock(DistributedStore.class);
        when(distributedStore.agentStateStore()).thenReturn(stateStore);
        when(distributedStore.baseStore()).thenReturn(mock(BaseStore.class));
        var builder =
                builder(mock(Model.class))
                        .sessionHistoryMode(SessionHistoryMode.LEGACY)
                        .distributedStore(distributedStore);
        try (var first = builder.build();
                var second = builder.build()) {
            assertSame(stateStore, first.getDelegate().getStateStore());
            assertSame(stateStore, second.getDelegate().getStateStore());
            // Dynamic subagent factories read this configuration after the parent is built.
            assertSame(stateStore, builder.stateStoreOverride);
        }
    }

    @Test
    void newInstanceContinuesPersistedConversationAfterPreviousInstanceCloses() {
        Model model = mock(Model.class);
        when(model.getModelName()).thenReturn("offline");
        List<List<Msg>> inputs = new ArrayList<>();
        when(model.stream(anyList(), any(), any()))
                .thenAnswer(
                        invocation -> {
                            inputs.add(List.copyOf(invocation.getArgument(0)));
                            return Flux.just(
                                    new ChatResponse(
                                            "reply-" + inputs.size(),
                                            List.of(
                                                    TextBlock.builder()
                                                            .text("acknowledged")
                                                            .build()),
                                            null,
                                            Map.of(),
                                            "stop"));
                        });
        var builder = builder(model);
        try (var first = builder.build()) {
            first.call(
                            new UserMessage("Remember lifecycle-marker-73"),
                            RuntimeContext.builder()
                                    .userId("alice")
                                    .sessionId("conversation")
                                    .build())
                    .block();
        }
        try (var second = builder.build()) {
            second.call(
                            new UserMessage("Continue"),
                            RuntimeContext.builder()
                                    .userId("alice")
                                    .sessionId("conversation")
                                    .build())
                    .block();
        }
        assertEquals(2, inputs.size());
        assertTrue(
                inputs.get(1).stream()
                        .anyMatch(msg -> msg.getTextContent().contains("lifecycle-marker-73")));
    }

    @Test
    void concurrentBuildsKeepIndependentMiddlewareChains() throws Exception {
        var builder = builder(mock(Model.class));
        var pool = Executors.newFixedThreadPool(4);
        List<HarnessAgent> agents = new ArrayList<>();
        try {
            List<Callable<HarnessAgent>> calls = new ArrayList<>();
            for (int i = 0; i < 8; i++) calls.add(builder::build);
            for (var result : pool.invokeAll(calls)) agents.add(result.get());
            var expected =
                    agents.get(0).getDelegate().getMiddlewares().stream()
                            .map(Object::getClass)
                            .toList();
            for (var agent : agents) {
                assertEquals(
                        expected,
                        agent.getDelegate().getMiddlewares().stream()
                                .map(Object::getClass)
                                .toList());
            }
        } finally {
            pool.shutdownNow();
            agents.forEach(HarnessAgent::close);
        }
    }
}
