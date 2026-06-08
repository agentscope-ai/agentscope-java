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

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import io.agentscope.core.model.ChatResponse;
import io.agentscope.core.model.Model;
import io.agentscope.core.state.AgentStateStore;
import io.agentscope.harness.agent.filesystem.remote.store.BaseStore;
import io.agentscope.harness.agent.filesystem.spec.DockerFilesystemSpec;
import io.agentscope.harness.agent.filesystem.spec.RemoteFilesystemSpec;
import io.agentscope.harness.agent.sandbox.snapshot.LocalSnapshotSpec;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import reactor.core.publisher.Flux;

class HarnessAgentDistributedSandboxTest {

    @TempDir Path workspace;

    @Test
    void sandboxMode_withLocalSession_buildsWithWarning() {
        // Sandbox mode with a local AgentStateStore now builds successfully (warn-only).
        assertDoesNotThrow(
                () ->
                        HarnessAgent.builder()
                                .name("agent")
                                .model(stubModel("ok"))
                                .workspace(workspace)
                                .filesystem(new DockerFilesystemSpec())
                                .build());
    }

    @Test
    void sandboxMode_withDistributedSession_builds() {
        AgentStateStore distributedSession = mock(AgentStateStore.class);
        assertDoesNotThrow(
                () ->
                        HarnessAgent.builder()
                                .name("agent")
                                .model(stubModel("ok"))
                                .workspace(workspace)
                                .stateStore(distributedSession)
                                .filesystem(new DockerFilesystemSpec())
                                .build());
    }

    @Test
    void sandboxMode_snapshotSpecOnFilesystemSpec() {
        AgentStateStore distributedSession = mock(AgentStateStore.class);
        DockerFilesystemSpec spec = new DockerFilesystemSpec();
        spec.isolationScope(IsolationScope.AGENT);
        spec.snapshotSpec(new LocalSnapshotSpec(workspace.resolve("snapshots")));

        assertDoesNotThrow(
                () ->
                        HarnessAgent.builder()
                                .name("agent")
                                .model(stubModel("ok"))
                                .workspace(workspace)
                                .stateStore(distributedSession)
                                .filesystem(spec)
                                .build());

        assertEquals(IsolationScope.AGENT, spec.getIsolationScope());
        assertInstanceOf(LocalSnapshotSpec.class, spec.toSandboxContext().getSnapshotSpec());
    }

    @Test
    void remoteFilesystemMode_withLocalSession_failsFast() {
        BaseStore store = mock(BaseStore.class);
        IllegalStateException ex =
                assertThrows(
                        IllegalStateException.class,
                        () ->
                                HarnessAgent.builder()
                                        .name("agent")
                                        .model(stubModel("ok"))
                                        .workspace(workspace)
                                        .filesystem(new RemoteFilesystemSpec(store))
                                        .build());
        assertEquals(
                true,
                ex.getMessage().contains("RemoteFilesystemSpec"),
                "Mode 1 should fail-fast when effective session is a local in-process"
                        + " implementation (JsonFileAgentStateStore / InMemoryAgentStateStore)");
    }

    @Test
    void remoteFilesystemMode_withDistributedSession_succeeds() throws Exception {
        BaseStore store = mock(BaseStore.class);
        AgentStateStore distributedSession = mock(AgentStateStore.class);
        HarnessAgent agent =
                HarnessAgent.builder()
                        .name("agent")
                        .model(stubModel("ok"))
                        .workspace(workspace)
                        .filesystem(new RemoteFilesystemSpec(store))
                        .stateStore(distributedSession)
                        .build();
        agent.close();
    }

    private static Model stubModel(String assistantText) {
        Model model = mock(Model.class);
        when(model.getModelName()).thenReturn("stub-model");
        ChatResponse chunk =
                new ChatResponse(
                        "stub-id",
                        List.of(
                                io.agentscope.core.message.TextBlock.builder()
                                        .text(assistantText)
                                        .build()),
                        null,
                        Map.of(),
                        "stop");
        when(model.stream(anyList(), any(), any())).thenReturn(Flux.just(chunk));
        return model;
    }
}
