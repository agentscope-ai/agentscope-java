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

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertSame;

import io.agentscope.harness.agent.sandbox.snapshot.SandboxSnapshotSpec;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

/** Tests for the default 4-arg create() method on {@link SandboxClient}. */
@ExtendWith(MockitoExtension.class)
class SandboxClientDefaultMethodTest {

    @Mock Sandbox sandbox3arg;

    @Test
    @DisplayName("default 4-arg create delegates to 3-arg create")
    void defaultMethodDelegates() {
        SandboxClient<SandboxClientOptions> client =
                new SandboxClient<>() {
                    @Override
                    public Sandbox create(
                            WorkspaceSpec ws, SandboxSnapshotSpec ss, SandboxClientOptions opts) {
                        return sandbox3arg;
                    }

                    @Override
                    public Sandbox resume(SandboxState state) {
                        return null;
                    }

                    @Override
                    public void delete(Sandbox sandbox) {}

                    @Override
                    public String serializeState(SandboxState state) {
                        return null;
                    }

                    @Override
                    public SandboxState deserializeState(String json) {
                        return null;
                    }
                };

        Sandbox result = client.create(new WorkspaceSpec(), null, null, "some-snapshot-id");
        assertSame(sandbox3arg, result);
        assertNotNull(result);
    }
}
