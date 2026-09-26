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

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;

import io.agentscope.harness.agent.sandbox.WorkspaceSpec;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;

/** Opt-in smoke test for a real OpenSandbox service. */
class OpenSandboxIntegrationTest {
    @Test
    void realServiceCreateExecTransferReconnectAndKill() throws Exception {
        String endpoint = System.getenv("OPEN_SANDBOX_ENDPOINT");
        String apiKey = System.getenv("OPEN_SANDBOX_API_KEY");
        Assumptions.assumeTrue(endpoint != null && !endpoint.isBlank());
        Assumptions.assumeTrue(apiKey != null && !apiKey.isBlank());

        OpenSandboxClientOptions options = new OpenSandboxClientOptions();
        options.setEndpoint(endpoint);
        options.setApiKey(apiKey);
        options.setImage(System.getenv().getOrDefault("OPEN_SANDBOX_TEST_IMAGE", "ubuntu:22.04"));
        OpenSandboxClient client = new OpenSandboxClient(options, null);
        OpenSandbox sandbox = (OpenSandbox) client.create(workspace("/workspace"), null, null);
        byte[] payload = new byte[] {0, 1, 2, (byte) 255};
        boolean terminated = false;
        try {
            sandbox.start();
            assertEquals(
                    "opensandbox-ok\n",
                    sandbox.exec(null, "printf 'opensandbox-ok\\n'", 30).stdout());
            sandbox.uploadFile("/workspace/data.bin", payload);
            assertArrayEquals(payload, sandbox.downloadFile("/workspace/data.bin"));

            OpenSandboxState saved = (OpenSandboxState) sandbox.getState();
            sandbox.stop();
            OpenSandbox resumed = (OpenSandbox) client.resume(saved);
            try {
                resumed.start();
                assertArrayEquals(payload, resumed.downloadFile("/workspace/data.bin"));
                resumed.stop();
            } finally {
                resumed.shutdown();
                terminated = true;
            }
        } finally {
            if (!terminated) {
                sandbox.shutdown();
            }
        }
    }

    private static WorkspaceSpec workspace(String root) {
        WorkspaceSpec spec = new WorkspaceSpec();
        spec.setRoot(root);
        return spec;
    }
}
