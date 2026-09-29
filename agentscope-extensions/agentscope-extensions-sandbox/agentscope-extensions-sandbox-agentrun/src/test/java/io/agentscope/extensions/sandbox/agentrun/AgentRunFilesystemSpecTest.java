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

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.agentscope.harness.agent.sandbox.WorkspaceSpec;
import org.junit.jupiter.api.Test;

class AgentRunFilesystemSpecTest {

    @Test
    void defaultWorkspaceSpec_hasAgentRunRoot() {
        AgentRunFilesystemSpec spec = new AgentRunFilesystemSpec();
        WorkspaceSpec ws = spec.workspaceSpec();
        assertEquals(AgentRunSandboxState.DEFAULT_WORKSPACE_ROOT, ws.getRoot());
    }

    @Test
    void workspaceSpec_doesNotMutateCallerObject() {
        WorkspaceSpec caller = new WorkspaceSpec();
        caller.setRoot("/caller/root");

        new AgentRunFilesystemSpec().workspaceRoot("/explicit/root").workspaceSpec(caller);

        assertEquals("/caller/root", caller.getRoot());
    }

    @Test
    void workspaceRootAndWorkspaceSpec_areOrderIndependent() {
        WorkspaceSpec caller = new WorkspaceSpec();
        caller.setRoot("/caller/root");

        // workspaceRoot applied last wins
        WorkspaceSpec specLast =
                new AgentRunFilesystemSpec()
                        .workspaceSpec(caller)
                        .workspaceRoot("/last")
                        .workspaceSpec();
        // workspaceSpec applied last wins (its root)
        WorkspaceSpec specFirst =
                new AgentRunFilesystemSpec()
                        .workspaceRoot("/last")
                        .workspaceSpec(caller)
                        .workspaceSpec();

        assertEquals("/last", specLast.getRoot());
        assertEquals("/caller/root", specFirst.getRoot());
    }

    /**
     * The mount check inside {@code AgentRunSandboxClient.create()} must run against the
     * backend-defaulted root, not the raw spec: a NAS mount at the default AgentRun root has to
     * be recognised so {@code doDestroyWorkspace()} does not rm -rf persistent data.
     */
    @Test
    void defaultRoot_isUnderAgentRunNasMount() {
        String nasMount = AgentRunSandboxState.DEFAULT_WORKSPACE_ROOT;
        WorkspaceSpec defaulted =
                WorkspaceSpec.withDefaultRoot(null, AgentRunSandboxState.DEFAULT_WORKSPACE_ROOT);

        assertEquals(nasMount, defaulted.getRoot());
        assertTrue(defaulted.getRoot().startsWith(nasMount));
    }
}
