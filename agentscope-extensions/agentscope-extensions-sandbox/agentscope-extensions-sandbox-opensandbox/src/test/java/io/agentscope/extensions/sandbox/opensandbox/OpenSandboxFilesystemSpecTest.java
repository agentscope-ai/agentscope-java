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

import io.agentscope.harness.agent.sandbox.SandboxClient;
import io.agentscope.harness.agent.sandbox.WorkspaceSpec;
import io.agentscope.harness.agent.sandbox.snapshot.NoopSnapshotSpec;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class OpenSandboxFilesystemSpecTest {
    @Test
    void defaultsToWorkspaceRootAndCreatesOpenSandboxClient() {
        OpenSandboxFilesystemSpec spec = new OpenSandboxFilesystemSpec();

        assertEquals("/workspace", spec.workspaceSpec().getRoot());
        assertInstanceOf(OpenSandboxClient.class, spec.createClient());
    }

    @Test
    void fluentConfigurationReturnsSameSpec() {
        OpenSandboxFilesystemSpec spec = new OpenSandboxFilesystemSpec();
        WorkspaceSpec workspace = new WorkspaceSpec();
        workspace.setRoot("/custom-workspace");
        NoopSnapshotSpec snapshot = new NoopSnapshotSpec();

        assertSame(spec, spec.endpoint("http://localhost:8080"));
        assertSame(spec, spec.apiKey("secret"));
        assertSame(spec, spec.image("ubuntu:24.04"));
        assertSame(spec, spec.entrypoint(List.of("sleep", "infinity")));
        assertSame(spec, spec.cpu("2"));
        assertSame(spec, spec.memory("4Gi"));
        assertSame(spec, spec.sandboxTimeoutSeconds(901));
        assertSame(spec, spec.readyTimeoutSeconds(41));
        assertSame(spec, spec.requestTimeoutSeconds(42));
        assertSame(spec, spec.useServerProxy(true));
        assertSame(spec, spec.workspaceRoot("/temporary-root"));
        assertSame(spec, spec.workspaceSpec(workspace));
        assertSame(spec, spec.snapshotSpec(snapshot));

        OpenSandboxClientOptions options =
                assertInstanceOf(OpenSandboxClientOptions.class, spec.clientOptions());
        assertEquals("secret", options.getApiKey());
        assertEquals("ubuntu:24.04", options.getImage());
        assertEquals(List.of("sleep", "infinity"), options.getEntrypoint());
        assertEquals(Map.of("cpu", "2", "memory", "4Gi"), options.getResourceLimits());
        assertEquals(901, options.getSandboxTimeoutSeconds());
        assertEquals(41, options.getReadyTimeoutSeconds());
        assertEquals(42, options.getRequestTimeoutSeconds());
        assertEquals(true, options.isUseServerProxy());
        assertSame(workspace, spec.workspaceSpec());
        assertSame(snapshot, spec.snapshotSpec());
    }

    @Test
    void customClientIsReturnedAndBlankResourcesAreRejected() {
        OpenSandboxFilesystemSpec spec = new OpenSandboxFilesystemSpec();
        SandboxClient<?> client = new OpenSandboxClient();

        assertSame(spec, spec.client(client));
        assertSame(client, spec.createClient());
        assertThrows(IllegalArgumentException.class, () -> spec.cpu(" "));
        assertThrows(IllegalArgumentException.class, () -> spec.memory(null));
    }
}
