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

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import io.agentscope.core.agent.RuntimeContext;
import io.agentscope.core.message.Msg;
import io.agentscope.core.message.MsgRole;
import io.agentscope.core.message.TextBlock;
import io.agentscope.core.message.ToolUseBlock;
import io.agentscope.core.model.ChatResponse;
import io.agentscope.core.model.Model;
import io.agentscope.core.session.InMemorySessionLogStore;
import io.agentscope.core.state.InMemoryAgentStateStore;
import io.agentscope.harness.agent.bus.AsyncToolRegistry;
import io.agentscope.harness.agent.bus.MessageBus;
import io.agentscope.harness.agent.example.support.InMemorySandboxClient;
import io.agentscope.harness.agent.example.support.InMemorySandboxFilesystemSpec;
import io.agentscope.harness.agent.sandbox.ExecResult;
import io.agentscope.harness.agent.sandbox.Sandbox;
import io.agentscope.harness.agent.testing.HarnessQuiescence;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

@HarnessQuiescence
class HarnessAgentLazySandboxTest {
    @TempDir Path workspace;
    private Sandbox sandbox;

    @Test
    void pureChatDoesNotAcquireSandboxWhenOptedIn() throws Exception {
        InMemorySandboxClient client = client();
        try (HarnessAgent agent = agent(client, true)) {
            assertNotNull(agent.call(message(), context()).block(Duration.ofSeconds(10)));
            assertFalse(
                    agent.streamEvents(message(), context())
                            .collectList()
                            .block(Duration.ofSeconds(10))
                            .isEmpty());
            verify(client, never()).create(any(), any(), any());
            verify(client, never()).resume(any());
        }
    }

    @Test
    void defaultRemainsEager() throws Exception {
        InMemorySandboxClient client = client();
        try (HarnessAgent agent = agent(client, false)) {
            assertNotNull(agent.call(message(), context()).block(Duration.ofSeconds(10)));
            verify(client).create(any(), any(), any());
        }
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void shellToolInitializesAndReleasesSandbox(boolean streaming) throws Exception {
        InMemorySandboxClient client = client();
        Model model = model();
        when(model.stream(anyList(), any(), any()))
                .thenReturn(
                        Flux.just(
                                new ChatResponse(
                                        "tool",
                                        List.of(
                                                ToolUseBlock.builder()
                                                        .id("t1")
                                                        .name("execute")
                                                        .input(Map.of("command", "echo hello"))
                                                        .content("{\"command\":\"echo hello\"}")
                                                        .build()),
                                        null,
                                        Map.of(),
                                        "tool_calls")),
                        Flux.just(response()));
        try (HarnessAgent agent = builder(client, true, model, false).build()) {
            if (streaming) {
                assertFalse(
                        agent.streamEvents(message(), context())
                                .collectList()
                                .block(Duration.ofSeconds(10))
                                .isEmpty());
            } else {
                assertNotNull(agent.call(message(), context()).block(Duration.ofSeconds(10)));
            }
            verify(client).create(any(), any(), any());
            verify(sandbox).start();
            verify(sandbox).exec(any(), eq("echo hello"), any());
            verify(sandbox).stop();
            verify(sandbox).shutdown();
        }
    }

    @Test
    void sandboxBackedWorkspaceContextStillInitializesForPlainChat() throws Exception {
        InMemorySandboxClient client = client();
        try (HarnessAgent agent = builder(client, true, model(), true).build()) {
            assertNotNull(agent.call(message(), context()).block(Duration.ofSeconds(10)));
            verify(client).create(any(), any(), any());
        }
    }

    @Test
    void cancellationBeforeFilesystemUseDoesNotStartSandbox() throws Exception {
        InMemorySandboxClient client = client();
        Model model = model();
        CountDownLatch subscribed = new CountDownLatch(1);
        when(model.stream(anyList(), any(), any()))
                .thenReturn(
                        Flux.<ChatResponse>never()
                                .doOnSubscribe(ignored -> subscribed.countDown()));
        try (HarnessAgent agent = builder(client, true, model, false).build()) {
            var subscription = agent.streamEvents(message(), context()).subscribe();
            try {
                assertTrue(subscribed.await(10, TimeUnit.SECONDS));
            } finally {
                subscription.dispose();
            }
            verify(client, never()).create(any(), any(), any());
            verify(client, never()).resume(any());
        }
    }

    @Test
    void cancellationAfterWorkspaceAccessReleasesSandbox() throws Exception {
        InMemorySandboxClient client = client();
        Model model = model();
        CountDownLatch subscribed = new CountDownLatch(1);
        when(model.stream(anyList(), any(), any()))
                .thenReturn(
                        Flux.<ChatResponse>never()
                                .doOnSubscribe(ignored -> subscribed.countDown()));
        try (HarnessAgent agent = builder(client, true, model, true).build()) {
            var subscription = agent.streamEvents(message(), context()).subscribe();
            try {
                assertTrue(subscribed.await(10, TimeUnit.SECONDS));
                verify(sandbox).start();
            } finally {
                subscription.dispose();
            }
            verify(sandbox).stop();
            verify(sandbox).shutdown();
        }
    }

    private InMemorySandboxClient client() throws Exception {
        InMemorySandboxClient client = mock(InMemorySandboxClient.class);
        sandbox = mock(Sandbox.class);
        when(sandbox.exec(any(), anyString(), any())).thenReturn(new ExecResult(0, "", "", false));
        when(client.create(any(), any(), any())).thenReturn(sandbox);
        return client;
    }

    private HarnessAgent agent(InMemorySandboxClient client, boolean lazy) {
        return builder(client, lazy, model(), false).build();
    }

    private Model model() {
        Model model = mock(Model.class);
        when(model.getModelName()).thenReturn("stub");
        when(model.stream(anyList(), any(), any())).thenReturn(Flux.just(response()));
        return model;
    }

    private ChatResponse response() {
        return new ChatResponse(
                "id", List.of(TextBlock.builder().text("hello").build()), null, Map.of(), "stop");
    }

    private HarnessAgent.Builder builder(
            InMemorySandboxClient client, boolean lazy, Model model, boolean workspaceContext) {
        MessageBus bus = mock(MessageBus.class);
        when(bus.inboxDrain(anyString(), anyInt())).thenReturn(Mono.just(List.of()));
        AsyncToolRegistry registry = mock(AsyncToolRegistry.class);
        when(registry.findStale(anyString(), any())).thenReturn(Mono.just(List.of()));
        InMemorySandboxFilesystemSpec spec = new InMemorySandboxFilesystemSpec(client);
        spec.lazyInitialization(lazy);
        HarnessAgent.Builder builder =
                HarnessAgent.builder()
                        .name("lazy-test")
                        .workspace(workspace)
                        .model(model)
                        .filesystem(spec)
                        .stateStore(new InMemoryAgentStateStore())
                        .sessionLogStore(new InMemorySessionLogStore())
                        .messageBus(bus)
                        .asyncToolRegistry(registry)
                        .disableMemoryHooks()
                        .disableMemoryTools()
                        .disableDynamicSkills()
                        .disableSubagents()
                        .disableCompaction()
                        .disableTranscript()
                        .disableAtPathExpansion()
                        .disableToolsConfig();
        if (!workspaceContext) builder.disableWorkspaceContext();
        return builder;
    }

    private Msg message() {
        return Msg.builder().role(MsgRole.USER).textContent("hello").build();
    }

    private RuntimeContext context() {
        return RuntimeContext.builder().userId("user").sessionId("session").build();
    }
}
