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
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import io.agentscope.core.agent.RuntimeContext;
import io.agentscope.core.message.Msg;
import io.agentscope.core.message.TextBlock;
import io.agentscope.core.message.UserMessage;
import io.agentscope.core.model.ChatResponse;
import io.agentscope.core.model.Model;
import io.agentscope.core.session.SessionHistoryMode;
import io.agentscope.core.state.AgentStateStore;
import io.agentscope.core.state.InMemoryAgentStateStore;
import io.agentscope.harness.agent.example.support.InMemorySandboxFilesystemSpec;
import io.agentscope.harness.agent.filesystem.remote.store.BaseStore;
import io.agentscope.harness.agent.filesystem.spec.RemoteFilesystemSpec;
import io.agentscope.harness.agent.sandbox.SandboxExecutionGuard;
import io.agentscope.harness.agent.sandbox.WorkspaceSpec;
import io.agentscope.harness.agent.sandbox.snapshot.SandboxSnapshotSpec;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Callable;
import java.util.concurrent.Executors;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import reactor.core.publisher.Flux;

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

    @Test
    void failedBuildDoesNotLeaveAutoWiredStateStoreOverrideBehind() {
        var pollutedStore = new InMemoryAgentStateStore();
        var distributedStore = mock(DistributedStore.class);
        when(distributedStore.agentStateStore()).thenReturn(pollutedStore);
        when(distributedStore.baseStore()).thenReturn(mock(BaseStore.class));
        var builder =
                builder(mock(Model.class))
                        .sessionHistoryMode(SessionHistoryMode.LEGACY)
                        .distributedStore(distributedStore)
                        // LEGACY + remote filesystem + a local (non-distributed) effective
                        // session store is rejected by design; the auto-wired override has
                        // already been written at that point.
                        .filesystem(new RemoteFilesystemSpec(mock(BaseStore.class)));
        assertThrows(IllegalStateException.class, builder::build);
        assertNull(
                builder.stateStoreOverride,
                "a failed build must restore the auto-wired stateStoreOverride so the"
                        + " shared builder stays reusable");
    }

    @Test
    void builderRemainsReusableAfterFailedBuild() {
        var pollutedStore = new InMemoryAgentStateStore();
        var distributedStore = mock(DistributedStore.class);
        when(distributedStore.agentStateStore()).thenReturn(pollutedStore);
        when(distributedStore.baseStore()).thenReturn(mock(BaseStore.class));
        var builder =
                builder(mock(Model.class))
                        .sessionHistoryMode(SessionHistoryMode.LEGACY)
                        .distributedStore(distributedStore)
                        .filesystem(new RemoteFilesystemSpec(mock(BaseStore.class)));
        assertThrows(IllegalStateException.class, builder::build);

        // The caller fixes the configuration on the same shared builder instance; a stale
        // override would silently win and the agent would keep the polluted local store.
        var fixedStore = mock(AgentStateStore.class);
        builder.stateStore(fixedStore);
        try (var agent = builder.build()) {
            assertSame(fixedStore, agent.getDelegate().getStateStore());
        }
    }

    @Test
    void failedBuildRestoresAutoWiredRemoteFilesystemStore() {
        var oldBaseStore = mock(BaseStore.class);
        var oldDistributedStore = mock(DistributedStore.class);
        when(oldDistributedStore.agentStateStore()).thenReturn(new InMemoryAgentStateStore());
        when(oldDistributedStore.baseStore()).thenReturn(oldBaseStore);
        var spec = new RemoteFilesystemSpec();
        var builder =
                builder(mock(Model.class))
                        .sessionHistoryMode(SessionHistoryMode.LEGACY)
                        .distributedStore(oldDistributedStore)
                        .filesystem(spec);

        assertThrows(IllegalStateException.class, builder::build);
        assertNull(spec.store());

        var newBaseStore = mock(BaseStore.class);
        var newDistributedStore = mock(DistributedStore.class);
        when(newDistributedStore.agentStateStore()).thenReturn(mock(AgentStateStore.class));
        when(newDistributedStore.baseStore()).thenReturn(newBaseStore);
        builder.distributedStore(newDistributedStore);
        try (var agent = builder.build()) {
            assertSame(newBaseStore, spec.store());
        }
    }

    @Test
    void failedBuildRestoresAutoWiredSandboxSpecForAnyThrowable() {
        var oldDistributedStore = mock(DistributedStore.class);
        when(oldDistributedStore.agentStateStore()).thenReturn(new InMemoryAgentStateStore());
        when(oldDistributedStore.baseStore()).thenReturn(mock(BaseStore.class));
        var spec = new FailingSandboxFilesystemSpec();
        var builder =
                builder(mock(Model.class)).distributedStore(oldDistributedStore).filesystem(spec);

        assertThrows(AssertionError.class, builder::build);
        assertNull(spec.getSnapshotSpecOverride());
        assertNull(spec.getExecutionGuard());
        assertNull(builder.stateStoreOverride);

        var newSnapshotSpec = mock(SandboxSnapshotSpec.class);
        var newExecutionGuard = mock(SandboxExecutionGuard.class);
        var newDistributedStore = mock(DistributedStore.class);
        when(newDistributedStore.agentStateStore()).thenReturn(mock(AgentStateStore.class));
        when(newDistributedStore.baseStore()).thenReturn(mock(BaseStore.class));
        when(newDistributedStore.sandboxSnapshotSpec()).thenReturn(newSnapshotSpec);
        when(newDistributedStore.sandboxExecutionGuard()).thenReturn(newExecutionGuard);
        builder.distributedStore(newDistributedStore);
        spec.stopFailing();
        try (var agent = builder.build()) {
            assertSame(newSnapshotSpec, spec.getSnapshotSpecOverride());
            assertSame(newExecutionGuard, spec.getExecutionGuard());
        }
    }

    private static final class FailingSandboxFilesystemSpec extends InMemorySandboxFilesystemSpec {
        private boolean failing = true;

        void stopFailing() {
            failing = false;
        }

        @Override
        protected WorkspaceSpec workspaceSpec() {
            if (failing) {
                throw new AssertionError("sandbox build failed");
            }
            return super.workspaceSpec();
        }
    }
}
