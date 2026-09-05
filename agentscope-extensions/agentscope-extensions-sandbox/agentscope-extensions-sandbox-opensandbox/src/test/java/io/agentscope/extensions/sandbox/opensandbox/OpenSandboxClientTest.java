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
package io.agentscope.extensions.sandbox.opensandbox;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.agentscope.harness.agent.sandbox.ExecResult;
import io.agentscope.harness.agent.sandbox.Sandbox;
import io.agentscope.harness.agent.sandbox.SandboxException;
import io.agentscope.harness.agent.sandbox.SandboxState;
import io.agentscope.harness.agent.sandbox.WorkspaceSpec;
import io.agentscope.harness.agent.sandbox.snapshot.SandboxSnapshot;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class OpenSandboxClientTest {

    @Test
    void createCopiesMergedCreationSettingsIntoState() {
        OpenSandboxClientOptions defaults = new OpenSandboxClientOptions();
        defaults.setImage("ubuntu:24.04");
        defaults.setResourceLimits(Map.of("cpu", "2", "memory", "4Gi"));
        RecordingSdk sdk = new RecordingSdk();
        OpenSandboxClient client = new OpenSandboxClient(defaults, null, sdk);

        OpenSandbox sandbox = (OpenSandbox) client.create(workspace("/workspace"), null, null);
        OpenSandboxState state = (OpenSandboxState) sandbox.getState();

        assertEquals("ubuntu:24.04", state.getImage());
        assertEquals(Map.of("cpu", "2", "memory", "4Gi"), state.getResourceLimits());
        assertTrue(state.isSandboxOwned());
    }

    @Test
    void resumeRejectsAnotherProviderState() {
        OpenSandboxClient client = new OpenSandboxClient();

        assertThrows(IllegalArgumentException.class, () -> client.resume(new SandboxState() {}));
        assertThrows(IllegalArgumentException.class, () -> client.resume(null));
    }

    @Test
    void callOptionsAndSnapshotOverrideDefaults() throws Exception {
        OpenSandboxClientOptions defaults = new OpenSandboxClientOptions();
        defaults.setApiKey("default-key");
        RecordingSdk sdk = new RecordingSdk();
        OpenSandboxClient client = new OpenSandboxClient(defaults, null, sdk);
        OpenSandboxClientOptions call = new OpenSandboxClientOptions();
        call.setEndpoint("https://sandbox.example.com:8443");
        call.setApiKey("call-key");
        call.setImage("ubuntu:24.04");
        call.setEntrypoint(List.of("sleep", "infinity"));
        call.setResourceLimits(Map.of("cpu", "4", "memory", "8Gi"));
        call.setSandboxTimeoutSeconds(901);
        call.setReadyTimeoutSeconds(41);
        call.setRequestTimeoutSeconds(42);
        call.setUseServerProxy(true);
        SandboxSnapshot snapshot = mock(SandboxSnapshot.class);

        OpenSandbox sandbox =
                (OpenSandbox) client.create(workspace("/custom"), ignored -> snapshot, call);
        OpenSandboxState state = (OpenSandboxState) sandbox.getState();
        sandbox.start();

        assertEquals("ubuntu:24.04", state.getImage());
        assertEquals(List.of("sleep", "infinity"), state.getEntrypoint());
        assertEquals(Map.of("cpu", "4", "memory", "8Gi"), state.getResourceLimits());
        assertEquals(901, state.getSandboxTimeoutSeconds());
        assertSame(snapshot, state.getSnapshot());
        assertEquals("https://sandbox.example.com:8443", sdk.lastOptions.getEndpoint());
        assertEquals("call-key", sdk.lastOptions.getApiKey());
        assertEquals(41, sdk.lastOptions.getReadyTimeoutSeconds());
        assertEquals(42, sdk.lastOptions.getRequestTimeoutSeconds());
        assertTrue(sdk.lastOptions.isUseServerProxy());
    }

    @Test
    void createWithNullInputsUsesIndependentDefaults() {
        RecordingSdk sdk = new RecordingSdk();
        OpenSandboxClient client = new OpenSandboxClient(null, null, sdk);

        OpenSandbox sandbox = (OpenSandbox) client.create(null, null, null);
        OpenSandboxState state = (OpenSandboxState) sandbox.getState();

        assertEquals("ubuntu:22.04", state.getImage());
        assertEquals("/workspace", state.getWorkspaceSpec().getRoot());
    }

    @Test
    void resumeRetainsProvidedState() {
        RecordingSdk sdk = new RecordingSdk();
        OpenSandboxClient client = new OpenSandboxClient(new OpenSandboxClientOptions(), null, sdk);
        OpenSandboxState state = new OpenSandboxState();
        state.setWorkspaceSpec(workspace("/resumed"));

        OpenSandbox resumed = (OpenSandbox) client.resume(state);

        assertSame(state, resumed.getState());
    }

    @Test
    void deleteHandlesNullSuccessAndFailure() throws Exception {
        OpenSandboxClient client = new OpenSandboxClient();
        Sandbox successful = mock(Sandbox.class);
        Sandbox failing = mock(Sandbox.class);
        doThrow(new IOException("shutdown failed")).when(failing).shutdown();

        client.delete(null);
        client.delete(successful);
        SandboxException.SandboxRuntimeException failure =
                assertThrows(
                        SandboxException.SandboxRuntimeException.class,
                        () -> client.delete(failing));

        verify(successful).shutdown();
        assertInstanceOf(IOException.class, failure.getCause());
    }

    @Test
    void clientSerializationRoundTripsState() {
        OpenSandboxClient client = new OpenSandboxClient();
        OpenSandboxState state = new OpenSandboxState();
        state.setSessionId("session-serialized");
        state.setWorkspaceSpec(workspace("/workspace"));

        String json = client.serializeState(state);
        SandboxState restored = client.deserializeState(json);

        OpenSandboxState decoded = assertInstanceOf(OpenSandboxState.class, restored);
        assertEquals("session-serialized", decoded.getSessionId());
    }

    @Test
    void clientSerializationWrapsMapperFailures() throws Exception {
        ObjectMapper mapper = mock(ObjectMapper.class);
        JsonProcessingException failure = new JsonProcessingException("broken") {};
        when(mapper.writeValueAsString(any())).thenThrow(failure);
        when(mapper.readValue(anyString(), eq(SandboxState.class))).thenThrow(failure);
        OpenSandboxClient client =
                new OpenSandboxClient(new OpenSandboxClientOptions(), mapper, new RecordingSdk());

        assertThrows(
                SandboxException.SandboxConfigurationException.class,
                () -> client.serializeState(new OpenSandboxState()));
        assertThrows(
                SandboxException.SandboxConfigurationException.class,
                () -> client.deserializeState("{}"));
    }

    private static WorkspaceSpec workspace(String root) {
        WorkspaceSpec spec = new WorkspaceSpec();
        spec.setRoot(root);
        return spec;
    }

    private static final class RecordingSdk implements OpenSandboxSdk {
        private OpenSandboxClientOptions lastOptions;

        @Override
        public Handle create(OpenSandboxState state, OpenSandboxClientOptions options) {
            lastOptions = options;
            return new Handle() {
                @Override
                public String id() {
                    return "sandbox-created";
                }

                @Override
                public ExecResult exec(
                        String command, String workingDirectory, int timeoutSeconds) {
                    return new ExecResult(0, "", "", false);
                }

                @Override
                public InputStream read(String absolutePath) {
                    return new ByteArrayInputStream(new byte[0]);
                }

                @Override
                public void write(String absolutePath, byte[] content) {}

                @Override
                public void close() {}
            };
        }

        @Override
        public Handle connect(String sandboxId, OpenSandboxClientOptions options) {
            throw new UnsupportedOperationException();
        }

        @Override
        public void kill(String sandboxId, OpenSandboxClientOptions options) {}

        @Override
        public boolean isNotFound(Throwable error) {
            return false;
        }
    }
}
