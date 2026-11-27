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
package io.agentscope.harness.agent.middleware;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import io.agentscope.core.agent.RuntimeContext;
import io.agentscope.core.message.Msg;
import io.agentscope.core.message.TextBlock;
import io.agentscope.core.message.ToolUseBlock;
import io.agentscope.core.model.ChatResponse;
import io.agentscope.core.model.GenerateOptions;
import io.agentscope.core.model.Model;
import io.agentscope.core.model.ToolSchema;
import io.agentscope.core.state.InMemoryAgentStateStore;
import io.agentscope.core.tool.Tool;
import io.agentscope.core.tool.Toolkit;
import io.agentscope.harness.agent.DistributedStore;
import io.agentscope.harness.agent.HarnessAgent;
import io.agentscope.harness.agent.bus.AsyncToolRecord;
import io.agentscope.harness.agent.bus.AsyncToolRegistry;
import io.agentscope.harness.agent.bus.MessageBus;
import io.agentscope.harness.agent.bus.WorkspaceAsyncToolRegistry;
import io.agentscope.harness.agent.bus.WorkspaceMessageBus;
import io.agentscope.harness.agent.example.support.InMemorySandboxFilesystemSpec;
import io.agentscope.harness.agent.filesystem.local.LocalFilesystem;
import io.agentscope.harness.agent.filesystem.remote.store.InMemoryStore;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Function;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.core.publisher.Sinks;

class SandboxControlStorageTest {

    private static final Duration TIMEOUT = Duration.ofSeconds(15);
    private static final String BUS_ROOT = ".agentscope/bus";

    @TempDir Path workspace;

    @Test
    void defaultControlStorageSurvivesSiblingReleaseAndAcceptsMessagesBetweenCalls()
            throws Exception {
        var firstReasoning = Sinks.<Boolean>one();
        var continueA = Sinks.<ChatResponse>one();
        var paused = new AtomicBoolean();
        var spec = new InMemorySandboxFilesystemSpec();
        var model =
                model(
                        messages -> {
                            if (messages.stream().anyMatch(m -> "run A".equals(m.getTextContent()))
                                    && paused.compareAndSet(false, true)) {
                                firstReasoning.emitValue(true, Sinks.EmitFailureHandler.FAIL_FAST);
                                return continueA.asMono().flux();
                            }
                            return Flux.just(done());
                        });
        var toolkit = new Toolkit();
        toolkit.registerTool(new ProbeTools());
        var contextA = context("alice", "session-a");

        var host = new LocalFilesystem(workspace, true);
        var registry = new WorkspaceAsyncToolRegistry(host, BUS_ROOT + "/async-tools");
        try (var bus = new WorkspaceMessageBus(host, BUS_ROOT);
                var agent = builder(model).filesystem(spec).toolkit(toolkit).build()) {
            var a = agent.streamEvents("run A", contextA).collectList().toFuture();
            try {
                firstReasoning.asMono().block(TIMEOUT);
                agent.call("run B", context("bob", "session-b")).block(TIMEOUT);
                assertEquals(2, spec.getClient().getCreateCount());
                assertFalse(a.isDone(), "A must still be active when B returns");

                // These writes happen after B releases its sandbox, before A reasons again.
                bus.inboxPush("session-a", Map.of("hint", "message after B")).block(TIMEOUT);
                registry.register(
                                new AsyncToolRecord(
                                        "stale-a",
                                        "session-a",
                                        "probe",
                                        "stale-a",
                                        AsyncToolRecord.RUNNING,
                                        Instant.now().minus(Duration.ofHours(1))))
                        .block(TIMEOUT);
                continueA.emitValue(
                        ChatResponse.builder()
                                .content(List.of(new ToolUseBlock("check-a", "probe", Map.of())))
                                .build(),
                        Sinks.EmitFailureHandler.FAIL_FAST);
                a.get(TIMEOUT.toSeconds(), TimeUnit.SECONDS);
                assertFalse(bus.inboxHasMessages("session-a").block(TIMEOUT));
                assertEquals(
                        List.of(), registry.findStale("session-a", Duration.ZERO).block(TIMEOUT));

                // Delivery can also happen with neither sandbox active.
                bus.inboxPush("session-a", Map.of("hint", "message between calls")).block(TIMEOUT);
                agent.call("run A again", contextA).block(TIMEOUT);
                assertFalse(bus.inboxHasMessages("session-a").block(TIMEOUT));
            } finally {
                a.cancel(true);
            }
        }
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void configuredControlStorageTakesPrecedenceOverWorkspaceDefaults(boolean explicit) {
        MessageBus bus = mock(MessageBus.class);
        AsyncToolRegistry registry = mock(AsyncToolRegistry.class);
        when(bus.inboxDrain(eq("session-a"), anyInt())).thenReturn(Mono.just(List.of()));
        when(registry.findStale(eq("session-a"), any())).thenReturn(Mono.just(List.of()));

        var store =
                DistributedStore.builder()
                        .agentStateStore(new InMemoryAgentStateStore())
                        .baseStore(new InMemoryStore())
                        .messageBus(explicit ? mock(MessageBus.class) : bus)
                        .asyncToolRegistry(explicit ? mock(AsyncToolRegistry.class) : registry)
                        .build();
        var builder =
                builder(model(messages -> Flux.just(done())))
                        .filesystem(new InMemorySandboxFilesystemSpec())
                        .distributedStore(store);
        if (explicit) {
            builder.messageBus(bus).asyncToolRegistry(registry);
        }
        try (var agent = builder.build()) {
            agent.call("run A", context("alice", "session-a")).block(TIMEOUT);
            verify(bus).inboxDrain(eq("session-a"), anyInt());
            verify(registry).findStale(eq("session-a"), any());
        }
    }

    private HarnessAgent.Builder builder(Model model) {
        return HarnessAgent.builder()
                .name("sandbox-control-storage-test")
                .workspace(workspace)
                .model(model)
                .stateStore(new InMemoryAgentStateStore())
                .journalEnabled(false)
                .disableDefaultWorkspaceSkills()
                .disableMemoryTools()
                .disableMemoryHooks()
                .disableCompaction()
                .disableSubagents()
                .disableWorkspaceContext()
                .enableTaskList(false);
    }

    private static Model model(Function<List<Msg>, Flux<ChatResponse>> responses) {
        return new Model() {
            @Override
            public String getModelName() {
                return "sandbox-control-storage-test";
            }

            @Override
            public Flux<ChatResponse> stream(
                    List<Msg> messages, List<ToolSchema> tools, GenerateOptions options) {
                return responses.apply(messages);
            }
        };
    }

    private static RuntimeContext context(String user, String session) {
        return RuntimeContext.builder().userId(user).sessionId(session).build();
    }

    private static ChatResponse done() {
        return ChatResponse.builder()
                .content(List.of(TextBlock.builder().text("done").build()))
                .build();
    }

    public static class ProbeTools {
        @Tool(name = "probe", description = "Continue to the next reasoning step", readOnly = true)
        public String probe() {
            return "ok";
        }
    }
}
