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
package io.agentscope.extensions.sandbox.kubernetes;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.startsWith;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockConstruction;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import io.agentscope.core.agent.RuntimeContext;
import io.agentscope.extensions.sandbox.kubernetes.client.CommandExecutor;
import io.agentscope.extensions.sandbox.kubernetes.client.CreateSandboxOptions;
import io.agentscope.extensions.sandbox.kubernetes.client.Filesystem;
import io.agentscope.extensions.sandbox.kubernetes.client.SandboxClient;
import io.agentscope.extensions.sandbox.kubernetes.client.model.ExecutionResult;
import io.agentscope.harness.agent.IsolationScope;
import io.agentscope.harness.agent.sandbox.Sandbox;
import io.agentscope.harness.agent.sandbox.SandboxAcquireResult;
import io.agentscope.harness.agent.sandbox.SandboxContext;
import io.agentscope.harness.agent.sandbox.SandboxManager;
import io.agentscope.harness.agent.sandbox.SessionSandboxStateStore;
import io.agentscope.harness.agent.sandbox.WorkspaceSpec;
import io.agentscope.harness.agent.sandbox.snapshot.RemoteSandboxSnapshot;
import io.agentscope.harness.agent.sandbox.snapshot.RemoteSnapshotClient;
import io.agentscope.harness.agent.sandbox.snapshot.RemoteSnapshotSpec;
import io.fabric8.kubernetes.client.KubernetesClient;
import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.mockito.MockedConstruction;

class KubernetesSandboxColdRestoreTest {

    @ParameterizedTest
    @CsvSource({"true,false", "false,false", "false,true"})
    void persistedSnapshotSurvivesAcquireStartAndStop(boolean claimGone, boolean workspacePreserved)
            throws Exception {
        String snapshotId = "original-snapshot";
        byte[] previousArchive = "previous workspace archive".getBytes(StandardCharsets.UTF_8);
        byte[] updatedArchive = "updated workspace archive".getBytes(StandardCharsets.UTF_8);
        RemoteSnapshotClient oldStorage = mock(RemoteSnapshotClient.class);
        RemoteSnapshotClient storage = mock(RemoteSnapshotClient.class);
        when(storage.exists(snapshotId)).thenReturn(true);
        when(storage.download(snapshotId))
                .thenAnswer(inv -> new ByteArrayInputStream(previousArchive));
        AtomicReference<byte[]> uploaded = new AtomicReference<>();
        doAnswer(
                        inv -> {
                            uploaded.set(inv.getArgument(1, InputStream.class).readAllBytes());
                            return null;
                        })
                .when(storage)
                .upload(eq(snapshotId), any(InputStream.class));
        RemoteSnapshotSpec snapshotSpec = new RemoteSnapshotSpec(storage);

        KubernetesSandboxClientOptions options = new KubernetesSandboxClientOptions();
        options.setKubernetesClient(mock(KubernetesClient.class));
        options.setWarmPoolName("pool");
        KubernetesSandboxClient client = new KubernetesSandboxClient(options);
        KubernetesSandboxState previousState = new KubernetesSandboxState();
        previousState.setSessionId("original-session");
        previousState.setNamespace("default");
        previousState.setClaimName("original-claim");
        previousState.setWorkspaceSpec(new WorkspaceSpec());
        previousState.setWorkspaceRootReady(true);
        previousState.setSnapshot(new RemoteSandboxSnapshot(oldStorage, snapshotId));
        String json = client.serializeState(previousState);
        SessionSandboxStateStore stateStore = mock(SessionSandboxStateStore.class);
        when(stateStore.load(any())).thenReturn(Optional.of(json));
        SandboxManager manager = new SandboxManager(client, stateStore, "agent");
        SandboxContext context =
                SandboxContext.builder()
                        .isolationScope(IsolationScope.SESSION)
                        .snapshotSpec(snapshotSpec)
                        .build();
        RuntimeContext runtime = RuntimeContext.builder().sessionId("conversation").build();

        var sdkSandbox = mock(io.agentscope.extensions.sandbox.kubernetes.client.Sandbox.class);
        CommandExecutor commands = mock(CommandExecutor.class);
        Filesystem files = mock(Filesystem.class);
        when(sdkSandbox.commands()).thenReturn(commands);
        when(sdkSandbox.files()).thenReturn(files);
        when(commands.run(anyString())).thenReturn(new ExecutionResult("", "", 0));
        when(commands.run(anyString(), any(java.time.Duration.class)))
                .thenReturn(new ExecutionResult("", "", workspacePreserved ? 0 : 1));
        when(files.read(startsWith(".agentscope-tmp/ws-persist-"))).thenReturn(updatedArchive);

        // External storage and Kubernetes transport are mocked. State JSON, client lifecycle,
        // manager, snapshot binding and archive transfer use the production code.
        try (MockedConstruction<SandboxClient> clients =
                mockConstruction(
                        SandboxClient.class,
                        (sdk, construction) -> {
                            if (claimGone) {
                                when(sdk.getSandbox("original-claim", "default"))
                                        .thenThrow(new IllegalStateException("claim gone"));
                            } else {
                                when(sdk.getSandbox("original-claim", "default"))
                                        .thenReturn(sdkSandbox);
                            }
                            when(sdk.createSandbox(any(CreateSandboxOptions.class)))
                                    .thenReturn(sdkSandbox);
                        })) {
            SandboxAcquireResult result = manager.acquire(context, runtime);
            Sandbox sandbox = result.getSandbox();
            try {
                assertEquals(snapshotId, sandbox.getState().getSnapshot().getId());
                if (claimGone) {
                    assertNotEquals(
                            previousState.getSessionId(), sandbox.getState().getSessionId());
                    assertEquals(2, clients.constructed().size());
                    verify(clients.constructed().get(1))
                            .createSandbox(any(CreateSandboxOptions.class));
                } else {
                    assertEquals(previousState.getSessionId(), sandbox.getState().getSessionId());
                    verify(clients.constructed().get(0), never())
                            .createSandbox(any(CreateSandboxOptions.class));
                }
                sandbox.start();
                assertTrue(sandbox.isRunning());
                if (!workspacePreserved) {
                    verify(storage).download(snapshotId);
                    verify(files)
                            .write(startsWith(".agentscope-tmp/ws-hydrate-"), eq(previousArchive));
                    verify(commands).run(startsWith("tar -xf "));
                } else {
                    verify(storage, never()).download(anyString());
                }
                sandbox.stop();
                assertArrayEquals(updatedArchive, uploaded.get());
                verify(storage).upload(eq(snapshotId), any(InputStream.class));
                verifyNoInteractions(oldStorage);
                manager.persistState(result, context, runtime);
                var saved = org.mockito.ArgumentCaptor.forClass(String.class);
                verify(stateStore).save(any(), saved.capture());
                assertEquals(
                        snapshotId,
                        client.deserializeState(saved.getValue()).getSnapshot().getId());
            } finally {
                sandbox.shutdown();
                result.getLease().close();
            }
        }
    }
}
