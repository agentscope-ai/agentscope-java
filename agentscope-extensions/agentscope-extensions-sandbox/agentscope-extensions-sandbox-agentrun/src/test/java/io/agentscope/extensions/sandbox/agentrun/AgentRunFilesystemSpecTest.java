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

import io.agentscope.harness.agent.sandbox.WorkspaceSpec;
import org.junit.jupiter.api.Test;

class AgentRunFilesystemSpecTest {

    @Test
    void defaultWorkspaceSpec_hasAgentRunRoot() {
        AgentRunFilesystemSpec spec = new AgentRunFilesystemSpec();
        WorkspaceSpec ws = spec.workspaceSpec();
        assertEquals(AgentRunSandboxState.DEFAULT_WORKSPACE_ROOT, ws.getRoot());
    }
}
