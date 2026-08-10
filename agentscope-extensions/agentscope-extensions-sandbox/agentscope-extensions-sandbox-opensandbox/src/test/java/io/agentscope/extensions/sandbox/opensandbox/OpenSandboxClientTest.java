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
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.agentscope.harness.agent.sandbox.ExecResult;
import io.agentscope.harness.agent.sandbox.SandboxState;
import io.agentscope.harness.agent.sandbox.WorkspaceSpec;
import java.io.ByteArrayInputStream;
import java.io.InputStream;
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
    }

    private static WorkspaceSpec workspace(String root) {
        WorkspaceSpec spec = new WorkspaceSpec();
        spec.setRoot(root);
        return spec;
    }

    private static final class RecordingSdk implements OpenSandboxSdk {
        @Override
        public Handle create(OpenSandboxState state, OpenSandboxClientOptions options) {
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
