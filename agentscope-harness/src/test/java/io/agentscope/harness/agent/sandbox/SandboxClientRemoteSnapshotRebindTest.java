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
package io.agentscope.harness.agent.sandbox;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.agentscope.harness.agent.sandbox.impl.docker.DockerSandboxState;
import io.agentscope.harness.agent.sandbox.json.HarnessSandboxJacksonModule;
import io.agentscope.harness.agent.sandbox.snapshot.RemoteSandboxSnapshot;
import io.agentscope.harness.agent.sandbox.snapshot.RemoteSnapshotClient;
import io.agentscope.harness.agent.sandbox.snapshot.RemoteSnapshotSpec;
import io.agentscope.harness.agent.sandbox.snapshot.SandboxSnapshot;
import io.agentscope.harness.agent.sandbox.snapshot.SandboxSnapshotSpec;
import java.io.InputStream;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Reproduces and guards against remote snapshot client loss after JSON deserialization
 * when a {@link SandboxClient} only implements the single-arg {@code deserializeState}.
 *
 * <p>See <a href="https://github.com/agentscope-ai/agentscope-java/issues/2303">#2303</a>.
 */
class SandboxClientRemoteSnapshotRebindTest {

    @Test
    @DisplayName(
            "default deserializeState(json, spec) must rebind RemoteSnapshotClient so"
                    + " isRestorable() works")
    void defaultTwoArgDeserializeRebindsRemoteClient() throws Exception {
        ObjectMapper mapper =
                new ObjectMapper()
                        .findAndRegisterModules()
                        .registerModule(new HarnessSandboxJacksonModule());

        // Simulates AgentRun / Daytona / E2B / Kubernetes clients: only single-arg deserialize.
        SandboxClient<SandboxClientOptions> client = new SingleArgDeserializeClient(mapper);

        DockerSandboxState original = new DockerSandboxState();
        original.setSessionId("remote-session-rebind");
        original.setSnapshot(new RemoteSandboxSnapshot(new FakeRemoteSnapshotClient(), "snap-x"));

        String json = client.serializeState(original);
        RemoteSnapshotSpec spec = new RemoteSnapshotSpec(new FakeRemoteSnapshotClient());

        SandboxState restored = client.deserializeState(json, spec);
        SandboxSnapshot snapshot = restored.getSnapshot();

        assertInstanceOf(RemoteSandboxSnapshot.class, snapshot);
        assertEquals("snap-x", snapshot.getId());
        assertTrue(snapshot.isRestorable());
    }

    @Test
    @DisplayName("single-arg deserialize leaves RemoteSnapshotClient unbound (bug evidence)")
    void singleArgDeserializeLeavesClientUnbound() throws Exception {
        ObjectMapper mapper =
                new ObjectMapper()
                        .findAndRegisterModules()
                        .registerModule(new HarnessSandboxJacksonModule());

        SandboxClient<SandboxClientOptions> client = new SingleArgDeserializeClient(mapper);

        DockerSandboxState original = new DockerSandboxState();
        original.setSessionId("remote-session-unbound");
        original.setSnapshot(new RemoteSandboxSnapshot(new FakeRemoteSnapshotClient(), "snap-y"));

        String json = client.serializeState(original);
        SandboxState restored = client.deserializeState(json);

        SandboxException.SnapshotException ex =
                assertThrows(
                        SandboxException.SnapshotException.class,
                        () -> restored.getSnapshot().isRestorable());
        assertInstanceOf(IllegalStateException.class, ex.getCause());
        assertTrue(ex.getCause().getMessage().contains("RemoteSnapshotClient is not bound"));
    }

    /**
     * Minimal client that only overrides the single-arg deserialize path — same shape as
     * AgentRunSandboxClient / DaytonaSandboxClient / E2bSandboxClient / KubernetesSandboxClient.
     */
    private static final class SingleArgDeserializeClient
            implements SandboxClient<SandboxClientOptions> {

        private final ObjectMapper objectMapper;

        private SingleArgDeserializeClient(ObjectMapper objectMapper) {
            this.objectMapper = objectMapper;
        }

        @Override
        public Sandbox create(
                WorkspaceSpec workspaceSpec,
                SandboxSnapshotSpec snapshotSpec,
                SandboxClientOptions options) {
            throw new UnsupportedOperationException();
        }

        @Override
        public Sandbox resume(SandboxState state) {
            throw new UnsupportedOperationException();
        }

        @Override
        public void delete(Sandbox sandbox) {}

        @Override
        public String serializeState(SandboxState state) {
            try {
                return objectMapper.writeValueAsString(state);
            } catch (Exception e) {
                throw new SandboxException.SandboxConfigurationException(
                        "Failed to serialize sandbox state", e);
            }
        }

        @Override
        public SandboxState deserializeState(String json) {
            try {
                return objectMapper.readValue(json, SandboxState.class);
            } catch (Exception e) {
                throw new SandboxException.SandboxConfigurationException(
                        "Failed to deserialize sandbox state", e);
            }
        }
    }

    private static final class FakeRemoteSnapshotClient implements RemoteSnapshotClient {

        @Override
        public void upload(String snapshotId, InputStream data) {}

        @Override
        public InputStream download(String snapshotId) {
            return InputStream.nullInputStream();
        }

        @Override
        public boolean exists(String snapshotId) {
            return true;
        }
    }
}
