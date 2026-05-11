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
import io.agentscope.core.session.Session;
import io.agentscope.harness.agent.filesystem.spec.DockerFilesystemSpec;
import io.agentscope.harness.agent.filesystem.spec.LocalFilesystemSpec;
import io.agentscope.harness.agent.filesystem.spec.RemoteFilesystemSpec;
import io.agentscope.harness.agent.sandbox.SandboxDistributedOptions;
import io.agentscope.harness.agent.sandbox.snapshot.LocalSnapshotSpec;
import io.agentscope.harness.agent.store.BaseStore;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import reactor.core.publisher.Flux;

class HarnessAgentDistributedSandboxTest {

    @TempDir Path workspace;

    @Test
    void sandboxDistributed_requiresSandboxFilesystemMode() {
        IllegalStateException ex =
                assertThrows(
                        IllegalStateException.class,
                        () ->
                                HarnessAgent.builder()
                                        .name("agent")
                                        .model(stubModel("ok"))
                                        .workspace(workspace)
                                        .filesystem(new LocalFilesystemSpec())
                                        .sandboxDistributed(
                                                SandboxDistributedOptions.builder().build())
                                        .build());
        assertEquals(
                true,
                ex.getMessage().contains("requires sandbox mode"),
                "should fail-fast when sandboxDistributed is used outside sandbox mode");
    }

    @Test
    void sandboxMode_withLocalSession_failsFastByDefault() {
        // Mode 2 (SandboxFilesystemSpec) now validates automatically — no sandboxDistributed()
        // needed.
        IllegalStateException ex =
                assertThrows(
                        IllegalStateException.class,
                        () ->
                                HarnessAgent.builder()
                                        .name("agent")
                                        .model(stubModel("ok"))
                                        .workspace(workspace)
                                        .filesystem(new DockerFilesystemSpec())
                                        .build());
        assertEquals(
                true,
                ex.getMessage().contains("distributed Session backend"),
                "sandbox mode should fail-fast when effective session remains local"
                        + " WorkspaceSession");
    }

    @Test
    void sandboxMode_explicitSandboxDistributed_alsoFailsOnLocalSession() {
        // Explicit sandboxDistributed() with default requireDistributed=true still fails.
        IllegalStateException ex =
                assertThrows(
                        IllegalStateException.class,
                        () ->
                                HarnessAgent.builder()
                                        .name("agent")
                                        .model(stubModel("ok"))
                                        .workspace(workspace)
                                        .filesystem(new DockerFilesystemSpec())
                                        .sandboxDistributed(
                                                SandboxDistributedOptions.builder().build())
                                        .build());
        assertEquals(
                true,
                ex.getMessage().contains("distributed Session backend"),
                "should fail-fast when effective session remains local");
    }

    @Test
    void sandboxDistributed_appliesSnapshotOverride() {
        Session distributedSession = mock(Session.class);
        DockerFilesystemSpec spec = new DockerFilesystemSpec();
        spec.isolationScope(IsolationScope.AGENT);
        LocalSnapshotSpec snapshotSpec = new LocalSnapshotSpec(workspace.resolve("snapshots"));

        SandboxDistributedOptions options =
                SandboxDistributedOptions.builder()
                        .session(distributedSession)
                        .snapshotSpec(snapshotSpec)
                        .build();

        assertDoesNotThrow(
                () ->
                        HarnessAgent.builder()
                                .name("agent")
                                .model(stubModel("ok"))
                                .workspace(workspace)
                                .filesystem(spec)
                                .sandboxDistributed(options)
                                .build());

        assertEquals(IsolationScope.AGENT, spec.getIsolationScope());
        assertInstanceOf(LocalSnapshotSpec.class, spec.toSandboxContext().getSnapshotSpec());
    }

    @Test
    void sandboxMode_requireDistributedFalse_allowsLocalSession() {
        // Single-node sandbox use: opt out of distributed validation explicitly.
        assertDoesNotThrow(
                () ->
                        HarnessAgent.builder()
                                .name("agent")
                                .model(stubModel("ok"))
                                .workspace(workspace)
                                .filesystem(new DockerFilesystemSpec())
                                .sandboxDistributed(
                                        SandboxDistributedOptions.builder()
                                                .requireDistributed(false)
                                                .build())
                                .build());
    }

    @Test
    void remoteFilesystemMode_withLocalSession_failsFast() {
        // Mode 1 (RemoteFilesystemSpec) always requires a distributed Session.
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
                "Mode 1 should fail-fast when effective session is local WorkspaceSession");
    }

    @Test
    void remoteFilesystemMode_withDistributedSession_succeeds() {
        // Mode 1 with a distributed Session should build successfully.
        BaseStore store = mock(BaseStore.class);
        Session distributedSession = mock(Session.class);
        assertDoesNotThrow(
                () ->
                        HarnessAgent.builder()
                                .name("agent")
                                .model(stubModel("ok"))
                                .workspace(workspace)
                                .filesystem(new RemoteFilesystemSpec(store))
                                .session(distributedSession)
                                .build());
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
