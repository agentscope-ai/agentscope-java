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
package io.agentscope.extensions.sandbox.agentrun;

import static org.junit.jupiter.api.Assertions.assertSame;

import io.agentscope.harness.agent.sandbox.snapshot.NoopSnapshotSpec;
import io.agentscope.harness.agent.sandbox.snapshot.SandboxSnapshotSpec;
import org.junit.jupiter.api.Test;

class AgentRunFilesystemSpecTest {

    @Test
    void snapshotSpecPreservesExplicitOverride() {
        SandboxSnapshotSpec snapshotSpec = new NoopSnapshotSpec();
        AgentRunFilesystemSpec spec = new AgentRunFilesystemSpec();

        assertSame(spec, spec.snapshotSpec(snapshotSpec));
        assertSame(snapshotSpec, spec.getSnapshotSpecOverride());
        assertSame(snapshotSpec, spec.snapshotSpec());
    }
}
