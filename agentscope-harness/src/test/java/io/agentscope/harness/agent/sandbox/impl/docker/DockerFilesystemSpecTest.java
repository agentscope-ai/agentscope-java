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
package io.agentscope.harness.agent.sandbox.impl.docker;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertSame;

import io.agentscope.harness.agent.sandbox.SandboxClient;
import io.agentscope.harness.agent.sandbox.snapshot.NoopSnapshotSpec;
import io.agentscope.harness.agent.sandbox.snapshot.SandboxSnapshotSpec;
import org.junit.jupiter.api.Test;

class DockerFilesystemSpecTest {

    @Test
    void createClient_returnsExternallySetClient() {
        DockerFilesystemSpec spec = new DockerFilesystemSpec();
        SandboxClient<?> external = new DockerSandboxClient();
        spec.client(external);
        assertSame(external, spec.createClient());
    }

    @Test
    void createClient_constructsDefaultClientWithSnapshotSpec() {
        DockerFilesystemSpec spec = new DockerFilesystemSpec();
        SandboxSnapshotSpec snapshotSpec = new NoopSnapshotSpec();
        spec.snapshotSpec(snapshotSpec);
        SandboxClient<?> client = spec.createClient();
        assertNotNull(client);
    }
}
